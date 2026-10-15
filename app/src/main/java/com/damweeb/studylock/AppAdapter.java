package com.damweeb.studylock;

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

public class AppAdapter extends ArrayAdapter<String> {
    private final Set<String> pending;        // apps flagged with pendingNote
    private final String pendingNote;// apps that aren't being measured yet

    private final Map<String, String> subtitles;   // text under the name, per app
    private final Map<String, Integer> progress;   // 0..1000 bar value, per app

    public AppAdapter(Context c, List<String> pkgs, Set<String> pending, String pendingNote,
                      Map<String, String> subtitles, Map<String, Integer> progress) {
        super(c, 0, pkgs);
        this.pending = pending;
        this.pendingNote = pendingNote;
        this.subtitles = subtitles;
        this.progress = progress;
    }

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

    private static String formatDuration(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }
}