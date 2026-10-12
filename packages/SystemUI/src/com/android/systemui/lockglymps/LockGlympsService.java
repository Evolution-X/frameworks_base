/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * Copyright (C) 2026 Evolution X contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.lockglymps;

import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LockGlympsService extends Service {
    private static final String TAG = "LockGlympsService";

    static final String ACTION_REFRESH_SETTINGS =
            "com.android.systemui.lockglymps.action.REFRESH_SETTINGS";
    static final String ACTION_CLEAR_CACHE =
            "com.android.systemui.lockglymps.action.CLEAR_CACHE";
    static final String ACTION_APPLY_NOW =
            "com.android.systemui.lockglymps.action.APPLY_NOW";

    private static final int MAX_CACHE_SIZE = 8;
    private static final long WALLPAPER_CACHE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long TEMP_CACHE_RETENTION_MS = 24L * 60 * 60 * 1000;
    private static final long MIN_FREE_DISK_BYTES = 128L * 1024 * 1024;
    private static final int MAX_NETWORK_REQUESTS_PER_DAY = 48;
    private static final int MAX_PREFETCH_IMAGES_PER_RUN = 2;
    private static final long MAX_NETWORK_BYTES_PER_DAY = 128L * 1024 * 1024;
    private static final long NETWORK_BUDGET_CHUNK_BYTES = 1024L * 1024;
    private static final int MAX_HISTORY_SIZE = 160;
    private static final int MAX_CUSTOM_URLS = 100;
    private static final int MAX_CUSTOM_URL_LENGTH = 4096;
    private static final int MAX_CSV_ENTRIES = 256;
    private static final int MAX_SETTING_TOKEN_LENGTH = 96;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_SOURCE_DIMENSION = 16384;
    private static final long MAX_SOURCE_PIXELS = 120_000_000L;
    // Keep decoded ARGB_8888 bitmaps below roughly 32 MiB in SystemUI.
    private static final long MAX_DECODED_PIXELS = 8_000_000L;
    private static final long MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L;
    // Local images are not downloaded. Keep their on-disk safety ceiling
    // independent from online bandwidth quotas, while still sampling decodes.
    private static final long MAX_LOCAL_WALLPAPER_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_4K_DOWNLOAD_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_8K_DOWNLOAD_BYTES = 24L * 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_JSON_RESPONSE_CHARS = 2 * 1024 * 1024;
    private static final long MAX_JSON_CACHE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_API_CACHE_FILES = 32;
    private static final long MAX_API_CACHE_BYTES = 8L * 1024L * 1024L;
    private static final int CONNECTION_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 30000;
    private static final int SCREEN_ON_DELAY = 800;
    private static final int SCREEN_OFF_DELAY = 450;
    private static final long DEFAULT_TIMER_INTERVAL = 1800000L;
    private static final long MIN_TIMER_INTERVAL = 60000L;
    private static final long MAX_TIMER_INTERVAL = 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_PROVIDER_COOLDOWN = 5L * 60L * 1000L;
    private static final long AUTH_PROVIDER_COOLDOWN = 60L * 60L * 1000L;
    private static final long DEFAULT_API_CACHE_TTL = 10L * 60L * 1000L;
    private static final String USER_AGENT = "EvolutionX-LockGlymps/2.0";

    private static final String LOCK_GLYMPS_ENABLED = "lock_glymps_enabled";
    private static final String LOCK_GLYMPS_LEGACY_SOURCE = "lock_glymps_source";
    private static final String LOCK_GLYMPS_PROVIDERS = "lock_glymps_providers";
    private static final String LOCK_GLYMPS_CATEGORY_STRATEGY = "lock_glymps_category_strategy";
    private static final String LOCK_GLYMPS_CATEGORIES = "lock_glymps_categories";
    private static final String LOCK_GLYMPS_PROVIDER_STRATEGY = "lock_glymps_provider_strategy";
    private static final String LOCK_GLYMPS_MIN_RESOLUTION = "lock_glymps_min_resolution";
    private static final String LOCK_GLYMPS_ORIENTATION = "lock_glymps_orientation";
    private static final String LOCK_GLYMPS_TONE = "lock_glymps_tone";
    private static final String LOCK_GLYMPS_ORDER = "lock_glymps_order";
    private static final String LOCK_GLYMPS_SFW_ONLY = "lock_glymps_sfw_only";
    private static final String LOCK_GLYMPS_NO_REPEAT = "lock_glymps_no_repeat";
    private static final String LOCK_GLYMPS_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String LOCK_GLYMPS_CHARGING_ONLY = "lock_glymps_charging_only";
    private static final String LOCK_GLYMPS_PAUSE_BATTERY_SAVER =
            "lock_glymps_pause_battery_saver";
    private static final String LOCK_GLYMPS_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String LOCK_GLYMPS_CACHE_LIMIT_MB = "lock_glymps_cache_limit_mb";
    private static final String LOCK_GLYMPS_DAILY_LIMIT_MB = "lock_glymps_daily_limit_mb";
    private static final String LOCK_GLYMPS_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String LOCK_GLYMPS_CHANGE_ON = "lock_glymps_change_on";
    private static final String LOCK_GLYMPS_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String LOCK_GLYMPS_WALLPAPER_TARGET = "lock_glymps_wallpaper_target";


    private static final String PROVIDER_WALLHAVEN = "wallhaven";
    private static final String PROVIDER_OPENVERSE = "openverse";
    private static final String PROVIDER_PICSUM = "picsum";
    private static final String PROVIDER_CUSTOM_URLS = "custom_urls";
    private static final String PROVIDER_LOCAL_FOLDER = "local_folder";

    private static final String DEFAULT_PROVIDERS = "wallhaven,openverse";
    private static final String DEFAULT_CATEGORIES = "nature,amoled,space";
    private static final String STORAGE_FOLDER = "Glymps";

    private static final int TARGET_LOCK_SCREEN = 0;
    private static final int TARGET_HOME_SCREEN = 1;
    private static final int TARGET_BOTH = 2;

    private WallpaperManager mWallpaperManager;
    private ExecutorService mExecutor;
    private Handler mHandler;
    private File mCacheDir;
    private File mApiCacheDir;
    private Random mRandom;
    private ScreenStateReceiver mScreenReceiver;
    private PowerManager.WakeLock mWakeLock;
    private Runnable mTimerRunnable;
    private SharedPreferences mHistoryPreferences;
    private SharedPreferences mCachePreferences;
    private SharedPreferences mNetworkBudgetPreferences;
    private SharedPreferences mCooldownPreferences;

    private volatile boolean mEnabled;
    private volatile boolean mWifiOnly = true;
    private volatile boolean mChargingOnly = false;
    private volatile boolean mPauseOnBatterySaver = true;
    private volatile boolean mSfwOnly = true;
    private volatile boolean mNoRepeat = true;
    private volatile int mCacheSize = 5;
    private volatile long mCacheByteLimit = 32L * 1024 * 1024;
    private volatile long mDailyNetworkByteLimit = 64L * 1024 * 1024;
    private volatile int mChangeOn = 1;
    private volatile long mTimerInterval = DEFAULT_TIMER_INTERVAL;
    private volatile int mWallpaperTarget = TARGET_LOCK_SCREEN;

    private volatile String mProviderStrategy = "balanced";
    private volatile String mCategoryStrategy = "round_robin";
    private volatile String mMinResolution = "auto";
    private volatile String mOrientation = "portrait";
    private volatile String mTone = "any";
    private volatile String mOrder = "latest";

    private volatile List<String> mProviders = new ArrayList<>();
    private volatile List<String> mCategories = new ArrayList<>();
    private volatile List<String> mCustomUrls = new ArrayList<>();

    private final Map<String, Integer> mProviderUsage = new ConcurrentHashMap<>();
    private final Map<String, Long> mProviderCooldownUntil = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> mRecentHistory = new LinkedHashSet<>();
    private final AtomicBoolean mWallpaperChangePending = new AtomicBoolean(false);
    private final AtomicInteger mConfigGeneration = new AtomicInteger(0);

    private int mRoundRobinIndex;
    private int mCategoryIndex;
    private int mLastCategoryIndex = -1;
    private Runnable mPendingWallpaperChange;

    @Override
    public void onCreate() {
        super.onCreate();

        mWallpaperManager = WallpaperManager.getInstance(this);
        mExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mRandom = new Random();
        mHistoryPreferences = getSharedPreferences("lock_glymps_history", MODE_PRIVATE);
        mCachePreferences = getSharedPreferences("lock_glymps_cache_meta", MODE_PRIVATE);
        // Continue cycling through the user's categories across service restarts.
        mCategoryIndex = Math.max(0, mCachePreferences.getInt("category_cursor", 0));
        mLastCategoryIndex = mCachePreferences.getInt("last_category_index", -1);
        mNetworkBudgetPreferences = getSharedPreferences(
                "lock_glymps_network_budget", MODE_PRIVATE);
        mCooldownPreferences = getSharedPreferences(
                "lock_glymps_provider_cooldowns", MODE_PRIVATE);
        for (String provider : Arrays.asList(
                PROVIDER_WALLHAVEN, PROVIDER_OPENVERSE, PROVIDER_PICSUM,
                PROVIDER_CUSTOM_URLS)) {
            long until = mCooldownPreferences.getLong(provider, 0L);
            if (until > System.currentTimeMillis()) {
                mProviderCooldownUntil.put(provider, until);
            }
        }
        loadHistory();

        mCacheDir = new File(getCacheDir(), "lock_glymps");
        if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create wallpaper cache directory");
        }

        // Enforce quotas when restarting, including after a force-stop during a download.
        mApiCacheDir = new File(getCacheDir(), "lock_glymps_api");
        if (!mApiCacheDir.exists() && !mApiCacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create provider response cache directory");
        }
        cleanApiCache();

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    TAG + ":WallpaperChange");
        }

        loadSettings();
        // A fresh service has no active downloads: remove orphaned temp files
        // immediately, then honor the user's persisted cache limits.
        clearOrphanedTemporaryFiles();
        cleanOldCache();

        if (mEnabled) {
            registerScreenReceiver();

            String currentSignature = getContentSignature();
            String cachedSignature = mCachePreferences.getString("signature", null);
            boolean staleCache = !safeEquals(cachedSignature, currentSignature);

            mExecutor.execute(() -> {
                if (staleCache) {
                    Log.d(TAG, "Cached wallpapers belong to an older configuration; clearing");
                    clearWallpaperCache();
                }
                persistCacheSignature(currentSignature);
                preFetchWallpapers();
            });

            if (mChangeOn == 2) {
                startTimer();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_REFRESH_SETTINGS.equals(action)) {
            int oldChangeOn = mChangeOn;
            String oldContentSignature = getContentSignature();
            List<String> oldProviders = new ArrayList<>(mProviders);
            loadSettings();
            resetProviderCooldownsAfterConfigChange(oldProviders);
            if (!mEnabled) {
                stopTimer();
                unregisterScreenReceiver();
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            if (oldChangeOn != mChangeOn || mScreenReceiver == null) {
                registerScreenReceiver();
            }

            if (mChangeOn == 2) {
                startTimer();
            } else {
                stopTimer();
            }

            String newContentSignature = getContentSignature();
            boolean contentChanged =
                    !oldContentSignature.equals(newContentSignature);
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.execute(() -> {
                    if (contentChanged) {
                        Log.d(TAG, "Wallpaper source/filter configuration changed; dropping stale cache");
                        clearWallpaperCache();
                        persistCacheSignature(newContentSignature);
                    }
                    preFetchWallpapers();
                });
            }
        } else if (ACTION_CLEAR_CACHE.equals(action)) {
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.execute(() -> {
                    // Clearing storage should not immediately refill it.
                    clearAllCaches();
                });
            }
        } else if (ACTION_APPLY_NOW.equals(action)) {
            enqueueWallpaperChange("manual");
        } else if (!mEnabled) {
            // Do not keep an idle sticky service alive if it was started while
            // the feature is disabled or restored by the process manager.
            stopTimer();
            unregisterScreenReceiver();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        return mEnabled ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Invalidate in-flight work before interrupting the executor so stale
        // downloads can never apply a wallpaper after the service is stopped.
        mEnabled = false;
        mConfigGeneration.incrementAndGet();

        stopTimer();

        unregisterScreenReceiver();

        if (mPendingWallpaperChange != null && mHandler != null) {
            mHandler.removeCallbacks(mPendingWallpaperChange);
            mPendingWallpaperChange = null;
        }

        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        mWallpaperChangePending.set(false);

        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to release wake lock", e);
            }
        }

        persistHistory();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadSettings() {
        try {
            mEnabled = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_ENABLED, 0) == 1;
            mWifiOnly = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_WIFI_ONLY, 1) == 1;
            mChargingOnly = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_CHARGING_ONLY, 0) == 1;
            mPauseOnBatterySaver = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_PAUSE_BATTERY_SAVER, 1) == 1;
            mSfwOnly = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_SFW_ONLY, 1) == 1;
            mNoRepeat = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_NO_REPEAT, 1) == 1;

            mCacheSize = clamp(
                    Settings.System.getInt(
                            getContentResolver(), LOCK_GLYMPS_CACHE_SIZE, 5),
                    1,
                    MAX_CACHE_SIZE);
            mCacheByteLimit = Math.min(MAX_CACHE_BYTES, clamp(
                    Settings.System.getInt(
                            getContentResolver(), LOCK_GLYMPS_CACHE_LIMIT_MB, 32),
                    16, 64) * 1024L * 1024L);
            mDailyNetworkByteLimit = Math.min(MAX_NETWORK_BYTES_PER_DAY, clamp(
                    Settings.System.getInt(
                            getContentResolver(), LOCK_GLYMPS_DAILY_LIMIT_MB, 64),
                    32, 128) * 1024L * 1024L);
            mChangeOn = clamp(
                    Settings.System.getInt(
                            getContentResolver(), LOCK_GLYMPS_CHANGE_ON, 1),
                    0,
                    2);
            mTimerInterval = clampLong(
                    Settings.System.getLong(
                            getContentResolver(),
                            LOCK_GLYMPS_TIMER_INTERVAL,
                            DEFAULT_TIMER_INTERVAL),
                    MIN_TIMER_INTERVAL,
                    MAX_TIMER_INTERVAL);
            mWallpaperTarget = clamp(
                    Settings.System.getInt(
                            getContentResolver(),
                            LOCK_GLYMPS_WALLPAPER_TARGET,
                            TARGET_LOCK_SCREEN),
                    TARGET_LOCK_SCREEN,
                    TARGET_BOTH);

            String providerCsv = Settings.System.getString(
                    getContentResolver(), LOCK_GLYMPS_PROVIDERS);
            if (isBlank(providerCsv)) {
                int legacySource = Settings.System.getInt(
                        getContentResolver(), LOCK_GLYMPS_LEGACY_SOURCE, 0);
                if (legacySource == 1) {
                    providerCsv = PROVIDER_CUSTOM_URLS;
                } else if (legacySource == 2) {
                    providerCsv = PROVIDER_LOCAL_FOLDER;
                } else {
                    providerCsv = DEFAULT_PROVIDERS;
                }
            }

            mProviders = filterKnownProviders(parseCsv(providerCsv));
            if (mProviders.isEmpty()) {
                mProviders = parseCsv(DEFAULT_PROVIDERS);
            }

            String categoryCsv = Settings.System.getString(
                    getContentResolver(), LOCK_GLYMPS_CATEGORIES);
            mCategories = parseCsv(isBlank(categoryCsv) ? DEFAULT_CATEGORIES : categoryCsv);
            if (mCategories.isEmpty()) {
                mCategories = parseCsv(DEFAULT_CATEGORIES);
            }

            mProviderStrategy = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_PROVIDER_STRATEGY),
                    "balanced",
                    "balanced", "priority", "random", "round_robin");
            mCategoryStrategy = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_CATEGORY_STRATEGY),
                    "round_robin", "round_robin", "random");
            mMinResolution = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_MIN_RESOLUTION),
                    "auto",
                    "auto", "1080", "1440", "2160", "4320");
            mOrientation = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_ORIENTATION),
                    "portrait",
                    "auto", "portrait", "landscape", "square");
            mTone = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_TONE),
                    "any",
                    "any", "dark", "amoled", "light", "colorful", "monochrome");
            mOrder = sanitizeChoice(
                    Settings.System.getString(
                            getContentResolver(), LOCK_GLYMPS_ORDER),
                    "latest",
                    "latest", "random", "popular", "top");


            mCustomUrls = parseCustomUrls(readAndMigrateCustomUrls());

            mConfigGeneration.incrementAndGet();

            Log.d(TAG, "Settings loaded: providers=" + mProviders.size()
                    + ", categories=" + mCategories.size()
                    + ", strategy=" + mProviderStrategy
                    + ", resolution=" + mMinResolution
                    + ", orientation=" + mOrientation
                    + ", order=" + mOrder
                    + ", cache=" + mCacheSize);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error loading Glymps settings", e);
        }
    }

    private String getContentSignature() {
        return joinCsv(mProviders)
                + "|" + joinCsv(mCategories)
                + "|" + mMinResolution
                + "|" + mOrientation
                + "|" + mTone
                + "|" + mOrder
                + "|" + mSfwOnly
                + "|custom=" + stableHash(joinLines(mCustomUrls));
    }

    private void persistCacheSignature(String signature) {
        if (mCachePreferences == null || signature == null) return;
        mCachePreferences.edit().putString("signature", signature).apply();
    }

    private void resetProviderCooldownsAfterConfigChange(List<String> oldProviders) {
        for (String provider : mProviders) {
            if (oldProviders == null || !oldProviders.contains(provider)) {
                mProviderCooldownUntil.remove(provider);
                if (mCooldownPreferences != null) {
                    mCooldownPreferences.edit().remove(provider).apply();
                }
            }
        }
    }

    private void registerScreenReceiver() {
        unregisterScreenReceiver();

        if (!mEnabled || (mChangeOn != 0 && mChangeOn != 1)) {
            return;
        }

        mScreenReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();

        if (mChangeOn == 0) {
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
        } else {
            filter.addAction(Intent.ACTION_SCREEN_OFF);
        }

        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenReceiver() {
        if (mScreenReceiver == null) return;

        try {
            unregisterReceiver(mScreenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mScreenReceiver = null;
    }

    private void startTimer() {
        if (mHandler == null || !mEnabled) return;

        stopTimer();

        mTimerRunnable = new Runnable() {
            @Override
            public void run() {
                enqueueWallpaperChange("timer");
                if (mHandler != null && mTimerRunnable != null) {
                    mHandler.postDelayed(mTimerRunnable, mTimerInterval);
                }
            }
        };

        mHandler.postDelayed(mTimerRunnable, mTimerInterval);
    }

    private void stopTimer() {
        if (mHandler != null && mTimerRunnable != null) {
            mHandler.removeCallbacks(mTimerRunnable);
            mTimerRunnable = null;
        }
    }

    private boolean ensureFeatureStillEnabled() {
        if (!mEnabled) return false;

        boolean enabled = Settings.System.getInt(
                getContentResolver(),
                LOCK_GLYMPS_ENABLED,
                0) == 1;
        if (enabled) return true;

        mEnabled = false;
        mConfigGeneration.incrementAndGet();
        stopTimer();
        unregisterScreenReceiver();
        stopSelf();
        return false;
    }

    /**
     * Persist network allowances before opening connections or consuming response bytes.
     * Every request consumes one request slot; each stream reserves data in 1 MiB blocks,
     * including bytes downloaded from a server that eventually returns invalid content.
     *
     * Charging in advance and committing synchronously keeps the limit intact when
     * SystemUI is killed or restarted mid-download. A rejected budget never touches
     * the network. Local wallpapers and already cached files do not consume allowance.
     */
    private synchronized void resetNetworkBudgetIfNewDay() {
        if (mNetworkBudgetPreferences == null) return;
        // UTC days give predictable limits regardless of the user's locale.
        long day = System.currentTimeMillis() / (24L * 60 * 60 * 1000);
        if (mNetworkBudgetPreferences.getLong("utc_day", -1L) < day) {
            mNetworkBudgetPreferences.edit()
                    .putLong("utc_day", day)
                    .putInt("requests", 0)
                    .putLong("reserved_bytes", 0L)
                    .commit();
        }
    }

    private synchronized boolean canStartNetworkRequest() {
        if (mNetworkBudgetPreferences == null) return false;
        resetNetworkBudgetIfNewDay();
        return mNetworkBudgetPreferences.getInt("requests", 0) < MAX_NETWORK_REQUESTS_PER_DAY
                && mNetworkBudgetPreferences.getLong("reserved_bytes", 0L)
                        < mDailyNetworkByteLimit;
    }

    private synchronized boolean reserveNetworkRequest() {
        if (!canStartNetworkRequest()) {
            Log.i(TAG, "Glymps daily network request/byte budget reached");
            return false;
        }
        int requests = mNetworkBudgetPreferences.getInt("requests", 0);
        return mNetworkBudgetPreferences.edit().putInt("requests", requests + 1).commit();
    }

    private synchronized boolean reserveNetworkBytes(long bytes) {
        if (bytes <= 0 || bytes > NETWORK_BUDGET_CHUNK_BYTES
                || mNetworkBudgetPreferences == null) {
            return false;
        }
        resetNetworkBudgetIfNewDay();
        long previous = mNetworkBudgetPreferences.getLong("reserved_bytes", 0L);
        if (previous > mDailyNetworkByteLimit - bytes) {
            return false;
        }
        return mNetworkBudgetPreferences.edit()
                .putLong("reserved_bytes", previous + bytes).commit();
    }

    private final class BudgetedInputStream extends java.io.FilterInputStream {
        private long mBytesLeftInReservation;

        BudgetedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int count = read(oneByte, 0, 1);
            return count < 0 ? -1 : oneByte[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (mBytesLeftInReservation == 0) {
                if (!reserveNetworkBytes(NETWORK_BUDGET_CHUNK_BYTES)) {
                    throw new IOException("Glymps daily network data cap reached");
                }
                mBytesLeftInReservation = NETWORK_BUDGET_CHUNK_BYTES;
            }
            int available = (int) Math.min((long) length, mBytesLeftInReservation);
            int count = in.read(buffer, offset, available);
            if (count > 0) {
                mBytesLeftInReservation -= count;
            }
            return count;
        }
    }

    private boolean shouldDownload() {
        PowerManager power = getSystemService(PowerManager.class);
        if (mPauseOnBatterySaver && power != null && power.isPowerSaveMode()) {
            return false;
        }
        if (mChargingOnly) {
            android.os.BatteryManager battery = getSystemService(
                    android.os.BatteryManager.class);
            if (battery == null || !battery.isCharging()) return false;
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        if (capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return false;
        }

        // Respect the device's Data Saver policy even if SystemUI happens
        // to have privileged network exemptions.
        if (cm.isActiveNetworkMetered()
                && cm.getRestrictBackgroundStatus()
                        == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            return false;
        }

        if (!mWifiOnly) {
            return true;
        }

        // Refuse Wi-Fi hotspots marked metered, and VPNs whose transport
        // could silently route over cellular when Wi-Fi-only is selected.
        return !cm.isActiveNetworkMetered()
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private void preFetchWallpapers() {
        if (!ensureFeatureStillEnabled() || mCacheDir == null) {
            return;
        }

        // Reclaim outdated files even with Wi-Fi disabled or the device offline.
        cleanOldCache();
        if (!shouldDownload() || !hasOnlineProvider() || !canStartNetworkRequest()) {
            return;
        }
        final int generation = mConfigGeneration.get();
        int cacheCount = countCacheFiles();
        int attempts = 0;
        int additions = 0;
        int maxAttempts = MAX_PREFETCH_IMAGES_PER_RUN * 3;

        while (cacheCount < mCacheSize
                && additions < MAX_PREFETCH_IMAGES_PER_RUN
                && getCachedWallpaperBytes() < mCacheByteLimit
                && canStartNetworkRequest()
                && attempts < maxAttempts
                && generation == mConfigGeneration.get()
                && !mWallpaperChangePending.get()
                && !Thread.currentThread().isInterrupted()) {
            attempts++;

            String query = nextSearchQuery();
            WallpaperDownload download = downloadFromProviderPool(query, true);
            if (download == null || download.bitmap == null) {
                break;
            }

            if (generation != mConfigGeneration.get()) {
                download.bitmap.recycle();
                break;
            }

            String fileName = buildCacheFileName(download.candidate, query);
            File cacheFile = new File(mCacheDir, fileName);

            boolean saved = saveBitmapToFile(download.bitmap, cacheFile);
            download.bitmap.recycle();
            if (!saved) {
                // Do not keep downloading candidates when there is not enough
                // room for the encoded result within the selected cache limit.
                Log.d(TAG, "Stopping Glymps prefetch: cache quota or disk limit reached");
                break;
            }
            cleanOldCache();
            cacheCount = countCacheFiles();
            additions++;
            Log.d(TAG, "Cached " + download.candidate.provider
                    + " wallpaper as " + cacheFile.getName());
        }

        cleanOldCache();
    }

    private WallpaperDownload downloadFromProviderPool(String query, boolean onlineOnly) {
        List<String> providers = getProviderOrder(onlineOnly, query);

        for (String provider : providers) {
            if (PROVIDER_LOCAL_FOLDER.equals(provider)) {
                continue;
            }

            for (int attempt = 0; attempt < 2; attempt++) {
                WallpaperCandidate candidate = fetchCandidate(provider, query, attempt);
                if (candidate == null) {
                    break;
                }

                Bitmap bitmap = downloadBitmap(candidate);
                if (bitmap == null) {
                    continue;
                }

                    noteProviderSuccess(provider);


                return new WallpaperDownload(candidate, bitmap);
            }
        }

        return null;
    }

    private List<String> getProviderOrder(boolean onlineOnly, String query) {
        ArrayList<String> available = new ArrayList<>();

        List<String> providerSnapshot = mProviders;
        for (String provider : providerSnapshot) {
            if (onlineOnly) {
                if (PROVIDER_LOCAL_FOLDER.equals(provider)) {
                    continue;
                }
                if (isProviderAvailable(provider)) {
                    available.add(provider);
                }
                continue;
            }

            if (PROVIDER_LOCAL_FOLDER.equals(provider)) {
                if (hasLocalWallpapers()) {
                    available.add(provider);
                }
            } else if (hasCachedWallpaper(provider, query) || isProviderAvailable(provider)) {
                // Cached wallpapers remain usable even while a provider is rate
                // limited or temporarily lacks credentials.
                available.add(provider);
            }
        }

        if (available.isEmpty()) {
            return available;
        }

        switch (mProviderStrategy) {
            case "priority":
                break;

            case "random":
                Collections.shuffle(available, mRandom);
                break;

            case "round_robin":
                if (available.size() > 1) {
                    int shift = Math.floorMod(mRoundRobinIndex++, available.size());
                    Collections.rotate(available, -shift);
                }
                break;

            case "balanced":
            default:
                Collections.shuffle(available, mRandom);
                available.sort(Comparator.comparingInt(
                        provider -> mProviderUsage.getOrDefault(provider, 0)));
                break;
        }

        return available;
    }

    private boolean isProviderAvailable(String provider) {
        Long cooldownUntil = mProviderCooldownUntil.get(provider);
        if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
            return false;
        }

        switch (provider) {
            case PROVIDER_WALLHAVEN:
            case PROVIDER_OPENVERSE:
                return true;
            case PROVIDER_PICSUM:
                // Requesting a large Picsum output does not establish the
                // original photograph's native resolution. Strict 4K/8K only
                // uses providers exposing verifiable source dimensions.
                return !"2160".equals(mMinResolution)
                        && !"4320".equals(mMinResolution);
            case PROVIDER_CUSTOM_URLS:
                return !mCustomUrls.isEmpty();
            case PROVIDER_LOCAL_FOLDER:
                return hasLocalWallpapers();
            default:
                return false;
        }
    }

    /**
     * Each cached image is tied to the exact topic/tone query that selected it.
     * An image prefetched for "space" must not later satisfy "flowers".
     * Old unscoped cache files are ignored and will expire during pruning.
     */
    static String cacheQueryPrefix(String provider, String query) {
        return cacheQueryPrefix(provider, query, "auto", "auto");
    }

    /**
     * A filter change invalidates the old image cache namespace. The cached
     * bitmap is display-sized, so its decoded dimensions cannot prove that
     * the original download met a newly requested 4K/8K resolution.
     */
    static String cacheQueryPrefix(
            String provider, String query, String resolution, String orientation) {
        String filterKey = query + "|resolution=" + resolution
                + "|orientation=" + orientation;
        return "glymps_" + provider.replaceAll("[^a-zA-Z0-9_-]", "_")
                + "_" + stableHash(filterKey) + "_";
    }

    private String currentCacheQueryPrefix(String provider, String query) {
        return cacheQueryPrefix(provider, query, mMinResolution, mOrientation);
    }

    private boolean hasCachedWallpaper(String provider, String query) {
        if (mCacheDir == null || isBlank(provider)) return false;

        String prefix = currentCacheQueryPrefix(provider, query);
        File[] files = mCacheDir.listFiles(
                (dir, name) -> name.startsWith(prefix) && name.endsWith(".img"));
        if (files == null) return false;
        for (File file : files) {
            String key = cachedHistoryKey(file);
            if (!mNoRepeat || key == null || !historyContains(key)) return true;
        }
        return false;
    }

    private boolean hasOnlineProvider() {
        List<String> providerSnapshot = mProviders;
        for (String provider : providerSnapshot) {
            if (!PROVIDER_LOCAL_FOLDER.equals(provider) && isProviderAvailable(provider)) {
                return true;
            }
        }
        return false;
    }

    private WallpaperCandidate fetchCandidate(String provider, String query, int attempt) {
        try {
            switch (provider) {
                case PROVIDER_WALLHAVEN:
                    return fetchWallhavenCandidate(query, attempt);
                case PROVIDER_OPENVERSE:
                    return fetchOpenverseCandidate(query, attempt);
                case PROVIDER_PICSUM:
                    return buildPicsumCandidate();
                case PROVIDER_CUSTOM_URLS:
                    return buildCustomUrlCandidate();
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Provider " + provider + " failed while selecting a wallpaper", e);
            return null;
        }
    }

    /**
     * Openverse searches openly licensed works. Restrict automatic wallpaper retrieval to
     * Creative Commons Zero / public-domain works, so a background rotation does not
     * silently omit attribution required by other Creative Commons licenses.
     *
     * Openverse may index third-party image hosts. The regular bounded downloader performs
     * public-address checks on each candidate URL and limits both bytes and image dimensions.
     */
    private WallpaperCandidate fetchOpenverseCandidate(String query, int attempt) {
        if (isBlank(query)) return null;

        final StringBuilder url = new StringBuilder(
                "https://api.openverse.org/v1/images/")
                .append("?q=").append(Uri.encode(query))
                .append("&license=cc0,pdm")
                .append("&mature=false")
                .append("&size=large")
                .append("&page_size=30")
                .append("&page=").append(1 + Math.min(4, Math.max(0, attempt)));

        // Apply orientation at the provider to avoid downloading pages of
        // unsuitable landscape images for a portrait phone.
        switch (mOrientation) {
            case "portrait":
                url.append("&aspect_ratio=tall");
                break;
            case "landscape":
                url.append("&aspect_ratio=wide");
                break;
            case "square":
                url.append("&aspect_ratio=square");
                break;
            default:
                break;
        }

        if ("latest".equals(mOrder)) {
            url.append("&unstable__sort_by=indexed_on");
        }

        JSONObject response = requestJson(
                PROVIDER_OPENVERSE, url.toString(), Collections.emptyMap());
        if (response == null) return null;

        JSONArray results = response.optJSONArray("results");
        if (results == null || results.length() == 0) return null;

        for (int index : shuffledIndexes(results.length())) {
            JSONObject item = results.optJSONObject(index);
            if (item == null || item.optBoolean("mature", false)) continue;

            String license = item.optString("license", "").toLowerCase(Locale.ROOT);
            if (!"cc0".equals(license) && !"pdm".equals(license)) {
                continue;
            }

            String id = item.optString("id", "");
            String imageUrl = item.optString("url", "");
            if (isBlank(id) || isBlank(imageUrl)) continue;

            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_OPENVERSE,
                    id,
                    imageUrl,
                    PROVIDER_OPENVERSE + ":" + id,
                    item.optInt("width", 0),
                    item.optInt("height", 0),
                    null);
            if (candidateAllowed(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private WallpaperCandidate fetchWallhavenCandidate(String query, int attempt) {
        String sorting;
        switch (mOrder) {
            case "random":
                sorting = "random";
                break;
            case "popular":
                sorting = "views";
                break;
            case "top":
                sorting = "toplist";
                break;
            case "latest":
            default:
                sorting = "date_added";
                break;
        }

        int[] minimum = getRequiredDimensions();
        StringBuilder url = new StringBuilder("https://wallhaven.cc/api/v1/search")
                .append("?q=").append(Uri.encode(query))
                .append("&categories=111")
                // Unauthenticated Wallhaven searches support SFW only.
                .append("&purity=100")
                .append("&sorting=").append(sorting)
                .append("&order=desc");

        if (minimum[0] > 0 && minimum[1] > 0) {
            url.append("&atleast=").append(minimum[0]).append('x').append(minimum[1]);
        }

        if ("toplist".equals(sorting)) {
            url.append("&topRange=1M");
        }

        if (!"random".equals(sorting) && attempt > 0) {
            url.append("&page=").append(1 + mRandom.nextInt(3));
        }

        JSONObject root = requestJson(
                PROVIDER_WALLHAVEN, url.toString(), Collections.emptyMap());
        if (root == null) return null;

        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) return null;

        ArrayList<Integer> indexes = shuffledIndexes(data.length());
        for (int index : indexes) {
            JSONObject item = data.optJSONObject(index);
            if (item == null) continue;

            String id = item.optString("id");
            String imageUrl = item.optString("path");
            int width = item.optInt("dimension_x", 0);
            int height = item.optInt("dimension_y", 0);

            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_WALLHAVEN,
                    id,
                    imageUrl,
                    PROVIDER_WALLHAVEN + ":" + id,
                    width,
                    height,
                    null);

            if (candidateAllowed(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private WallpaperCandidate buildPicsumCandidate() {
        int[] required = getRequiredDimensions();
        int width = required[0] > 0 ? required[0] : 1080;
        int height = required[1] > 0 ? required[1] : 1920;

        // Picsum can generate large images, but avoid pathological network/memory loads.
        width = Math.min(width, 4320);
        height = Math.min(height, 7680);

        String seed = UUID.randomUUID().toString();
        String url = "https://picsum.photos/seed/" + seed + "/" + width + "/" + height;
        WallpaperCandidate candidate = new WallpaperCandidate(
                PROVIDER_PICSUM,
                seed,
                url,
                PROVIDER_PICSUM + ":" + seed,
                width,
                height,
                null);

        return candidateAllowed(candidate) ? candidate : null;
    }

    private WallpaperCandidate buildCustomUrlCandidate() {
        if (mCustomUrls.isEmpty()) return null;

        ArrayList<String> urls = new ArrayList<>(mCustomUrls);
        Collections.shuffle(urls, mRandom);

        for (String imageUrl : urls) {
            if (isBlank(imageUrl)
                    || (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://"))) {
                continue;
            }

            String id = stableHash(imageUrl);
            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_CUSTOM_URLS,
                    id,
                    imageUrl,
                    PROVIDER_CUSTOM_URLS + ":" + id,
                    0,
                    0,
                    null);

            if (candidateAllowed(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private JSONObject requestJson(
            String provider,
            String requestUrl,
            Map<String, String> headers) {
        JSONObject cached = shouldUseApiCache(provider, requestUrl)
                ? readCachedJson(provider, requestUrl) : null;
        if (cached != null) {
            return cached;
        }

        if (!reserveNetworkRequest()) return null;

        HttpURLConnection connection = null;
        InputStream stream = null;

        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                handleProviderHttpError(provider, connection, responseCode);
                return null;
            }

            stream = new BufferedInputStream(new BudgetedInputStream(
                    connection.getInputStream()));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            // Never use readLine() for untrusted JSON: an unbounded line
            // could allocate a large String before the size check runs.
            StringBuilder body = new StringBuilder(Math.min(MAX_JSON_RESPONSE_CHARS, 8192));
            char[] chunk = new char[4096];
            int read;
            while ((read = reader.read(chunk, 0, chunk.length)) != -1) {
                if (Thread.currentThread().isInterrupted()
                        || !shouldDownload()) {
                    return null;
                }
                if (read > MAX_JSON_RESPONSE_CHARS - body.length()) {
                    Log.w(TAG, provider + " API response exceeded safety limit");
                    coolDownProvider(provider, DEFAULT_PROVIDER_COOLDOWN);
                    return null;
                }
                body.append(chunk, 0, read);
            }

            JSONObject result = new JSONObject(body.toString());
            if (shouldUseApiCache(provider, requestUrl)) {
                writeCachedJson(provider, requestUrl, body.toString());
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, provider + " API request failed ("
                    + e.getClass().getSimpleName() + ")");
            coolDownProvider(provider, DEFAULT_PROVIDER_COOLDOWN);
            return null;
        } finally {
            closeQuietly(stream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean shouldUseApiCache(String provider, String requestUrl) {
        return !(PROVIDER_WALLHAVEN.equals(provider)
                && requestUrl != null
                && requestUrl.contains("sorting=random"));
    }

    private JSONObject readCachedJson(String provider, String requestUrl) {
        File cacheFile = getApiCacheFile(provider, requestUrl);
        if (cacheFile == null || !cacheFile.isFile()) {
            return null;
        }

        if (cacheFile.length() > MAX_JSON_CACHE_BYTES) {
            if (!cacheFile.delete()) {
                Log.d(TAG, "Unable to delete oversized provider cache");
            }
            return null;
        }

        long ttl = DEFAULT_API_CACHE_TTL;
        if (System.currentTimeMillis() - cacheFile.lastModified() > ttl) {
            if (!cacheFile.delete()) {
                Log.d(TAG, "Unable to delete expired provider cache");
            }
            return null;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new java.io.FileInputStream(cacheFile),
                        StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return new JSONObject(body.toString());
        } catch (Exception e) {
            if (!cacheFile.delete()) {
                Log.d(TAG, "Unable to delete invalid provider cache");
            }
            return null;
        }
    }

    private void writeCachedJson(String provider, String requestUrl, String body) {
        File cacheFile = getApiCacheFile(provider, requestUrl);
        if (cacheFile == null || body == null) {
            return;
        }

        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        boolean written = false;
        boolean committed = false;

        try (FileOutputStream stream = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(
                     stream, StandardCharsets.UTF_8)) {
            writer.write(body);
            writer.flush();
            stream.getFD().sync();
            written = true;
        } catch (IOException e) {
            Log.d(TAG, "Unable to cache provider response", e);
        }

        if (written) {
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.d(TAG, "Unable to replace old provider cache");
            }

            committed = tempFile.renameTo(cacheFile);
            if (!committed) {
                Log.d(TAG, "Unable to commit provider cache");
            }
        }

        if (!committed && tempFile.exists() && !tempFile.delete()) {
            Log.d(TAG, "Unable to delete temporary provider cache");
        }

        cleanApiCache();
    }

    private File getApiCacheFile(String provider, String requestUrl) {
        if (mApiCacheDir == null || isBlank(provider) || isBlank(requestUrl)) {
            return null;
        }

        String identity = provider + ":" + requestUrl;
        return new File(mApiCacheDir, provider + "_" + stableHash(identity) + ".json");
    }

    private void cleanApiCache() {
        if (mApiCacheDir == null) return;

        File[] files = mApiCacheDir.listFiles();
        if (files == null || files.length == 0) return;

        long now = System.currentTimeMillis();
        for (File file : files) {
            long maxAge = DEFAULT_API_CACHE_TTL;
            if (now - file.lastModified() > maxAge && !file.delete()) {
                Log.d(TAG, "Unable to delete expired provider response cache");
            }
        }

        files = mApiCacheDir.listFiles();
        if (files == null || files.length == 0) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += Math.max(0L, file.length());
        }

        int remaining = files.length;
        for (File file : files) {
            if (remaining <= MAX_API_CACHE_FILES && totalBytes <= MAX_API_CACHE_BYTES) {
                break;
            }

            long length = Math.max(0L, file.length());
            if (file.delete()) {
                totalBytes -= length;
                remaining--;
            }
        }
    }

    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16 && i < bytes.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void handleProviderHttpError(
            String provider,
            HttpURLConnection connection,
            int responseCode) {
        if (responseCode == 429) {
            long cooldown = DEFAULT_PROVIDER_COOLDOWN;
            String retryAfter = connection.getHeaderField("Retry-After");
            if (!isBlank(retryAfter)) {
                try {
                    cooldown = Math.max(
                            DEFAULT_PROVIDER_COOLDOWN,
                            Long.parseLong(retryAfter.trim()) * 1000L);
                } catch (NumberFormatException ignored) {
                }
            }
            coolDownProvider(provider, cooldown);
            Log.w(TAG, provider + " rate limited; temporarily cooling down");
        } else if (responseCode == 401 || responseCode == 403) {
            coolDownProvider(provider, AUTH_PROVIDER_COOLDOWN);
            Log.w(TAG, provider + " rejected credentials or access");
        } else if (responseCode >= 500) {
            coolDownProvider(provider, DEFAULT_PROVIDER_COOLDOWN);
            Log.w(TAG, provider + " server error: " + responseCode);
        } else {
            Log.w(TAG, provider + " API HTTP error: " + responseCode);
        }
    }

    private void coolDownProvider(String provider, long durationMs) {
        long until = System.currentTimeMillis() + Math.max(60000L, durationMs);
        mProviderCooldownUntil.put(provider, until);
        if (mCooldownPreferences != null) {
            mCooldownPreferences.edit().putLong(provider, until).apply();
        }
    }

    private HttpURLConnection openSafeImageConnection(String sourceUrl) throws IOException {
        URL current = new URL(sourceUrl);

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!isSafeRemoteUrl(current)) {
                throw new IOException("Blocked unsafe wallpaper URL");
            }

            // Charge every HTTP hop, not just the initial URL. A redirect
            // chain must not bypass the daily 48-request ceiling.
            if (!reserveNetworkRequest()) {
                throw new IOException("Glymps daily HTTP request cap reached");
            }
            HttpURLConnection connection =
                    (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty(
                    "Accept",
                    "image/avif,image/webp,image/*,*/*;q=0.8");

            int code = connection.getResponseCode();
            if (!isRedirect(code)) {
                return connection;
            }

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (isBlank(location)) {
                throw new IOException("Wallpaper redirect missing Location header");
            }

            URL redirected = new URL(current, location);
            if ("https".equalsIgnoreCase(current.getProtocol())
                    && "http".equalsIgnoreCase(redirected.getProtocol())) {
                throw new IOException("Blocked HTTPS to HTTP wallpaper redirect");
            }
            current = redirected;
        }

        throw new IOException("Too many wallpaper redirects");
    }

    private boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    private boolean isSafeRemoteUrl(URL url) {
        if (url == null) return false;

        String protocol = url.getProtocol();
        // Privileged SystemUI must not fetch unencrypted images or expose
        // userinfo to arbitrary servers through user-supplied image URLs.
        if (!"https".equalsIgnoreCase(protocol)
                || (url.getPort() != -1 && url.getPort() != 443)
                || (url.getAuthority() != null && url.getAuthority().contains("@"))) {
            return false;
        }

        String host = url.getHost();
        if (isBlank(host)
                || "localhost".equalsIgnoreCase(host)
                || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                || host.toLowerCase(Locale.ROOT).endsWith(".local")) {
            return false;
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;

            for (InetAddress address : addresses) {
                if (isPrivateOrLocalAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPrivateOrLocalAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();
        if (raw == null) return true;

        if (raw.length == 4) {
            int a = raw[0] & 0xff;
            int b = raw[1] & 0xff;

            // Unspecified, carrier-grade NAT and benchmarking ranges should not
            // be reachable through a privileged wallpaper downloader.
            if (a == 0) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 198 && (b == 18 || b == 19)) return true;
        } else if (raw.length == 16) {
            int first = raw[0] & 0xff;
            // IPv6 Unique Local Address range fc00::/7.
            if ((first & 0xfe) == 0xfc) return true;
        }

        return false;
    }

    private Bitmap downloadBitmap(WallpaperCandidate candidate) {
        if (candidate == null || isBlank(candidate.url)
                || !canStartNetworkRequest()) return null;

        HttpURLConnection connection = null;
        InputStream input = null;
        BufferedOutputStream output = null;
        File temporaryFile = null;

        try {
            connection = openSafeImageConnection(candidate.url);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                handleProviderHttpError(candidate.provider, connection, responseCode);
                Log.w(TAG, candidate.provider + " image HTTP error: " + responseCode);
                return null;
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > getImageDownloadLimitBytes()) {
                Log.w(TAG, "Skipping oversized wallpaper: " + contentLength + " bytes");
                return null;
            }

            if (mCacheDir == null
                    || mCacheDir.getUsableSpace()
                            < MIN_FREE_DISK_BYTES + getImageDownloadLimitBytes()) {
                Log.w(TAG, "Skipping wallpaper download: disk space is low");
                return null;
            }
            temporaryFile = File.createTempFile("glymps_", ".tmp", mCacheDir);
            input = new BufferedInputStream(new BudgetedInputStream(
                    connection.getInputStream()));
            output = new BufferedOutputStream(new FileOutputStream(temporaryFile));

            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            long nextNetworkCheck = 1024L * 1024L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                total += read;
                if (total > getImageDownloadLimitBytes()) {
                    Log.w(TAG, "Wallpaper exceeded download safety limit");
                    return null;
                }

                // Recheck battery saver, charging, and connectivity even when
                // the user allows mobile data; device conditions may change
                // during a long image download.
                if (total >= nextNetworkCheck) {
                    if (!shouldDownload()) {
                        Log.d(TAG, "Stopping wallpaper download after network/power policy changed");
                        return null;
                    }
                    nextNetworkCheck = total + 1024L * 1024L;
                }

                output.write(buffer, 0, read);
            }
            output.flush();
            closeQuietly(output);
            output = null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temporaryFile.getAbsolutePath(), bounds);

            if (!isSafeImageBounds(bounds.outWidth, bounds.outHeight)) {
                Log.w(TAG, "Downloaded image dimensions are invalid or unsafe: "
                        + bounds.outWidth + "x" + bounds.outHeight);
                return null;
            }

            if (!passesDimensionFilters(bounds.outWidth, bounds.outHeight)) {
                Log.d(TAG, "Downloaded wallpaper rejected by dimension/orientation filters: "
                        + bounds.outWidth + "x" + bounds.outHeight);
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateDecodeSample(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap bitmap = BitmapFactory.decodeFile(
                    temporaryFile.getAbsolutePath(), options);

            if (bitmap != null) {
                Log.d(TAG, "Downloaded " + candidate.provider + " wallpaper: source="
                        + bounds.outWidth + "x" + bounds.outHeight
                        + ", decoded=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            }

            return bitmap;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Wallpaper decode exceeded memory budget", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Unable to download wallpaper from " + candidate.provider
                    + " (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            if (connection != null) {
                connection.disconnect();
            }
            if (temporaryFile != null && temporaryFile.exists() && !temporaryFile.delete()) {
                Log.d(TAG, "Unable to delete temporary wallpaper file");
            }
        }
    }

    /** Strict original-size selection. Output may be sampled to screen dimensions. */
    private long getImageDownloadLimitBytes() {
        return downloadLimitForResolution(mMinResolution);
    }

    static long downloadLimitForResolution(String resolution) {
        if ("4320".equals(resolution)) return MAX_8K_DOWNLOAD_BYTES;
        if ("2160".equals(resolution)) return MAX_4K_DOWNLOAD_BYTES;
        return MAX_DOWNLOAD_BYTES;
    }

    static boolean meetsNativeResolution(int width, int height, String resolution) {
        if (width <= 0 || height <= 0) return false;
        int shortest = Math.min(width, height);
        int longest = Math.max(width, height);
        if ("4320".equals(resolution)) return shortest >= 4320 && longest >= 7680;
        if ("2160".equals(resolution)) return shortest >= 2160 && longest >= 3840;
        if ("1440".equals(resolution)) return shortest >= 1440 && longest >= 2560;
        if ("1080".equals(resolution)) return shortest >= 1080 && longest >= 1920;
        return true;
    }

    private boolean isSafeImageBounds(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return false;
        return (long) width * (long) height <= MAX_SOURCE_PIXELS;
    }

    private int calculateDecodeSample(int width, int height) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetWidth = Math.max(1440, metrics.widthPixels);
        int targetHeight = Math.max(2560, metrics.heightPixels);
        return calculateDecodeSampleForDisplay(width, height, targetWidth, targetHeight);
    }

    // Package-private so the decoder's memory ceiling can be tested without
    // creating a SystemUI service instance or downloading a real image.
    static int calculateDecodeSampleForDisplay(
            int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0) return 1;

        int sample = 1;
        while (sample < 128
                && width / (sample * 2) >= targetWidth
                && height / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        // Very wide or tall images can exceed the memory budget even when
        // their smallest side is near the display size. Never decode these
        // full-size inside the SystemUI process.
        while (sample < 128
                && ((long) ((width + sample - 1) / sample))
                        * ((height + sample - 1) / sample) > MAX_DECODED_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    private boolean isAlreadyCached(WallpaperCandidate candidate) {
        if (mCacheDir == null) return false;
        String identity = "_" + stableHash(candidate.historyKey) + "_";
        File[] matches = mCacheDir.listFiles((dir, name) ->
                name.startsWith("glymps_") && name.endsWith(".img")
                        && name.contains(identity));
        return matches != null && matches.length > 0;
    }

    /** Recover a stable image identity from the scoped cache file name. */
    static String cachedHistoryKey(File file) {
        if (file == null) return null;
        String name = file.getName();
        if (!name.endsWith(".img")) return null;
        int timestampSeparator = name.lastIndexOf('_');
        int hashSeparator = name.lastIndexOf('_', timestampSeparator - 1);
        if (timestampSeparator <= 0 || hashSeparator < 0) return null;
        String hash = name.substring(hashSeparator + 1, timestampSeparator);
        return hash.isEmpty() ? null : "seen:" + hash;
    }

    private boolean candidateAllowed(WallpaperCandidate candidate) {
        if (candidate == null || isBlank(candidate.url) || isBlank(candidate.historyKey)) {
            return false;
        }

        if (mNoRepeat && (historyContains(candidate.historyKey)
                || historyContains("seen:" + stableHash(candidate.historyKey))
                || isAlreadyCached(candidate))) {
            return false;
        }

        if ((PROVIDER_WALLHAVEN.equals(candidate.provider)
                || PROVIDER_OPENVERSE.equals(candidate.provider))
                && ("2160".equals(mMinResolution) || "4320".equals(mMinResolution))
                && (candidate.width <= 0 || candidate.height <= 0)) {
            // In strict 4K/8K modes, skip unknown remote source dimensions
            // rather than spending data only to discover it is too small.
            return false;
        }

        return candidate.width <= 0
                || candidate.height <= 0
                || (isSafeImageBounds(candidate.width, candidate.height)
                        && passesDimensionFilters(candidate.width, candidate.height));
    }

    private boolean passesDimensionFilters(int width, int height) {
        if (width <= 0 || height <= 0) return false;

        switch (mOrientation) {
            case "portrait":
                if (height <= width) return false;
                break;
            case "landscape":
                if (width <= height) return false;
                break;
            case "square":
                float ratio = (float) width / (float) height;
                if (ratio < 0.90f || ratio > 1.10f) return false;
                break;
            default:
                break;
        }

        if (!meetsNativeResolution(width, height, mMinResolution)) return false;
        int[] required = getRequiredShortLong();
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        return shortSide >= required[0] && longSide >= required[1];
    }

    private int[] getRequiredShortLong() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int deviceShort = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
        int deviceLong = Math.max(1, Math.max(metrics.widthPixels, metrics.heightPixels));

        switch (mMinResolution) {
            case "1080":
                return new int[]{1080, 1920};
            case "1440":
                return new int[]{1440, 2560};
            case "2160":
                return new int[]{2160, 3840};
            case "4320":
                return new int[]{4320, 7680};
            case "auto":
            default:
                return new int[]{deviceShort, deviceLong};
        }
    }

    private int[] getRequiredDimensions() {
        int[] normalized = getRequiredShortLong();
        int shortSide = normalized[0];
        int longSide = normalized[1];

        if ("landscape".equals(mOrientation)) {
            return new int[]{longSide, shortSide};
        }
        if ("square".equals(mOrientation)) {
            return new int[]{shortSide, shortSide};
        }
        if ("portrait".equals(mOrientation)) {
            return new int[]{shortSide, longSide};
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (metrics.widthPixels >= metrics.heightPixels) {
            return new int[]{longSide, shortSide};
        }
        return new int[]{shortSide, longSide};
    }

    private int getPreferredDownloadWidth(int sourceWidth) {
        int[] required = getRequiredDimensions();
        int requested = Math.max(1080, required[0]);
        if (sourceWidth > 0) {
            requested = Math.min(sourceWidth, requested);
        }
        return Math.min(7680, requested);
    }

    private String nextSearchQuery() {
        List<String> categories = mCategories;
        if (categories == null || categories.isEmpty()) {
            return applyToneToQuery("nature");
        }

        int index;
        if ("random".equals(mCategoryStrategy) && categories.size() > 1) {
            int last = mLastCategoryIndex;
            if (last >= 0 && last < categories.size()) {
                // Avoid immediately repeating the last selected topic.
                int choice = mRandom.nextInt(categories.size() - 1);
                index = choice >= last ? choice + 1 : choice;
            } else {
                index = mRandom.nextInt(categories.size());
            }
        } else {
            index = Math.floorMod(mCategoryIndex++, categories.size());
            if (mCategoryIndex < 0) mCategoryIndex = 0;
        }
        mLastCategoryIndex = index;
        if (mCachePreferences != null) {
            mCachePreferences.edit()
                    .putInt("category_cursor", mCategoryIndex)
                    .putInt("last_category_index", index).apply();
        }
        String category = categories.get(index);
        return applyToneToQuery(categoryQuery(category));
    }

    private String categoryQuery(String category) {
        if (category == null) return "nature";

        switch (category) {
            case "amoled":
                return "AMOLED pure black wallpaper";
            case "pure_black":
                return "pure black dark wallpaper";
            case "oled":
                return "OLED black wallpaper";
            case "dark_minimal":
                return "dark minimal wallpaper";
            case "dark_abstract":
                return "dark abstract wallpaper";
            case "night_nature":
                return "night nature landscape";
            case "night_cities":
                return "night city skyline";
            case "cyberpunk_cities":
                return "cyberpunk futuristic city";
            case "cosmic_abstract":
                return "cosmic space abstract";
            case "milky_way":
                return "Milky Way night sky";
            case "ai":
                return "artificial intelligence technology";
            case "jdm":
                return "JDM cars";
            case "quranic_art":
                return "Quran Islamic art";
            case "islamic":
                return "Islamic art architecture";
            case "islamic_architecture":
                return "Islamic mosque architecture";
            case "islamic_geometry":
                return "Islamic geometric art";
            case "islamic_patterns":
                return "Islamic geometric patterns";
            case "kaaba":
                return "Kaaba Mecca";
            case "mecca":
                return "Mecca mosque";
            case "medina":
                return "Medina mosque";
            case "ramadan":
                return "Ramadan Islamic";
            case "eid":
                return "Eid Islamic";
            case "middle_east":
                return "Middle East landscape architecture";
            case "abstract_3d":
                return "3D abstract art";
            case "art_3d":
                return "3D digital art";
            case "scifi_gaming":
                return "science fiction gaming";
            default:
                return category.replace('_', ' ');
        }
    }

    private String applyToneToQuery(String query) {
        switch (mTone) {
            case "dark":
                return query + " dark";
            case "amoled":
                return query + " AMOLED black dark";
            case "light":
                return query + " bright light";
            case "colorful":
                return query + " colorful vibrant";
            case "monochrome":
                return query + " monochrome black and white";
            default:
                return query;
        }
    }

    private File getLocalWallpaper() {
        File storageDir = new File(
                Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
        if (!storageDir.exists() || !storageDir.isDirectory()) {
            return null;
        }

        File[] files = storageDir.listFiles(
                file -> isSafeLocalWallpaperFile(storageDir, file));

        if (files == null || files.length == 0) {
            return null;
        }

        ArrayList<File> candidates = new ArrayList<>(Arrays.asList(files));
        Collections.shuffle(candidates, mRandom);

        File fallback = null;
        for (File file : candidates) {
            if (!localFilePassesFilters(file)) continue;
            if (fallback == null) fallback = file;

            String historyKey = localHistoryKey(file);
            if (!mNoRepeat || !historyContains(historyKey)) {
                return file;
            }
        }

        if (fallback != null && mNoRepeat) {
            forgetHistoryPrefix(PROVIDER_LOCAL_FOLDER + ":");
            return fallback;
        }

        return fallback;
    }

    private boolean hasLocalWallpapers() {
        File storageDir = new File(
                Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
        if (!storageDir.exists() || !storageDir.isDirectory()) return false;

        File[] files = storageDir.listFiles(
                file -> isSafeLocalWallpaperFile(storageDir, file));
        return files != null && files.length > 0;
    }

    private boolean isSafeLocalWallpaperFile(File storageDir, File file) {
        if (storageDir == null || file == null || !file.isFile()) {
            return false;
        }

        long length = file.length();
        if (length <= 0L || length > MAX_LOCAL_WALLPAPER_BYTES) {
            return false;
        }

        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jpg")
                && !lower.endsWith(".jpeg")
                && !lower.endsWith(".png")
                && !lower.endsWith(".webp")) {
            return false;
        }

        try {
            String root = storageDir.getCanonicalPath() + File.separator;
            String candidate = file.getCanonicalPath();
            return candidate.startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean localFilePassesFilters(File file) {
        File storageDir = new File(
                Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
        if (!isSafeLocalWallpaperFile(storageDir, file)) {
            return false;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return isSafeImageBounds(bounds.outWidth, bounds.outHeight)
                && passesDimensionFilters(bounds.outWidth, bounds.outHeight);
    }

    private Bitmap decodeLocalWallpaper(File file) {
        if (file == null) return null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            if (!isSafeImageBounds(bounds.outWidth, bounds.outHeight)
                    || !passesDimensionFilters(bounds.outWidth, bounds.outHeight)) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateDecodeSample(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Local wallpaper decode exceeded memory budget", e);
            return null;
        }
    }

    private Bitmap decodeCachedWallpaper(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L
                || file.length() > getImageDownloadLimitBytes()) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (!isSafeImageBounds(bounds.outWidth, bounds.outHeight)) {
                Log.w(TAG, "Skipping cached wallpaper with invalid dimensions");
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateDecodeSample(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Skipping cached wallpaper that exceeded bitmap memory budget", e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to decode cached wallpaper", e);
            return null;
        }
    }

    private String localHistoryKey(File file) {
        return PROVIDER_LOCAL_FOLDER + ":"
                + stableHash(file.getAbsolutePath() + ":" + file.lastModified());
    }

    private void changeWallpaper() {
        if (!ensureFeatureStillEnabled()) return;

        final int generation = mConfigGeneration.get();

        if (mWakeLock != null && !mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire(60000L);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to acquire wallpaper wake lock", e);
            }
        }

        Bitmap bitmap = null;
        String appliedHistoryKey = null;
        File consumedCacheFile = null;
        try {
            String query = nextSearchQuery();
            List<String> providerOrder = getProviderOrder(false, query);

            for (String provider : providerOrder) {
                if (PROVIDER_LOCAL_FOLDER.equals(provider)) {
                    File local = getLocalWallpaper();
                    bitmap = decodeLocalWallpaper(local);
                    if (bitmap != null) {
                        appliedHistoryKey = localHistoryKey(local);
                        noteProviderSuccess(provider);
                        break;
                    }
                    continue;
                }

                File cached = getCachedWallpaper(provider, query);
                if (cached != null) {
                    bitmap = decodeCachedWallpaper(cached);
                    if (bitmap == null) {
                        // Remove unreadable files so the next wallpaper change does
                        // not keep selecting the same broken cache entry.
                        if (!cached.delete()) {
                            Log.w(TAG, "Unable to remove invalid wallpaper cache entry");
                        }
                    }
                    if (bitmap != null) {
                        consumedCacheFile = cached;
                        appliedHistoryKey = cachedHistoryKey(cached);
                        noteProviderSuccess(provider);
                        break;
                    }
                }

                if (!shouldDownload()) {
                    continue;
                }

                WallpaperDownload download = downloadFromSingleProvider(provider, query);
                if (download != null) {
                    bitmap = download.bitmap;
                    appliedHistoryKey = "seen:" + stableHash(download.candidate.historyKey);
                    break;
                }
            }

            if (bitmap == null) {
                // If the selected topic has no cached image while offline, try
                // another *selected* topic; never fall back to an unrelated file.
                File cachedAlternative = getCachedSelectedWallpaper();
                if (cachedAlternative != null) {
                    bitmap = decodeCachedWallpaper(cachedAlternative);
                    if (bitmap != null) {
                        consumedCacheFile = cachedAlternative;
                        appliedHistoryKey = cachedHistoryKey(cachedAlternative);
                    } else if (!cachedAlternative.delete()) {
                        Log.w(TAG, "Unable to remove invalid fallback cache entry");
                    }
                }
            }

            if (bitmap != null) {
                if (generation != mConfigGeneration.get()) {
                    Log.d(TAG, "Discarding wallpaper produced for stale settings");
                    return;
                }

                if (setWallpaperFromBitmap(bitmap)) {
                    noteHistory(appliedHistoryKey);
                    if (consumedCacheFile != null && !consumedCacheFile.delete()) {
                        Log.w(TAG, "Unable to remove applied wallpaper cache entry");
                    }
                    if (mExecutor != null && !mExecutor.isShutdown()) {
                        mExecutor.execute(this::preFetchWallpapers);
                    }
                }
            } else {
                Log.w(TAG, "No wallpaper available for the current source/filter configuration");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error changing wallpaper", e);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            if (mWakeLock != null && mWakeLock.isHeld()) {
                try {
                    mWakeLock.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to release wallpaper wake lock", e);
                }
            }
        }
    }

    private WallpaperDownload downloadFromSingleProvider(String provider, String query) {
        if (!isProviderAvailable(provider) || PROVIDER_LOCAL_FOLDER.equals(provider)) {
            return null;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            WallpaperCandidate candidate = fetchCandidate(provider, query, attempt);
            if (candidate == null) break;

            Bitmap bitmap = downloadBitmap(candidate);
            if (bitmap == null) continue;

            noteProviderSuccess(provider);


            return new WallpaperDownload(candidate, bitmap);
        }

        return null;
    }

    private void enqueueWallpaperChange(String reason) {
        ExecutorService executor = mExecutor;
        if (!ensureFeatureStillEnabled() || executor == null || executor.isShutdown()) {
            Log.d(TAG, "Skipping " + reason + " wallpaper change: service unavailable");
            return;
        }

        if (!mWallpaperChangePending.compareAndSet(false, true)) {
            Log.d(TAG, "Coalescing duplicate " + reason + " wallpaper change");
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    changeWallpaper();
                } finally {
                    mWallpaperChangePending.set(false);
                }
            });
        } catch (RuntimeException e) {
            mWallpaperChangePending.set(false);
            Log.w(TAG, "Unable to enqueue " + reason + " wallpaper change", e);
        }
    }

    private void scheduleWallpaperChange() {
        if (!mEnabled || mHandler == null) return;

        if (mPendingWallpaperChange != null) {
            mHandler.removeCallbacks(mPendingWallpaperChange);
        }

        int delay = mChangeOn == 0 ? SCREEN_ON_DELAY : SCREEN_OFF_DELAY;
        mPendingWallpaperChange = () -> {
            enqueueWallpaperChange(mChangeOn == 0 ? "screen-on" : "screen-off");
            mPendingWallpaperChange = null;
        };

        mHandler.postDelayed(mPendingWallpaperChange, delay);
    }

    private boolean setWallpaperFromBitmap(Bitmap bitmap) {
        if (mWallpaperManager == null || bitmap == null) return false;

        try {
            int wallpaperId = mWallpaperManager.setBitmap(
                    bitmap, null, false, getWallpaperFlags());
            if (wallpaperId != 0) {
                Log.d(TAG, "Wallpaper applied successfully, id=" + wallpaperId);
                return true;
            }
            Log.w(TAG, "WallpaperManager rejected wallpaper without exception");
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Applying wallpaper exceeded memory budget", e);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to apply wallpaper", e);
        }
        return false;
    }

    private int getWallpaperFlags() {
        switch (mWallpaperTarget) {
            case TARGET_HOME_SCREEN:
                return WallpaperManager.FLAG_SYSTEM;
            case TARGET_BOTH:
                return WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM;
            case TARGET_LOCK_SCREEN:
            default:
                return WallpaperManager.FLAG_LOCK;
        }
    }

    private String buildCacheFileName(WallpaperCandidate candidate, String query) {
        String provider = candidate.provider.replaceAll("[^a-zA-Z0-9_-]", "_");
        String id = stableHash(candidate.historyKey);
        return currentCacheQueryPrefix(provider, query) + id
                + "_" + System.currentTimeMillis() + ".img";
    }

    private boolean saveBitmapToFile(Bitmap bitmap, File file) {
        if (bitmap == null || file == null || file.getParentFile() == null
                || file.getParentFile().getUsableSpace()
                        < MIN_FREE_DISK_BYTES + getImageDownloadLimitBytes()) {
            return false;
        }

        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        boolean written = false;
        boolean committed = false;

        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            Bitmap.CompressFormat format =
                    bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            int quality = format == Bitmap.CompressFormat.JPEG ? 92 : 100;

            if (bitmap.compress(format, quality, output)) {
                output.flush();
                output.getFD().sync();
                long encodedBytes = tempFile.length();
                written = encodedBytes > 0L && encodedBytes <= getImageDownloadLimitBytes();
                if (!written) {
                    Log.w(TAG, "Rejecting oversized or empty encoded wallpaper cache");
                }
            }
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Caching wallpaper exceeded memory budget", e);
        } catch (IOException e) {
            Log.w(TAG, "Unable to save cached wallpaper", e);
        }

        if (written && tempFile.length() + getCachedWallpaperBytes() > mCacheByteLimit) {
            Log.d(TAG, "Encoded wallpaper exceeds remaining configured cache space");
            written = false;
        }

        if (written) {
            if (file.exists() && !file.delete()) {
                Log.d(TAG, "Unable to replace old wallpaper cache entry");
            }

            committed = tempFile.renameTo(file);
            if (!committed) {
                Log.w(TAG, "Unable to commit wallpaper cache");
            }
        }

        if (!committed && tempFile.exists() && !tempFile.delete()) {
            Log.d(TAG, "Unable to delete temporary wallpaper cache");
        }

        return committed;
    }

    private int countCacheFiles() {
        File[] files = listCacheFiles();
        return files == null ? 0 : files.length;
    }

    private long getCachedWallpaperBytes() {
        File[] files = listCacheFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            total += Math.max(0L, file.length());
        }
        return total;
    }

    private File[] listCacheFiles() {
        if (mCacheDir == null) return null;
        return mCacheDir.listFiles((dir, name) ->
                name.startsWith("glymps_") && name.endsWith(".img"));
    }

    private File getCachedWallpaper(String provider, String query) {
        if (mCacheDir == null || provider == null) return null;

        String prefix = currentCacheQueryPrefix(provider, query);

        File[] files = mCacheDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".img"));
        if (files == null || files.length == 0) return null;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            String key = cachedHistoryKey(file);
            if (!mNoRepeat || key == null || !historyContains(key)) return file;
        }
        return null;
    }

    private File getCachedSelectedWallpaper() {
        File[] files = listCacheFiles();
        if (files == null || files.length == 0) return null;

        // Only accept a cache namespace for an explicitly selected provider
        // and subject. Work from an in-memory snapshot to avoid repeated
        // filesystem scans for each configured category.
        ArrayList<String> allowedPrefixes = new ArrayList<>();
        for (String provider : mProviders) {
            if (PROVIDER_LOCAL_FOLDER.equals(provider)) continue;
            for (String category : mCategories) {
                allowedPrefixes.add(currentCacheQueryPrefix(
                        provider, applyToneToQuery(categoryQuery(category))));
            }
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            String key = cachedHistoryKey(file);
            if (mNoRepeat && key != null && historyContains(key)) continue;
            for (String prefix : allowedPrefixes) {
                if (file.getName().startsWith(prefix)) return file;
            }
        }
        return null;
    }

    /**
     * The service has one download worker, so at creation time there cannot be
     * a valid in-flight temporary wallpaper. Remove partial files left behind
     * by a killed process instead of letting many restarts fill the cache.
     */
    private void clearOrphanedTemporaryFiles() {
        if (mCacheDir == null) return;
        File[] orphaned = mCacheDir.listFiles(
                (dir, name) -> name.startsWith("glymps_") && name.endsWith(".tmp"));
        if (orphaned == null) return;
        for (File file : orphaned) {
            if (file.isFile() && !file.delete()) {
                Log.w(TAG, "Unable to clear leftover wallpaper download");
            }
        }
    }

    private void cleanOldCache() {
        if (mCacheDir == null) return;

        // A process crash may leave partially downloaded/compressed temporary files.
        // Remove those after 24 hours, and remove invalid or older-than-7-days images.
        File[] all = mCacheDir.listFiles();
        long now = System.currentTimeMillis();
        if (all != null) {
            for (File file : all) {
                if (!file.isFile()) continue;
                String name = file.getName();
                long age = Math.max(0L, now - file.lastModified());
                boolean expiredTemporary = name.endsWith(".tmp")
                        && age > TEMP_CACHE_RETENTION_MS;
                boolean invalidWallpaper = name.startsWith("glymps_")
                        && name.endsWith(".img")
                        && (age > WALLPAPER_CACHE_RETENTION_MS
                            || file.length() <= 0L
                            || file.length() > getImageDownloadLimitBytes());
                if ((expiredTemporary || invalidWallpaper) && !file.delete()) {
                    Log.w(TAG, "Unable to remove expired wallpaper cache entry");
                }
            }
        }

        File[] files = listCacheFiles();
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += Math.max(0L, file.length());
        }

        int remaining = files.length;
        for (File file : files) {
            if (remaining <= mCacheSize && totalBytes <= mCacheByteLimit) {
                break;
            }

            long length = Math.max(0L, file.length());
            if (file.delete()) {
                totalBytes -= length;
                remaining--;
            }
        }
        if (remaining > mCacheSize || totalBytes > mCacheByteLimit) {
            Log.w(TAG, "Glymps cache still exceeds limits; could not delete all old files");
        }
    }

    private void clearWallpaperCache() {
        if (mCacheDir == null) return;

        File[] files = mCacheDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.delete()) {
                Log.d(TAG, "Unable to delete wallpaper cache entry: " + file.getName());
            }
        }
    }

    private void clearApiCache() {
        if (mApiCacheDir == null) return;

        File[] files = mApiCacheDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.delete()) {
                Log.d(TAG, "Unable to delete API cache entry: " + file.getName());
            }
        }
    }

    private void clearAllCaches() {
        clearWallpaperCache();
        clearApiCache();
    }

    private synchronized void loadHistory() {
        mRecentHistory.clear();
        if (mHistoryPreferences == null) return;

        String serialized = mHistoryPreferences.getString("recent", "");
        if (isBlank(serialized)) return;

        for (String item : serialized.split("\\n")) {
            if (!isBlank(item)) {
                mRecentHistory.add(item);
            }
        }
        trimHistory();
    }

    private synchronized void persistHistory() {
        if (mHistoryPreferences == null) return;

        StringBuilder serialized = new StringBuilder();
        for (String item : mRecentHistory) {
            if (serialized.length() > 0) serialized.append('\n');
            serialized.append(item);
        }

        mHistoryPreferences.edit()
                .putString("recent", serialized.toString())
                .apply();
    }

    private synchronized boolean historyContains(String key) {
        return mRecentHistory.contains(key);
    }

    private synchronized void noteHistory(String key) {
        if (!mNoRepeat || isBlank(key)) return;

        mRecentHistory.remove(key);
        mRecentHistory.add(key);
        trimHistory();
        persistHistory();
    }

    private synchronized void forgetHistoryPrefix(String prefix) {
        if (isBlank(prefix)) return;

        mRecentHistory.removeIf(item -> item.startsWith(prefix));
        persistHistory();
    }

    private synchronized void trimHistory() {
        while (mRecentHistory.size() > MAX_HISTORY_SIZE) {
            String first = mRecentHistory.iterator().next();
            mRecentHistory.remove(first);
        }
    }

    private void noteProviderSuccess(String provider) {
        mProviderUsage.put(
                provider,
                mProviderUsage.getOrDefault(provider, 0) + 1);
        mProviderCooldownUntil.remove(provider);
        if (mCooldownPreferences != null) {
            mCooldownPreferences.edit().remove(provider).apply();
        }
    }

    private ArrayList<Integer> shuffledIndexes(int size) {
        ArrayList<Integer> indexes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indexes.add(i);
        }
        Collections.shuffle(indexes, mRandom);
        return indexes;
    }

    private List<String> parseCsv(String csv) {
        ArrayList<String> result = new ArrayList<>();
        if (isBlank(csv)) return result;

        for (String item : csv.split(",")) {
            if (result.size() >= MAX_CSV_ENTRIES) break;

            String value = item.trim();
            if (!value.isEmpty()
                    && value.length() <= MAX_SETTING_TOKEN_LENGTH
                    && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> filterKnownProviders(List<String> providers) {
        ArrayList<String> result = new ArrayList<>();
        if (providers == null) return result;

        for (String provider : providers) {
            if (isKnownProvider(provider) && !result.contains(provider)) {
                result.add(provider);
            }
        }
        return result;
    }

    private boolean isKnownProvider(String provider) {
        return PROVIDER_WALLHAVEN.equals(provider)
                || PROVIDER_OPENVERSE.equals(provider)
                || PROVIDER_PICSUM.equals(provider)
                || PROVIDER_CUSTOM_URLS.equals(provider)
                || PROVIDER_LOCAL_FOLDER.equals(provider);
    }

    private List<String> parseCustomUrls(String raw) {
        ArrayList<String> result = new ArrayList<>();
        if (isBlank(raw)) return result;

        String[] entries = raw.indexOf('\n') >= 0
                ? raw.split("\\r?\\n")
                : raw.split(",(?=\\s*https?://)");

        for (String item : entries) {
            if (result.size() >= MAX_CUSTOM_URLS) break;

            String value = item.trim();
            if (value.isEmpty() || value.length() > MAX_CUSTOM_URL_LENGTH) {
                continue;
            }

            try {
                URL url = new URL(value);
                String protocol = url.getProtocol();
                if ("https".equalsIgnoreCase(protocol)
                        && (url.getPort() == -1 || url.getPort() == 443)
                        && url.getAuthority() != null
                        && !url.getAuthority().contains("@")
                        && !isBlank(url.getHost())) {
                    result.add(value);
                }
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    private String readAndMigrateCustomUrls() {
        String secure = Settings.Secure.getString(
                getContentResolver(), LOCK_GLYMPS_CUSTOM_URLS);
        if (secure != null) {
            return secure;
        }

        String legacy = Settings.System.getString(
                getContentResolver(), LOCK_GLYMPS_CUSTOM_URLS);
        String migrated = legacy == null ? "" : legacy;

        try {
            boolean stored = Settings.Secure.putString(
                    getContentResolver(), LOCK_GLYMPS_CUSTOM_URLS, migrated);
            if (stored) {
                Settings.System.putString(
                        getContentResolver(), LOCK_GLYMPS_CUSTOM_URLS, null);
            } else {
                Log.w(TAG, "Unable to migrate custom wallpaper URLs to Settings.Secure");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to migrate custom wallpaper URLs", e);
        }

        return migrated;
    }

    private String joinLines(List<String> values) {
        if (values == null || values.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('\n');
            result.append(value);
        }
        return result.toString();
    }

    private String joinCsv(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private String sanitizeChoice(
            String value,
            String defaultValue,
            String... allowedValues) {
        if (value != null) {
            for (String allowed : allowedValues) {
                if (allowed.equals(value)) {
                    return value;
                }
            }
        }
        return defaultValue;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static boolean safeEquals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) && mChangeOn == 0) {
                scheduleWallpaperChange();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action) && mChangeOn == 1) {
                scheduleWallpaperChange();
            } else if (Intent.ACTION_USER_PRESENT.equals(action) && mChangeOn == 0) {
                if (mPendingWallpaperChange != null && mHandler != null) {
                    mHandler.removeCallbacks(mPendingWallpaperChange);
                    mPendingWallpaperChange = null;
                }
            }
        }
    }

    private static final class WallpaperCandidate {
        final String provider;
        final String id;
        final String url;
        final String historyKey;
        final int width;
        final int height;
        final String downloadLocation;

        WallpaperCandidate(
                String provider,
                String id,
                String url,
                String historyKey,
                int width,
                int height,
                String downloadLocation) {
            this.provider = provider;
            this.id = id;
            this.url = url;
            this.historyKey = historyKey;
            this.width = width;
            this.height = height;
            this.downloadLocation = downloadLocation;
        }
    }

    private static final class WallpaperDownload {
        final WallpaperCandidate candidate;
        final Bitmap bitmap;

        WallpaperDownload(WallpaperCandidate candidate, Bitmap bitmap) {
            this.candidate = candidate;
            this.bitmap = bitmap;
        }
    }
}
