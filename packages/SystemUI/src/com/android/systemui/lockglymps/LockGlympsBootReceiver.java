package com.android.systemui.lockglymps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Starts {@link LockGlympsService} after boot when Wallpaper Glymps is enabled.
 */
public class LockGlympsBootReceiver extends BroadcastReceiver {
    private static final String TAG = "LockGlympsBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        int enabled = Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.LOCK_GLYMPS_ENABLED,
                0);
        if (enabled != 1) {
            Log.d(TAG, "Wallpaper Glymps disabled; service will not start");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, LockGlympsService.class);
            context.startService(serviceIntent);
            Log.d(TAG, "Wallpaper Glymps service started after boot");
        } catch (RuntimeException e) {
            // Never let an optional wallpaper feature destabilize SystemUI boot.
            Log.e(TAG, "Unable to start Wallpaper Glymps service after boot", e);
        }
    }
}
