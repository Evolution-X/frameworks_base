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
package org.rising.server;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

public final class ShakeGestureService {

    private static final String TAG = "ShakeGestureService";

    private static final String SHAKE_GESTURES_ENABLED = "shake_gestures_enabled";
    private static final String SHAKE_GESTURES_ACTION = "shake_gestures_action";
    private static final int USER_ALL = UserHandle.USER_ALL;

    private final Context mContext;
    private ShakeGestureUtils mShakeGestureUtils;
    private static volatile ShakeGestureService instance;
    private final ShakeGesturesCallbacks mShakeCallbacks;

    private final SettingsObserver mSettingsObserver;
    private boolean mShakeServiceEnabled = false;

    private ShakeGestureUtils.OnShakeListener mShakeListener;

    public interface ShakeGesturesCallbacks {
        void onShake();
    }

    private ShakeGestureService(Context context, ShakeGesturesCallbacks callback) {
        mContext = context;
        mShakeCallbacks = callback;
        mShakeListener = () -> {
            if (mShakeServiceEnabled && mShakeCallbacks != null) {
                mShakeCallbacks.onShake();
            }
        };
        mSettingsObserver = new SettingsObserver(null);
    }

    public static synchronized ShakeGestureService getInstance(Context context, ShakeGesturesCallbacks callback) {
        if (instance == null) {
            synchronized (ShakeGestureService.class) {
                if (instance == null) {
                    instance = new ShakeGestureService(context, callback);
                }
            }
        }
        return instance;
    }

    public void onStart() {
        if (mShakeGestureUtils == null) {
            mShakeGestureUtils = new ShakeGestureUtils(mContext);
        }
        updateSettings();
        mSettingsObserver.observe();
        if (mShakeServiceEnabled) {
            mShakeGestureUtils.registerListener(mShakeListener);
        }
    }

    private void updateSettings() {
        boolean wasShakeServiceEnabled = mShakeServiceEnabled;
        mShakeServiceEnabled = Settings.System.getInt(mContext.getContentResolver(),
                SHAKE_GESTURES_ENABLED, 0) == 1;
        if (mShakeServiceEnabled && !wasShakeServiceEnabled) {
            mShakeGestureUtils.registerListener(mShakeListener);
        } else if (!mShakeServiceEnabled && wasShakeServiceEnabled) {
            mShakeGestureUtils.unregisterListener(mShakeListener);
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(SHAKE_GESTURES_ENABLED), false, this, USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(SHAKE_GESTURES_ACTION), false, this, USER_ALL);
        }
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
}
