/*
 * Copyright (C) 2026 Evolution X contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.lockglymps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Small signature-protected IPC surface for controlling the private Glymps service.
 *
 * Keeping LockGlympsService non-exported avoids exposing a long-running privileged
 * service directly to other packages while still allowing the Settings app to
 * issue explicit commands.
 */
public class LockGlympsControlReceiver extends BroadcastReceiver {
    private static final String TAG = "LockGlympsControl";

    public static final String ACTION_ENABLE =
            "com.android.systemui.lockglymps.action.ENABLE";
    public static final String ACTION_DISABLE =
            "com.android.systemui.lockglymps.action.DISABLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        Intent serviceIntent = new Intent(context, LockGlympsService.class);

        try {
            if (ACTION_DISABLE.equals(action)) {
                context.stopService(serviceIntent);
                return;
            }

            int enabled = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.LOCK_GLYMPS_ENABLED,
                    0);
            if (enabled != 1) {
                Log.d(TAG, "Ignoring Glymps command while feature is disabled: " + action);
                return;
            }

            switch (action) {
                case ACTION_ENABLE:
                    context.startService(serviceIntent);
                    break;
                case LockGlympsService.ACTION_REFRESH_SETTINGS:
                case LockGlympsService.ACTION_CLEAR_CACHE:
                case LockGlympsService.ACTION_APPLY_NOW:
                    serviceIntent.setAction(action);
                    context.startService(serviceIntent);
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown Glymps command: " + action);
                    break;
            }
        } catch (RuntimeException e) {
            // An optional wallpaper feature must never destabilize SystemUI.
            Log.e(TAG, "Unable to handle Wallpaper Glymps command: " + action, e);
        }
    }
}
