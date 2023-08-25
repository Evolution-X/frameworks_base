/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.power.stats;

import android.annotation.Nullable;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.Parcelable;
import android.os.SynchronousResultReceiver;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.server.LocalServices;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A Worker that fetches data from external sources (WiFi controller, bluetooth chipset) on a
 * dedicated thread and updates BatteryStatsImpl with that information.
 *
 * As much work as possible is done without holding the BatteryStatsImpl lock, and only the
 * readily available data is pushed into BatteryStatsImpl with the lock held.
 */
public class BatteryExternalStatsWorker implements BatteryStatsImpl.ExternalStatsSync {
    private static final String TAG = "BatteryExternalStatsWorker";
    private static final boolean DEBUG = false;

    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;

    // There is some accuracy error in wifi reports so allow some slop in the results.
    private static final long MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS = 750;

    // Delay for clearing out battery stats for UIDs corresponding to a removed user
    public static final int UID_QUICK_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS = 2_000;

    // Delay for the _final_ clean-up of battery stats after a user removal - just in case
    // some UIDs took longer than UID_QUICK_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS to
    // stop running.
    public static final int UID_FINAL_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS = 10_000;

    // Various types of sync, passed to Handler
    private static final int SYNC_UPDATE = 1;
    private static final int SYNC_WAKELOCK_CHANGE = 2;
    private static final int SYNC_BATTERY_LEVEL_CHANGE = 3;
    private static final int SYNC_PROCESS_STATE_CHANGE = 4;
    private static final int SYNC_USER_REMOVAL = 5;

    private final Handler mHandler;
    private final Clock mClock;

    @GuardedBy("mStats")
    private final BatteryStatsImpl mStats;

    @GuardedBy("this")
    @ExternalUpdateFlag
    private int mUpdateFlags = 0;

    @GuardedBy("this")
    private String mCurrentReason = null;

    @GuardedBy("this")
    private boolean mOnBattery;

    @GuardedBy("this")
    private boolean mOnBatteryScreenOff;

    @GuardedBy("this")
    private int mScreenState;

    @GuardedBy("this")
    private int[] mPerDisplayScreenStates;

    @GuardedBy("this")
    private boolean mUseLatestStates = true;

    // If both mStats and mWorkerLock need to be synchronized, mWorkerLock must be acquired first.
    private final Object mWorkerLock = new Object();

    @GuardedBy("mWorkerLock")
    private WifiManager mWifiManager = null;

    @GuardedBy("mWorkerLock")
    private TelephonyManager mTelephony = null;

    // WiFi keeps an accumulated total of stats. Keep the last WiFi stats so we can compute a delta.
    // (This is unlike Bluetooth, where BatteryStatsImpl is left responsible for taking the delta.)
    @GuardedBy("mWorkerLock")
    private WifiActivityEnergyInfo mLastWifiInfo = null;

    /**
     * Timestamp at which all external stats were last collected in
     * {@link Clock#elapsedRealtime()} time base.
     */
    @GuardedBy("this")
    private long mLastCollectionTimeStamp;

    final Injector mInjector;

    @VisibleForTesting
    public static class Injector {
        private final Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        public <T> T getSystemService(Class<T> serviceClass) {
            return mContext.getSystemService(serviceClass);
        }

        public <T> T getLocalService(Class<T> serviceClass) {
            return LocalServices.getService(serviceClass);
        }
    }

    public BatteryExternalStatsWorker(Context context, BatteryStatsImpl stats, Handler handler,
            Clock clock) {
        this(new Injector(context), stats, handler, clock);
    }

    @VisibleForTesting
    BatteryExternalStatsWorker(Injector injector, BatteryStatsImpl stats, Handler handler,
            Clock clock) {
        mInjector = injector;
        mStats = stats;
        mHandler = handler;
        mClock = clock;
    }

