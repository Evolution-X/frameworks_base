/*
 * Copyright (C) 2006-2011 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.IntArray;
import android.util.SparseArray;
import android.widget.WidgetFlags;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.NamedLock;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helper class for watching a set of core settings which the framework
 * propagates to application processes to avoid multiple lookups and potentially
 * disk I/O operations. Note: This class assumes that all core settings reside
 * in {@link Settings.Secure}.
 */
class CoreSettingsObserver extends ContentObserver {
    private static final String TAG = CoreSettingsObserver.class.getSimpleName();
    protected static final boolean DEBUG = false;

    protected final Object mLock = NamedLock.create("CoreSettingsObserverLock");

    /**
     * Holds the core settings for each running user. The key is the user ID.
     * This ensures that settings for one user do not leak to another.
     */
    @GuardedBy("mLock")
    private final SparseArray<Bundle> mCoreSettingsPerUser = new SparseArray<>();

    @GuardedBy("mLock")
    private final Bundle mGlobalSettingsBundle;
    @GuardedBy("mLock")
    private final Bundle mDeviceConfigBundle;

    private static class DeviceConfigEntry<T> {
        String namespace;
        String flag;
        String coreSettingKey;
        Class<T> type;
        T defaultValue;

        DeviceConfigEntry(String namespace, String flag, String coreSettingKey, Class<T> type,
                @NonNull T defaultValue) {
            this.namespace = namespace;
            this.flag = flag;
            this.coreSettingKey = coreSettingKey;
            this.type = type;
            this.defaultValue = Objects.requireNonNull(defaultValue);
        }
    }

