/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm;

import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;
import static android.provider.Settings.System.ACCELEROMETER_ROTATION;
import static android.provider.Settings.System.getUriFor;

import static com.android.internal.view.RotationPolicy.getNaturalRotation;
import static com.android.internal.view.RotationPolicy.isCurrentRotationAllowed;
import static com.android.internal.view.RotationPolicy.useCurrentRotationOnRotationLockChange;
import static com.android.server.wm.DisplayRotation.NO_UPDATE_USER_ROTATION;
import static com.android.server.wm.DisplayRotation.USE_CURRENT_ROTATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.devicestate.DeviceState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure.DeviceStateRotationLockKey;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.DeviceStateAutoRotateSettingController.Event.PersistedSettingUpdate;
import com.android.server.wm.DeviceStateAutoRotateSettingController.Event.UpdateAccelerometerRotationSetting;
import com.android.server.wm.DeviceStateAutoRotateSettingController.Event.UpdateDevicePosture;
import com.android.server.wm.DeviceStateAutoRotateSettingController.Event.UpdateDeviceStateAutoRotateSetting;
import com.android.server.wm.DeviceStateController.DeviceStateEnum;
import com.android.settingslib.devicestate.DeviceStateAutoRotateSettingManager;
import com.android.settingslib.devicestate.DeviceStateAutoRotateSettingManager.DeviceStateAutoRotateSetting;
import com.android.settingslib.devicestate.PostureDeviceStateConverter;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Syncs ACCELEROMETER_ROTATION and DEVICE_STATE_ROTATION_LOCK setting to consistent values.
 * <ul>
 * <li>On device state change: Reads value of DEVICE_STATE_ROTATION_LOCK for new device state and
 * writes it into ACCELEROMETER_ROTATION.</li>
 * <li>On ACCELEROMETER_ROTATION setting change: Write updated ACCELEROMETER_ROTATION value into
 * DEVICE_STATE_ROTATION_LOCK setting for current device state.</li>
 * <li>On DEVICE_STATE_ROTATION_LOCK setting change: If the key for the changed value matches
 * current device state, write updated auto rotate value to ACCELEROMETER_ROTATION.</li>
 * </ul>
 *
 * @see Settings.System#ACCELEROMETER_ROTATION
 * @see Settings.Secure#DEVICE_STATE_ROTATION_LOCK
 */

public class DeviceStateAutoRotateSettingController {
    private static final String TAG = "DSAutoRotateCtrl";
    private static final int ACCELEROMETER_ROTATION_OFF = 0;
    private static final int ACCELEROMETER_ROTATION_ON = 1;
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final int MSG_UPDATE_STATE = 1;

    private final Handler mHandler;
    private final Handler mEventHandler;
    private final DeviceStateAutoRotateSettingManager mDeviceStateAutoRotateSettingManager;
    private final ContentResolver mContentResolver;
    private final DeviceStateController mDeviceStateController;
    private final List<Event> mPendingEvents = new ArrayList<>();
    private final WindowManagerService mWm;
    private final Context mContext;
    private final PostureDeviceStateConverter mPostureDeviceStateConverter;
    private final DeviceStateAutoRotateHistory mDeviceStateAutoRotateHistory =
            new DeviceStateAutoRotateHistory();

    @DeviceStateRotationLockKey
    private int mDevicePosture = DEVICE_STATE_ROTATION_KEY_UNKNOWN;
    private boolean mAccelerometerSetting;
    private DeviceStateAutoRotateSetting mDeviceStateAutoRotateSetting;

