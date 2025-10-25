package com.android.systemui.lockglymps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Boot receiver to start LockGlympsService on device boot
 * Place in: frameworks/base/packages/SystemUI/src/com/android/systemui/lockglymps/
 */
public class LockGlympsBootReceiver extends BroadcastReceiver {
    private static final String TAG = "LockGlympsBootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            
            Log.d(TAG, "Boot completed, checking if should start LockGlympsService");
            
            // Check if feature is enabled
            int enabled = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.LOCK_GLYMPS_ENABLED, 0);
            
            if (enabled == 1) {
                Intent serviceIntent = new Intent(context, LockGlympsService.class);
                context.startService(serviceIntent);
                Log.d(TAG, "LockGlympsService started");
            } else {
                Log.d(TAG, "LockGlymps disabled, not starting service");
            }
        }
    }
}