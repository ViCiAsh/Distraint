package com.damweeb.studylock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private ListView lockedList, studyAppList;
    private StudyState state;
    private TextView timerText, statusText;
    private Button toggleButton, unlockButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean studying;
    private long lastTick;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        state = new StudyState(this);
        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        unlockButton = findViewById(R.id.unlockButton);

        toggleButton.setOnClickListener(v -> { if (studying) stopStudying(); else startStudying(); });
        unlockButton.setOnClickListener(v -> { state.unlock(); refresh(); });
        findViewById(R.id.accessButton).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
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
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.adminButton).setOnClickListener(v -> requestAdmin());

        lockedList = findViewById(R.id.lockedList);
        studyAppList = findViewById(R.id.studyList);
        lockedList.setEmptyView(findViewById(R.id.lockedEmpty));
        studyAppList.setEmptyView(findViewById(R.id.studyEmpty));
    }

    private boolean hasBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

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

    @Override
    protected void onResume() {
        super.onResume();
        state.rollover();
        loadLists();
        runOnboarding();
        updatePermissionButtons();
        refresh();
    }

    // Leaving the app pauses the timer, so you have to stay here to earn credit.
    @Override protected void onPause() {
        super.onPause();
        stopStudying();
    }

    private void startStudying() {
        studying = true;
        lastTick = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        toggleButton.setText("Pause");
        handler.post(tick);
    }

    private void stopStudying() {
        studying = false;
        handler.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (toggleButton != null) toggleButton.setText("Start studying");
    }

    private void loadLists() {
        List<String> locked = new ArrayList<>(state.blocked());
        Collections.sort(locked);
        lockedList.setAdapter(new AppAdapter(this, locked,
                state.pendingRemovals(), " · unlocks at next reset"));

        Set<String> pendingAdds = state.pendingStudyAdds();
        Set<String> studyPkgs = state.studyApps();
        studyPkgs.addAll(pendingAdds);
        List<String> study = new ArrayList<>(studyPkgs);
        Collections.sort(study);
        studyAppList.setAdapter(new AppAdapter(this, study,
                pendingAdds, " · from from next reset"));
    }

    private boolean isAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    private Intent adminIntent() {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, AdminReceiver.class));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Stops StudyLock from being uninstalled with one tap.");
        return i;
    }

    private void requestAdmin() {
        startActivity(adminIntent());
    }

    private boolean asking;

    @SuppressLint("BatteryLife")
    private void runOnboarding() {
        if (asking) return;                          // a dialog is already on screen
        while (state.onboardStep() < 4) {
            int step = state.onboardStep();
            state.setOnboardStep(step + 1);          // remember we asked, even if skipped

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

    private void askFor(String title, String message, Intent intent) {
        asking = true;
        new AlertDialog.Builder(this)
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

    private void updatePermissionButtons() {
        findViewById(R.id.usageButton).setVisibility(
                state.hasUsageAccess() ? View.GONE : View.VISIBLE);
        findViewById(R.id.accessButton).setVisibility(
                isBlockerEnabled() ? View.GONE : View.VISIBLE);
        findViewById(R.id.batteryButton).setVisibility(
                hasBatteryExemption() ? View.GONE : View.VISIBLE);
        findViewById(R.id.adminButton).setVisibility(
                isAdminActive() ? View.GONE : View.VISIBLE);
    }

    private boolean isBlockerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private void refresh() {
        state.rollover();
        long s = state.totalStudiedSec();
        timerText.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));

        String status;
        if (state.isUnlocked()) status = "Apps are unlocked.";
        else if (state.canUnlock()) status = "Goal reached. You can unlock your apps.";
        else status = String.format(Locale.US, "Study %d min today to unlock apps.", state.requiredMin());
        statusText.setText(status);
        unlockButton.setEnabled(state.canUnlock());
    }
}
