package com.damweeb.studylock;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodInfo;
import android.widget.TextView;


/** Watches window changes; if a locked game comes up, sends the user to the study screen. */
public class BlockerService extends AccessibilityService {

    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }
    private WindowManager wm;
    private View overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::closeOverlay;
    private static final long BANNER_MS = 4_000L;    // how long the popup stays up
    private static final int GRACE_NOTIF_ID = 42;
    private static final String CHANNEL_ID = "grace";

    private boolean graceActive;
    private final Runnable graceEnd = this::finishGrace;
    private final Runnable hideBanner = this::removeBanner;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        running = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (isNoise(pkg)) return;
        if (overlay != null) return;                       // lock screen already showing

        StudyState state = new StudyState(this);
        state.rollover();

        if (!state.blocked().contains(pkg)) {
            // The user left the game: forget it and cancel any countdown.
            currentLocked = null;
            handler.removeCallbacks(expiry);
            removeGrace();
            return;
        }

        long left = state.msLeftFor(pkg);
        if (left > 0) {
            // A locked app is open and still has time: note it and schedule its end.
            currentLocked = pkg;
            handler.removeCallbacks(expiry);
            handler.postDelayed(expiry, left);
            return;
        }

        // Locked game and no unlock. This also covers "the screen changed during the countdown".
        removeGrace();
        currentLocked = null;
        showOverlay(pkg, state);
    }
    /** Windows that belong to the system or keyboard, not to the app the user is actually in. */
    private boolean isNoise(String pkg) {
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return true;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(pkg)) return true;
        }
        return false;
    }

    private void startGrace() {
        if (graceActive) return;
        graceActive = true;

        showBanner();
        postGraceNotification();
        handler.postDelayed(hideBanner, BANNER_MS);   // the popup goes away on its own
        handler.postDelayed(graceEnd, GRACE_MS);      // the lock happens when the time is up
    }

    private void finishGrace() {
        String pkg = currentLocked;
        removeGrace();
        currentLocked = null;
        if (pkg == null) return;
        StudyState s = new StudyState(this);
        s.rollover();
        if (!s.isUnlocked()) showOverlay(pkg, s);
    }

    private void removeGrace() {
        if (!graceActive) return;
        graceActive = false;
        handler.removeCallbacks(graceEnd);
        handler.removeCallbacks(hideBanner);
        removeBanner();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(GRACE_NOTIF_ID);
    }

    private void showBanner() {
        graceBanner = LayoutInflater.from(this).inflate(R.layout.overlay_timeup, null);
        ((TextView) graceBanner.findViewById(R.id.graceCount))
                .setText("Locking in " + (GRACE_MS / 1000) + " s");

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (48 * getResources().getDisplayMetrics().density);
        wm.addView(graceBanner, lp);
    }

    private void removeBanner() {
        if (graceBanner != null) {
            wm.removeView(graceBanner);
            graceBanner = null;
        }
    }

    private void postGraceNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Unlock countdown", NotificationManager.IMPORTANCE_LOW));

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Time's up")
                .setContentText("Your locked apps lock when this reaches zero")
                .setWhen(System.currentTimeMillis() + GRACE_MS)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .build();
        nm.notify(GRACE_NOTIF_ID, n);
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

        long remaining = state.goalSec() - state.totalStudiedSec();
        String title, message;
        if (state.perAppActive() && state.isUnlocked()) {
            title = "Time's up";
            message = "You've used your " + state.timedMin() + " minutes in " + label + " for today.";
        }else if (remaining > 0) {
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
        running = false;
        handler.removeCallbacks(autoClose);
        if (overlay != null) {
            wm.removeView(overlay);
            overlay = null;
        }
        handler.removeCallbacks(expiry);
        removeGrace();
    }
    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    private static final long GRACE_MS = 30_000L;   // change to 30 * 60_000L if you really want minutes

    private String currentLocked;        // locked game currently on screen under an unlock
    private View graceBanner;
    private long graceEndsAt;

    private final Runnable expiry = this::checkExpiry;

    private void checkExpiry() {
        if (currentLocked == null) return;
        StudyState s = new StudyState(this);
        s.rollover();
        long left = s.msLeftFor(currentLocked);
        if (left > 1000) {                      // not really up yet: wait the rest
            handler.postDelayed(expiry, left);
            return;
        }
        startGrace();
    }



    @Override
    public void onInterrupt() { }
}
