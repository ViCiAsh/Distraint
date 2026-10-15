package com.damweeb.studylock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * MainActivity: the home screen.
 *
 * Shows the progress ring (study time vs goal), the Start/Pause and Unlock buttons, the two app lists
 * (locked apps, study apps), the "+" menu, and a setup card for missing permissions. It also runs the
 * first-run tour (runOnboarding) that asks for permissions and the first-time settings.
 * All saved data lives in StudyState; the actual blocking is done by BlockerService.
 *
 * LIFECYCLE: onResume() is the main refresh point (reset check, lists, tour, permission buttons, warning
 * banner, ring). The in-app timer only runs while this screen is in front: onPause() stops it.
 *
 * DEBUGGING TIPS
 *   - NullPointerException in onCreate: a findViewById id is missing from activity_main.xml (or misspelled).
 *   - Ring or clock wrong: look at StudyState.totalStudiedSec() / goalSec(), then refresh().
 *   - Lists empty or wrong: loadLists() and StudyState.blocked() / studyApps().
 *   - A tour question appears again or never: StudyState.onboardStep() and the *_chosen flags
 *     (clear the app's storage in App info to rerun the tour).
 */
public class MainActivity extends AppCompatActivity {
    // ---- Views (looked up once in onCreate) ----
    private ListView lockedList, studyAppList;
    private StudyState state;
    private TextView timerText, statusText;
    private Button toggleButton, unlockButton;
    private CircularProgressIndicator ring;
    private TextView goalText;
    // animateRing: true for the first redraw after returning to this screen, so the ring glides instead of jumping.
    // lastMet: whether the goal was already reached at the last refresh, so the "goal reached" haptic fires once.
    private boolean animateRing;
    private boolean lastMet;
    // Main-thread handler: drives the study timer loop and a delayed banner re-check.
    private final Handler handler = new Handler(Looper.getMainLooper());
    // studying: the in-app timer is running. lastTick: elapsedRealtime when the last whole second was counted.
    private boolean studying;
    private long lastTick;
    private TextView lockedCount, studyCount;

    /**
     * The study timer loop: every 200 ms it adds the whole seconds elapsed since lastTick to today's study
     * time, then redraws. It uses elapsedRealtime (monotonic), so changing the phone's clock cannot
     * fast-forward study time. Started by startStudying(), stopped by stopStudying().
     */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // Monotonic clock, so changing the phone's time can't fast-forward studying.
            long now = SystemClock.elapsedRealtime();
            long secs = (now - lastTick) / 1000;
            if (secs >= 1) {
                state.addStudied(secs);
                lastTick += secs * 1000;
            }
            refresh();
            if (studying) handler.postDelayed(this, 200);
        }
    };

    /**
     * Finds the views and wires up every click listener. A NullPointerException in here almost always
     * means a view id is missing from (or misspelled in) activity_main.xml.
     */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        state = new StudyState(this);
        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        unlockButton = findViewById(R.id.unlockButton);

        // Start/Pause: haptic tap, then toggle the in-app timer.
        toggleButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (studying) stopStudying(); else startStudying();
        });
        // Unlock: the button is only enabled once the goal is reached (see refresh()); StudyState.unlock() sets the window.
        unlockButton.setOnClickListener(v -> {
            v.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            state.unlock();
            refresh();
        });
        // Setup-card buttons open the system screen where each permission is granted.
        findViewById(R.id.accessButton).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        // "+" menu: opens GamePickerActivity for the locked apps (extra study=false) or the study apps (study=true).
        findViewById(R.id.addButton).setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add(0, 0, 0, "Apps to lock");
            menu.getMenu().add(0, 1, 1, "Study apps");
            menu.setOnMenuItemClickListener(item -> {
                Intent i = new Intent(this, GamePickerActivity.class);
                i.putExtra("study", item.getItemId() == 1);
                startActivity(i);
                return true;
            });
            menu.show();
        });
        findViewById(R.id.batteryButton).setOnClickListener(v -> requestBatteryExemption());
        findViewById(R.id.usageButton).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        // Gear icon in the top bar.
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.adminButton).setOnClickListener(v -> requestAdmin());
        // Red banner (see updateBlockerWarning) opens accessibility settings.
        findViewById(R.id.warningBanner).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Remaining views. The "empty" views are shown automatically when a list has no rows.
        ring = findViewById(R.id.ring);
        goalText = findViewById(R.id.goalText);
        lockedCount = findViewById(R.id.lockedCount);
        studyCount = findViewById(R.id.studyCount);
        lockedList = findViewById(R.id.lockedList);
        studyAppList = findViewById(R.id.studyList);
        lockedList.setEmptyView(findViewById(R.id.lockedEmpty));
        studyAppList.setEmptyView(findViewById(R.id.studyEmpty));
    }

    /** True if Android is not restricting this app's background running (needed to keep the blocker alive). */
    private boolean hasBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /**
     * Opens Android's "allow background running" prompt. If nothing happens: the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission must be in the manifest as a direct child of
     * <manifest>. The toasts are leftover debugging aids.
     */
    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        if (hasBatteryExemption()) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            updatePermissionButtons();
            return;
        }
        Toast.makeText(this, "Opening battery settings", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    /** Main refresh point: runs every time the screen comes to the front. */
    @Override
    protected void onResume() {
        super.onResume();

        // Re-check everything that may have changed while the user was away:
        // daily reset -> lists -> first-run tour -> permission buttons -> blocker warning -> ring.
        state.rollover();
        loadLists();
        runOnboarding();
        updatePermissionButtons();
        updateBlockerWarning();
        // Check again after 2 s: right after launch the accessibility service may still be reconnecting,
        // which would flash a false "blocker isn't running" banner.
        handler.postDelayed(this::updateBlockerWarning, 2000);
        // Make the next refresh() animate the ring once.
        animateRing = true;
        refresh();
    }

    // Leaving the app pauses the timer, so you have to stay here to earn credit.
    @Override protected void onPause() {
        super.onPause();
        stopStudying();
    }

    /** Starts the in-app timer: keeps the screen on while studying and begins the tick loop. */
    private void startStudying() {
        studying = true;
        lastTick = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        toggleButton.setText("Pause");
        handler.post(tick);
    }

    /** Stops the timer and lets the screen sleep again. Safe to call when not studying (onPause does). */
    private void stopStudying() {
        studying = false;
        handler.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (toggleButton != null) toggleButton.setText("Start studying");
    }

    /**
     * Fills the two home-screen lists. This method decides what each row says; AppAdapter only displays it.
     * Locked apps: "Locked - used ..." while locked, time left when unlocked (per-app or shared window),
     * or usage when unlocked until the reset. Study apps: plain time (combined mode) or "time / required"
     * with a bar (individual mode). Apps queued for the next reset get a note instead of a timer.
     */
    private void loadLists() {
        Map<String, Long> usage = state.appSeconds();

        // ---- locked apps ----
        List<String> locked = new ArrayList<>(state.blocked());
        Collections.sort(locked);
        Map<String, String> lockedSub = new HashMap<>();
        Map<String, Integer> lockedBar = new HashMap<>();

        // windowLeftSec: seconds left in the one shared unlock window.
        // budgetSec: length of one timed unlock (the 100% mark of the progress bars).
        long windowLeftSec = Math.max(0, (state.unlockedUntil() - System.currentTimeMillis()) / 1000);
        long budgetSec = state.timedMin() * 60L;
        // One subtitle (and maybe a progress-bar value) per locked app, depending on the current unlock situation.
        for (String pkg : locked) {
            long used = usage.getOrDefault(pkg, 0L);
            if (!state.isUnlocked()) {
                lockedSub.put(pkg, "Locked · used " + clock(used));
            } else if (state.perAppActive()) {
                long left = Math.max(0, state.msLeftFor(pkg) / 1000);
                lockedSub.put(pkg, clock(left) + " left");
                lockedBar.put(pkg, (int) (1000 * left / Math.max(1, budgetSec)));
            } else if (state.mode() == StudyState.MODE_TIMED) {
                lockedSub.put(pkg, clock(windowLeftSec) + " left");
                lockedBar.put(pkg, (int) (1000 * windowLeftSec / Math.max(1, budgetSec)));
            } else {
                lockedSub.put(pkg, "Unlocked · used " + clock(used));
            }
        }
        lockedList.setAdapter(new AppAdapter(this, locked, state.pendingRemovals(),
                " · unlocks at next reset", lockedSub, lockedBar));
        lockedCount.setText(String.valueOf(locked.size()));

        // ---- study apps ----
        Set<String> pendingAdds = state.pendingStudyAdds();
        Set<String> studyPkgs = state.studyApps();
        studyPkgs.addAll(pendingAdds);
        List<String> study = new ArrayList<>(studyPkgs);
        Collections.sort(study);
        Map<String, String> studySub = new HashMap<>();
        Map<String, Integer> studyBar = new HashMap<>();

        // cap: the per-app requirement in individual mode. Queued (pending) study apps are not measured yet, so they get no timer.
        long cap = state.requiredMin() * 60L;
        boolean each = state.individualActive();
        for (String pkg : study) {
            if (pendingAdds.contains(pkg)) continue;          // not measured yet
            long s = usage.getOrDefault(pkg, 0L);
            if (each) {
                long shown = Math.min(s, cap);
                studySub.put(pkg, clock(shown) + " / " + clock(cap));
                studyBar.put(pkg, (int) (1000 * shown / Math.max(1, cap)));
            } else {
                studySub.put(pkg, clock(s));
            }
        }
        studyAppList.setAdapter(new AppAdapter(this, study, pendingAdds,
                " · from next reset", studySub, studyBar));
        studyCount.setText(String.valueOf(study.size()));
    }

    /** True if StudyLock is an active device admin (what blocks uninstalling and clearing its data from App info). */
    private boolean isAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    /** The system screen that asks the user to activate StudyLock as a device admin. */
    private Intent adminIntent() {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, AdminReceiver.class));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Stops StudyLock from being uninstalled with one tap.");
        return i;
    }

    // Used by the "Protect from uninstall" button.
    private void requestAdmin() {
        startActivity(adminIntent());
    }

    // True while a first-run dialog is on screen, so runOnboarding() never stacks a second one.
    private boolean asking;

    /**
     * First-run tour: asks the next unanswered question, one at a time. Called from onResume and after every
     * dialog answer, so each call picks up where the previous one left off.
     *
     * Steps: 0 accessibility, 1 usage access, 2 battery exemption, 3 uninstall protection (device admin),
     *        4 notifications, 5 how study apps count, 6 daily study minutes, 7 how unlock time is counted,
     *        8 unlock length.
     * The step counter moves forward BEFORE a question is shown, so every step is asked at most once, even if
     * it is skipped or the app is killed. Steps that are already satisfied are skipped silently (continue).
     *
     * DEBUG: to rerun the tour, clear the app's storage in Android's App info. If you add a step, raise the
     * loop limit in the while condition.
     */
    @SuppressLint("BatteryLife")
    private void runOnboarding() {
        if (asking) return;                          // a dialog is already on screen
        while (state.onboardStep() < 9) {
            int step = state.onboardStep();
            state.setOnboardStep(step + 1); // remember we asked, even if skipped
            // Steps 4 to 8 are handled here; steps 0 to 3 are permission screens handled by the switch below.
            if (step == 4) {
                if (Build.VERSION.SDK_INT < 33
                        || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) continue;
                askNotifications();
                return;
            }
            if (step == 5) {
                if (state.combineChosen()) continue;      // already decided, nothing to ask
                askCombineMode();
                return;
            }
            if (step == 6) {
                if (state.requiredChosen()) continue;
                askNumber("Daily study time",
                        "How many minutes do you need to study each day before your locked apps unlock?",
                        state.requiredMin(), 1, 600,
                        minutes -> state.chooseRequiredFirstTime(minutes));
                return;
            }
            if (step == 7) {
                if (state.limitChosen()) continue;
                askLimitStyle();
                return;
            }
            if (step == 8) {
                if (state.unlockChosen()) continue;
                askNumber("Unlock length",
                        "After you've studied enough, for how many minutes should your locked apps stay open? "
                                + "Enter 0 to keep them open until the daily reset.",
                        state.unlockMinutes(), 0, 1440,
                        minutes -> state.chooseUnlockFirstTime(minutes));
                return;
            }

            // Steps 0-3: show an explanation, then open the matching system settings page (see askFor).
            String title, message;
            Intent intent;
            switch (step) {
                case 0:
                    if (isBlockerEnabled()) continue;
                    title = "Accessibility";
                    message = "StudyLock uses this to notice when a locked game opens. "
                            + "On the next screen, find StudyLock and turn it on.";
                    intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    break;
                case 1:
                    if (state.hasUsageAccess()) continue;
                    title = "Usage access";
                    message = "This lets StudyLock count the time you spend in your study apps. "
                            + "Find StudyLock in the list and allow it.";
                    intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    break;
                case 2:
                    if (hasBatteryExemption()) continue;
                    title = "Background running";
                    message = "This keeps your phone from shutting the blocker down to save battery.";
                    intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    break;
                // step 3
                default:
                    if (isAdminActive()) continue;
                    title = "Uninstall protection";
                    message = "This makes StudyLock harder to remove on an impulse.";
                    intent = adminIntent();
                    break;
            }
            askFor(title, message, intent);
            return;
        }
    }

    /** Explains the notification countdown, then shows Android's permission prompt (Android 13+). */
    private void askNotifications() {
        asking = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Notifications")
                .setMessage("StudyLock shows a countdown in your notifications when your unlock time is about to run out.")
                .setCancelable(false)
                .setPositiveButton("Continue", (d, w) -> {
                    asking = false;
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
                })
                .setNegativeButton("Skip", (d, w) -> {
                    asking = false;
                    runOnboarding();
                })
                .show();
    }

    /** First-run question: shared unlock timer or individual time per locked app. Applies immediately. */
    private void askLimitStyle() {
        asking = true;
        String[] options = {
                "Shared: one timer for all locked apps",
                "Individual: each locked app gets its own time"
        };
        final int[] picked = {0};

        new MaterialAlertDialogBuilder(this)
                .setTitle("How should unlock time count?")
                .setSingleChoiceItems(options, 0, (d, which) -> picked[0] = which)
                .setCancelable(false)
                .setPositiveButton("Confirm", (d, w) -> {
                    asking = false;
                    state.chooseLimitFirstTime(picked[0] == 1
                            ? StudyState.LIMIT_PER_APP : StudyState.LIMIT_SHARED);
                    runOnboarding();
                })
                .show();
    }

    // Called after the system notification-permission prompt; whatever the answer was, continue the tour.
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        runOnboarding();
    }

    /**
     * First-run question: how study apps count (combined or individual). Applies immediately.
     * Note: this one uses AppCompat's AlertDialog.Builder while the other dialogs use MaterialAlertDialogBuilder,
     * so it looks slightly different; switching the builder would make them consistent.
     */
    private void askCombineMode() {
        asking = true;
        String[] options = {
                "Combined: time adds up across all study apps",
                "Individual: every study app needs the full time"
        };
        final int[] picked = {StudyState.COMBINE_TOTAL};

        new AlertDialog.Builder(this)
                .setTitle("How should study apps count?")
                .setSingleChoiceItems(options, 0, (d, which) -> picked[0] = which)
                .setCancelable(false)
                .setPositiveButton("Confirm", (d, w) -> {
                    asking = false;
                    state.chooseCombineFirstTime(picked[0] == 1
                            ? StudyState.COMBINE_INDIVIDUAL : StudyState.COMBINE_TOTAL);
                    refresh();
                    runOnboarding();            // continue the tour with the next question
                })
                .show();
    }

    /** Explanation dialog for a permission step: Continue opens the system screen, Skip moves on. */
    private void askFor(String title, String message, Intent intent) {
        asking = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Continue", (d, w) -> {
                    asking = false;
                    startActivity(intent);
                })
                .setNegativeButton("Skip", (d, w) -> {
                    asking = false;
                    runOnboarding();
                })
                .show();
    }

    /** Shows each setup button only while its permission is missing, and the setup card only if something is. */
    private void updatePermissionButtons() {
        findViewById(R.id.usageButton).setVisibility(
                state.hasUsageAccess() ? View.GONE : View.VISIBLE);
        findViewById(R.id.accessButton).setVisibility(
                isBlockerEnabled() ? View.GONE : View.VISIBLE);
        findViewById(R.id.batteryButton).setVisibility(
                hasBatteryExemption() ? View.GONE : View.VISIBLE);
        findViewById(R.id.adminButton).setVisibility(
                isAdminActive() ? View.GONE : View.VISIBLE);
        boolean anyMissing = !state.hasUsageAccess() || !isBlockerEnabled()
                || !hasBatteryExemption() || !isAdminActive();
        findViewById(R.id.setupCard).setVisibility(anyMissing ? View.VISIBLE : View.GONE);
    }

    /**
     * True if the accessibility service is switched on in Android's settings (string match on the list of
     * enabled services). That does not prove it is running: the phone may have killed it, which
     * BlockerService.isRunning() reports.
     */
    private boolean isBlockerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    /**
     * Shows a dialog with a number box (clamped to min..max; junk falls back to `initial`). `onChosen` receives
     * the answer, so the same dialog serves several questions. Afterwards the tour continues.
     */
    private void askNumber(String title, String message, int initial,
                           int min, int max, IntConsumer onChosen) {
        asking = true;

        View content = getLayoutInflater().inflate(R.layout.dialog_number, null);
        TextInputEditText input = content.findViewById(R.id.numberInput);
        input.setText(String.valueOf(initial));
        ((TextInputLayout) content.findViewById(R.id.numberLayout))
                .setHelperText("From " + min + " to " + max);

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton("Confirm", (d, w) -> {
                    int v = initial;
                    try {
                        v = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException ignored) { }
                    asking = false;
                    onChosen.accept(Math.max(min, Math.min(max, v)));
                    refresh();
                    runOnboarding();                 // carry on to the next question
                })
                .show();
    }

    /** Formats seconds as mm:ss. */
    private static String clock(long s) {
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /**
     * Redraws the ring, clock, status text and Unlock button from StudyState. Cheap enough to call often: the
     * study timer calls it every 200 ms. Also gives a haptic tap the moment the goal is reached while studying.
     */
    private void refresh() {
        state.rollover();
        long total = state.totalStudiedSec();
        long goal = Math.max(1, state.goalSec());
        timerText.setText(clock(total));
        goalText.setText("of " + clock(goal));
        ring.setProgressCompat((int) (1000 * Math.min(total, goal) / goal), animateRing);
        animateRing = false;               // only the first draw after returning animates

        // Status line under the ring: unlocked / ready to unlock / what is still required (wording depends on the counting mode).
        String status;
        if (state.isUnlocked()) status = "Apps are unlocked.";
        else if (state.canUnlock()) status = "Goal reached. You can unlock your apps.";
        else {
            if (state.individualActive()) {
                int n = state.studyApps().size();
                status = String.format(Locale.US,
                        "Reach %d min: each of your %d study apps counts up to %d min, "
                                + "and the Start studying timer has no limit.",
                        state.requiredMin() * n, n, state.requiredMin());
            } else {
                status = String.format(Locale.US,
                        "Study %d min today to unlock games.", state.requiredMin());
            }
        }
        statusText.setText(status);
        unlockButton.setEnabled(state.canUnlock());
        unlockButton.setText(state.isUnlocked() ? "Unlocked" : "Unlock");
        boolean met = state.requirementMet();
        if (met && !lastMet && studying) {
            timerText.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
        }
        lastMet = met;
    }
    /**
     * Red banner when locked apps exist but the blocker cannot protect them: either the accessibility service is
     * switched off, or it is on but not running (killed by the phone). Hidden while nothing is locked yet.
     */
    private void updateBlockerWarning() {
        TextView banner = findViewById(R.id.warningBanner);

        // Nothing to protect yet, so no need to alarm anyone during first setup.
        if (state.blocked().isEmpty()) {
            banner.setVisibility(View.GONE);
            return;
        }

        if (!isBlockerEnabled()) {
            banner.setText("Blocker is off. Your games are NOT locked. Tap to fix.");
            banner.setVisibility(View.VISIBLE);
        } else if (!BlockerService.isRunning()) {
            banner.setText("Blocker isn't running. Tap, then switch StudyLock off and on.");
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
    }
}