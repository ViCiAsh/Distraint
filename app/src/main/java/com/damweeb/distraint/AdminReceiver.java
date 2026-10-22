package com.damweeb.distraint;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives device-admin events. Distraint is a device admin only for its side effect: while it is active, Android
 * disables Uninstall, Force stop and Clear storage for the app in App info. It requests no admin policies
 * (see res/xml/device_admin.xml). Activated from the home screen's setup card or the first-run tour; deactivating
 * means going through Android's device admin settings, which shows the warning returned below.
 */
public class AdminReceiver extends DeviceAdminReceiver {
    // The text shown in Android's confirmation dialog when someone tries to deactivate the admin.
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "This lets Distraint be uninstalled. Are you sure you want to give up on studying?";
    }
    // Runs when the admin is (re)activated. Protection comes back on with it.
    @Override
    public void onEnabled(Context context, Intent intent) {
        new StudyState(context).setProtectEnabled(true);
    }
}