/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.theme;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.res.R;

public class RisingThemeController {

    private static final String TAG = "RisingThemeController";
    private final ContentResolver mContentResolver;
    private final Handler mBackgroundHandler;
    private Context mContext;

    private Runnable mDebounceRunnable;

    public RisingThemeController(Context context, Handler backgroundHandler) {
        this.mContext = context;
        this.mContentResolver = mContext.getContentResolver();
        this.mBackgroundHandler = backgroundHandler;
    }

    public void observeSettings(Runnable reevaluateSystemThemeCallback) {
        observeSettingsKeys(RisingSettingsConstants.SYSTEM_SETTINGS_KEYS, reevaluateSystemThemeCallback, true);
        observeSettingsKeys(RisingSettingsConstants.SECURE_SETTINGS_KEYS, reevaluateSystemThemeCallback, false);
        observeSettingsKeys(RisingSettingsConstants.SYSTEM_SETTINGS_NOTIFY_ONLY_KEYS, null, true);
        observeSettingsKeys(RisingSettingsConstants.SECURE_SETTINGS_NOTIFY_ONLY_KEYS, null, false);
        observeRestartKey();
    }
    
    private void observeRestartKey() {
        Uri restartUri = Settings.System.getUriFor("system_ui_restart");
        observe(restartUri, () -> {
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private void observeSettingsKeys(String[] keys, Runnable reevaluateSystemThemeCallback, boolean isSystem) {
        for (String key : keys) {
            Uri uri = isSystem ? Settings.System.getUriFor(key) : Settings.Secure.getUriFor(key);
            observe(uri, reevaluateSystemThemeCallback);
        }
    }

    private void observe(Uri uri, Runnable reevaluateSystemThemeCallback) {
        if (uri != null) {
            ContentObserver contentObserver = new ContentObserver(mBackgroundHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    Toast toast = Toast.makeText(mContext, R.string.reevaluating_system_theme, Toast.LENGTH_SHORT);
                    if (isDeviceSetupComplete()) {
                        toast.show();
                    }
                    if (mDebounceRunnable != null) {
                        mBackgroundHandler.removeCallbacks(mDebounceRunnable);
                    }
                    mDebounceRunnable = () -> {
                        if (reevaluateSystemThemeCallback != null) {
                            mBackgroundHandler.post(() -> reevaluateSystemThemeCallback.run());
                        }
                    };
                    mBackgroundHandler.postDelayed(mDebounceRunnable, 2000);
                }
            };
            mContentResolver.registerContentObserver(uri, false, contentObserver);
        } else {
            Log.e(TAG, "Failed to get URI for key");
        }
    }

    private boolean isDeviceSetupComplete() {
        try {
            return Settings.Secure.getInt(mContentResolver, Settings.Secure.USER_SETUP_COMPLETE) == 1;
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "USER_SETUP_COMPLETE setting not found", e);
            return false;
        }
    }
}
