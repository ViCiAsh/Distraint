package com.damweeb.studylock;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodInfo;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;


/**
 * BlockerService: the accessibility service that actually enforces the lock.
 *
 * It listens for window changes (TYPE_WINDOW_STATE_CHANGED). For each change it works out which app came to
 * the front and decides, using StudyState, whether that app may be used right now.
 *
 *   locked app, no time left   -> full-screen lock overlay (showOverlay), then the user is sent home
 *   locked app, still has time -> remember it (currentLocked) and schedule 'expiry' for when its time ends
 *   expiry fires               -> "Time's up" popup + notification countdown (startGrace), then lock (finishGrace)
 *   any other app              -> forget currentLocked and cancel a running countdown
 *
 * Overlays use TYPE_ACCESSIBILITY_OVERLAY, which needs no extra permission but only works from inside an
 * accessibility service.
 *
 * DEBUGGING TIPS
 *   - Nothing is blocked: is the service enabled AND running? (BlockerService.isRunning(); the red banner on
 *     the home screen reports it.) Some phones kill the service in the background: check the battery exemption.
 *   - Lock screen never appears: check StudyState.blocked(), msLeftFor(pkg), and that usage access is granted
 *     when per-app mode is on.
 *   - Lock screen keeps reappearing or never closes: look at the 'overlay' field; it is null only while no
 *     overlay is showing.
 *   - Countdown notification missing: notification permission (Android 13+) or the "grace" channel was
 *     switched off by the user.
 */
public class BlockerService extends AccessibilityService {