    public void systemServicesReady() {
        synchronized (mWorkerLock) {
            mWifiManager = mInjector.getSystemService(WifiManager.class);
            mTelephony = mInjector.getSystemService(TelephonyManager.class);
            synchronized (mStats) {
                mPerDisplayScreenStates = new int[mStats.getDisplayCount()];
            }
        }
    }

    @Override
    public synchronized void scheduleSync(String reason, @ExternalUpdateFlag int flags) {
        scheduleSyncLocked(reason, flags);
    }

    @Override
    public synchronized void scheduleCpuSyncDueToRemovedUid(int uid) {
        scheduleSyncLocked("remove-uid", UPDATE_CPU);
    }

    @Override
    public void scheduleSyncDueToScreenStateChange(@ExternalUpdateFlag int flags, boolean onBattery,
            boolean onBatteryScreenOff, int screenState, int[] perDisplayScreenStates) {
        synchronized (BatteryExternalStatsWorker.this) {
            if (!mHandler.hasMessages(SYNC_UPDATE) || (mUpdateFlags & UPDATE_CPU) == 0) {
                mOnBattery = onBattery;
                mOnBatteryScreenOff = onBatteryScreenOff;
                mUseLatestStates = false;
            }
            // always update screen state
            mScreenState = screenState;
            mPerDisplayScreenStates = perDisplayScreenStates;
            scheduleSyncLocked("screen-state", flags);
        }
    }

    @Override
    public void scheduleCpuSyncDueToWakelockChange(long delayMillis) {
        synchronized (BatteryExternalStatsWorker.this) {
            scheduleDelayedSyncLocked(SYNC_WAKELOCK_CHANGE,
                    () -> {
                        scheduleSync("wakelock-change", UPDATE_CPU);
                        scheduleRunnable(() -> mStats.postBatteryNeedsCpuUpdateMsg());
                    },
                    delayMillis);
        }
    }

    @Override
    public void cancelCpuSyncDueToWakelockChange() {
        mHandler.removeMessages(SYNC_WAKELOCK_CHANGE);
    }

    @GuardedBy("this")
    private void cancelSyncDueToBatteryLevelChangeLocked() {
        mHandler.removeMessages(SYNC_BATTERY_LEVEL_CHANGE);
    }

    @Override
    public void scheduleSyncDueToProcessStateChange(int flags, long delayMillis) {
        synchronized (BatteryExternalStatsWorker.this) {
            scheduleDelayedSyncLocked(SYNC_PROCESS_STATE_CHANGE,
                    () -> scheduleSync("procstate-change", flags),
                    delayMillis);
        }
    }

    public void cancelSyncDueToProcessStateChange() {
        mHandler.removeMessages(SYNC_PROCESS_STATE_CHANGE);
    }

    @Override
    public void scheduleCleanupDueToRemovedUser(int userId) {
        // Initial quick clean-up after a user removal
        mHandler.postDelayed(() -> {
            synchronized (mStats) {
                mStats.clearRemovedUserUidsLocked(userId);
            }
        }, SYNC_USER_REMOVAL, UID_QUICK_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS);

        // Final clean-up after a user removal, to take care of UIDs that were running
        // longer than expected
        mHandler.postDelayed(() -> {
            synchronized (mStats) {
                mStats.clearRemovedUserUidsLocked(userId);
            }
        }, SYNC_USER_REMOVAL, UID_FINAL_REMOVAL_AFTER_USER_REMOVAL_DELAY_MILLIS);
    }

    /**
     * Schedule a sync {@param syncRunnable} with a delay. If there's already a scheduled sync, a
     * new sync won't be scheduled unless it is being scheduled to run immediately (delayMillis=0).
     *
     * @param lastScheduledSync the task which was earlier scheduled to run
     * @param syncRunnable the task that needs to be scheduled to run
     * @param delayMillis time after which {@param syncRunnable} needs to be scheduled
     * @return scheduled {@link Future} which can be used to check if task is completed or to
     *         cancel it if needed
     */
    @GuardedBy("this")
    private void scheduleDelayedSyncLocked(int what, Runnable syncRunnable,
            long delayMillis) {
        if (mHandler.hasMessages(what)) {
            // If there's already a scheduled task, leave it as is if we're trying to
            // re-schedule it again with a delay, otherwise cancel and re-schedule it.
            if (delayMillis == 0) {
                mHandler.removeMessages(what);
            } else {
                return;
            }
        }

        mHandler.postDelayed(syncRunnable, what, delayMillis);
    }

