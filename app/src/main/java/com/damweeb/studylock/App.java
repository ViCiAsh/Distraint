package com.damweeb.studylock;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Application class (registered in the manifest with android:name=".App"). Runs once per app process, before any
 * screen, so the saved light/dark/system theme is applied everywhere from the start.
 * DEBUG: if the saved theme is ignored after a restart, check that android:name=".App" is on the <application> tag.
 */
public class App extends Application {
    // Re-apply the saved theme (AppCompatDelegate.MODE_NIGHT_*) every time the process starts.
    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(new StudyState(this).themeMode());
    }
}