    public DeviceStateAutoRotateSettingController(
            @NonNull DeviceStateController deviceStateController,
            @NonNull DeviceStateAutoRotateSettingManager deviceStateAutoRotateSettingManager,
            @NonNull WindowManagerService wmService,
            @NonNull PostureDeviceStateConverter postureDeviceStateConverter) {
        mDeviceStateAutoRotateSettingManager = deviceStateAutoRotateSettingManager;
        mWm = wmService;
        mContext = mWm.mContext;
        mHandler = getHandler();
        mContentResolver = mContext.getContentResolver();
        mDeviceStateController = deviceStateController;
        mPostureDeviceStateConverter = postureDeviceStateConverter;
        mDeviceStateAutoRotateSetting = getDeviceStateAutoRotateSetting();
        if (mDeviceStateAutoRotateSetting == null) {
            // Map would be null if string value of DEVICE_STATE_ROTATION_LOCK is corrupted.
            mDeviceStateAutoRotateSetting = getDefaultDeviceStateAutoRotateSetting();
        }
        mAccelerometerSetting = getAccelerometerRotationSetting();
        mEventHandler = new Handler(mHandler.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                final Event event = (Event) msg.obj;
                if (mDevicePosture == DEVICE_STATE_ROTATION_KEY_UNKNOWN
                        && !(event instanceof UpdateDevicePosture)) {
                    mPendingEvents.add(event);
                    Log.w(TAG, "Trying to write into auto-rotate settings, while "
                            + "device-state is unavailable.\n" + "Could not process the event="
                            + event.getClass().getSimpleName() + ".\n"
                            + "This event will be queued and processed later once we receive "
                            + "device-state update.");
                    return;
                }

                handleEvent(event);

                if (!mPendingEvents.isEmpty()) {
                    for (int i = 0; i < mPendingEvents.size(); i++) {
                        handleEvent(mPendingEvents.get(i));
                    }
                    mPendingEvents.clear();
                }
            }
        };

