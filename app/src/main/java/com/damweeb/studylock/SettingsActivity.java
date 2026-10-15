package com.damweeb.studylock;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class SettingsActivity extends AppCompatActivity {
    private StudyState state;
    private EditText requiredEdit, unlockEdit, resetEdit;
    private RadioGroup combineGroup;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        state = new StudyState(this);
        state.rollover();

        requiredEdit = findViewById(R.id.requiredEdit);
        unlockEdit = findViewById(R.id.unlockEdit);
        resetEdit = findViewById(R.id.resetEdit);

        requiredEdit.setText(String.valueOf(state.shown("required_min")));
        unlockEdit.setText(String.valueOf(shownUnlockMin()));
        resetEdit.setText(String.valueOf(state.shown("rollover_hour")));

        RadioGroup themeGroup = findViewById(R.id.themeGroup);
        int mode = state.themeMode();
        themeGroup.check(mode == AppCompatDelegate.MODE_NIGHT_NO ? R.id.themeLight
                : mode == AppCompatDelegate.MODE_NIGHT_YES ? R.id.themeDark
                  : R.id.themeSystem);
        themeGroup.setOnCheckedChangeListener((group, id) -> {
            int m = id == R.id.themeLight ? AppCompatDelegate.MODE_NIGHT_NO
                    : id == R.id.themeDark ? AppCompatDelegate.MODE_NIGHT_YES
                      : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            state.setThemeMode(m);
            AppCompatDelegate.setDefaultNightMode(m);
        });
        combineGroup = findViewById(R.id.combineGroup);
        combineGroup.check(state.shown("combine") == StudyState.COMBINE_INDIVIDUAL
                ? R.id.combineEach : R.id.combineTotal);
    }
    private int shownUnlockMin() {
        return state.shown("mode") == StudyState.MODE_REST_OF_DAY ? 0 : state.shown("timed_min");
    }

    private int read(EditText field, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(field.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;            // empty or junk: keep the current value
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        state.setPending("required_min", read(requiredEdit, state.shown("required_min"), 1, 600));

        int unlock = read(unlockEdit, shownUnlockMin(), 0, 1440);
        state.setUnlockMinutes(unlock);

        state.setPending("combine",
                combineGroup.getCheckedRadioButtonId() == R.id.combineEach
                        ? StudyState.COMBINE_INDIVIDUAL : StudyState.COMBINE_TOTAL);

        state.setPending("rollover_hour", read(resetEdit, state.shown("rollover_hour"), 0, 23));
    }
}
