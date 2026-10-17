package com.damweeb.distraint;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One picker screen for three lists. It is started with two boolean extras:
 *   strict = true -> apps under strict lock (StudyState.saveStrictApps), started from the Strict lock page
 *   study  = true -> study apps (StudyState.saveStudyApps), started from MainActivity's "+" menu
 *   neither       -> apps to lock (StudyState.saveBlocked), started from MainActivity's "+" menu
 *
 * Apps already in the OTHER one of the locked / study lists are hidden, so an app can't be picked for both.
 * The strict lock is not part of that rule: strict apps may also be locked apps, and the strict picker
 * offers every app. An app that is already in both locked and study (left over from before the rule)
 * stays visible in both pickers, marked "also in ...", so it can be unticked.
 *
 * The UI is built in code (title, hint, multi-choice list). Nothing is saved until the screen is left:
 * onPause() reads the ticked rows and hands them to StudyState, which decides what applies now and what
 * waits for the reset.
 *
 * DEBUGGING TIPS
 *   - The app list is nearly empty: the <queries> entry for launcher apps is missing from the manifest (Android 11+).
 *   - A change "didn't apply": check the hint text on this screen and StudyState's pending_ keys; it may be queued.
 *   - An app is missing from a picker: it is probably in the other list (see hiddenPackages()).
 */
public class GamePickerActivity extends AppCompatActivity {
    // Which list is being edited (from the "study" and "strict" extras). Neither = the locked apps list.
    private boolean studyMode;
    private boolean strictMode;
    // Parallel to the rows of the list: row i belongs to packages.get(i).
    private final List<String> packages = new ArrayList<>();
    private ListView list;
    private StudyState state;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        state = new StudyState(this);
        studyMode = getIntent().getBooleanExtra("study", false);
        strictMode = getIntent().getBooleanExtra("strict", false);

        // The screen is built in code: a title, a hint about when changes apply, and a multi-choice list.
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        TextView title = new TextView(this);
        title.setText(strictMode ? "Strict lock apps" : studyMode ? "Study apps" : "Apps to lock");
        title.setTextSize(24);
        title.setPadding(48, 48, 48, 8);

        TextView hint = new TextView(this);
        hint.setText(strictMode
                ? "New apps are strictly locked now. Removing one waits for a reset, and isn't possible while a strict lock is active."
                : studyMode
                  ? (state.studySetupDone()
                     ? "Removed apps stop counting now. New apps count from the next reset."
                     : "Your first study apps count right away. Later additions wait for the next reset.")
                  : "New apps lock now. Unticked apps unlock at the next reset.");
        hint.setPadding(48, 0, 48, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(title);
        root.addView(hint);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // List every app that has a launcher icon, sorted by name. Our own app and apps that belong to the
        // other list are skipped.
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        List<String> labels = new ArrayList<>();
        Set<String> hidden = hiddenPackages();
        Set<String> dups = duplicates();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;   // don't offer to lock ourselves
            if (hidden.contains(pkg)) continue;           // already in the other list
            packages.add(pkg);
            String name = ri.loadLabel(pm).toString();
            labels.add(dups.contains(pkg)
                    ? name + "  ·  also in " + (studyMode ? "locked apps" : "study apps")
                    : name);
        }

        // Standard checkbox row; getView() adds the app icon at the start of each row.
        list.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_multiple_choice, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                CheckedTextView row = (CheckedTextView) super.getView(position, convertView, parent);
                float density = getResources().getDisplayMetrics().density;
                int size = (int) (40 * density);              // 40dp expressed in pixels
                try {
                    Drawable icon = pm.getApplicationIcon(packages.get(position));
                    icon.setBounds(0, 0, size, size);
                    row.setCompoundDrawablesRelative(icon, null, null, null);
                } catch (PackageManager.NameNotFoundException e) {
                    row.setCompoundDrawablesRelative(null, null, null, null);
                }
                row.setCompoundDrawablePadding((int) (12 * density));
                return row;
            }
        });

        // Pre-tick the current choices. Queued study apps count as ticked (what the user chose last time);
        // apps queued for removal (locked or strict) count as unticked.
        Set<String> ticked0;
        if (strictMode) {
            ticked0 = state.strictApps();
            ticked0.removeAll(state.pendingStrictAppRemovals());
        } else if (studyMode) {
            ticked0 = state.studyApps();
            ticked0.addAll(state.pendingStudyAdds());
        } else {
            ticked0 = state.blocked();
            ticked0.removeAll(state.pendingRemovals());
        }
        for (int i = 0; i < packages.size(); i++) {
            list.setItemChecked(i, ticked0.contains(packages.get(i)));
        }
    }

    // There is no Save button: the choices are saved whenever the screen is left (back, home, or another screen).
    @Override
    protected void onPause() {
        super.onPause();
        Set<String> chosen = new HashSet<>();
        SparseBooleanArray ticked = list.getCheckedItemPositions();
        for (int i = 0; i < packages.size(); i++) {
            if (ticked.get(i)) chosen.add(packages.get(i));
        }
        if (strictMode) state.saveStrictApps(chosen);
        else if (studyMode) state.saveStudyApps(chosen);
        else state.saveBlocked(chosen);
    }

    /**
     * Apps hidden from the study and locked pickers because they are already in the other list.
     * Strict lock is not involved: the strict picker offers every app. An app that is already in THIS
     * list is never hidden, otherwise saving would read it as unticked and queue its removal.
     */
    private Set<String> hiddenPackages() {
        if (strictMode) return new HashSet<>();

        Set<String> study = state.studyApps();
        study.addAll(state.pendingStudyAdds());               // queued study apps already count as chosen
        Set<String> locked = state.blocked();                 // includes ones queued for removal (still locked until the reset)

        Set<String> mine = studyMode ? study : locked;
        Set<String> hide = new HashSet<>(studyMode ? locked : study);
        hide.removeAll(mine);
        return hide;
    }

    /** Apps that are in BOTH the study and locked lists (left over from before the rule). They stay visible so they can be unticked. */
    private Set<String> duplicates() {
        if (strictMode) return new HashSet<>();
        Set<String> study = state.studyApps();
        study.addAll(state.pendingStudyAdds());
        Set<String> dup = state.blocked();
        dup.retainAll(study);
        return dup;
    }
}