    // mapping form property name to its type
    @VisibleForTesting
    static final Map<String, Class<?>> sSecureSettingToTypeMap = new HashMap<>();
    @VisibleForTesting
    static final Map<String, Class<?>> sSystemSettingToTypeMap = new HashMap<>();
    @VisibleForTesting
    static final Map<String, Class<?>> sGlobalSettingToTypeMap = new HashMap<>();
    static final List<DeviceConfigEntry> sDeviceConfigEntries = new ArrayList<>();
    static {
        sSecureSettingToTypeMap.put(Settings.Secure.LONG_PRESS_TIMEOUT, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.MULTI_PRESS_TIMEOUT, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.KEY_REPEAT_TIMEOUT_MS, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.KEY_REPEAT_DELAY_MS, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.KEY_REPEAT_ENABLED, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.ACCESSIBILITY_TEXT_CURSOR_BLINK_INTERVAL_MS,
                int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.STYLUS_POINTER_ICON_ENABLED, int.class);
        // add other secure settings here...

        sSystemSettingToTypeMap.put(Settings.System.TIME_12_24, String.class);
        // add other system settings here...

        sGlobalSettingToTypeMap.put(Settings.Global.DEBUG_VIEW_ATTRIBUTES, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_DEBUG_PACKAGE, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_DYNAMIC_DENYLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_ALL_ANGLE, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_SELECTION_PKGS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_SELECTION_VALUES, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_EGL_FEATURES, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.SHOW_ANGLE_IN_USE_DIALOG_BOX, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.ENABLE_GPU_DEBUG_LAYERS, int.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_APP, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYERS, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYERS_GLES, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYER_APP, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.UPDATABLE_DRIVER_ALL_APPS, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_IN_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRERELEASE_OPT_IN_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_OUT_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_ALLOWLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLISTS, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.UPDATABLE_DRIVER_SPHAL_LIBRARIES, String.class);
        // add other global settings here...

        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_CURSOR_DRAG_FROM_ANYWHERE,
                WidgetFlags.KEY_ENABLE_CURSOR_DRAG_FROM_ANYWHERE, boolean.class,
                WidgetFlags.ENABLE_CURSOR_DRAG_FROM_ANYWHERE_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL,
                WidgetFlags.KEY_CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL, int.class,
                WidgetFlags.CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.FINGER_TO_CURSOR_DISTANCE,
                WidgetFlags.KEY_FINGER_TO_CURSOR_DISTANCE, int.class,
                WidgetFlags.FINGER_TO_CURSOR_DISTANCE_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_INSERTION_HANDLE_GESTURES,
                WidgetFlags.KEY_ENABLE_INSERTION_HANDLE_GESTURES, boolean.class,
                WidgetFlags.ENABLE_INSERTION_HANDLE_GESTURES_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.INSERTION_HANDLE_DELTA_HEIGHT,
                WidgetFlags.KEY_INSERTION_HANDLE_DELTA_HEIGHT, int.class,
                WidgetFlags.INSERTION_HANDLE_DELTA_HEIGHT_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.INSERTION_HANDLE_OPACITY,
                WidgetFlags.KEY_INSERTION_HANDLE_OPACITY, int.class,
                WidgetFlags.INSERTION_HANDLE_OPACITY_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.LINE_SLOP_RATIO,
                WidgetFlags.KEY_LINE_SLOP_RATIO, float.class,
                WidgetFlags.LINE_SLOP_RATIO_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_NEW_MAGNIFIER,
                WidgetFlags.KEY_ENABLE_NEW_MAGNIFIER, boolean.class,
                WidgetFlags.ENABLE_NEW_MAGNIFIER_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.MAGNIFIER_ZOOM_FACTOR,
                WidgetFlags.KEY_MAGNIFIER_ZOOM_FACTOR, float.class,
                WidgetFlags.MAGNIFIER_ZOOM_FACTOR_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.MAGNIFIER_ASPECT_RATIO,
                WidgetFlags.KEY_MAGNIFIER_ASPECT_RATIO, float.class,
                WidgetFlags.MAGNIFIER_ASPECT_RATIO_DEFAULT));

        // add other device configs here...
    }
    private static volatile boolean sDeviceConfigContextEntriesLoaded = false;

    protected final ActivityManagerService mActivityManagerService;

    @Nullable
    private VirtualDeviceManager mVirtualDeviceManager;

    protected CoreSettingsObserver(ActivityManagerService activityManagerService) {
        super(activityManagerService.mHandler);

        if (!sDeviceConfigContextEntriesLoaded) {
            synchronized (sDeviceConfigEntries) {
                if (!sDeviceConfigContextEntriesLoaded) {
                    loadDeviceConfigContextEntries(activityManagerService.mContext);
                    sDeviceConfigContextEntriesLoaded = true;
                }
            }
        }

        mActivityManagerService = activityManagerService;
        mGlobalSettingsBundle = new Bundle(sGlobalSettingToTypeMap.size());
        mDeviceConfigBundle = new Bundle(sDeviceConfigEntries.size());
        updateGlobalSettings();

        beginObserveCoreSettings(/* allUsers */ true);
        sendCoreSettings();
    }

    /**
     * Factory method for creating a {@link CoreSettingsObserver} instance.
     *
     * @param activityManagerService The {@link ActivityManagerService} instance.
     * @return A new {@link CoreSettingsObserver} instance.
     */
    static CoreSettingsObserver create(ActivityManagerService activityManagerService) {
        return new CoreSettingsObserver(activityManagerService);
    }

    private static void loadDeviceConfigContextEntries(Context context) {
        sDeviceConfigEntries.add(new DeviceConfigEntry<>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ANALOG_CLOCK_SECONDS_HAND_FPS,
                WidgetFlags.KEY_ANALOG_CLOCK_SECONDS_HAND_FPS, int.class,
                context.getResources()
                        .getInteger(R.integer.config_defaultAnalogClockSecondsHandFps)));
    }

    /**
     * Gets a deep copy of the core settings for a specific user.
     *
     * @param userId The user ID for which to retrieve the settings.
     * @return A deep copy of the core settings {@link Bundle}.
     */
    public Bundle getCoreSettings(@UserIdInt int userId) {
        synchronized (mLock) {
            Bundle settings = mCoreSettingsPerUser.get(userId);
            if (settings == null) {
                IntArray currentUsers = new IntArray(mCoreSettingsPerUser.size());
                for (int i = 0; i < mCoreSettingsPerUser.size(); i++) {
                    currentUsers.add(mCoreSettingsPerUser.keyAt(i));
                }
                Slogf.w(TAG, "No core settings found for user %d. Current users: %s",
                        userId, currentUsers);
                return Bundle.EMPTY;
            }
            return settings.deepCopy();
        }
    }

    /**
     * Called when a user is starting.
     * This triggers a refresh of the core settings for the starting user to ensure the
     * new user's settings are populated and sent to the relevant processes.
     *
     * <p>This method builds and dispatches core settings exclusively for the new user,
     * avoiding redundant updates for already running users. The resulting {@link SparseArray}
     * sent to {@link ActivityManagerService#onCoreSettingsChange} contains settings for only
     * the starting user, which {@link com.android.server.os.ProcessList#updateCoreSettings}
     * will then apply selectively.
     */
    public void onUserStarting(@UserIdInt int userId) {
        if (DEBUG) {
            Slogf.d(TAG, "onUserStarting %d", userId);
        }
        synchronized (mLock) {
            if (mCoreSettingsPerUser.contains(userId)) {
                // The boot process commonly calls this method for the system user,
                // at which point this class has already been initialized for the same user.
                if (DEBUG) {
                    Slogf.d(TAG, "Core settings for user %d already exist.", userId);
                }
                return;
            }
        }
        Bundle userSettings = buildSettingsForUser(userId);
        synchronized (mLock) {
            mCoreSettingsPerUser.put(userId, userSettings);
        }
        SparseArray<Bundle> settingsToSend = new SparseArray<>(1);
        settingsToSend.put(userId, userSettings);
        mActivityManagerService.onCoreSettingsChange(settingsToSend);
    }

    /**
     * Called when a user is stopping.
     * This removes the stopped user's settings from the cache.
     */
    public void onUserStopping(@UserIdInt int userId) {
        if (DEBUG) {
            Slogf.d(TAG, "onUserStopping %d", userId);
        }
        synchronized (mLock) {
            mCoreSettingsPerUser.remove(userId);
        }
    }

    @Override
    public void onChange(boolean selfChange) {
        if (DEBUG) {
            Slogf.d(TAG, "onChange(%b)", selfChange);
        }
        updateGlobalSettings();
        sendCoreSettings();
    }

    protected final IntArray getVirtualDeviceIds() {
        if (mVirtualDeviceManager == null) {
            // System providers are installed before VirtualDeviceManagerService starts.
            if (ServiceManager.checkService(Context.VIRTUAL_DEVICE_SERVICE) == null) {
                return new IntArray(0);
            }
            mVirtualDeviceManager = mActivityManagerService.mContext.getSystemService(
                    VirtualDeviceManager.class);
            if (mVirtualDeviceManager == null) {
                return new IntArray(0);
            }
        }

        List<VirtualDevice> virtualDevices = mVirtualDeviceManager.getVirtualDevices();
        IntArray deviceIds = new IntArray(virtualDevices.size());
        for (int i = 0; i < virtualDevices.size(); i++) {
            deviceIds.add(virtualDevices.get(i).getDeviceId());
        }
        return deviceIds;
    }

    /**
     * Populates the core settings bundle with the latest values and sends them to app processes
     * via {@link ActivityThread}.
     * This will send the latest settings to all running users.
     */
    protected void sendCoreSettings() {
        int[] runningUserIds = mActivityManagerService.getRunningUserIds();
        if (DEBUG) {
            Slogf.d(TAG, "sendCoreSettings for users: %s", Arrays.toString(runningUserIds));
        }

        SparseArray<Bundle> settingsToSendPerUser = new SparseArray<>(runningUserIds.length);
        for (int userId : runningUserIds) {
            settingsToSendPerUser.put(userId, buildSettingsForUser(userId));
        }

        synchronized (mLock) {
            mCoreSettingsPerUser.clear();
            for (int i = 0; i < settingsToSendPerUser.size(); i++) {
                mCoreSettingsPerUser.put(settingsToSendPerUser.keyAt(i),
                        settingsToSendPerUser.valueAt(i));
            }
        }

        mActivityManagerService.onCoreSettingsChange(settingsToSendPerUser);
    }

    private void updateGlobalSettings() {
        synchronized (mLock) {
            mGlobalSettingsBundle.clear();
            populateSettings(mActivityManagerService.mContext, UserHandle.USER_SYSTEM,
                    mGlobalSettingsBundle, sGlobalSettingToTypeMap);
            mDeviceConfigBundle.clear();
            populateSettingsFromDeviceConfig(mDeviceConfigBundle);
        }
    }

    /**
     * Builds a Bundle containing all core settings for a specific user.
     *
     * <p>This includes user-specific settings (Secure and System) as well as cached global
     * settings. It also handles device-aware settings by creating a nested structure if needed.
     *
     * @param userId The user to build the settings for.
     * @return A {@link Bundle} containing the core settings.
     */
    private Bundle buildSettingsForUser(@UserIdInt int userId) {
        Context context = mActivityManagerService.mContext;
        Bundle globalSettingsBundle;
        Bundle deviceConfigBundle;
        synchronized (mLock) {
            globalSettingsBundle = new Bundle(mGlobalSettingsBundle);
            deviceConfigBundle = new Bundle(mDeviceConfigBundle);
        }

        final IntArray deviceIds = getVirtualDeviceIds();
        deviceIds.add(Context.DEVICE_ID_DEFAULT);
        final Bundle userSettingsBundle = new Bundle(deviceIds.size());
        for (int i = 0; i < deviceIds.size(); i++) {
            int deviceId = deviceIds.get(i);
            Context deviceContext;
            if (deviceId == Context.DEVICE_ID_DEFAULT) {
                deviceContext = context;
            } else {
                try {
                    deviceContext = context.createDeviceContext(deviceId);
                } catch (IllegalArgumentException e) {
                    Slogf.e(TAG, e, "Exception during Context#createDeviceContext "
                            + "for deviceId: %d", deviceId);
                    continue;
                }
            }

            if (DEBUG) {
                Slogf.d(TAG, "Populating settings for userId: %d, deviceId: %d",
                        userId, deviceId);
            }
            final Bundle deviceBundle = new Bundle();
            populateSettings(deviceContext, userId, deviceBundle, sSecureSettingToTypeMap);
            populateSettings(deviceContext, userId, deviceBundle, sSystemSettingToTypeMap);

            // Copy global settings and device config values.
            deviceBundle.putAll(globalSettingsBundle);
            deviceBundle.putAll(deviceConfigBundle);

            userSettingsBundle.putBundle(String.valueOf(deviceId), deviceBundle);
        }
        return userSettingsBundle;
    }

    protected final void beginObserveCoreSettings(boolean allUsers) {
        ContentResolver cr = mActivityManagerService.mContext.getContentResolver();
        final int user = allUsers ? UserHandle.USER_ALL : UserHandle.USER_SYSTEM;

        for (String setting : sSecureSettingToTypeMap.keySet()) {
            Uri uri = Settings.Secure.getUriFor(setting);
            cr.registerContentObserver(uri, false, this, user);
        }

        for (String setting : sSystemSettingToTypeMap.keySet()) {
            Uri uri = Settings.System.getUriFor(setting);
            cr.registerContentObserver(uri, false, this, user);
        }

        for (String setting : sGlobalSettingToTypeMap.keySet()) {
            Uri uri = Settings.Global.getUriFor(setting);
            cr.registerContentObserver(uri, false, this, user);
        }

        HashSet<String> deviceConfigNamespaces = new HashSet<>();
        for (DeviceConfigEntry entry : sDeviceConfigEntries) {
            if (!deviceConfigNamespaces.contains(entry.namespace)) {
                DeviceConfig.addOnPropertiesChangedListener(
                        entry.namespace, ActivityThread.currentApplication().getMainExecutor(),
                        (DeviceConfig.Properties prop) -> onChange(false));
                deviceConfigNamespaces.add(entry.namespace);
            }
        }
    }

    /**
     * Populates settings {@link Bundle} with values from the SettingsProvider for a specific user.
     *
     * @param context {@link Context} used to access the {@link android.content.ContentResolver}.
     * @param userId The user ID for whom the settings are being retrieved.
     * @param snapshot The {@link Bundle} to be populated with the settings.
     * @param map A map where keys are setting names and values are their data types.
     */
    @VisibleForTesting
    protected final void populateSettings(
            Context context, @UserIdInt int userId, Bundle snapshot, Map<String, Class<?>> map) {
        final ContentResolver cr = context.getContentResolver();
        for (Map.Entry<String, Class<?>> entry : map.entrySet()) {
            String setting = entry.getKey();
            final String value;
            if (map == sSecureSettingToTypeMap) {
                value = Settings.Secure.getStringForUser(cr, setting, userId);
            } else if (map == sSystemSettingToTypeMap) {
                value = Settings.System.getStringForUser(cr, setting, userId);
            } else {
                value = Settings.Global.getString(cr, setting);
            }
            if (value == null) {
                snapshot.remove(setting);
                continue;
            }
            Class<?> type = entry.getValue();
            try {
                if (type == String.class) {
                    snapshot.putString(setting, value);
                } else if (type == int.class) {
                    snapshot.putInt(setting, Integer.parseInt(value));
                } else if (type == float.class) {
                    snapshot.putFloat(setting, Float.parseFloat(value));
                } else if (type == long.class) {
                    snapshot.putLong(setting, Long.parseLong(value));
                }
            } catch (NumberFormatException e) {
                Slogf.w(TAG, e, "Couldn't parse %s for %s", value, setting);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected static final void populateSettingsFromDeviceConfig(Bundle bundle) {
        for (DeviceConfigEntry<?> entry : sDeviceConfigEntries) {
            if (entry.type == String.class) {
                String defaultValue = ((DeviceConfigEntry<String>) entry).defaultValue;
                bundle.putString(entry.coreSettingKey,
                        DeviceConfig.getString(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == int.class) {
                int defaultValue = ((DeviceConfigEntry<Integer>) entry).defaultValue;
                bundle.putInt(entry.coreSettingKey,
                        DeviceConfig.getInt(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == float.class) {
                float defaultValue = ((DeviceConfigEntry<Float>) entry).defaultValue;
                bundle.putFloat(entry.coreSettingKey,
                        DeviceConfig.getFloat(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == long.class) {
                long defaultValue = ((DeviceConfigEntry<Long>) entry).defaultValue;
                bundle.putLong(entry.coreSettingKey,
                        DeviceConfig.getLong(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == boolean.class) {
                boolean defaultValue = ((DeviceConfigEntry<Boolean>) entry).defaultValue;
                bundle.putInt(entry.coreSettingKey,
                        DeviceConfig.getBoolean(entry.namespace, entry.flag, defaultValue) ? 1 : 0);
            }
        }
    }
}
