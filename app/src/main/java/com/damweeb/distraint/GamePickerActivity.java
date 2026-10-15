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
 * One picker screen for both lists. MainActivity's "+" menu starts it with the boolean extra "study":
 *   study = false -> apps to lock (StudyState.saveBlocked)
 *   study = true  -> study apps (StudyState.saveStudyApps)
 *
 * The UI is built in code (title, hint, multi-choice list). Nothing is saved until the screen is left:
 * onPause() reads the ticked rows and hands them to StudyState, which decides what applies now and what
 * waits for the reset.
 *
 * DEBUGGING TIPS
 *   - The app list is nearly empty: the <queries> entry for launcher apps is missing from the manifest (Android 11+).
 *   - A change "didn't apply": check the hint text on this screen and StudyState's pending_ keys; it may be queued.
 */
public class GamePickerActivity extends AppCompatActivity {
    // true = editing the study apps list, false = the locked apps list (from the "study" extra).
    private boolean studyMode;
    // Parallel to the rows of the list: row i belongs to packages.get(i).
    private final List<String> packages = new ArrayList<>();
    private ListView list;
    private StudyState state;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        state = new StudyState(this);
        studyMode = getIntent().getBooleanExtra("study", false);

        // The screen is built in code: a title, a hint about when changes apply, and a multi-choice list.
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        TextView title = new TextView(this);
        title.setText(studyMode ? "Study apps" : "Apps to lock");
        title.setTextSize(24);
        title.setPadding(48, 48, 48, 8);

        TextView hint = new TextView(this);
        hint.setText(studyMode
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

        // List every app that has a launcher icon, sorted by name. Our own app is skipped below.
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        List<String> labels = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;   // don't offer to lock ourselves
            packages.add(pkg);
            labels.add(ri.loadLabel(pm).toString());
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
        // locked apps queued for removal count as unticked.
        Set<String> ticked0;
        if (studyMode) {
            ticked0 = state.studyApps();
            ticked0.addAll(state.pendingStudyAdds());   // show queued choices as ticked
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
        if (studyMode) state.saveStudyApps(chosen);
        else state.saveBlocked(chosen);
    }
}