/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.lunaris;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LunarisIdleManager {

    private static final String TAG = "LunarisIdleManager";

    private static final String ACTION_SCAN = "com.android.systemui.lunaris.ACTION_IDLE_SCAN";
    private static final String ACTION_HALT = "com.android.systemui.lunaris.ACTION_IDLE_HALT";

    private static final int PI_SCAN_REQUEST = 0x4C494D01;
    private static final int PI_HALT_REQUEST = 0x4C494D02;

    public static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);

    private static final long ALARM_BUFFER_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long MIN_DELAY_MS = 100L;

    private static final long SCAN_INTERVAL_CHARGING_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long SCAN_INTERVAL_HIGH_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long SCAN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long SCAN_INTERVAL_LOW_MS = TimeUnit.MINUTES.toMillis(30);

    private static final long INITIAL_SCAN_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long SCAN_DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(10);

    private static final int BATTERY_LOW_THRESHOLD = 15;
    private static final int BATTERY_HIGH_THRESHOLD = 35;

    public static final int STANDBY_BUCKET_ACTIVE = 10;
    public static final int STANDBY_BUCKET_WORKING_SET = 20;
    public static final int STANDBY_BUCKET_FREQUENT = 30;
    public static final int STANDBY_BUCKET_RARE = 40;
    public static final int STANDBY_BUCKET_RESTRICTED = 45;

    private volatile long mLastScanStartMs = 0L;

    public enum IdleAction {
        STANDBY_BUCKET_RARE,
        STANDBY_BUCKET_RESTRICTED,
        KILL_BACKGROUND,
        FULL_KILL;

        public String toDisplayName() {
            switch (this) {
                case STANDBY_BUCKET_RARE:
                    return "Rare Bucket";
                case STANDBY_BUCKET_RESTRICTED:
                    return "Restricted";
                case KILL_BACKGROUND:
                    return "Kill BG";
                case FULL_KILL:
                    return "Full Kill";
                default:
                    return name();
            }
        }
    }

    public static final class AppConfig {
        public final String packageName;
        public final IdleAction action;

        public AppConfig(@NonNull String packageName, @NonNull IdleAction action) {
            this.packageName = packageName;
            this.action = action;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", action.name());
            return o;
        }

        public static AppConfig fromJson(@NonNull JSONObject o) throws Exception {
            String pkg = o.getString("package");
            IdleAction act = parseAction(o.optString("action",
                    IdleAction.STANDBY_BUCKET_RARE.name()));
            return new AppConfig(pkg, act);
        }

        private static IdleAction parseAction(String s) {
            try   { return IdleAction.valueOf(s); }
            catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
        }
    }

    public static final class AppEnforcementRecord {
        public final String packageName;
        public final IdleAction actionTaken;
        public final long timestampMs;
        public final int killCount;

        public AppEnforcementRecord(String pkg, IdleAction action, long ts, int count) {
            this.packageName = pkg;
            this.actionTaken = action;
            this.timestampMs = ts;
            this.killCount = count;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", actionTaken.name());
            o.put("ts", timestampMs);
            o.put("kill_count", killCount);
            return o;
        }

        public static AppEnforcementRecord fromJson(JSONObject o) throws Exception {
            return new AppEnforcementRecord(
                    o.getString("package"),
                    IdleAction.valueOf(o.optString("action",
                            IdleAction.STANDBY_BUCKET_RARE.name())),
                    o.optLong("ts", 0L),
                    o.optInt("kill_count", 0)
            );
        }
    }

    private static final class AppIdleState {
        int currentBucket = STANDBY_BUCKET_ACTIVE;
        boolean isRestricted = false;
        long restrictedAtMs = 0L;
    }

    private static volatile LunarisIdleManager sInstance;
    private static final Object sLock = new Object();

    private final Context mContext;
    private final Handler mMainHandler;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AudioManager mAudioManager;
    private final UsageStatsManager mUsageStatsManager;
    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;
    private final Executor mIoExecutor;

    private volatile boolean mEnabled = true;
    private volatile boolean mDestroyed = false;
    private volatile Map<String, AppConfig> mAppConfigCache = Collections.emptyMap();

    private final Map<String, AppIdleState> mAppIdleStates = new ConcurrentHashMap<>();
    private final Map<String, Long> mLastKillTime = new ConcurrentHashMap<>();

    private volatile int mBatteryLevel = 100;
    private volatile boolean mIsCharging = false;

    private volatile boolean mHasScanCompleted = false;
    private int mHaltRetries = 0;

    private BroadcastReceiver mBatteryReceiver;
    private BroadcastReceiver mAlarmReceiver;
    private BroadcastReceiver mDozeReceiver;
    private ContentObserver mSettingsObserver;
    private Runnable mHaltRunnable;
    private volatile boolean mIsRunning = false;

    private PowerManager.WakeLock mScanWakeLock;

    private LunarisIdleManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUsageStatsManager = (UsageStatsManager) mContext.getSystemService(Context.USAGE_STATS_SERVICE);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        mIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LunarisIdleManager-IO");
            t.setDaemon(true);
            return t;
        });

        loadConfigFromSettings();
        registerSettingsObserver();
        registerBatteryReceiver();
        registerAlarmReceiver();
        registerDozeReceiver();
        initScanWakeLock();
        initRunnables();
    }

    public static void initManager(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new LunarisIdleManager(context);
                }
            }
        }
    }

    @Nullable
    public static LunarisIdleManager getInstance() { return sInstance; }

    public void executeManager() {
        if (mDestroyed) {
            Log.w(TAG, "executeManager called on destroyed instance");
            return;
        }
        if (!mEnabled) {
            Log.d(TAG, "LunarisIdleManager disabled — skipping");
            return;
        }
        if (mIsRunning) {
            Log.d(TAG, "Already running — ignoring duplicate start");
            return;
        }
        mIsRunning = true;
        mHasScanCompleted = false;
        mHaltRetries = 0;
        cancelCallbacks();

        Log.d(TAG, "executeManager: appCount=" + mAppConfigCache.size()
                + " enabled=" + mEnabled);

        long timeUntilAlarm = getMillisUntilNextAlarm();
        long firstDelay;

        if (timeUntilAlarm > 0 && timeUntilAlarm < IDLE_TIMEOUT_MS) {
            firstDelay = MIN_DELAY_MS;
            Log.d(TAG, "Alarm soon — scheduling immediate scan");
        } else {
            firstDelay = INITIAL_SCAN_DELAY_MS;
            Log.d(TAG, "First scan in "
                    + TimeUnit.MILLISECONDS.toSeconds(firstDelay) + " sec");
        }

        scheduleScanAlarm(firstDelay);

        if (timeUntilAlarm > ALARM_BUFFER_MS) {
            scheduleHaltAlarm(timeUntilAlarm - ALARM_BUFFER_MS);
        }
    }

    public void haltManager() {
        if (mDestroyed) return;
        Log.d(TAG, "Halting LunarisIdleManager");
        cancelCallbacks();
        mIsRunning = false;
    }

    public void cleanup() {
        mDestroyed = true;
        haltManager();
        unregisterSettingsObserver();
        unregisterBatteryReceiver();
        unregisterDozeReceiver();
        unregisterAlarmReceiver();
        releaseWakeLockIfHeld();
        synchronized (sLock) { sInstance = null; }
        Log.d(TAG, "LunarisIdleManager cleaned up");
    }

    public boolean isEnabled() {
        if (mDestroyed) {
            return false;
        }
        return mEnabled;
    }

    public boolean isRunning() {
        if (mDestroyed) {
            return false;
        }
        return mIsRunning;
    }

    public Map<String, AppConfig> getAppConfigs() {
        if (mDestroyed) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(mAppConfigCache);
    }

    public void setEnabled(boolean enabled) {
        if (mDestroyed) return;
        mEnabled = enabled;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER, enabled ? 1 : 0);
        if (!enabled) {
            haltManager();
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 1);
            restoreAllBuckets();
        }
    }

    public void saveAppConfigs(@NonNull Map<String, AppConfig> configs) {
        if (mDestroyed) return;
        mAppConfigCache = new HashMap<>(configs);
        persistAppConfigs(configs);
    }

    public void addOrUpdateApp(@NonNull AppConfig config) {
        if (mDestroyed) return;
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.put(config.packageName, config);
        saveAppConfigs(updated);
    }

    public void removeApp(@NonNull String packageName) {
        if (mDestroyed) return;
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.remove(packageName);
        saveAppConfigs(updated);
        restoreBucket(packageName);
        mAppIdleStates.remove(packageName);
        mLastKillTime.remove(packageName);
    }

    @NonNull
    public List<AppEnforcementRecord> getEnforcementRecords() {
        if (mDestroyed) return Collections.emptyList();
        List<AppEnforcementRecord> records = new ArrayList<>();
        String json = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.IDLE_MANAGER_KILL_STATS);
        if (json == null || json.isEmpty()) return records;
        try {
            JSONObject root = new JSONObject(json);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) continue;
                records.add(new AppEnforcementRecord(
                        pkg,
                        parseAction(entry.optString("last_action",
                                IdleAction.STANDBY_BUCKET_RARE.name())),
                        entry.optLong("last_kill", 0L),
                        entry.optInt("count", 0)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse enforcement records", e);
        }
        records.sort((a, b) -> Long.compare(b.timestampMs, a.timestampMs));
        return records;
    }

    private static IdleAction parseAction(String s) {
        try { return IdleAction.valueOf(s); }
        catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
    }

    private void initScanWakeLock() {
        if (mPowerManager != null) {
            mScanWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + ":ScanLock");
            mScanWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireWakeLock() {
        if (mScanWakeLock != null && !mScanWakeLock.isHeld()) {
            mScanWakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }
    }

    private void releaseWakeLockIfHeld() {
        if (mScanWakeLock != null && mScanWakeLock.isHeld()) {
            mScanWakeLock.release();
        }
    }

    private void scheduleScanAlarm(long delayMs) {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + Math.max(delayMs, MIN_DELAY_MS);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        Log.d(TAG, "Scan alarm set in " + TimeUnit.MILLISECONDS.toMinutes(delayMs) + " min");
    }

    private void cancelScanAlarm() {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) mAlarmManager.cancel(pi);
    }

    private void scheduleHaltAlarm(long delayMs) {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_HALT_REQUEST,
                new Intent(ACTION_HALT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + Math.max(delayMs, MIN_DELAY_MS);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    private void cancelHaltAlarm() {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_HALT_REQUEST,
                new Intent(ACTION_HALT),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) mAlarmManager.cancel(pi);
    }

    private void registerAlarmReceiver() {
        mAlarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_SCAN.equals(action)) {
                    onScanAlarmFired();
                } else if (ACTION_HALT.equals(action)) {
                    onHaltAlarmFired();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCAN);
        filter.addAction(ACTION_HALT);
        mContext.registerReceiver(mAlarmReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterAlarmReceiver() {
        if (mAlarmReceiver != null) {
            try { mContext.unregisterReceiver(mAlarmReceiver); }
            catch (IllegalArgumentException ignored) {}
            mAlarmReceiver = null;
        }
    }

    private void onScanAlarmFired() {
        if (!mIsRunning || mDestroyed) return;
        long now = System.currentTimeMillis();
        if ((now - mLastScanStartMs) < SCAN_DEBOUNCE_MS) {
            Log.d(TAG, "onScanAlarmFired: debounced (last scan " 
                    + (now - mLastScanStartMs) + "ms ago)");
            return;
        }
        mLastScanStartMs = now;
        acquireWakeLock();
        mIoExecutor.execute(() -> {
            try {
                performIdleScan();
                mHasScanCompleted = true;
                if (mIsRunning) {
                    long interval = getDynamicScanIntervalMs();
                    Log.d(TAG, "Next scan in "
                            + TimeUnit.MILLISECONDS.toMinutes(interval)
                            + " min [battery=" + mBatteryLevel
                            + "%, charging=" + mIsCharging + "]");
                    scheduleScanAlarm(interval);
                }
            } finally {
                releaseWakeLockIfHeld();
            }
        });
    }

    private void onHaltAlarmFired() {
        if (!mIsRunning) return;
        if (!mHasScanCompleted && mHaltRetries < 3) {
            mHaltRetries++;
            Log.d(TAG, "Halt deferred (attempt " + mHaltRetries + ") — scan not yet run");
            scheduleHaltAlarm(ALARM_BUFFER_MS * 2);
            return;
        }
        haltManager();
    }

    private void initRunnables() {
        mHaltRunnable = () -> { /* no-op: halt is driven by scheduleHaltAlarm */ };
    }

    private void performIdleScan() {
        if (mActivityManager == null || mUsageStatsManager == null) return;

        boolean screenOff = mPowerManager == null || !mPowerManager.isInteractive();

        if (!screenOff) {
            Log.d(TAG, "Screen is on — skipping scan");
            return;
        }

        Log.d(TAG, "performIdleScan: trigger=[screenOff]"
                + " evaluating " + mAppConfigCache.size() + " configured apps");

        List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = mActivityManager.getRunningAppProcesses();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching processes", e);
            return;
        }
        if (processes == null) processes = Collections.emptyList();

        Set<String> foregroundPkgs = getForegroundPackages(processes);
        boolean audioActive = isAudioActive();
        long now = System.currentTimeMillis();
        int restricted = 0;
        int killed = 0;

        for (Map.Entry<String, AppConfig> entry : mAppConfigCache.entrySet()) {
            String pkg = entry.getKey();
            AppConfig cfg = entry.getValue();

            if (LunarisIdleConstants.PROTECTED_PACKAGES.contains(pkg)) {
                Log.w(TAG, "Skipping protected package: " + pkg);
                continue;
            }

            if (foregroundPkgs.contains(pkg)) {
                Log.v(TAG, "Skipping foreground: " + pkg);
                continue;
            }

            if (audioActive && isActiveMediaApp(pkg, processes)) {
                Log.v(TAG, "Skipping active media: " + pkg);
                continue;
            }

            if (!isAppIdleLongEnough(pkg, now)) {
                Log.v(TAG, "Not idle long enough: " + pkg);
                continue;
            }

            boolean didAct = false;

            switch (cfg.action) {
                case STANDBY_BUCKET_RARE:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RARE);
                    if (didAct) restricted++;
                    break;
                case STANDBY_BUCKET_RESTRICTED:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED);
                    if (didAct) restricted++;
                    break;
                case KILL_BACKGROUND:
                    didAct = killBackground(pkg, now);
                    if (didAct) killed++;
                    break;
                case FULL_KILL:
                    boolean r = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED);
                    boolean k = forceStop(pkg, now);
                    didAct = r || k;
                    if (r) restricted++;
                    if (k) killed++;
                    break;
            }

            if (didAct) {
                updateKillStats(pkg, now, cfg.action);
            }
        }

        Log.i(TAG, "Scan done — restricted=" + restricted
                + " killed=" + killed
                + " total=" + mAppConfigCache.size());
    }

    private boolean applyStandbyBucket(String pkg, int targetBucket) {
        if (mUsageStatsManager == null) return false;

        AppIdleState state = mAppIdleStates.computeIfAbsent(pkg, k -> new AppIdleState());
        if (state.isRestricted && state.currentBucket <= targetBucket) {
            return false;
        }

        try {
            mUsageStatsManager.setAppStandbyBucket(pkg, targetBucket);
            state.isRestricted = true;
            state.currentBucket = targetBucket;
            state.restrictedAtMs = System.currentTimeMillis();
            Log.d(TAG, "Bucket applied [" + bucketName(targetBucket) + "]: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to set standby bucket for " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean killBackground(String pkg, long now) {
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < IDLE_TIMEOUT_MS) {
            return false;
        }
        try {
            mActivityManager.killBackgroundProcesses(pkg);
            mLastKillTime.put(pkg, now);
            Log.d(TAG, "Killed background: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to kill " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean forceStop(String pkg, long now) {
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < IDLE_TIMEOUT_MS) {
            return false;
        }

        boolean stopped = false;

        try {
            java.lang.reflect.Method method = mActivityManager.getClass()
                    .getMethod("forceStopPackage", String.class);
            method.invoke(mActivityManager, pkg);
            stopped = true;
            Log.d(TAG, "Force stopped via reflection: " + pkg);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "forceStopPackage unavailable on this build — falling back: " + pkg);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.w(TAG, "forceStopPackage threw for " + pkg + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        } catch (IllegalAccessException e) {
            Log.w(TAG, "forceStopPackage access denied for " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "forceStop unexpected error for " + pkg + ": " + e.getMessage());
        }

        try {
            mActivityManager.killBackgroundProcesses(pkg);
            stopped = true;
            Log.d(TAG, "killBackgroundProcesses called: " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "killBackgroundProcesses failed for " + pkg + ": " + e.getMessage());
        }

        if (stopped) {
            mLastKillTime.put(pkg, now);
        }

        return stopped;
    }

    private void restoreBucket(String pkg) {
        AppIdleState state = mAppIdleStates.get(pkg);
        if (state == null || !state.isRestricted) return;
        try {
            mUsageStatsManager.setAppStandbyBucket(pkg, STANDBY_BUCKET_WORKING_SET);
            state.isRestricted  = false;
            state.currentBucket = STANDBY_BUCKET_WORKING_SET;
            Log.d(TAG, "Bucket restored (WORKING_SET): " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore bucket for " + pkg);
        }
    }

    private void restoreAllBuckets() {
        mIoExecutor.execute(() -> {
            Log.d(TAG, "restoreAllBuckets: starting for "
                    + mAppIdleStates.size() + " apps");
            for (String pkg : new HashSet<>(mAppIdleStates.keySet())) {
                restoreBucket(pkg);
            }
            Settings.Secure.putInt(
                    mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
            Log.d(TAG, "restoreAllBuckets: complete");
        });
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case STANDBY_BUCKET_ACTIVE:
                return "ACTIVE";
            case STANDBY_BUCKET_WORKING_SET:
                return "WORKING_SET";
            case STANDBY_BUCKET_FREQUENT:
                return "FREQUENT";
            case STANDBY_BUCKET_RARE:
                return "RARE";
            case STANDBY_BUCKET_RESTRICTED:
                return "RESTRICTED";
            default:
                return "UNKNOWN(" + bucket + ")";
        }
    }

    private boolean isAppIdleLongEnough(String pkg, long now) {
        try {
            long begin = now - IDLE_TIMEOUT_MS;
            Map<String, UsageStats> stats =
                    mUsageStatsManager.queryAndAggregateUsageStats(begin, now);

            if (stats == null || stats.isEmpty()) {
                Log.w(TAG, "UsageStats returned empty — "
                        + "check PACKAGE_USAGE_STATS grant. Treating " + pkg + " as idle.");
                return true;
            }

            UsageStats appStats = stats.get(pkg);
            if (appStats == null) {
                Log.v(TAG, pkg + ": no usage in window → idle");
                return true;
            }

            long lastUsed = appStats.getLastTimeUsed();
            long idleDuration = now - lastUsed;

            Log.v(TAG, pkg + ": idle for "
                    + TimeUnit.MILLISECONDS.toMinutes(idleDuration)
                    + " min (threshold="
                    + TimeUnit.MILLISECONDS.toMinutes(IDLE_TIMEOUT_MS) + " min)");

            return idleDuration >= IDLE_TIMEOUT_MS;

        } catch (Exception e) {
            Log.w(TAG, "UsageStats query failed for " + pkg + ": " + e.getMessage());
            Long lastKill = mLastKillTime.get(pkg);
            return lastKill == null || (now - lastKill) >= IDLE_TIMEOUT_MS;
        }
    }

    private Set<String> getForegroundPackages(
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        Set<String> fg = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.importance
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                    && p.pkgList != null) {
                for (String pkg : p.pkgList) fg.add(pkg);
            }
        }
        return fg;
    }

    private boolean isAudioActive() {
        if (mAudioManager == null) return false;
        try {
            if (mAudioManager.isMusicActive()) return true;

            int mode = mAudioManager.getMode();
            if (mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE) {
                Log.d(TAG, "Audio mode active (" + mode + ") — skipping enforcement");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "isAudioActive audio check failed: " + e.getMessage());
        }

        if (mTelephonyManager != null) {
            try {
                int callState = mTelephonyManager.getCallState();
                if (callState != TelephonyManager.CALL_STATE_IDLE) {
                    Log.d(TAG, "Call state active (" + callState + ") — skipping enforcement");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "TelephonyManager.getCallState() unavailable: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean isActiveMediaApp(@NonNull String pkg,
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.pkgList == null) continue;
            for (String name : p.pkgList) {
                if (name.equals(pkg)) {
                    return p.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
                }
            }
        }
        return false;
    }

    private void loadConfigFromSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Secure.getInt(cr, Settings.Secure.IDLE_MANAGER, 1) == 1;

        int restorePending = Settings.Secure.getInt(
                cr, Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
        if (restorePending == 1) {
            Log.w(TAG, "Previous restoreAllBuckets was interrupted — re-running");
            mIoExecutor.execute(() -> {
                for (String pkg : new HashSet<>(mAppIdleStates.keySet())) {
                    restoreBucket(pkg);
                }
                Settings.Secure.putInt(
                        cr, Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
            });
        }

        String appsJson = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_APPS);
        Log.d(TAG, "loadConfigFromSettings: json=" + appsJson);
        mAppConfigCache = parseAppConfigs(appsJson);
        Log.d(TAG, "Config loaded — enabled=" + mEnabled
                + ", apps=" + mAppConfigCache.size());
    }

    private void registerSettingsObserver() {
        ContentResolver cr = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                Log.d(TAG, "Settings changed — refreshing config");
                loadConfigFromSettings();
            }
        };
        for (String key : new String[]{
                Settings.Secure.IDLE_MANAGER,
                Settings.Secure.IDLE_MANAGER_APPS}) {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mSettingsObserver);
        }
    }

    private void unregisterSettingsObserver() {
        if (mSettingsObserver != null)
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                if (level >= 0 && scale > 0)
                    mBatteryLevel = (int) ((level / (float) scale) * 100);
                mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = mContext.registerReceiver(mBatteryReceiver, filter);
        if (sticky != null) mBatteryReceiver.onReceive(mContext, sticky);
    }

    private void registerDozeReceiver() {
        mDozeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                if (mDestroyed || !mIsRunning) return;
                boolean idle = mPowerManager != null && mPowerManager.isDeviceIdleMode();
                Log.d(TAG, "Doze mode changed — idle=" + idle);
                if (idle) {
                    onScanAlarmFired();
                }
            }
        };
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(mDozeReceiver, filter);
    }

    private void unregisterDozeReceiver() {
        if (mDozeReceiver != null) {
            try {
                mContext.unregisterReceiver(mDozeReceiver);
            } catch (IllegalArgumentException ignored) {}
            mDozeReceiver = null;
        }
    }

    private void unregisterBatteryReceiver() {
        if (mBatteryReceiver != null) {
            try { mContext.unregisterReceiver(mBatteryReceiver); }
            catch (IllegalArgumentException ignored) {}
            mBatteryReceiver = null;
        }
    }

    private void persistAppConfigs(@NonNull Map<String, AppConfig> configs) {
        mIoExecutor.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (AppConfig c : configs.values()) arr.put(c.toJson());
                Settings.Secure.putString(
                        mContext.getContentResolver(),
                        Settings.Secure.IDLE_MANAGER_APPS, arr.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist configs", e);
            }
        });
    }

    @NonNull
    private Map<String, AppConfig> parseAppConfigs(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        Map<String, AppConfig> map = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AppConfig c = AppConfig.fromJson(arr.getJSONObject(i));
                map.put(c.packageName, c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse configs: " + e.getMessage());
        }
        return map;
    }

    private void updateKillStats(@NonNull String pkg, long tsMs, @NonNull IdleAction action) {
        mIoExecutor.execute(() -> {
            ContentResolver cr = mContext.getContentResolver();
            try {
                String existing = Settings.Secure.getString(
                        cr, Settings.Secure.IDLE_MANAGER_KILL_STATS);
                JSONObject root = (existing != null && !existing.isEmpty())
                        ? new JSONObject(existing) : new JSONObject();

                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();

                entry.put("count", entry.optInt("count", 0) + 1);
                entry.put("last_kill", tsMs);
                entry.put("last_action", action.name());
                root.put(pkg, entry);

                Settings.Secure.putString(
                        cr, Settings.Secure.IDLE_MANAGER_KILL_STATS, root.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to update stats for " + pkg, e);
            }
        });
    }

    private long getDynamicScanIntervalMs() {
        if (mIsCharging) 
            return SCAN_INTERVAL_CHARGING_MS;
        if (mBatteryLevel > BATTERY_HIGH_THRESHOLD) 
            return SCAN_INTERVAL_HIGH_MS;
        if (mBatteryLevel > BATTERY_LOW_THRESHOLD)  
            return SCAN_INTERVAL_MS;
        return SCAN_INTERVAL_LOW_MS;
    }

    private long getMillisUntilNextAlarm() {
        if (mAlarmManager == null) return 0;
        try {
            AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock();
            if (info != null)
                return Math.max(0, info.getTriggerTime() - System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Error reading next alarm", e);
        }
        return 0;
    }

    private void cancelCallbacks() {
        cancelScanAlarm();
        cancelHaltAlarm();
        if (mHaltRunnable != null)
            mMainHandler.removeCallbacks(mHaltRunnable);
    }
}
