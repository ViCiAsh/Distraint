package com.damweeb.studylock;

import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * Settings screen. Values are shown from StudyState.shown() (the queued value if one exists, otherwise the live one).
 * Saving happens in onPause() through StudyState.setPending() / setUnlockMinutes(): the first-ever choice of a
 * setting applies immediately, later changes are queued until the next daily reset. Only the theme applies at once.
 *
 * DEBUGGING TIPS
 *   - NullPointerException when opening or leaving the screen: a view id is missing from activity_settings.xml.
 *   - A change "does nothing": it is queued; the live value changes at the next reset.
 *   - To add a setting: add its view and id to the layout, show it in onCreate, save it in onPause, and register
 *     the key in StudyState (live() and the key list in rollover()).
 */
public class SettingsActivity extends AppCompatActivity {
    private StudyState state;
    // Number boxes: required study minutes, unlock length (0 = until the next reset), and reset hour (0-23).
    private EditText requiredEdit, unlockEdit, resetEdit;
    // Segmented controls: how study apps count, and how unlock time is counted. (The theme control is a local in onCreate.)
    private MaterialButtonToggleGroup combineGroup, limitGroup;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        state = new StudyState(this);
        state.rollover();

        // Look up the views. A NullPointerException on opening Settings usually means an id is missing in activity_settings.xml.
        requiredEdit = findViewById(R.id.requiredEdit);
        unlockEdit = findViewById(R.id.unlockEdit);
        resetEdit = findViewById(R.id.resetEdit);

        // Fill the boxes and controls with what the user last chose (queued values included).
        requiredEdit.setText(String.valueOf(state.shown("required_min")));
        unlockEdit.setText(String.valueOf(shownUnlockMin()));
        resetEdit.setText(String.valueOf(state.shown("rollover_hour")));
        limitGroup = findViewById(R.id.limitGroup);
        combineGroup = findViewById(R.id.combineGroup);
        combineGroup.check(state.shown("combine") == StudyState.COMBINE_INDIVIDUAL
                ? R.id.combineEach : R.id.combineTotal);
        limitGroup.check(state.shown("limit_style") == StudyState.LIMIT_PER_APP
                ? R.id.limitEach : R.id.limitShared);

        // The back arrow closes the screen; saving happens in onPause().
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialButtonToggleGroup themeGroup = findViewById(R.id.themeGroup);
        int mode = state.themeMode();
        // check() runs BEFORE the listener is attached, so setting the starting selection does not trigger it.
        themeGroup.check(mode == AppCompatDelegate.MODE_NIGHT_NO ? R.id.themeLight
                : mode == AppCompatDelegate.MODE_NIGHT_YES ? R.id.themeDark
                  : R.id.themeSystem);
        // Theme is the one setting that applies immediately. The toggle group reports both the button that turned off
        // and the one that turned on, so only react when isChecked is true.
        themeGroup.addOnButtonCheckedListener((group, id, isChecked) -> {
            if (!isChecked) return;
            int m = id == R.id.themeLight ? AppCompatDelegate.MODE_NIGHT_NO
                    : id == R.id.themeDark ? AppCompatDelegate.MODE_NIGHT_YES
                      : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            state.setThemeMode(m);
            AppCompatDelegate.setDefaultNightMode(m);   // recreates activities automatically
        });
    }
    /** Unlock length as the screen should show it: 0 = until the next reset, otherwise minutes (queued value if any). */
    private int shownUnlockMin() {
        return state.shown("mode") == StudyState.MODE_REST_OF_DAY ? 0 : state.shown("timed_min");
    }

    /** Reads a number from a box, clamped to min..max. Empty or invalid text returns `fallback`, so a half-typed box never crashes or clears a setting. */
    private int read(EditText field, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(field.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;            // empty or junk: keep the current value
        }
    }

    // Save everything here (there is no Save button). Each call either applies immediately (first-ever choice)
    // or is queued for the next reset; see StudyState.setPending().
    @Override
    protected void onPause() {
        super.onPause();
        state.setPending("required_min", read(requiredEdit, state.shown("required_min"), 1, 600));

        int unlock = read(unlockEdit, shownUnlockMin(), 0, 1440);
        state.setUnlockMinutes(unlock);

        state.setPending("combine",
                combineGroup.getCheckedButtonId() == R.id.combineEach
                        ? StudyState.COMBINE_INDIVIDUAL : StudyState.COMBINE_TOTAL);

        state.setPending("rollover_hour", read(resetEdit, state.shown("rollover_hour"), 0, 23));
        state.setPending("limit_style",
                limitGroup.getCheckedButtonId() == R.id.limitEach
                        ? StudyState.LIMIT_PER_APP : StudyState.LIMIT_SHARED);
    }

    // Empty override: nothing to refresh when returning to this screen. Safe to delete.
    @Override
    protected void onResume() {
        super.onResume();

    }
}