package com.damweeb.distraint;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Row adapter for the two lists on the home screen. It only DISPLAYS: MainActivity.loadLists() decides what
 * subtitle and progress bar each package gets (via the two maps), and this class turns a package name into
 * an icon + app name + optional subtitle + optional bar.
 *
 * DEBUGGING TIPS
 *   - NullPointerException at findViewById: an id is missing from item_app.xml.
 *   - A row shows a raw package name and no icon: the app is no longer installed.
 *   - No subtitle or bar on a row: the maps have no entry for that package (pending apps get none on purpose).
 */
public class AppAdapter extends ArrayAdapter<String> {
    private final Set<String> pending;        // apps flagged with pendingNote
    private final String pendingNote;// text appended to the name of pending apps

    // Per-package display data supplied by MainActivity. A missing entry means "hide that element".
    private final Map<String, String> subtitles;   // text under the name, per app
    private final Map<String, Integer> progress;   // 0..1000 bar value, per app

    // pkgs: the package names to show, in order. super(c, 0, pkgs): resource 0 because getView() builds the row itself.
    public AppAdapter(Context c, List<String> pkgs, Set<String> pending, String pendingNote,
                      Map<String, String> subtitles, Map<String, Integer> progress) {
        super(c, 0, pkgs);
        this.pending = pending;
        this.pendingNote = pendingNote;
        this.subtitles = subtitles;
        this.progress = progress;
    }

    /**
     * Builds one row. convertView is a recycled row from an item that scrolled off screen, so EVERY element must
     * be set (or hidden) on both branches below; otherwise a recycled row would show stale text or a stale bar.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_app, parent, false);
        }
        String pkg = getItem(position);
        TextView name = convertView.findViewById(R.id.appName);
        TextView time = convertView.findViewById(R.id.appTime);
        ImageView icon = convertView.findViewById(R.id.appIcon);
        LinearProgressIndicator bar = convertView.findViewById(R.id.appProgress);

        PackageManager pm = getContext().getPackageManager();
        String label;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(info).toString();
            icon.setImageDrawable(pm.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;                       // app was uninstalled
            icon.setImageDrawable(null);
        }
        name.setText(pending.contains(pkg) ? label + pendingNote : label);

        String sub = subtitles.get(pkg);
        if (sub != null) {
            time.setText(sub);
            time.setVisibility(View.VISIBLE);
        } else {
            time.setVisibility(View.GONE);
        }

        Integer value = progress.get(pkg);
        if (value != null) {
            bar.setProgressCompat(value, false);
            bar.setVisibility(View.VISIBLE);
        } else {
            bar.setVisibility(View.GONE);
        }
        return convertView;
    }

    // UNUSED: leftover from an earlier version (MainActivity formats times now). Safe to delete, together with the Locale import.
    private static String formatDuration(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }
}