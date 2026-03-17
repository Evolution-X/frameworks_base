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
import android.provider.Settings;
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

    public static final long POLICY_BALANCED_MS = TimeUnit.MINUTES.toMillis(60);
    public static final long POLICY_AGGRESSIVE_MS = TimeUnit.MINUTES.toMillis(15);
    public static final long DEFAULT_TIMEOUT_MS = POLICY_BALANCED_MS;

    private static final long ALARM_BUFFER_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long MIN_DELAY_MS = 100L;
    private static final long SCAN_INTERVAL_HIGH_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long SCAN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long SCAN_INTERVAL_CHARGING_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long SCAN_INTERVAL_LOW_MS = TimeUnit.MINUTES.toMillis(3);

    private static final int BATTERY_LOW_THRESHOLD  = 15;
    private static final int BATTERY_HIGH_THRESHOLD = 35;

    public static final int STANDBY_BUCKET_ACTIVE = 10;
    public static final int STANDBY_BUCKET_WORKING_SET = 20;
    public static final int STANDBY_BUCKET_FREQUENT = 30;
    public static final int STANDBY_BUCKET_RARE = 40;
    public static final int STANDBY_BUCKET_RESTRICTED = 45;

    public enum Policy { BALANCED, AGGRESSIVE, CUSTOM }

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
        public final Policy policy;
        public final long customTimeoutMs;
        public final IdleAction action;

        public AppConfig(
                @NonNull String packageName,
                @NonNull Policy policy,
                long customTimeoutMs,
                @NonNull IdleAction action) {
            this.packageName = packageName;
            this.policy = policy;
            this.customTimeoutMs = customTimeoutMs;
            this.action = action;
        }

        public long resolvedTimeoutMs() {
            switch (policy) {
                case AGGRESSIVE: 
                    return POLICY_AGGRESSIVE_MS;
                case CUSTOM:     
                    return customTimeoutMs > 0 ? customTimeoutMs : DEFAULT_TIMEOUT_MS;
                default:         
                    return POLICY_BALANCED_MS;
            }
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("policy", policy.name());
            o.put("timeout_minutes", TimeUnit.MILLISECONDS.toMinutes(customTimeoutMs));
            o.put("action", action.name());
            return o;
        }

        public static AppConfig fromJson(@NonNull JSONObject o) throws Exception {
            String pkg = o.getString("package");
            Policy pol = Policy.valueOf(o.optString("policy", "BALANCED"));
            long mins = o.optLong("timeout_minutes", 60);
            IdleAction act = parseAction(o.optString("action",
                    IdleAction.STANDBY_BUCKET_RARE.name()));
            return new AppConfig(pkg, pol, TimeUnit.MINUTES.toMillis(mins), act);
        }

        private static IdleAction parseAction(String s) {
            try {
                return IdleAction.valueOf(s);
            }
            catch (Exception e) {
                return IdleAction.STANDBY_BUCKET_RARE;
            }
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
    private final Executor mIoExecutor;

    private volatile boolean mEnabled = true;
    private volatile Map<String, AppConfig> mAppConfigCache = Collections.emptyMap();

    private final Map<String, AppIdleState> mAppIdleStates = new ConcurrentHashMap<>();
    private final Map<String, Long> mLastKillTime = new ConcurrentHashMap<>();

    private volatile int mBatteryLevel = 100;
    private volatile boolean mIsCharging = false;

    private BroadcastReceiver mBatteryReceiver;
    private ContentObserver mSettingsObserver;
    private Runnable mScanRunnable;
    private Runnable mHaltRunnable;
    private volatile boolean mIsRunning = false;

    private LunarisIdleManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUsageStatsManager = (UsageStatsManager) mContext.getSystemService(Context.USAGE_STATS_SERVICE);

        mIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LunarisIdleManager-IO");
            t.setDaemon(true);
            return t;
        });

        loadConfigFromSettings();
        registerSettingsObserver();
        registerBatteryReceiver();
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
        if (!mEnabled) {
            Log.d(TAG, "LunarisIdleManager disabled — skipping");
            return;
        }
        if (mIsRunning) {
            Log.d(TAG, "Already running — ignoring duplicate start");
            return;
        }
        mIsRunning = true;
        cancelCallbacks();

        Log.d(TAG, "executeManager: appCount=" + mAppConfigCache.size()
                + " enabled=" + mEnabled);

        long timeUntilAlarm = getMillisUntilNextAlarm();
        long firstDelay;

        if (timeUntilAlarm > 0 && timeUntilAlarm < getMinConfiguredTimeout()) {
            firstDelay = MIN_DELAY_MS;
            Log.d(TAG, "Alarm soon — scheduling immediate scan");
        } else {
            firstDelay = getMinConfiguredTimeout();
            Log.d(TAG, "First scan in "
                    + TimeUnit.MILLISECONDS.toMinutes(firstDelay) + " min");
        }

        mMainHandler.postDelayed(mScanRunnable, firstDelay);

        if (timeUntilAlarm > ALARM_BUFFER_MS) {
            mMainHandler.postDelayed(mHaltRunnable, timeUntilAlarm - ALARM_BUFFER_MS);
        }
    }

    public void haltManager() {
        Log.d(TAG, "Halting LunarisIdleManager");
        cancelCallbacks();
        mIsRunning = false;
    }

    public void cleanup() {
        haltManager();
        unregisterSettingsObserver();
        unregisterBatteryReceiver();
        synchronized (sLock) { sInstance = null; }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    public Map<String, AppConfig> getAppConfigs() {
        return Collections.unmodifiableMap(mAppConfigCache);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER, enabled ? 1 : 0);
        if (!enabled) {
            haltManager();
            restoreAllBuckets();
        }
    }

    public void saveAppConfigs(@NonNull Map<String, AppConfig> configs) {
        mAppConfigCache = new HashMap<>(configs);
        persistAppConfigs(configs);
    }

    public void addOrUpdateApp(@NonNull AppConfig config) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.put(config.packageName, config);
        saveAppConfigs(updated);
    }

    public void removeApp(@NonNull String packageName) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.remove(packageName);
        saveAppConfigs(updated);
        restoreBucket(packageName);
        mAppIdleStates.remove(packageName);
        mLastKillTime.remove(packageName);
    }

    @NonNull
    public List<AppEnforcementRecord> getEnforcementRecords() {
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

    private void initRunnables() {
        mScanRunnable = () -> {
            mIoExecutor.execute(this::performIdleScan);
            if (mIsRunning) {
                long interval = getDynamicScanIntervalMs();
                Log.d(TAG, "Next scan in "
                        + TimeUnit.MILLISECONDS.toMinutes(interval)
                        + " min [battery=" + mBatteryLevel
                        + "%, charging=" + mIsCharging + "]");
                mMainHandler.postDelayed(mScanRunnable, interval);
            }
        };
        mHaltRunnable = this::haltManager;
    }

    private void performIdleScan() {
        if (mActivityManager == null || mUsageStatsManager == null) return;

        Log.d(TAG, "performIdleScan: evaluating " + mAppConfigCache.size() + " configured apps");

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

            if (foregroundPkgs.contains(pkg)) {
                Log.v(TAG, "Skipping foreground: " + pkg);
                continue;
            }

            if (audioActive && isActiveMediaApp(pkg, processes)) {
                Log.v(TAG, "Skipping active media: " + pkg);
                continue;
            }

            long timeoutMs = cfg.resolvedTimeoutMs();

            if (!isAppIdleLongEnough(pkg, timeoutMs, now)) {
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
                    didAct = killBackground(pkg, now, timeoutMs);
                    if (didAct) killed++;
                    break;
                case FULL_KILL:
                    boolean r = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED);
                    boolean k = forceStop(pkg, now, timeoutMs);
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

            Log.d(TAG, "Bucket applied ["
                    + bucketName(targetBucket) + "]: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to set standby bucket for "
                    + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean killBackground(String pkg, long now, long timeoutMs) {
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < timeoutMs) {
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

    private boolean forceStop(String pkg, long now, long timeoutMs) {
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < timeoutMs) {
            return false;
        }
        try {
            java.lang.reflect.Method method = mActivityManager.getClass()
                    .getMethod("forceStopPackage", String.class);
            method.invoke(mActivityManager, pkg);
            mLastKillTime.put(pkg, now);
            Log.d(TAG, "Force stopped: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "forceStop failed for " + pkg + ": " + e.getMessage());
            return killBackground(pkg, now, timeoutMs);
        }
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
            for (String pkg : new HashSet<>(mAppIdleStates.keySet())) {
                restoreBucket(pkg);
            }
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

    private boolean isAppIdleLongEnough(String pkg, long timeoutMs, long now) {
        try {
            long begin = now - timeoutMs;
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
                    + TimeUnit.MILLISECONDS.toMinutes(timeoutMs) + " min)");

            return idleDuration >= timeoutMs;

        } catch (Exception e) {
            Log.w(TAG, "UsageStats query failed for " + pkg + ": " + e.getMessage());
            Long lastKill = mLastKillTime.get(pkg);
            return lastKill == null || (now - lastKill) >= timeoutMs;
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
        try { return mAudioManager.isMusicActive(); }
        catch (Exception e) { return false; }
    }

    private boolean isActiveMediaApp(@NonNull String pkg,
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.pkgList == null) continue;
            for (String name : p.pkgList) {
                if (name.equals(pkg)) {
                    return p.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        }
        return false;
    }

    private void loadConfigFromSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Secure.getInt(cr, Settings.Secure.IDLE_MANAGER, 1) == 1;
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
                Settings.Secure.IDLE_MANAGER_APPS,
                Settings.Secure.IDLE_MANAGER_TIMEOUT}) {
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
                Log.d(TAG, "updateKillStats: existing=" + existing);

                JSONObject root = (existing != null && !existing.isEmpty())
                        ? new JSONObject(existing) : new JSONObject();

                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();

                entry.put("count", entry.optInt("count", 0) + 1);
                entry.put("last_kill",   tsMs);
                entry.put("last_action", action.name());
                root.put(pkg, entry);

                String newJson = root.toString();
                boolean wrote = Settings.Secure.putString(
                        cr, Settings.Secure.IDLE_MANAGER_KILL_STATS, newJson);
                Log.d(TAG, "updateKillStats: wrote=" + wrote
                        + " pkg=" + pkg + " count=" + entry.optInt("count")
                        + " action=" + action.name());
            } catch (Exception e) {
                Log.e(TAG, "Failed to update stats for " + pkg, e);
            }
        });
    }

    private long getMinConfiguredTimeout() {
        if (mAppConfigCache.isEmpty()) return DEFAULT_TIMEOUT_MS;
        long min = Long.MAX_VALUE;
        for (AppConfig c : mAppConfigCache.values())
            min = Math.min(min, c.resolvedTimeoutMs());
        return min == Long.MAX_VALUE ? DEFAULT_TIMEOUT_MS : min;
    }

    private long getDynamicScanIntervalMs() {
        if (mIsCharging) 
            return SCAN_INTERVAL_CHARGING_MS;
        if (mBatteryLevel <= BATTERY_LOW_THRESHOLD) 
            return SCAN_INTERVAL_LOW_MS;
        if (mBatteryLevel > BATTERY_HIGH_THRESHOLD) 
            return SCAN_INTERVAL_HIGH_MS;
        return SCAN_INTERVAL_MS;
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
        if (mScanRunnable != null) 
            mMainHandler.removeCallbacks(mScanRunnable);
        if (mHaltRunnable != null) 
            mMainHandler.removeCallbacks(mHaltRunnable);
    }
}
