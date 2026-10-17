package com.damweeb.distraint;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Strict lock page, opened from the "+" menu. Every action saves immediately through StudyState;
 * refresh() redraws the whole screen from the saved state, so what you see is always what is stored.
 * Enforcement itself happens in BlockerService (isStrictLocked), not here.
 */
public class StrictLockActivity extends AppCompatActivity {
    private StudyState state;
    private TextView appsSummary, timerStatus;
    private LinearLayout windowList;
    private EditText timerEdit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_strict);
        state = new StudyState(this);

        appsSummary = findViewById(R.id.strictAppsSummary);
        timerStatus = findViewById(R.id.timerStatus);
        windowList = findViewById(R.id.windowList);
        timerEdit = findViewById(R.id.timerEdit);

        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());

        // The picker is the same screen as the other two lists, switched into strict mode with an extra.
        findViewById(R.id.chooseAppsButton).setOnClickListener(v -> {
            Intent i = new Intent(this, GamePickerActivity.class);
            i.putExtra("strict", true);
            startActivity(i);
        });

        // Two time pickers in a row: start, then end. Nothing is saved until the second one is confirmed.
        findViewById(R.id.addWindowButton).setOnClickListener(v ->
                pickTime("Lock starts at", 22 * 60, start ->
                        pickTime("Lock ends at", 6 * 60, end -> {
                            if (start == end) {
                                Toast.makeText(this, "Start and end can't be the same", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            state.addStrictWindow(start, end);
                            refresh();
                        })));

        findViewById(R.id.startTimerButton).setOnClickListener(v -> confirmTimer());
    }

    // Runs on return from the picker too, so the app count is always current. rollover() applies due removals.
    @Override
    protected void onResume() {
        super.onResume();
        state.rollover();
        refresh();
    }

    /** Material time picker (follows the phone's 12/24-hour setting). Reports minutes after midnight. */
    private void pickTime(String title, int initialMin, IntConsumer onPicked) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(initialMin / 60)
                .setMinute(initialMin % 60)
                .setTitleText(title)
                .build();
        picker.addOnPositiveButtonClickListener(v ->
                onPicked.accept(picker.getHour() * 60 + picker.getMinute()));
        picker.show(getSupportFragmentManager(), "time");
    }

    /** The timer cannot be cancelled, so ask once before starting it. */
    private void confirmTimer() {
        int mins;
        try {
            mins = Integer.parseInt(timerEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter the number of minutes", Toast.LENGTH_SHORT).show();
            return;
        }
        final int minutes = Math.max(1, Math.min(10080, mins));   // 1 minute to 7 days
        new MaterialAlertDialogBuilder(this)
                .setTitle("Start strict lock?")
                .setMessage("Your strict-lock apps will be locked for " + duration(minutes)
                        + ". This cannot be cancelled.")
                .setPositiveButton("Lock", (d, w) -> {
                    state.startStrictTimer(minutes);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Redraws everything from StudyState. */
    private void refresh() {
        // Apps: queued removals no longer count in the summary (they are unticked in the picker too).
        Set<String> apps = state.strictApps();
        apps.removeAll(state.pendingStrictAppRemovals());
        appsSummary.setText(apps.isEmpty() ? "No apps chosen"
                : apps.size() + (apps.size() == 1 ? " app" : " apps"));

        // Windows, sorted by start time.
        windowList.removeAllViews();
        List<String> wins = new ArrayList<>(state.strictWindows());
        wins.sort((a, c) -> Integer.parseInt(a.split("-")[0]) - Integer.parseInt(c.split("-")[0]));
        Set<String> queued = state.pendingStrictWindowRemovals();
        if (wins.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No daily windows yet");
            windowList.addView(none);
        }
        for (String w : wins) windowList.addView(windowRow(w, queued.contains(w)));

        // Timer.
        long until = state.strictUntil();
        if (System.currentTimeMillis() < until) {
            String when = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                    .format(Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()));
            timerStatus.setText("Locked until " + when);
        } else {
            timerStatus.setText("No timer running");
        }
    }

    /** One row: "22:00 to 06:00" and a Remove button. Removal is refused while a lock is active. */
    private LinearLayout windowRow(String w, boolean removalQueued) {
        String[] a = w.split("-");
        String text = clock(Integer.parseInt(a[0])) + " to " + clock(Integer.parseInt(a[1]))
                + (removalQueued ? "  (removal queued)" : "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton remove = new MaterialButton(this, null,
                android.R.attr.borderlessButtonStyle);
        remove.setText("Remove");
        remove.setOnClickListener(v -> {
            if (state.requestRemoveStrictWindow(w)) {
                Toast.makeText(this, "Removal queued for the next reset", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Can't remove while a strict lock is active", Toast.LENGTH_SHORT).show();
            }
            refresh();
        });
        row.addView(remove);
        return row;
    }

    private static String clock(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String duration(int minutes) {
        int d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" d ");
        if (h > 0) sb.append(h).append(" h ");
        if (m > 0 || sb.length() == 0) sb.append(m).append(" min");
        return sb.toString().trim();
    }
}