    /**
     * Schedule and async writing of battery stats to disk
     */
    public synchronized void scheduleWrite() {
        scheduleSyncLocked("write", UPDATE_ALL);
        mHandler.post(mWriteTask);
    }

    /**
     * Schedules a task to run on the BatteryExternalStatsWorker thread. If scheduling more work
     * within the task, never wait on the resulting Future. This will result in a deadlock.
     */
    public synchronized void scheduleRunnable(Runnable runnable) {
        mHandler.post(runnable);
    }

    public void shutdown() {
        mHandler.removeMessages(SYNC_UPDATE);
        mHandler.removeMessages(SYNC_WAKELOCK_CHANGE);
        mHandler.removeMessages(SYNC_BATTERY_LEVEL_CHANGE);
        mHandler.removeMessages(SYNC_PROCESS_STATE_CHANGE);
        mHandler.removeMessages(SYNC_USER_REMOVAL);
    }

    @GuardedBy("this")
    private void scheduleSyncLocked(String reason, @ExternalUpdateFlag int flags) {
        if (!mHandler.hasMessages(SYNC_UPDATE)) {
            mUpdateFlags = flags;
            mCurrentReason = reason;
            synchronized (mClock) {
                long elapsedRealtimeMs = mClock.elapsedRealtime();
                long uptimeMs = mClock.uptimeMillis();
                mHandler.postDelayed(() -> sync(elapsedRealtimeMs, uptimeMs), SYNC_UPDATE, 0);
            }
        }
        mUpdateFlags |= flags;
    }

    public long getLastCollectionTimeStamp() {
        synchronized (this) {
            return mLastCollectionTimeStamp;
        }
    }

    private void sync(long elapsedRealtimeMs, long uptimeMs) {
        // Capture a snapshot of the state we are meant to process.
        final int updateFlags;
        final String reason;
        final boolean onBattery;
        final boolean onBatteryScreenOff;
        final int screenState;
        final int[] displayScreenStates;
        final boolean useLatestStates;
        synchronized (BatteryExternalStatsWorker.this) {
            updateFlags = mUpdateFlags;
            reason = mCurrentReason;
            onBattery = mOnBattery;
            onBatteryScreenOff = mOnBatteryScreenOff;
            screenState = mScreenState;
            displayScreenStates = mPerDisplayScreenStates;
            useLatestStates = mUseLatestStates;
            mUpdateFlags = 0;
            mCurrentReason = null;
            mUseLatestStates = true;
            if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                cancelSyncDueToBatteryLevelChangeLocked();
            }
            if ((updateFlags & UPDATE_CPU) != 0) {
                cancelCpuSyncDueToWakelockChange();
            }
            if ((updateFlags & UPDATE_ON_PROC_STATE_CHANGE) == UPDATE_ON_PROC_STATE_CHANGE) {
                cancelSyncDueToProcessStateChange();
            }
        }

