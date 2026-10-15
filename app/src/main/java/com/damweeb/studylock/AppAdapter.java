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

import java.util.List;
import java.util.Set;

public class AppAdapter extends ArrayAdapter<String> {
    private final Set<String> pending;
    private final String pendingNote;

    public AppAdapter(Context c, List<String> pkgs, Set<String> pending, String pendingNote) {
        super(c, 0, pkgs);
        this.pending = pending;
        this.pendingNote = pendingNote;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_app, parent, false);
        }
        String pkg = getItem(position);
        TextView name = convertView.findViewById(R.id.appName);
        ImageView icon = convertView.findViewById(R.id.appIcon);

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
        return convertView;
    }
}
