package com.damweeb.distraint;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.security.SecureRandom;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

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
    private TextView protectStatus;
    private Button protectButton;

    // What the code is made of. Look-alikes (I, l, 1, O, o, 0) are left out so it can be read off the screen reliably.
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%&*?+=-";

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
        protectStatus = findViewById(R.id.protectStatus);
        protectButton = findViewById(R.id.protectButton);
        protectButton.setOnClickListener(v -> onProtectClicked());
        findViewById(R.id.syncButton).setOnClickListener(v ->
                startActivity(new Intent(this, SyncActivity.class)));
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
        refreshProtection();
        TextView syncStatus = findViewById(R.id.syncStatus);
        syncStatus.setText(state.syncSecret() == null
                ? "Not paired. Study time isn't shared with your PC."
                : state.peersSummary());
    }
    private boolean adminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    /** Redraws the card from the saved switch and the real admin state. */
    private void refreshProtection() {
        if (!state.protectEnabled()) {
            protectStatus.setText("Protection is off. Distraint can be uninstalled or force-stopped.");
            protectButton.setText("Turn on protection");
        } else if (adminActive()) {
            protectStatus.setText("Protection is on. Distraint can't be uninstalled or force-stopped.");
            protectButton.setText("Turn off protection…");
        } else {
            protectStatus.setText("Device admin isn't active, so Distraint isn't protected yet.");
            protectButton.setText("Turn on protection");
        }
    }

    private void onProtectClicked() {
        if (state.protectEnabled() && adminActive()) {
            if (state.strictActiveNow()) {          // "no matter what": no way out in the middle of a strict lock
                Toast.makeText(this, "Can't turn off protection during a strict lock", Toast.LENGTH_LONG).show();
                return;
            }
            showDisableDialog();
        } else {
            // Standard Android screen for activating a device admin; AdminReceiver.onEnabled() switches protection on.
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, AdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "While active, Distraint can't be uninstalled or force-stopped.");
            startActivity(i);
        }
    }

    /** 16 random characters from a cryptographically secure generator, with at least one of each kind. A new code every time the dialog opens. */
    private static String newCode() {
        SecureRandom r = new SecureRandom();
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        List<Character> chars = new ArrayList<>();
        chars.add(UPPER.charAt(r.nextInt(UPPER.length())));      // one of each kind first...
        chars.add(LOWER.charAt(r.nextInt(LOWER.length())));
        chars.add(DIGITS.charAt(r.nextInt(DIGITS.length())));
        chars.add(SYMBOLS.charAt(r.nextInt(SYMBOLS.length())));
        while (chars.size() < 16) chars.add(all.charAt(r.nextInt(all.length())));
        Collections.shuffle(chars, r);                           // ...then shuffle so they don't sit at the front
        StringBuilder sb = new StringBuilder();
        for (char c : chars) sb.append(c);
        return sb.toString();
    }

    private void showDisableDialog() {
        final String code = newCode();
        View v = getLayoutInflater().inflate(R.layout.dialog_protect, null);
        TextView shown = v.findViewById(R.id.codeText);
        TextInputLayout layout = v.findViewById(R.id.codeLayout);
        android.widget.EditText input = v.findViewById(R.id.codeInput);
        // Shown in groups of four to be easier to copy by eye; the box takes the 16 digits with no spaces.
        shown.setText(code.substring(0, 4) + "  " + code.substring(4, 8) + "  "
                + code.substring(8, 12) + "  " + code.substring(12, 16));
        blockPaste(input);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Turn off protection?")
                .setView(v)
                .setPositiveButton("Turn off", null)      // real handler below, so a wrong code doesn't close the dialog
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            if (input.getText().toString().equals(code)) {
                disableProtection();
                dialog.dismiss();
            } else {
                layout.setError("That doesn't match");
            }
        }));
        dialog.show();
    }

    /** Removes our own device admin (so Uninstall works) and records that protection is off. */
    private void disableProtection() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin);
        state.setProtectEnabled(false);
        refreshProtection();
    }

    /** Best-effort paste blocking: no paste menu, no drag-and-drop, and any change that adds more than one character at once is undone. */
    private void blockPaste(android.widget.EditText input) {
        ActionMode.Callback none = new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode m) { }
        };
        input.setCustomSelectionActionModeCallback(none);
        input.setCustomInsertionActionModeCallback(none);
        input.setLongClickable(false);
        input.setOnDragListener((view, e) -> true);
        input.addTextChangedListener(new TextWatcher() {
            private String before = "";
            private boolean undoing;
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                if (!undoing) before = s.toString();
            }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (undoing) return;
                if (s.length() > before.length() + 1) {   // several characters appeared at once: that's a paste
                    undoing = true;
                    s.replace(0, s.length(), before);
                    input.setSelection(s.length());
                    undoing = false;
                }
            }
        });
    }
}