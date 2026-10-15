package com.damweeb.studylock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;


/** Watches window changes; if a locked game comes up, sends the user to the study screen. */
public class BlockerService extends AccessibilityService {
    private WindowManager wm;
    private View overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::closeOverlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (overlay != null) return;                       // already showing
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        StudyState state = new StudyState(this);
        state.rollover();
        if (!state.blocked().contains(pkg.toString()) || state.isUnlocked()) return;

        showOverlay(pkg.toString(), state);
    }
    private void showOverlay(String pkg, StudyState state) {
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay_block, null);

        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            // keep the raw package name
        }

        long remaining = state.requiredMin() * 60L - state.totalStudiedSec();
        String title, message;
        if (remaining > 0) {
            long mins = (remaining + 59) / 60;
            title = "Study first";
            message = mins + (mins == 1 ? " more minute" : " more minutes")
                    + " of studying unlocks your apps.";
        } else if (state.canUnlock()) {
            title = "You've earned it";
            message = "Open StudyLock and unlock your apps.";
        } else {
            title = "Not today";
            message = "Today's unlock has ended. Apps open again after the daily reset.";
        }

        ((TextView) overlay.findViewById(R.id.overlayApp)).setText(label + " is locked");
        ((TextView) overlay.findViewById(R.id.overlayTitle)).setText(title);
        ((TextView) overlay.findViewById(R.id.overlayMessage)).setText(message);
        overlay.setOnClickListener(v -> closeOverlay());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(overlay, lp);

        handler.postDelayed(autoClose, 2000);
    }

    private void closeOverlay() {
        handler.removeCallbacks(autoClose);
        if (overlay == null) return;
        performGlobalAction(GLOBAL_ACTION_HOME);            // push the game away first
        handler.postDelayed(() -> {
            if (overlay != null) {
                wm.removeView(overlay);
                overlay = null;
            }
        }, 300);                                            // then remove the cover
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(autoClose);
        if (overlay != null) {
            wm.removeView(overlay);
            overlay = null;
        }
    }

    @Override
    public void onInterrupt() { }
}