        registerDeviceStateAutoRotateSettingObserver();
        registerAccelerometerRotationSettingObserver();
        registerDeviceStateObserver();
    }

    private void handleEvent(@NonNull Event event) {
        final boolean persistedAccelerometerRotationSettingBefore =
                getAccelerometerRotationSetting();
        final DeviceStateAutoRotateSetting persistedDeviceStateAutoRotateSettingBefore =
                getDeviceStateAutoRotateSetting();

        updateInMemoryState(event, persistedAccelerometerRotationSettingBefore,
                persistedDeviceStateAutoRotateSettingBefore);

        final boolean wasPersistedSettingChanged = writeInMemoryStateIntoPersistedSetting(
                persistedAccelerometerRotationSettingBefore,
                persistedDeviceStateAutoRotateSettingBefore);
        writeUserRotationSettingIfNeeded(event, persistedAccelerometerRotationSettingBefore);
        if (DEBUG) {
            mDeviceStateAutoRotateHistory.addRecord(event,
                    persistedAccelerometerRotationSettingBefore,
                    persistedDeviceStateAutoRotateSettingBefore, mDevicePosture,
                    mAccelerometerSetting, mDeviceStateAutoRotateSetting.clone(),
                    wasPersistedSettingChanged);
        }
    }

    /** Request to change {@link DEVICE_STATE_ROTATION_LOCK} persisted setting. */
    public void requestDeviceStateAutoRotateSettingChange(int deviceState, boolean autoRotate) {
        final int devicePosture = mPostureDeviceStateConverter.deviceStateToPosture(deviceState);
        if (devicePosture == DEVICE_STATE_ROTATION_KEY_UNKNOWN) {
            Log.e(TAG,
                    "Device state auto rotate setting change requested for invalid device state. "
                            + "No matching posture found for device state: " + deviceState);
            return;
        }

        postUpdate(new UpdateDeviceStateAutoRotateSetting(devicePosture, autoRotate));
    }

    /**
     * Request to change {@link ACCELEROMETER_ROTATION} persisted setting. If needed, we might also
     * write into {@link USER_ROTATION} with {@code userRotation}.
     */
    public void requestAccelerometerRotationSettingChange(boolean autoRotate, int userRotation,
            String caller) {
        postUpdate(new UpdateAccelerometerRotationSetting(autoRotate, userRotation, caller));
    }

    private void registerDeviceStateAutoRotateSettingObserver() {
        mDeviceStateAutoRotateSettingManager.registerListener(
                () -> postUpdate(PersistedSettingUpdate.INSTANCE));
    }

    private void registerAccelerometerRotationSettingObserver() {
        mContentResolver.registerContentObserver(
                getUriFor(ACCELEROMETER_ROTATION),
                /* notifyForDescendants= */ false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        postUpdate(PersistedSettingUpdate.INSTANCE);
                    }
                }, UserHandle.USER_CURRENT);
    }

    private void registerDeviceStateObserver() {
        mDeviceStateController.registerDeviceStateCallback(
                (DeviceStateEnum deviceStateEnum, DeviceState deviceState) -> {
                    final int devicePosture = mPostureDeviceStateConverter.deviceStateToPosture(
                            deviceState.getIdentifier());
                    if (devicePosture == DEVICE_STATE_ROTATION_KEY_UNKNOWN) {
                        Log.e(TAG,
                                "Updated device state is invalid. No matching posture found for "
                                        + "device state: "
                                        + deviceState);
                        return;
                    }

                    postUpdate(new UpdateDevicePosture(devicePosture));
                },
                new HandlerExecutor(mHandler));
    }

    private void postUpdate(Event event) {
        Message.obtain(mEventHandler, MSG_UPDATE_STATE, event).sendToTarget();
    }

    private void updateInMemoryState(Event event, boolean persistedAccelerometerRotationSetting,
            DeviceStateAutoRotateSetting persistedDeviceStateAutoRotateSetting) {
        // Compare persisted setting value with in-memory state before making any changes to
        // in-memory state. This is to detect if persisted setting was changed directly, which is
        // not expected.
        // Object would be null if string value of DEVICE_STATE_ROTATION_LOCK is corrupted.
        final boolean isDeviceStateAutoRotateSettingCorrupted =
                persistedDeviceStateAutoRotateSetting == null;
        if (isDeviceStateAutoRotateSettingCorrupted) {
            // If string value of DEVICE_STATE_ROTATION_LOCK is corrupted, rewrite it with default
            // value while also respecting current ACCELEROMETER_ROTATION setting value.
            persistedDeviceStateAutoRotateSetting = getDefaultDeviceStateAutoRotateSetting();
            if (mDevicePosture != DEVICE_STATE_ROTATION_KEY_UNKNOWN) {
                persistedDeviceStateAutoRotateSetting.set(mDevicePosture,
                        mAccelerometerSetting ? DEVICE_STATE_ROTATION_LOCK_UNLOCKED
                                : DEVICE_STATE_ROTATION_LOCK_LOCKED);
            }
        }

        final boolean wasAccelerometerRotationSettingChanged =
                (persistedAccelerometerRotationSetting != mAccelerometerSetting);
        final boolean wasDevicesStateAutoRotateSettingChanged =
                !mDeviceStateAutoRotateSetting.equals(persistedDeviceStateAutoRotateSetting);

        if (wasAccelerometerRotationSettingChanged || wasDevicesStateAutoRotateSettingChanged) {
            // System apps should only request changes via DeviceStateAutoRotateSettingManager's
            // APIs. Direct updates to the persisted setting will trigger an error.
            StringBuilder errorMessage = new StringBuilder("Persisted setting:\n");
            if (wasAccelerometerRotationSettingChanged) {
                errorMessage.append("ACCELEROMETER_ROTATION setting changed from ").append(
                        mAccelerometerSetting).append(" to ").append(
                        persistedAccelerometerRotationSetting).append(" via Settings API.\n");
            }
            if (wasDevicesStateAutoRotateSettingChanged) {
                errorMessage.append(
                        "DEVICE_STATE_ROTATION_LOCK setting directly changed from ").append(
                        mDeviceStateAutoRotateSetting).append(" to ").append(
                        persistedDeviceStateAutoRotateSetting).append(
                        "\nExpectation is for system-apps to only use defined apis to change "
                                + "auto-rotate persisted settings.\n");
            }
            Slog.e(TAG, errorMessage
                    + "Using Settings API to write auto-rotate persisted setting, could result "
                    + "in inconsistent auto-rotate values.");
        }

        // TODO(b/412714949): Add logging or a mechanism to dump the state whenever changes are made
        //  to relevant settings
        updateInMemoryStateFromEvent(event);

        // At this point, all in-memory properties should be updated, excluding any changes made
        // directly to persisted settings.
        // When ACCELEROMETER_ROTATION and DEVICE_STATE_ROTATION_LOCK persisted settings both change
        // since the last update was processed, there is no way to know the order of their change.
        // Conflicts will arise in determining which change to persist. In that case, we will
        // prioritize ACCELEROMETER_ROTATION because it has a direct impact on the user visible
        // behavior.
        if (wasDevicesStateAutoRotateSettingChanged) {
            // Clone the persistedDeviceStateAutoRotateSetting to avoid modifying it when updating
            // mDeviceStateAutoRotateSetting in future
            mDeviceStateAutoRotateSetting = persistedDeviceStateAutoRotateSetting.clone();
            mAccelerometerSetting = mDeviceStateAutoRotateSetting.get(mDevicePosture);
        }
        if (wasAccelerometerRotationSettingChanged) {
            mAccelerometerSetting = persistedAccelerometerRotationSetting;
            mDeviceStateAutoRotateSetting.set(mDevicePosture,
                    mAccelerometerSetting ? DEVICE_STATE_ROTATION_LOCK_UNLOCKED
                            : DEVICE_STATE_ROTATION_LOCK_LOCKED);
        }
    }

    private void updateInMemoryStateFromEvent(Event event) {
        switch (event) {
            case UpdateAccelerometerRotationSetting updateAccelerometerRotationSetting -> {
                mAccelerometerSetting = updateAccelerometerRotationSetting.mAutoRotate;
                mDeviceStateAutoRotateSetting.set(mDevicePosture,
                        mAccelerometerSetting ? DEVICE_STATE_ROTATION_LOCK_UNLOCKED
                                : DEVICE_STATE_ROTATION_LOCK_LOCKED);
            }
            case UpdateDeviceStateAutoRotateSetting updateDeviceStateAutoRotateSetting -> {
                mDeviceStateAutoRotateSetting.set(updateDeviceStateAutoRotateSetting.mDevicePosture,
                        updateDeviceStateAutoRotateSetting.mAutoRotate
                                ? DEVICE_STATE_ROTATION_LOCK_UNLOCKED
                                : DEVICE_STATE_ROTATION_LOCK_LOCKED);
                mAccelerometerSetting = mDeviceStateAutoRotateSetting.get(mDevicePosture);
            }
            case UpdateDevicePosture updateDevicePosture -> {
                mDevicePosture = updateDevicePosture.mDevicePosture;
                mAccelerometerSetting = mDeviceStateAutoRotateSetting.get(mDevicePosture);
            }
            default -> {
            }
        }
    }

    private boolean writeInMemoryStateIntoPersistedSetting(
            boolean persistedAccelerometerRotationSetting,
            DeviceStateAutoRotateSetting persistedDeviceStateAutoRotateSetting) {
        boolean wasPersistedSettingChanged = false;
        if (mAccelerometerSetting != persistedAccelerometerRotationSetting) {
            Settings.System.putIntForUser(mContentResolver, ACCELEROMETER_ROTATION,
                    mAccelerometerSetting ? ACCELEROMETER_ROTATION_ON : ACCELEROMETER_ROTATION_OFF,
                    UserHandle.USER_CURRENT);

            if (DEBUG) {
                Slog.d(TAG, "Wrote into persisted setting:\n" + "ACCELEROMETER_ROTATION="
                        + mAccelerometerSetting);
            }
            wasPersistedSettingChanged = true;
        }

        if (!mDeviceStateAutoRotateSetting.equals(persistedDeviceStateAutoRotateSetting)) {
            mDeviceStateAutoRotateSetting.write();

            if (DEBUG) {
                Slog.d(TAG, "Wrote into persisted setting:\n" + "DEVICE_STATE_ROTATION_LOCK="
                        + mDeviceStateAutoRotateSetting);
            }
            wasPersistedSettingChanged = true;
        }
        return wasPersistedSettingChanged;
    }

    private void writeUserRotationSettingIfNeeded(Event event,
            boolean persistedAccelerometerRotationSetting) {
        if (!(event instanceof UpdateAccelerometerRotationSetting)
                && (mAccelerometerSetting == persistedAccelerometerRotationSetting)) {
            return;
        }
        final int userRotation;
        final String caller;

        if (event instanceof UpdateAccelerometerRotationSetting) {
            // If the event is `UpdateAccelerometerRotationSetting`, it means that the
            // userRotation was provided, so we should set it.
            userRotation = ((UpdateAccelerometerRotationSetting) event).mUserRotation;
            caller = ((UpdateAccelerometerRotationSetting) event).mCaller;
        } else {
            // If the event is not `UpdateAccelerometerRotationSetting`, it means that the
            // userRotation was not explicitly provided.
            if (mAccelerometerSetting) {
                userRotation = NO_UPDATE_USER_ROTATION;
            } else {
                userRotation = isCurrentRotationAllowed(mContext)
                        || useCurrentRotationOnRotationLockChange(mContext)
                        ? USE_CURRENT_ROTATION
                        : getNaturalRotation();
            }
            caller = "DSAutoRotateCtrl#" + event.getClass().getSimpleName();
        }
        synchronized (mWm.mRoot.mService.mGlobalLock) {
            mWm.mRoot.getDefaultDisplay().getDisplayRotation().setUserRotationSetting(
                    mAccelerometerSetting ? WindowManagerPolicy.USER_ROTATION_FREE
                            : WindowManagerPolicy.USER_ROTATION_LOCKED, userRotation, caller);
        }
    }

    private boolean getAccelerometerRotationSetting() {
        return Settings.System.getIntForUser(mContentResolver, ACCELEROMETER_ROTATION,
                /* def= */ -1, UserHandle.USER_CURRENT) == ACCELEROMETER_ROTATION_ON;
    }

    @Nullable
    private DeviceStateAutoRotateSetting getDeviceStateAutoRotateSetting() {
        return mDeviceStateAutoRotateSettingManager.getRotationLockSetting();
    }

    @NonNull
    private DeviceStateAutoRotateSetting getDefaultDeviceStateAutoRotateSetting() {
        return mDeviceStateAutoRotateSettingManager.getDefaultRotationLockSetting();
    }

    public void dump(String prefix, PrintWriter pw) {
        mDeviceStateAutoRotateHistory.dump(prefix, pw);
    }

    @VisibleForTesting
    Handler getHandler() {
        return mWm.mH;
    }

    /**
     * Stores a recent history of events and the resulting actions for debugging purposes.
     * The history has a maximum size and old records are discarded.
     */
    private static class DeviceStateAutoRotateHistory {
        private static final int MAX_SIZE = 16;
        private final ArrayDeque<Record> mRecords = new ArrayDeque<>(MAX_SIZE);

        /** Dumps the history of records to the provided {@link PrintWriter}. */
        void dump(String prefix, PrintWriter pw) {
            if (!mRecords.isEmpty()) {
                pw.println();
                pw.println(prefix + "  DeviceStateAutoRotateHistory");
                prefix = "    " + prefix;
                for (Record r : mRecords) {
                    r.dump(prefix, pw);
                }
                pw.println();
            }
        }

        /**
         * Adds a record of an event that was received and the operation that was performed in
         * response. This captures the state of the system before the event is processed and after
         * the operation has been finished.
         *
         * @param event                                       the event that was received.
         * @param persistedAccelerometerSettingBefore         the value of the accelerometer setting
         *                                                    as read from persisted storage before
         *                                                    the event was processed.
         * @param persistedDeviceStateAutoRotateSettingBefore the value of the device state auto
         *                                                    -rotate setting as read from
         *                                                    persisted storage before the event was
         *                                                    processed.
         * @param devicePostureAfter                          the in-memory value of the  device
         *                                                    posture after the operation performed.
         * @param accelerometerSettingAfter                   the in-memory value of the
         *                                                    accelerometer setting after the
         *                                                    operation performed.
         * @param deviceStateAutoRotateSettingAfter           the in-memory value of the device
         *                                                    state auto-rotate setting after the
         *                                                    operation performed.
         * @param wasPersistedSettingChanged                  is true if any persisted setting is
         *                                                    written into at the end of the latest
         *                                                    operation.
         */
        void addRecord(Event event, boolean persistedAccelerometerSettingBefore,
                DeviceStateAutoRotateSetting persistedDeviceStateAutoRotateSettingBefore,
                @DeviceStateRotationLockKey int devicePostureAfter,
                boolean accelerometerSettingAfter,
                DeviceStateAutoRotateSetting deviceStateAutoRotateSettingAfter,
                boolean wasPersistedSettingChanged) {
            if (mRecords.size() >= MAX_SIZE) {
                mRecords.removeFirst();
            }
            mRecords.addLast(new Record(event, persistedAccelerometerSettingBefore,
                    persistedDeviceStateAutoRotateSettingBefore, devicePostureAfter,
                    accelerometerSettingAfter, deviceStateAutoRotateSettingAfter,
                    wasPersistedSettingChanged));
        }

        /** A single entry in the history, representing an event and the operation that followed. */
        private static final class Record {
            final long mTimestamp = System.currentTimeMillis();
            private final Event mEvent;
            private final boolean mPersistedAccelerometerSettingBefore;
            private final DeviceStateAutoRotateSetting mPersistedDeviceStateAutoRotateSettingBefore;
            @DeviceStateRotationLockKey
            private final int mDevicePostureAfter;
            private final boolean mAccelerometerSettingAfter;
            private final DeviceStateAutoRotateSetting mDeviceStateAutoRotateSettingAfter;
            private final boolean mWasPersistedSettingChanged;

            private Record(Event event, boolean persistedAccelerometerSettingBefore,
                    DeviceStateAutoRotateSetting persistedDeviceStateAutoRotateSettingBefore,
                    @DeviceStateRotationLockKey int devicePostureAfter,
                    boolean accelerometerSettingAfter,
                    DeviceStateAutoRotateSetting deviceStateAutoRotateSettingAfter,
                    boolean wasPersistedSettingChanged) {
                mEvent = event;
                mPersistedAccelerometerSettingBefore = persistedAccelerometerSettingBefore;
                mPersistedDeviceStateAutoRotateSettingBefore =
                        persistedDeviceStateAutoRotateSettingBefore;
                mDevicePostureAfter = devicePostureAfter;
                mAccelerometerSettingAfter = accelerometerSettingAfter;
                mDeviceStateAutoRotateSettingAfter = deviceStateAutoRotateSettingAfter;
                mWasPersistedSettingChanged = wasPersistedSettingChanged;
            }


            /** Dumps the contents of this record to the provided {@link PrintWriter}. */
            void dump(String prefix, PrintWriter pw) {
                pw.println(prefix + TimeUtils.logTimeOfDay(mTimestamp));
                prefix = "    " + prefix;
                pw.println(prefix + "Received Event: " + mEvent);
                pw.println(prefix + "Persisted setting values before event: "
                        + "[ACCELEROMETER_ROTATION=" + mPersistedAccelerometerSettingBefore
                        + ", DEVICE_STATE_ROTATION_LOCK="
                        + mPersistedDeviceStateAutoRotateSettingBefore + "]");
                String actionDescription =
                        mWasPersistedSettingChanged ? "Wrote into persisted setting"
                                : "Did not write into persisted setting";
                pw.println(prefix + actionDescription + ", in-memory state after event "
                        + "[mDevicePosture=" + mDevicePostureAfter + ", mAccelerometerSetting="
                        + mAccelerometerSettingAfter + ", mDeviceStateAutoRotateSetting="
                        + mDeviceStateAutoRotateSettingAfter + "]");
            }
        }
    }

    static sealed class Event {
        private Event() {
        }

        /**
         * Event sent when there is a request to update the current auto-rotate setting.
         * This occurs when actions like `freezeRotation` or `thawRotation` are triggered.
         * It also contains the user rotation that should be set, if userRotation is -1 then
         * {@link Settings.System#USER_ROTATION} setting will not be updated.
         */
        static final class UpdateAccelerometerRotationSetting extends Event {
            final boolean mAutoRotate;
            final int mUserRotation;
            final String mCaller;

            /**
             * @param autoRotate   The desired auto-rotate state to write into
             *                     ACCELEROMETER_ROTATION.
             * @param userRotation The desired user rotation to write into USER_ROTATION.
             * @param caller       Identifying the caller for logging/debugging purposes.
             */
            UpdateAccelerometerRotationSetting(boolean autoRotate, int userRotation,
                    String caller) {
                mAutoRotate = autoRotate;
                mUserRotation = userRotation;
                mCaller = caller;
            }

            @Override
            public String toString() {
                return "UpdateAccelerometerRotationSetting[mAutoRotate=" + mAutoRotate
                        + ", mUserRotation=" + mUserRotation + ", mCaller=" + mCaller + "]";
            }
        }

        /**
         * Event sent when there is a request to update the device's auto-rotate
         * setting(DEVICE_STATE_ROTATION_LOCK) for a specific device posture.
         */
        static final class UpdateDeviceStateAutoRotateSetting extends Event {
            @DeviceStateRotationLockKey
            final int mDevicePosture;
            final boolean mAutoRotate;

            /**
             * @param devicePosture The device posture the change is intended for.
             * @param autoRotate    The desired auto-rotate state for this device state.
             */
            UpdateDeviceStateAutoRotateSetting(@DeviceStateRotationLockKey int devicePosture,
                    boolean autoRotate) {
                mDevicePosture = devicePosture;
                mAutoRotate = autoRotate;
            }

            @Override
            public String toString() {
                return "UpdateDeviceStateAutoRotateSetting[mDevicePosture=" + mDevicePosture
                        + ", mAutoRotate=" + mAutoRotate + "]";
            }
        }

        /**
         * Event sent when the device posture changes.
         */
        static final class UpdateDevicePosture extends Event {
            @DeviceStateRotationLockKey
            final int mDevicePosture;

            /**
             * @param devicePosture New device posture.
             */
            UpdateDevicePosture(@DeviceStateRotationLockKey int devicePosture) {
                mDevicePosture = devicePosture;
            }

            @Override
            public String toString() {
                return "UpdateDevicePosture[mDevicePosture=" + mDevicePosture + "]";
            }
        }

        /**
         * Event sent when there is a change in either of the two persisted settings:
         * <ul>
         * <li> Current auto-rotate setting</li>
         * <li> Device state auto-rotate setting</li>
         * </ul>
         */
        static final class PersistedSettingUpdate extends Event {
            static final PersistedSettingUpdate INSTANCE = new PersistedSettingUpdate();

            private PersistedSettingUpdate() {
            }

            @Override
            public String toString() {
                return "PersistedSettingUpdate";
            }
        }
    }
}
