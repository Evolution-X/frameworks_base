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

    private static final int MAX_CACHE_SIZE = 10;
    private static final int MAX_HISTORY_SIZE = 160;
    private static final int MAX_CUSTOM_URLS = 100;
    private static final int MAX_CUSTOM_URL_LENGTH = 4096;
    private static final int MAX_API_KEY_LENGTH = 512;
    private static final int MAX_CSV_ENTRIES = 256;
    private static final int MAX_SETTING_TOKEN_LENGTH = 96;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_SOURCE_DIMENSION = 16384;
    private static final long MAX_SOURCE_PIXELS = 120_000_000L;
    private static final long MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = 160L * 1024L * 1024L;
    private static final int MAX_JSON_RESPONSE_CHARS = 2 * 1024 * 1024;
    private static final long MAX_JSON_CACHE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_API_CACHE_FILES = 128;
    private static final long MAX_API_CACHE_BYTES = 32L * 1024L * 1024L;
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
    private static final long PIXABAY_API_CACHE_TTL = 24L * 60L * 60L * 1000L;
    private static final String USER_AGENT = "EvolutionX-LockGlymps/2.0";

    private static final String LOCK_GLYMPS_ENABLED = "lock_glymps_enabled";
    private static final String LOCK_GLYMPS_LEGACY_SOURCE = "lock_glymps_source";
    private static final String LOCK_GLYMPS_PROVIDERS = "lock_glymps_providers";
    private static final String LOCK_GLYMPS_CATEGORIES = "lock_glymps_categories";
    private static final String LOCK_GLYMPS_PROVIDER_STRATEGY = "lock_glymps_provider_strategy";
    private static final String LOCK_GLYMPS_MIN_RESOLUTION = "lock_glymps_min_resolution";
    private static final String LOCK_GLYMPS_ORIENTATION = "lock_glymps_orientation";
    private static final String LOCK_GLYMPS_TONE = "lock_glymps_tone";
    private static final String LOCK_GLYMPS_ORDER = "lock_glymps_order";
    private static final String LOCK_GLYMPS_SFW_ONLY = "lock_glymps_sfw_only";
    private static final String LOCK_GLYMPS_NO_REPEAT = "lock_glymps_no_repeat";
    private static final String LOCK_GLYMPS_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String LOCK_GLYMPS_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String LOCK_GLYMPS_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String LOCK_GLYMPS_CHANGE_ON = "lock_glymps_change_on";
    private static final String LOCK_GLYMPS_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String LOCK_GLYMPS_WALLPAPER_TARGET = "lock_glymps_wallpaper_target";

    private static final String LOCK_GLYMPS_PEXELS_API_KEY = "lock_glymps_pexels_api_key";
    private static final String LOCK_GLYMPS_UNSPLASH_API_KEY = "lock_glymps_unsplash_api_key";
    private static final String LOCK_GLYMPS_PIXABAY_API_KEY = "lock_glymps_pixabay_api_key";

    private static final String PROVIDER_WALLHAVEN = "wallhaven";
    private static final String PROVIDER_OPENVERSE = "openverse";
    private static final String PROVIDER_PICSUM = "picsum";
    private static final String PROVIDER_PEXELS = "pexels";
    private static final String PROVIDER_UNSPLASH = "unsplash";
    private static final String PROVIDER_PIXABAY = "pixabay";
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

    private volatile boolean mEnabled;
    private volatile boolean mWifiOnly = true;
    private volatile boolean mSfwOnly = true;
    private volatile boolean mNoRepeat = true;
    private volatile int mCacheSize = 5;
    private volatile int mChangeOn = 1;
    private volatile long mTimerInterval = DEFAULT_TIMER_INTERVAL;
    private volatile int mWallpaperTarget = TARGET_LOCK_SCREEN;

    private volatile String mProviderStrategy = "balanced";
    private volatile String mMinResolution = "auto";
    private volatile String mOrientation = "portrait";
    private volatile String mTone = "any";
    private volatile String mOrder = "latest";
    private volatile String mPexelsApiKey = "";
    private volatile String mUnsplashApiKey = "";
    private volatile String mPixabayApiKey = "";

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
        loadHistory();

        mCacheDir = new File(getCacheDir(), "lock_glymps");
        if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create wallpaper cache directory");
        }

        mApiCacheDir = new File(getCacheDir(), "lock_glymps_api");
        if (!mApiCacheDir.exists() && !mApiCacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create provider response cache directory");
        }

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    TAG + ":WallpaperChange");
        }

        loadSettings();

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
            String oldPexelsKey = mPexelsApiKey;
            String oldUnsplashKey = mUnsplashApiKey;
            String oldPixabayKey = mPixabayApiKey;
            loadSettings();
            resetProviderCooldownsAfterConfigChange(
                    oldProviders,
                    oldPexelsKey,
                    oldUnsplashKey,
                    oldPixabayKey);

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
                    clearAllCaches();
                    if (mEnabled) {
                        preFetchWallpapers();
                    }
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
            mSfwOnly = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_SFW_ONLY, 1) == 1;
            mNoRepeat = Settings.System.getInt(
                    getContentResolver(), LOCK_GLYMPS_NO_REPEAT, 1) == 1;

            mCacheSize = clamp(
                    Settings.System.getInt(
                            getContentResolver(), LOCK_GLYMPS_CACHE_SIZE, 5),
                    1,
                    MAX_CACHE_SIZE);
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

            mPexelsApiKey = sanitizeApiKey(Settings.Secure.getString(
                    getContentResolver(), LOCK_GLYMPS_PEXELS_API_KEY));
            mUnsplashApiKey = sanitizeApiKey(Settings.Secure.getString(
                    getContentResolver(), LOCK_GLYMPS_UNSPLASH_API_KEY));
            mPixabayApiKey = sanitizeApiKey(Settings.Secure.getString(
                    getContentResolver(), LOCK_GLYMPS_PIXABAY_API_KEY));

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

    private void resetProviderCooldownsAfterConfigChange(
            List<String> oldProviders,
            String oldPexelsKey,
            String oldUnsplashKey,
            String oldPixabayKey) {
        if (!safeEquals(oldPexelsKey, mPexelsApiKey)) {
            mProviderCooldownUntil.remove(PROVIDER_PEXELS);
            scheduleProviderApiCacheClear(PROVIDER_PEXELS);
        }
        if (!safeEquals(oldUnsplashKey, mUnsplashApiKey)) {
            mProviderCooldownUntil.remove(PROVIDER_UNSPLASH);
            scheduleProviderApiCacheClear(PROVIDER_UNSPLASH);
        }
        if (!safeEquals(oldPixabayKey, mPixabayApiKey)) {
            mProviderCooldownUntil.remove(PROVIDER_PIXABAY);
            scheduleProviderApiCacheClear(PROVIDER_PIXABAY);
        }

        for (String provider : mProviders) {
            if (oldProviders == null || !oldProviders.contains(provider)) {
                mProviderCooldownUntil.remove(provider);
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

    private boolean shouldDownload() {
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

        if (!mWifiOnly) {
            return true;
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return true;
        }

        // The default network can be a VPN even when its underlying transport is
        // Wi-Fi. In that case, keep honoring the user's Wi-Fi-only intent rather
        // than disabling downloads just because TRANSPORT_VPN is on top.
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities candidateCaps = cm.getNetworkCapabilities(candidate);
                if (candidateCaps != null
                        && candidateCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && candidateCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && candidateCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void preFetchWallpapers() {
        if (!ensureFeatureStillEnabled() || mCacheDir == null || !shouldDownload()) {
            return;
        }

        if (!hasOnlineProvider()) {
            return;
        }

        final int generation = mConfigGeneration.get();
        int cacheCount = countCacheFiles();
        int attempts = 0;
        int maxAttempts = Math.max(4, mCacheSize * 4);

        while (cacheCount < mCacheSize
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

            String fileName = buildCacheFileName(download.candidate);
            File cacheFile = new File(mCacheDir, fileName);

            if (saveBitmapToFile(download.bitmap, cacheFile)) {
                cacheCount++;
                Log.d(TAG, "Cached " + download.candidate.provider
                        + " wallpaper as " + cacheFile.getName());
            }

            download.bitmap.recycle();
        }

        cleanOldCache();
    }

    private WallpaperDownload downloadFromProviderPool(String query, boolean onlineOnly) {
        List<String> providers = getProviderOrder(onlineOnly);

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

                noteHistory(candidate.historyKey);
                noteProviderSuccess(provider);

                if (PROVIDER_UNSPLASH.equals(provider)
                        && !isBlank(candidate.downloadLocation)) {
                    triggerUnsplashDownload(candidate.downloadLocation);
                }

                return new WallpaperDownload(candidate, bitmap);
            }
        }

        return null;
    }

    private List<String> getProviderOrder(boolean onlineOnly) {
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
            } else if (hasCachedWallpaper(provider) || isProviderAvailable(provider)) {
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
            case PROVIDER_PICSUM:
                return true;
            // Pexels prohibits using its API to provide a standalone wallpaper app.
            // Unsplash requires hotlinking/attribution unavailable for device wallpapers.
            // Keep these legacy identifiers recognized, but do not issue requests.
            case PROVIDER_PEXELS:
            case PROVIDER_UNSPLASH:
                return false;
            case PROVIDER_PEXELS:
                return !isBlank(mPexelsApiKey);
            case PROVIDER_UNSPLASH:
                return !isBlank(mUnsplashApiKey);
            case PROVIDER_PIXABAY:
                return !isBlank(mPixabayApiKey);
            case PROVIDER_CUSTOM_URLS:
                return !mCustomUrls.isEmpty();
            case PROVIDER_LOCAL_FOLDER:
                return hasLocalWallpapers();
            default:
                return false;
        }
    }

    private boolean hasCachedWallpaper(String provider) {
        if (mCacheDir == null || isBlank(provider)) return false;

        String prefix = "glymps_"
                + provider.replaceAll("[^a-zA-Z0-9_-]", "_")
                + "_";
        File[] files = mCacheDir.listFiles(
                (dir, name) -> name.startsWith(prefix) && !name.endsWith(".tmp"));
        return files != null && files.length > 0;
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
                case PROVIDER_PEXELS:
                    return fetchPexelsCandidate(query, attempt);
                case PROVIDER_UNSPLASH:
                    return fetchUnsplashCandidate(query, attempt);
                case PROVIDER_PIXABAY:
                    return fetchPixabayCandidate(query, attempt);
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
                .append("&page_size=30")
                .append("&page=").append(1 + Math.min(4, Math.max(0, attempt)));

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
                .append("&purity=").append(mSfwOnly ? "100" : "110")
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

    private WallpaperCandidate fetchPexelsCandidate(String query, int attempt) {
        StringBuilder url = new StringBuilder("https://api.pexels.com/v1/search")
                .append("?query=").append(Uri.encode(query))
                .append("&per_page=40");

        if ("portrait".equals(mOrientation)
                || "landscape".equals(mOrientation)
                || "square".equals(mOrientation)) {
            url.append("&orientation=").append(mOrientation);
        }

        if ("2160".equals(mMinResolution) || "4320".equals(mMinResolution)) {
            url.append("&size=large");
        } else if ("1440".equals(mMinResolution)) {
            url.append("&size=medium");
        } else if ("1080".equals(mMinResolution)) {
            url.append("&size=small");
        }

        if ("dark".equals(mTone) || "amoled".equals(mTone)) {
            url.append("&color=black");
        } else if ("monochrome".equals(mTone)) {
            url.append("&color=gray");
        }

        if ("random".equals(mOrder) || attempt > 0) {
            url.append("&page=").append(1 + mRandom.nextInt(4));
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", mPexelsApiKey);

        JSONObject root = requestJson(
                PROVIDER_PEXELS, url.toString(), headers);
        if (root == null) return null;

        JSONArray photos = root.optJSONArray("photos");
        if (photos == null || photos.length() == 0) return null;

        for (int index : shuffledIndexes(photos.length())) {
            JSONObject item = photos.optJSONObject(index);
            if (item == null) continue;

            String id = String.valueOf(item.optLong("id", 0L));
            int width = item.optInt("width", 0);
            int height = item.optInt("height", 0);
            JSONObject src = item.optJSONObject("src");
            if (src == null) continue;

            String imageUrl = firstNonBlank(
                    src.optString("original"),
                    src.optString("large2x"),
                    src.optString("portrait"),
                    src.optString("large"));

            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_PEXELS,
                    id,
                    imageUrl,
                    PROVIDER_PEXELS + ":" + id,
                    width,
                    height,
                    null);

            if (candidateAllowed(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private WallpaperCandidate fetchUnsplashCandidate(String query, int attempt) {
        String orderBy = "latest".equals(mOrder) ? "latest" : "relevant";

        StringBuilder url = new StringBuilder("https://api.unsplash.com/search/photos")
                .append("?query=").append(Uri.encode(query))
                .append("&per_page=30")
                .append("&content_filter=").append(mSfwOnly ? "high" : "low")
                .append("&order_by=").append(orderBy);

        if ("portrait".equals(mOrientation) || "landscape".equals(mOrientation)) {
            url.append("&orientation=").append(mOrientation);
        } else if ("square".equals(mOrientation)) {
            url.append("&orientation=squarish");
        }

        if ("dark".equals(mTone) || "amoled".equals(mTone)) {
            url.append("&color=black");
        } else if ("monochrome".equals(mTone)) {
            url.append("&color=black_and_white");
        }

        if ("random".equals(mOrder) || attempt > 0) {
            url.append("&page=").append(1 + mRandom.nextInt(4));
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Client-ID " + mUnsplashApiKey);

        JSONObject root = requestJson(
                PROVIDER_UNSPLASH, url.toString(), headers);
        if (root == null) return null;

        JSONArray results = root.optJSONArray("results");
        if (results == null || results.length() == 0) return null;

        for (int index : shuffledIndexes(results.length())) {
            JSONObject item = results.optJSONObject(index);
            if (item == null) continue;

            String id = item.optString("id");
            int width = item.optInt("width", 0);
            int height = item.optInt("height", 0);

            JSONObject urls = item.optJSONObject("urls");
            if (urls == null) continue;

            String imageUrl = firstNonBlank(
                    urls.optString("raw"),
                    urls.optString("full"),
                    urls.optString("regular"));
            if (!isBlank(imageUrl) && imageUrl.contains("images.unsplash.com")) {
                int requestedWidth = getPreferredDownloadWidth(width);
                if (requestedWidth > 0 && imageUrl.contains("?")) {
                    imageUrl += "&w=" + requestedWidth + "&q=90&fm=jpg&fit=max";
                }
            }

            JSONObject links = item.optJSONObject("links");
            String downloadLocation = links == null
                    ? null : links.optString("download_location");

            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_UNSPLASH,
                    id,
                    imageUrl,
                    PROVIDER_UNSPLASH + ":" + id,
                    width,
                    height,
                    downloadLocation);

            if (candidateAllowed(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private WallpaperCandidate fetchPixabayCandidate(String query, int attempt) {
        StringBuilder url = new StringBuilder("https://pixabay.com/api/")
                .append("?key=").append(Uri.encode(mPixabayApiKey))
                .append("&q=").append(Uri.encode(query))
                .append("&image_type=photo")
                .append("&per_page=40")
                .append("&safesearch=").append(mSfwOnly ? "true" : "false")
                .append("&order=").append("latest".equals(mOrder) ? "latest" : "popular");

        if ("portrait".equals(mOrientation)) {
            url.append("&orientation=vertical");
        } else if ("landscape".equals(mOrientation)) {
            url.append("&orientation=horizontal");
        }

        int[] minimum = getRequiredDimensions();
        if (minimum[0] > 0 && minimum[1] > 0) {
            url.append("&min_width=").append(minimum[0])
                    .append("&min_height=").append(minimum[1]);
        }

        if ("dark".equals(mTone) || "amoled".equals(mTone)) {
            url.append("&colors=black");
        } else if ("monochrome".equals(mTone)) {
            url.append("&colors=grayscale");
        }

        if ("random".equals(mOrder) || attempt > 0) {
            url.append("&page=").append(1 + mRandom.nextInt(4));
        }

        JSONObject root = requestJson(
                PROVIDER_PIXABAY, url.toString(), Collections.emptyMap());
        if (root == null) return null;

        JSONArray hits = root.optJSONArray("hits");
        if (hits == null || hits.length() == 0) return null;

        for (int index : shuffledIndexes(hits.length())) {
            JSONObject item = hits.optJSONObject(index);
            if (item == null) continue;

            String id = String.valueOf(item.optLong("id", 0L));
            int width = item.optInt("imageWidth", 0);
            int height = item.optInt("imageHeight", 0);
            String imageUrl = firstNonBlank(
                    item.optString("imageURL"),
                    item.optString("fullHDURL"),
                    item.optString("largeImageURL"),
                    item.optString("webformatURL"));

            WallpaperCandidate candidate = new WallpaperCandidate(
                    PROVIDER_PIXABAY,
                    id,
                    imageUrl,
                    PROVIDER_PIXABAY + ":" + id,
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

            stream = new BufferedInputStream(connection.getInputStream());
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.length() + line.length() > MAX_JSON_RESPONSE_CHARS) {
                    Log.w(TAG, provider + " API response exceeded safety limit");
                    coolDownProvider(provider, DEFAULT_PROVIDER_COOLDOWN);
                    return null;
                }
                body.append(line);
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

        long ttl = PROVIDER_PIXABAY.equals(provider)
                ? PIXABAY_API_CACHE_TTL : DEFAULT_API_CACHE_TTL;
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
            long maxAge = file.getName().startsWith(PROVIDER_PIXABAY + "_")
                    ? PIXABAY_API_CACHE_TTL : DEFAULT_API_CACHE_TTL;
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

    private String stableHash(String value) {
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
        mProviderCooldownUntil.put(
                provider,
                System.currentTimeMillis() + Math.max(60000L, durationMs));
    }

    private void triggerUnsplashDownload(String downloadLocation) {
        if (isBlank(downloadLocation) || isBlank(mUnsplashApiKey)) {
            return;
        }

        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            URL url = new URL(downloadLocation);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !"api.unsplash.com".equalsIgnoreCase(url.getHost())) {
                Log.w(TAG, "Ignoring unexpected Unsplash download endpoint");
                return;
            }

            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization",
                    "Client-ID " + mUnsplashApiKey);

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                stream = connection.getInputStream();
            } else {
                handleProviderHttpError(PROVIDER_UNSPLASH, connection, code);
            }
        } catch (IOException e) {
            Log.d(TAG, "Unable to register Unsplash download", e);
        } finally {
            closeQuietly(stream);
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection openSafeImageConnection(String sourceUrl) throws IOException {
        URL current = new URL(sourceUrl);

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!isSafeRemoteUrl(current)) {
                throw new IOException("Blocked unsafe wallpaper URL");
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
        if (!"https".equalsIgnoreCase(protocol)
                && !"http".equalsIgnoreCase(protocol)) {
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
        if (candidate == null || isBlank(candidate.url)) return null;

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
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                Log.w(TAG, "Skipping oversized wallpaper: " + contentLength + " bytes");
                return null;
            }

            temporaryFile = File.createTempFile("glymps_", ".tmp", mCacheDir);
            input = new BufferedInputStream(connection.getInputStream());
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
                if (total > MAX_DOWNLOAD_BYTES) {
                    Log.w(TAG, "Wallpaper exceeded download safety limit");
                    return null;
                }

                if (mWifiOnly && total >= nextNetworkCheck) {
                    if (!shouldDownload()) {
                        Log.d(TAG, "Stopping wallpaper download after Wi-Fi became unavailable");
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

    private boolean isSafeImageBounds(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return false;
        return (long) width * (long) height <= MAX_SOURCE_PIXELS;
    }

    private int calculateDecodeSample(int width, int height) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetWidth = Math.max(1440, metrics.widthPixels);
        int targetHeight = Math.max(2560, metrics.heightPixels);

        int sample = 1;
        while (sample <= 64
                && width / (sample * 2) >= targetWidth
                && height / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private boolean candidateAllowed(WallpaperCandidate candidate) {
        if (candidate == null || isBlank(candidate.url) || isBlank(candidate.historyKey)) {
            return false;
        }

        if (mNoRepeat && historyContains(candidate.historyKey)) {
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

        int index = Math.floorMod(mCategoryIndex++, categories.size());
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
        if (length <= 0L || length > MAX_DOWNLOAD_BYTES) {
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
        try {
            String query = nextSearchQuery();
            List<String> providerOrder = getProviderOrder(false);

            for (String provider : providerOrder) {
                if (PROVIDER_LOCAL_FOLDER.equals(provider)) {
                    File local = getLocalWallpaper();
                    bitmap = decodeLocalWallpaper(local);
                    if (bitmap != null) {
                        noteHistory(localHistoryKey(local));
                        noteProviderSuccess(provider);
                        break;
                    }
                    continue;
                }

                File cached = getCachedWallpaper(provider);
                if (cached != null) {
                    bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
                    if (!cached.delete()) {
                        Log.d(TAG, "Unable to delete consumed cache file: " + cached.getName());
                    }
                    if (bitmap != null) {
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
                    break;
                }
            }

            // Generic cache fallback is useful when a provider is temporarily offline.
            if (bitmap == null) {
                File fallback = getAnyCachedWallpaper();
                if (fallback != null) {
                    bitmap = BitmapFactory.decodeFile(fallback.getAbsolutePath());
                    if (!fallback.delete()) {
                        Log.d(TAG, "Unable to delete fallback cache file: " + fallback.getName());
                    }
                }
            }

            if (bitmap != null) {
                if (generation != mConfigGeneration.get()) {
                    Log.d(TAG, "Discarding wallpaper produced for stale settings");
                    return;
                }

                setWallpaperFromBitmap(bitmap);
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(this::preFetchWallpapers);
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

            noteHistory(candidate.historyKey);
            noteProviderSuccess(provider);

            if (PROVIDER_UNSPLASH.equals(provider)
                    && !isBlank(candidate.downloadLocation)) {
                triggerUnsplashDownload(candidate.downloadLocation);
            }

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

    private void setWallpaperFromBitmap(Bitmap bitmap) {
        if (mWallpaperManager == null || bitmap == null) return;

        try {
            int wallpaperId = mWallpaperManager.setBitmap(
                    bitmap,
                    null,
                    false,
                    getWallpaperFlags());
            Log.d(TAG, "Wallpaper applied successfully, id=" + wallpaperId);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Applying wallpaper exceeded memory budget", e);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to apply wallpaper", e);
        }
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

    private String buildCacheFileName(WallpaperCandidate candidate) {
        String provider = candidate.provider.replaceAll("[^a-zA-Z0-9_-]", "_");
        String id = stableHash(candidate.historyKey);
        return "glymps_" + provider + "_" + id + "_" + System.currentTimeMillis() + ".img";
    }

    private boolean saveBitmapToFile(Bitmap bitmap, File file) {
        if (bitmap == null || file == null || file.getParentFile() == null) {
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
                written = encodedBytes > 0L && encodedBytes <= MAX_DOWNLOAD_BYTES;
                if (!written) {
                    Log.w(TAG, "Rejecting oversized or empty encoded wallpaper cache");
                }
            }
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Caching wallpaper exceeded memory budget", e);
        } catch (IOException e) {
            Log.w(TAG, "Unable to save cached wallpaper", e);
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

    private File[] listCacheFiles() {
        if (mCacheDir == null) return null;
        return mCacheDir.listFiles((dir, name) ->
                name.startsWith("glymps_") && !name.endsWith(".tmp"));
    }

    private File getCachedWallpaper(String provider) {
        if (mCacheDir == null || provider == null) return null;

        String prefix = "glymps_"
                + provider.replaceAll("[^a-zA-Z0-9_-]", "_")
                + "_";

        File[] files = mCacheDir.listFiles((dir, name) -> name.startsWith(prefix));
        if (files == null || files.length == 0) return null;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return files[0];
    }

    private File getAnyCachedWallpaper() {
        File[] files = listCacheFiles();
        if (files == null || files.length == 0) return null;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return files[0];
    }

    private void cleanOldCache() {
        File[] files = listCacheFiles();
        if (files == null || files.length == 0) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += Math.max(0L, file.length());
        }

        int remaining = files.length;
        for (File file : files) {
            if (remaining <= mCacheSize && totalBytes <= MAX_CACHE_BYTES) {
                break;
            }

            long length = Math.max(0L, file.length());
            if (file.delete()) {
                totalBytes -= length;
                remaining--;
            }
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

    private void scheduleProviderApiCacheClear(String provider) {
        ExecutorService executor = mExecutor;
        if (executor == null || executor.isShutdown() || isBlank(provider)) {
            return;
        }

        try {
            executor.execute(() -> clearProviderApiCache(provider));
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to schedule provider cache invalidation", e);
        }
    }

    private void clearProviderApiCache(String provider) {
        if (mApiCacheDir == null || isBlank(provider)) return;

        String prefix = provider + "_";
        File[] files = mApiCacheDir.listFiles(
                (dir, name) -> name != null && name.startsWith(prefix));
        if (files == null) return;

        for (File file : files) {
            if (!file.delete()) {
                Log.d(TAG, "Unable to delete stale " + provider + " API cache entry");
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
                || PROVIDER_PIXABAY.equals(provider)
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
                if (("https".equalsIgnoreCase(protocol)
                        || "http".equalsIgnoreCase(protocol))
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

    private String sanitizeApiKey(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= MAX_API_KEY_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_API_KEY_LENGTH);
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
