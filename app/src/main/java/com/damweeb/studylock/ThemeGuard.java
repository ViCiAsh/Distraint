package com.damweeb.studylock;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public final class ThemeGuard {
    private static long lastRecreate;

    private ThemeGuard() { }

    private static boolean nightWanted(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) return false;
        int sys = Resources.getSystem().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return sys == Configuration.UI_MODE_NIGHT_YES;           // "follow system"
    }

    private static boolean nightShowing(Activity a) {
        int ui = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Makes sure the screen really matches the saved theme choice. */
    public static void enforce(AppCompatActivity a, int mode) {
        long now = SystemClock.elapsedRealtime();
        if (nightWanted(mode) == nightShowing(a)) return;        // already correct
        if (now - lastRecreate < 3000) return;                   // never loop
        lastRecreate = now;

        AppCompatDelegate.setDefaultNightMode(mode);
        Toast.makeText(a, "Theme corrected", Toast.LENGTH_SHORT).show();
        a.recreate();
    }
}