    // true between onServiceConnected and onUnbind/onDestroy. MainActivity reads it (same process) to detect a dead service.
    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }
    // wm: adds and removes overlay windows. overlay: the full-screen lock view while it is showing; null otherwise,
    // so it doubles as the "already showing" guard. handler: main-thread timer for delayed actions.
    private WindowManager wm;
    private View overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Closes the lock overlay a moment after it appears (scheduled in showMessage).
    private final Runnable autoClose = this::closeOverlay;
    private static final long BANNER_MS = 4_000L;    // how long the popup stays up
    // Notification id and channel for the countdown shown in the notification shade.
    private static final int GRACE_NOTIF_ID = 42;
    private static final String CHANNEL_ID = "grace";

    // "Time's up" grace period state. graceEnd locks the app when the countdown ends; hideBanner removes only
    // the popup after BANNER_MS, while the notification keeps counting down.
    private boolean graceActive;
    private final Runnable graceEnd = this::finishGrace;
    private final Runnable hideBanner = this::removeBanner;

    /** Called by Android when the user switches the service on (or the phone restarts it). */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        running = true;
    }

    /**
     * Runs on every window change. Decides what to do about the app that just came to the front:
     *   - system UI, keyboard or StudyLock itself: ignore
     *   - not a locked app: forget the current locked app and cancel any running countdown
     *   - locked app that still has time: remember it and schedule the end of its time (expiry)
     *   - locked app with no time left: show the lock screen (this also covers a screen change during the countdown)
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        // Ignore our own windows, the notification shade and the keyboard: they are not "the app the user is in".
        if (isNoise(pkg)) return;
        if (overlay != null) return;                       // lock screen already showing

        // A fresh StudyState reads the current saved values; rollover() applies the daily reset if it is due.
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

    /**
     * Starts the "Time's up" grace period for the locked app on screen: a short popup, a countdown notification,
     * and a timer that locks the app after GRACE_MS. Does nothing if a grace period is already running.
     */
    private void startGrace() {
        if (graceActive) return;
        graceActive = true;

        showBanner();
        postGraceNotification();
        handler.postDelayed(hideBanner, BANNER_MS);   // the popup goes away on its own
        handler.postDelayed(graceEnd, GRACE_MS);      // the lock happens when the time is up
    }

    /** Grace period over: lock the app, unless it has been unlocked again in the meantime. */
    private void finishGrace() {
        String pkg = currentLocked;
        removeGrace();
        currentLocked = null;
        if (pkg == null) return;
        StudyState s = new StudyState(this);
        s.rollover();
        if (!s.isUnlocked()) showOverlay(pkg, s);
    }

    /** Cancels everything belonging to a grace period (timers, popup, notification). Safe to call at any time. */
    private void removeGrace() {
        if (!graceActive) return;
        graceActive = false;
        handler.removeCallbacks(graceEnd);
        handler.removeCallbacks(hideBanner);
        removeBanner();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(GRACE_NOTIF_ID);
    }

    /** The small "Time's up" popup at the top of the screen. Touches pass through it, so the game stays usable. */
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
        graceBanner.setAlpha(0f);
        graceBanner.setTranslationY(-48 * getResources().getDisplayMetrics().density);
        wm.addView(graceBanner, lp);
        graceBanner.animate().alpha(1f).translationY(0f).setDuration(240).start();
    }

    /** Fades the popup out and removes it. The field is cleared first so other code treats it as gone at once. */
    private void removeBanner() {
        View b = graceBanner;
        if (b == null) return;
        graceBanner = null;
        b.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            try { wm.removeView(b); } catch (Exception ignored) { }
        }).start();
    }

    /** Silent notification with a live countdown to the lock. The system ticks the clock itself (chronometer, counting down). */
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
    /** Formats seconds as mm:ss. */
    private static String clock(long s) {
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /**
     * Works out what the lock screen should say for a locked app (four situations: per-app time used up, study
     * still required, goal reached but not unlocked, today's unlock over) and passes it to showMessage().
     */
    private void showOverlay(String pkg, StudyState state) {
        PackageManager pm = getPackageManager();
        String label = pkg;
        Drawable icon = null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(info).toString();
            icon = pm.getApplicationIcon(info);
        } catch (Exception e) {
            // keep the raw package name and no icon
        }

        long goal = Math.max(1, state.goalSec());
        long total = state.totalStudiedSec();
        long remaining = goal - total;
        String title, message;
        int progress = -1;                 // -1 means "no progress bar"
        String progressText = null;

        if (state.perAppActive() && state.isUnlocked()) {
            title = "Time's up";
            message = "You've used your " + state.timedMin() + " minutes in " + label + " for today.";
        } else if (remaining > 0) {
            long mins = (remaining + 59) / 60;
            title = "Study first";
            message = mins + (mins == 1 ? " more minute" : " more minutes")
                    + " of studying unlocks your apps.";
            progress = (int) (1000 * Math.min(total, goal) / goal);
            progressText = clock(total) + " / " + clock(goal);
        } else if (state.canUnlock()) {
            title = "You've earned it";
            message = "Open StudyLock and unlock your apps.";
        } else {
            title = "Not today";
            message = "Today's unlock has ended. Apps open again after the daily reset.";
        }
        showMessage(icon, label + " is locked", title, message, progress, progressText);
    }

    /** Builds and shows the full-screen lock overlay. progress < 0 hides the progress bar. Closes itself after 2 s. */
    private void showMessage(Drawable icon, String appLine, String title, String message,
                             int progress, String progressText) {
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay_block, null);

        ImageView iconView = overlay.findViewById(R.id.overlayIcon);
        if (icon != null) iconView.setImageDrawable(icon);
        else iconView.setVisibility(View.GONE);

        ((TextView) overlay.findViewById(R.id.overlayApp)).setText(appLine);
        ((TextView) overlay.findViewById(R.id.overlayTitle)).setText(title);
        ((TextView) overlay.findViewById(R.id.overlayMessage)).setText(message);

        View box = overlay.findViewById(R.id.overlayProgressBox);
        if (progress >= 0) {
            ((ProgressBar) overlay.findViewById(R.id.overlayProgress)).setProgress(progress);
            ((TextView) overlay.findViewById(R.id.overlayProgressText)).setText(progressText);
            box.setVisibility(View.VISIBLE);
        } else {
            box.setVisibility(View.GONE);
        }
        overlay.setOnClickListener(v -> closeOverlay());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        overlay.setAlpha(0f);                                  // start invisible, fade in
        wm.addView(overlay, lp);
        overlay.animate().alpha(1f).setDuration(180).start();

        handler.postDelayed(autoClose, 2000);
    }

    /** Sends the user to the home screen, then removes the overlay a moment later so the app is never seen again. */
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

    /** Service is stopping: clean up so no overlay or timer is left behind. */
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
    // Called when the service is switched off in Android's settings.
    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    // Length of the "Time's up" countdown, in milliseconds (30_000 = 30 seconds).
    private static final long GRACE_MS = 30_000L;   // change to 30 * 60_000L if you really want minutes

    // Package of the locked app the user is in while it still has time (null otherwise). Set in onAccessibilityEvent;
    // expiry and finishGrace use it to know which app to lock.
    private String currentLocked;        // locked game currently on screen under an unlock
    // The popup view while it is showing (null otherwise).
    private View graceBanner;
    // UNUSED: left over from an earlier on-screen ticking countdown; safe to delete.
    private long graceEndsAt;

    // Fires when the current locked app's time should be over; checkExpiry() double-checks before locking.
    private final Runnable expiry = this::checkExpiry;

    /** Re-checks the app's remaining time (timers can fire early after the phone sleeps); starts the grace period only if time is truly up. */
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



    // Required by AccessibilityService; nothing to do.
    @Override
    public void onInterrupt() { }
}