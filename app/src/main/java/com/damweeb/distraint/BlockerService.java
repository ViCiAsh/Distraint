package com.damweeb.distraint;

import android.view.accessibility.AccessibilityNodeInfo;
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
import android.os.SystemClock;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;


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
    // ---- "Heads-up" warning shortly before an unlock ends (separate from the grace period after it ends) ----
    private static final long WARN_MS = 5 * 60_000L;        // how long before the end the warning appears
    private static final long WARN_BANNER_MS = 6_000L;      // how long the warning popup stays up
    private View warnBanner;                                // the popup while showing (null otherwise)
    // Which unlocks we already warned about: key ("shared" or an app package) -> the unlock_at value it was shown for.
    private final Map<String, Long> warnedFor = new HashMap<>();
    private final Runnable warn = this::checkWarn;          // fires WARN_MS before the end
    private final Runnable hideWarn = this::removeWarnBanner;

    // Uninstall protection: the system screens that can remove or stop an app. Nothing else is ever read.
    private static final String[] EXIT_SCREEN_PACKAGES = {
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller"
    };
    private long lastGuardMs;                              // throttles the guard: content events arrive in bursts

    /** Called by Android when the user switches the service on (or the phone restarts it). */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        running = true;
    }

    /**
     * Runs on every window change. Decides what to do about the app that just came to the front:
     *   - system UI, keyboard or Distraint itself: ignore
     *   - not a locked app: forget the current locked app and cancel any running countdown
     *   - locked app that still has time: remember it and schedule the end of its time (expiry)
     *   - locked app with no time left: show the lock screen (this also covers a screen change during the countdown)
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence guardPkg = event.getPackageName();
        if (guardPkg != null) guardExitScreens(guardPkg.toString());   // uninstall protection, for window AND content events
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (isNoise(pkg)) return;
        if (overlay != null) return;                       // lock screen already showing

        StudyState state = new StudyState(this);
        state.rollover();

        // STRICT LOCK comes first and ignores study progress and unlocks completely.
        handler.removeCallbacks(strictCheck);
        currentStrictPkg = null;
        if (state.isStrictLocked(pkg)) {
            currentLocked = null;                          // drop any unlock bookkeeping for this app
            handler.removeCallbacks(expiry);
            handler.removeCallbacks(warn);
            removeWarnBanner();
            removeGrace();
            showStrictOverlay(pkg, state);
            return;
        }
        // A strict app is open but no lock is on yet: if a daily window starts while it stays open,
        // no window event would fire by itself, so schedule a re-check for the start (plus half a second
        // so the clock minute has certainly changed). This does not return: the normal rules still apply.
        if (state.strictApps().contains(pkg)) {
            long untilStart = state.msUntilNextStrictStart();
            if (untilStart >= 0) {
                currentStrictPkg = pkg;
                handler.postDelayed(strictCheck, untilStart + 500);
            }
        }

        if (!state.blocked().contains(pkg)) {
            // The user left the game: forget it and cancel any countdown.
            currentLocked = null;
            handler.removeCallbacks(expiry);
            handler.removeCallbacks(warn);                 // NEW: cancel a pending warning
            removeWarnBanner();                            // NEW: and hide one that is showing
            removeGrace();
            return;
        }

        long left = state.msLeftFor(pkg);
        if (left > 0) {
            // A locked app is open and still has time: note it and schedule its end.
            currentLocked = pkg;
            handler.removeCallbacks(expiry);
            handler.postDelayed(expiry, left);
            scheduleWarning(state, pkg, left);             // NEW: also schedule the heads-up
            return;
        }

        // Locked game and no unlock. This also covers "the screen changed during the countdown".
        handler.removeCallbacks(warn);                     // NEW: no warning for a lock that is already on
        removeWarnBanner();
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
            message = "Open Distraint and unlock your apps.";
        } else {
            title = "Not today";
            message = "Today's unlock has ended. Apps open again after the daily reset.";
        }
        showMessage(icon, label + " is locked", title, message, progress, progressText);
    }

    /** Lock screen for a strictly locked app: says when the lock ends and that studying will not open it. */
    private void showStrictOverlay(String pkg, StudyState state) {
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

        long end = state.strictEndsAt();
        String message;
        if (end > System.currentTimeMillis()) {
            ZonedDateTime endZ = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault());
            boolean today = endZ.toLocalDate().equals(LocalDate.now());
            String when = DateTimeFormatter
                    .ofPattern(today ? "HH:mm" : "EEE HH:mm", Locale.getDefault()).format(endZ);
            message = "This app can't be opened until " + when + ". Studying won't unlock it.";
        } else {
            message = "This app is locked right now.";     // the lock ended a moment ago
        }
        showMessage(icon, label + " is locked", "Strict lock", message, -1, null);
    }

    /** Fires when a daily window should have started while a strict app was open. Re-checks first, like checkExpiry(). */
    private void checkStrict() {
        String pkg = currentStrictPkg;
        if (pkg == null || overlay != null) return;
        StudyState s = new StudyState(this);
        s.rollover();
        if (s.isStrictLocked(pkg)) {
            currentStrictPkg = null;
            currentLocked = null;
            handler.removeCallbacks(expiry);
            handler.removeCallbacks(warn);
            removeWarnBanner();
            removeGrace();
            showStrictOverlay(pkg, s);
        } else {
            long next = s.msUntilNextStrictStart();        // fired early: wait for the real start
            if (next >= 0) handler.postDelayed(strictCheck, next + 500);
        }
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
        handler.removeCallbacks(strictCheck);
        removeGrace();
        handler.removeCallbacks(warn);
        removeWarnBanner();
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

    // Strict lock: a strict app that is open right now while no lock is active yet (null otherwise),
    // and the timer that re-checks it when the next daily window begins.
    private String currentStrictPkg;
    private final Runnable strictCheck = this::checkStrict;

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
    /**
     * Schedules the heads-up for a locked app that is open and still has time. Called on every window event of
     * that app, so it first cancels any earlier schedule. Skipped when this unlock was already warned about, or
     * when the whole unlock is no longer than the warning time (it would pop up the moment the app opens).
     */
    private void scheduleWarning(StudyState s, String pkg, long leftMs) {
        handler.removeCallbacks(warn);
        if (s.mode() == StudyState.MODE_TIMED && s.timedMin() * 60_000L <= WARN_MS) return;
        Long done = warnedFor.get(warnKey(s, pkg));
        if (done != null && done == s.unlockAt()) return;            // already warned for this unlock
        handler.postDelayed(warn, Math.max(0, leftMs - WARN_MS));     // 0 = warn now (under 5 min left already)
    }

    /** Shared mode has one window for all apps, so one warning; per-app mode warns once per app. */
    private String warnKey(StudyState s, String pkg) {
        return s.perAppActive() ? pkg : "shared";
    }

    /**
     * Fires WARN_MS before the end. Like checkExpiry(), it re-reads the remaining time first, because a
     * timer can fire early after the phone sleeps. Only shows the popup if it is really time.
     */
    private void checkWarn() {
        if (currentLocked == null || graceActive || overlay != null) return;
        StudyState s = new StudyState(this);
        s.rollover();
        long left = s.msLeftFor(currentLocked);
        if (left <= 0) return;                                        // already over: expiry handles it
        if (left > WARN_MS + 1000) {                                  // too early: wait the difference
            handler.postDelayed(warn, left - WARN_MS);
            return;
        }
        warnedFor.put(warnKey(s, currentLocked), s.unlockAt());       // remember: only once per unlock
        showWarnBanner(left);
    }

    /** Amber popup at the top: "N minutes left". Touches pass through, so the game stays usable. */
    private void showWarnBanner(long leftMs) {
        removeWarnBanner();
        long mins = Math.round(leftMs / 60_000.0);
        String title = mins >= 1
                ? mins + (mins == 1 ? " minute left" : " minutes left")
                : "Less than a minute left";

        warnBanner = LayoutInflater.from(this).inflate(R.layout.overlay_warning, null);
        ((TextView) warnBanner.findViewById(R.id.warnTitle)).setText(title);
        ((TextView) warnBanner.findViewById(R.id.warnSub)).setText("before your unlock ends");

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        float density = getResources().getDisplayMetrics().density;
        lp.y = (int) (48 * density);
        warnBanner.setAlpha(0f);
        warnBanner.setTranslationY(-48 * density);
        wm.addView(warnBanner, lp);
        warnBanner.animate().alpha(1f).translationY(0f).setDuration(240).start();

        handler.postDelayed(hideWarn, WARN_BANNER_MS);                // the popup removes itself
    }

    /** Fades the warning popup out. Clears the field first so other code treats it as gone at once. */
    private void removeWarnBanner() {
        handler.removeCallbacks(hideWarn);
        View b = warnBanner;
        if (b == null) return;
        warnBanner = null;
        b.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            try { wm.removeView(b); } catch (Exception ignored) { }
        }).start();
    }

    /**
     * Uninstall protection. On a Settings or installer screen whose text is about Distraint AND offers to remove or
     * stop it (uninstall, force stop, deactivate, clear storage, or the accessibility switch/stop dialog), cover the
     * screen and send the user home. Does nothing when protection is off (see Settings > Uninstall protection).
     * Matches English wording only.
     */
    private void guardExitScreens(String pkg) {
        boolean listed = false;
        for (String g : EXIT_SCREEN_PACKAGES) {
            if (g.equals(pkg)) { listed = true; break; }
        }
        if (!listed || overlay != null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastGuardMs < 300) return;               // events come in bursts; look at most every 300 ms
        lastGuardMs = now;
        if (!new StudyState(this).protectEnabled()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !pkg.contentEquals(root.getPackageName())) return;
        StringBuilder sb = new StringBuilder();
        collectText(root, sb, new int[]{0});
        String text = sb.toString().toLowerCase(Locale.ROOT);

        String us = getApplicationInfo().loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT);
        if (!text.contains(us)) return;                    // not about Distraint
        // Settings: look for an actual removal control. Installer dialogs: the whole dialog is about one app,
        // and on some Android versions its buttons are just "OK", so the word "uninstall" anywhere is enough.
        boolean removes = pkg.equals("com.android.settings")
                ? hasRemovalControl(root, us, new int[]{0})
                : text.contains("uninstall");
        if (!removes) return;

        showMessage(null, "Distraint is protected", "Protected",
                "This screen can remove or stop Distraint. To change that, turn off protection in Distraint's settings.",
                -1, null);
    }

    /** Appends the text of a screen's views to `out`. Capped at 400 views so a huge screen can't make this slow. */
    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0]++ > 400) return;
        CharSequence t = node.getText();
        if (t != null) out.append(t).append('\n');
        CharSequence d = node.getContentDescription();
        if (d != null) out.append(d).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, count);
    }
    // Labels that START the views that remove or stop an app. Only short texts count (see isRemovalText).
    private static final String[] REMOVAL_STARTS = {
            "uninstall", "force stop", "deactivate", "clear storage", "clear data"
    };

    /** True if some view on this screen is a control that removes or stops us. Capped at 400 views. */
    private boolean hasRemovalControl(AccessibilityNodeInfo node, String us, int[] count) {
        if (node == null || count[0]++ > 400) return false;
        if (isRemovalText(node.getText(), us) || isRemovalText(node.getContentDescription(), us)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasRemovalControl(node.getChild(i), us, count)) return true;
        }
        return false;
    }

    /** A short text that begins with a removal action. Long sentences are explanations, not controls, so they never count. */
    private static boolean isRemovalText(CharSequence cs, String us) {
        if (cs == null) return false;
        String t = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.length() > 45) return false;
        if (t.startsWith("use " + us) || t.startsWith("stop " + us)) return true;   // accessibility switch / stop dialog
        for (String s : REMOVAL_STARTS) {
            if (t.startsWith(s)) return true;
        }
        return false;
    }



    // Required by AccessibilityService; nothing to do.
    @Override
    public void onInterrupt() { }
}