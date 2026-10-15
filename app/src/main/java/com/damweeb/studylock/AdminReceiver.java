package com.damweeb.studylock;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "This lets StudyLock be uninstalled. Are you sure you want to give up on studying?";
    }
}