        try {
            synchronized (mWorkerLock) {
                if (DEBUG) {
                    Slog.d(TAG, "begin updateExternalStatsSync reason=" + reason);
                }
                try {
                    updateExternalStatsLocked(reason, updateFlags, onBattery,
                            onBatteryScreenOff, screenState, displayScreenStates,
                            useLatestStates, elapsedRealtimeMs, uptimeMs);
                } finally {
                    if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                        synchronized (mStats) {
                            // This helps mStats deal with ignoring data from prior to resets.
                            mStats.informThatAllExternalStatsAreFlushed();
                        }
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "end updateExternalStatsSync");
                    }
                }
            }

            if ((updateFlags & UPDATE_CPU) != 0) {
                mStats.updateCpuTimesForAllUids();
            }

            // Clean up any UIDs if necessary.
            synchronized (mStats) {
                mStats.clearPendingRemovedUidsLocked();
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Error updating external stats: ", e);
        }

        if ((updateFlags & RESET) != 0) {
            synchronized (BatteryExternalStatsWorker.this) {
                mLastCollectionTimeStamp = 0;
            }
        } else if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
            synchronized (BatteryExternalStatsWorker.this) {
                mLastCollectionTimeStamp = elapsedRealtimeMs;
            }
        }
    };

    private final Runnable mWriteTask = new Runnable() {
        @Override
        public void run() {
            synchronized (mStats) {
                mStats.writeAsyncLocked();
            }
        }
    };

    @GuardedBy("mWorkerLock")
    private void updateExternalStatsLocked(final String reason, int updateFlags, boolean onBattery,
            boolean onBatteryScreenOff, int screenState, int[] displayScreenStates,
            boolean useLatestStates, long elapsedRealtime, long uptime) {
        // We will request data from external processes asynchronously, and wait on a timeout.
        SynchronousResultReceiver wifiReceiver = null;
        SynchronousResultReceiver bluetoothReceiver = null;
        CompletableFuture<ModemActivityInfo> modemFuture = CompletableFuture.completedFuture(null);
        boolean railUpdated = false;

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_WIFI) != 0) {
            // We were asked to fetch WiFi data.
            // Only fetch WiFi power data if it is supported.
            if (mWifiManager != null && mWifiManager.isEnhancedPowerReportingSupported()) {
                SynchronousResultReceiver tempWifiReceiver = new SynchronousResultReceiver("wifi");
                mWifiManager.getWifiActivityEnergyInfoAsync(
                        new Executor() {
                            @Override
                            public void execute(Runnable runnable) {
                                // run the listener on the binder thread, if it was run on the main
                                // thread it would deadlock since we would be waiting on ourselves
                                runnable.run();
                            }
                        },
                        info -> {
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                            tempWifiReceiver.send(0, bundle);
                        }
                );
                wifiReceiver = tempWifiReceiver;
            }
            synchronized (mStats) {
                mStats.updateRailStatsLocked();
            }
            railUpdated = true;
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_BT) != 0) {
            @SuppressWarnings("GuardedBy")
            PowerStatsCollector collector = mStats.getPowerStatsCollector(
                    BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
            if (collector.isEnabled()) {
                collector.schedule();
            } else {
                // We were asked to fetch Bluetooth data.
                final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    SynchronousResultReceiver resultReceiver =
                            new SynchronousResultReceiver("bluetooth");
                    adapter.requestControllerActivityEnergyInfo(
                            Runnable::run,
                            new BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback() {
                                @Override
                                public void onBluetoothActivityEnergyInfoAvailable(
                                        BluetoothActivityEnergyInfo info) {
                                    Bundle bundle = new Bundle();
                                    bundle.putParcelable(
                                            BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                                    resultReceiver.send(0, bundle);
                                }

                                @Override
                                public void onBluetoothActivityEnergyInfoError(int errorCode) {
                                    //Slog.w(TAG, "error reading Bluetooth stats: " + errorCode);
                                    Bundle bundle = new Bundle();
                                    bundle.putParcelable(
                                            BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, null);
                                    resultReceiver.send(0, bundle);
                                }
                            }
                    );
                    bluetoothReceiver = resultReceiver;
                }
            }
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_RADIO) != 0) {
            @SuppressWarnings("GuardedBy")
            PowerStatsCollector collector = mStats.getPowerStatsCollector(
                    BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);
            if (collector.isEnabled()) {
                collector.schedule();
            } else {
                // We were asked to fetch Telephony data.
                if (mTelephony != null) {
                    CompletableFuture<ModemActivityInfo> temp = new CompletableFuture<>();
                    mTelephony.requestModemActivityInfo(Runnable::run,
                            new OutcomeReceiver<ModemActivityInfo,
                                    TelephonyManager.ModemActivityInfoException>() {
                                @Override
                                public void onResult(ModemActivityInfo result) {
                                    temp.complete(result);
                                }

                                @Override
                                public void onError(TelephonyManager.ModemActivityInfoException e) {
                                    //Slog.w(TAG, "error reading modem stats:" + e);
                                    temp.complete(null);
                                }
                            });
                    modemFuture = temp;
                }
            }
            if (!railUpdated) {
                synchronized (mStats) {
                    mStats.updateRailStatsLocked();
                }
            }
        }

        if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_RPM) != 0) {
            // Collect the latest low power stats without holding the mStats lock.
            mStats.fillLowPowerStats();
        }

        final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
        final BluetoothActivityEnergyInfo bluetoothInfo = awaitControllerInfo(bluetoothReceiver);
        ModemActivityInfo modemInfo = null;
        try {
            modemInfo = modemFuture.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            Slog.w(TAG, "timeout or interrupt reading modem stats: " + e);
        } catch (ExecutionException e) {
            Slog.w(TAG, "exception reading modem stats: " + e.getCause());
        }

        final long elapsedRealtimeUs = elapsedRealtime * 1000;
        final long uptimeUs = uptime * 1000;

        // Now that we have finally received all the data, we can tell mStats about it.
        synchronized (mStats) {
            mStats.recordHistoryEventLocked(
                    elapsedRealtime,
                    uptime,
                    BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS,
                    reason, 0);

            if ((updateFlags & UPDATE_CPU) != 0) {
                if (useLatestStates) {
                    onBattery = mStats.isOnBatteryLocked();
                    onBatteryScreenOff = mStats.isOnBatteryScreenOffLocked();
                }

                mStats.updateCpuTimeLocked(onBattery, onBatteryScreenOff);
            }

            if ((updateFlags & UPDATE_ALL) == UPDATE_ALL) {
                mStats.updateKernelWakelocksLocked(elapsedRealtimeUs);
                mStats.updateKernelMemoryBandwidthLocked(elapsedRealtimeUs);
            }

            if ((updateFlags & UPDATE_RPM) != 0) {
                mStats.updateRpmStatsLocked(elapsedRealtimeUs);
            }

            if (bluetoothInfo != null) {
                if (bluetoothInfo.isValid()) {
                    mStats.updateBluetoothStateLocked(bluetoothInfo, elapsedRealtime, uptime);
                } else {
                    Slog.w(TAG, "bluetooth info is invalid: " + bluetoothInfo);
                }
            }
        }

        // WiFi and Modem state are updated without the mStats lock held, because they
        // do some network stats retrieval before internally grabbing the mStats lock.
        if (wifiInfo != null) {
            if (wifiInfo.isValid()) {
                final NetworkStatsManager networkStatsManager = mInjector.getSystemService(
                        NetworkStatsManager.class);
                mStats.updateWifiState(extractDeltaLocked(wifiInfo), elapsedRealtime, uptime,
                        networkStatsManager);
            } else {
                Slog.w(TAG, "wifi info is invalid: " + wifiInfo);
            }
        }

        if (modemInfo != null) {
            final NetworkStatsManager networkStatsManager = mInjector.getSystemService(
                    NetworkStatsManager.class);
            mStats.noteModemControllerActivity(modemInfo, elapsedRealtime, uptime,
                    networkStatsManager);
        }
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(
                        BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    /**
     * Return a delta WifiActivityEnergyInfo from the last WifiActivityEnergyInfo passed to the
     * method.
     */
    @VisibleForTesting
    @GuardedBy("mWorkerLock")
    public WifiActivityEnergyInfo extractDeltaLocked(WifiActivityEnergyInfo latest) {
        if (mLastWifiInfo == null) {
            // This is the first time WifiActivityEnergyInfo has been collected since system boot.
            // Use this first WifiActivityEnergyInfo as the starting point for all accumulations.
            mLastWifiInfo = latest;
        }
        final long timePeriodMs = latest.getTimeSinceBootMillis()
                - mLastWifiInfo.getTimeSinceBootMillis();
        final long lastScanMs = mLastWifiInfo.getControllerScanDurationMillis();
        final long lastIdleMs = mLastWifiInfo.getControllerIdleDurationMillis();
        final long lastTxMs = mLastWifiInfo.getControllerTxDurationMillis();
        final long lastRxMs = mLastWifiInfo.getControllerRxDurationMillis();
        final long lastEnergy = mLastWifiInfo.getControllerEnergyUsedMicroJoules();

        final long deltaTimeSinceBootMillis = latest.getTimeSinceBootMillis();
        final int deltaStackState = latest.getStackState();
        final long deltaControllerTxDurationMillis;
        final long deltaControllerRxDurationMillis;
        final long deltaControllerScanDurationMillis;
        final long deltaControllerIdleDurationMillis;
        final long deltaControllerEnergyUsedMicroJoules;

        final long txTimeMs = latest.getControllerTxDurationMillis() - lastTxMs;
        final long rxTimeMs = latest.getControllerRxDurationMillis() - lastRxMs;
        final long idleTimeMs = latest.getControllerIdleDurationMillis() - lastIdleMs;
        final long scanTimeMs = latest.getControllerScanDurationMillis() - lastScanMs;

        final boolean wasReset;
        if (txTimeMs < 0 || rxTimeMs < 0 || scanTimeMs < 0 || idleTimeMs < 0) {
            // The stats were reset by the WiFi system (which is why our delta is negative).
            // Returns the unaltered stats. The total on time should not exceed the time
            // duration between reports.
            final long totalOnTimeMs = latest.getControllerTxDurationMillis()
                    + latest.getControllerRxDurationMillis()
                    + latest.getControllerIdleDurationMillis();
            if (totalOnTimeMs <= timePeriodMs + MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS) {
                deltaControllerEnergyUsedMicroJoules = latest.getControllerEnergyUsedMicroJoules();
                deltaControllerRxDurationMillis = latest.getControllerRxDurationMillis();
                deltaControllerTxDurationMillis = latest.getControllerTxDurationMillis();
                deltaControllerIdleDurationMillis = latest.getControllerIdleDurationMillis();
                deltaControllerScanDurationMillis = latest.getControllerScanDurationMillis();
            } else {
                deltaControllerEnergyUsedMicroJoules = 0;
                deltaControllerRxDurationMillis = 0;
                deltaControllerTxDurationMillis = 0;
                deltaControllerIdleDurationMillis = 0;
                deltaControllerScanDurationMillis = 0;
            }
            wasReset = true;
        } else {
            // These times seem to be the most reliable.
            deltaControllerTxDurationMillis = txTimeMs;
            deltaControllerRxDurationMillis = rxTimeMs;
            deltaControllerScanDurationMillis = scanTimeMs;
            deltaControllerIdleDurationMillis = idleTimeMs;
            deltaControllerEnergyUsedMicroJoules =
                    Math.max(0, latest.getControllerEnergyUsedMicroJoules() - lastEnergy);
            wasReset = false;
        }

        mLastWifiInfo = latest;
        WifiActivityEnergyInfo delta = new WifiActivityEnergyInfo(
                deltaTimeSinceBootMillis,
                deltaStackState,
                deltaControllerTxDurationMillis,
                deltaControllerRxDurationMillis,
                deltaControllerScanDurationMillis,
                deltaControllerIdleDurationMillis,
                deltaControllerEnergyUsedMicroJoules);
        if (wasReset) {
            Slog.v(TAG, "WiFi energy data was reset, new WiFi energy data is " + delta);
        }
        return delta;
    }
}
