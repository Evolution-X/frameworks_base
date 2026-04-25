/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Consolidated utility for hiding apps from app lists and developer status checks.
 * Replaces HideAppListUtils and HideDeveloperStatusUtils.
 * @hide
 */
public class HideAppsUtils {

    public enum Mode {
        APP_LIST(Settings.Secure.HIDE_APPLIST),
        DEV_STATUS(Settings.Secure.HIDE_DEVELOPER_STATUS);

        public final String settingsKey;
        Mode(String key) { this.settingsKey = key; }
    }

    private static final Set<String> DEV_SETTINGS_TO_HIDE = new HashSet<>(Arrays.asList(
            Settings.Global.ADB_ENABLED,
            Settings.Global.ADB_WIFI_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    ));

    private enum Action { ADD, REMOVE, SET }

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    // -------------------------------------------------------------------------
    // Public check methods — called from Settings.java and ComputerEngine hotpath
    // -------------------------------------------------------------------------

    public static boolean shouldHideAppList(Context context, String packageName) {
        if (context == null) return false;
        return shouldHideAppList(context.getContentResolver(), packageName);
    }

    public static boolean shouldHideAppList(ContentResolver cr, String packageName) {
        if (cr == null || packageName == null || !isBootCompleted()) return false;
        return getApps(cr, Mode.APP_LIST).contains(packageName);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String packageName, String name) {
        if (cr == null || packageName == null || name == null || !isBootCompleted()) return false;
        if (!DEV_SETTINGS_TO_HIDE.contains(name)) return false;
        return getApps(cr, Mode.DEV_STATUS).contains(packageName);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    public static Set<String> getApps(Context context, Mode mode) {
        if (context == null) return new HashSet<>();
        return getApps(context.getContentResolver(), mode);
    }

    public static Set<String> getApps(ContentResolver cr, Mode mode) {
        if (cr == null) return new HashSet<>();
        try {
            String raw = Settings.Secure.getString(cr, mode.settingsKey);
            if (raw != null && !raw.isEmpty() && !raw.equals(",")) {
                return new HashSet<>(Arrays.asList(raw.split(",")));
            }
        } catch (IllegalStateException ignored) {}
        return new HashSet<>();
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    private static void putAppsForUser(Context context, String packageName,
            int userId, Action action, Mode mode) {
        if (context == null || userId < 0) return;
        Set<String> apps = getApps(context, mode);
        switch (action) {
            case ADD:    apps.add(packageName); break;
            case REMOVE: apps.remove(packageName); break;
            case SET:    break;
        }
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                mode.settingsKey,
                String.join(",", apps),
                userId);
    }

    public void addApp(Context context, String packageName, int userId, Mode mode) {
        if (context == null || packageName == null || userId < 0) return;
        putAppsForUser(context, packageName, userId, Action.ADD, mode);
    }

    public void removeApp(Context context, String packageName, int userId, Mode mode) {
        if (context == null || packageName == null || userId < 0) return;
        putAppsForUser(context, packageName, userId, Action.REMOVE, mode);
    }

    public void setApps(Context context, int userId, Mode mode) {
        if (context == null || userId < 0) return;
        putAppsForUser(context, null, userId, Action.SET, mode);
    }
}
