package com.android.internal.util.evolution;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** @hide */
public class HideDeveloperStatusUtils {

    private static final Set<String> DEV_SETTINGS_TO_HIDE = new HashSet<>(Arrays.asList(
            Settings.Global.ADB_ENABLED,
            Settings.Global.ADB_WIFI_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    ));

    private enum Action { ADD, REMOVE, SET }

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String packageName, String name) {
        if (cr == null || packageName == null || name == null || !isBootCompleted()) return false;
        if (!DEV_SETTINGS_TO_HIDE.contains(name)) return false;
        return getApps(cr).contains(packageName);
    }

    public static Set<String> getApps(Context context) {
        if (context == null) return new HashSet<>();
        return getApps(context.getContentResolver());
    }

    public static Set<String> getApps(ContentResolver cr) {
        if (cr == null) return new HashSet<>();
        try {
            String raw = Settings.Secure.getString(cr, Settings.Secure.HIDE_DEVELOPER_STATUS);
            if (raw != null && !raw.isEmpty() && !raw.equals(",")) {
                return new HashSet<>(Arrays.asList(raw.split(",")));
            }
        } catch (IllegalStateException ignored) {}
        return new HashSet<>();
    }

    private static void putAppsForUser(Context context, String packageName,
            int userId, Action action) {
        if (context == null || userId < 0) return;
        Set<String> apps = getApps(context);
        switch (action) {
            case ADD:    apps.add(packageName); break;
            case REMOVE: apps.remove(packageName); break;
            case SET:    break;
        }
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS,
                String.join(",", apps),
                userId);
    }

    public void addApp(Context context, String packageName, int userId) {
        if (context == null || packageName == null || userId < 0) return;
        putAppsForUser(context, packageName, userId, Action.ADD);
    }

    public void removeApp(Context context, String packageName, int userId) {
        if (context == null || packageName == null || userId < 0) return;
        putAppsForUser(context, packageName, userId, Action.REMOVE);
    }

    public void setApps(Context context, int userId) {
        if (context == null || userId < 0) return;
        putAppsForUser(context, null, userId, Action.SET);
    }
}
