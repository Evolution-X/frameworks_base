/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps;

import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.lockglymps.providers.CustomUrlsProvider;
import com.android.systemui.lockglymps.providers.LocalFolderProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glymps engine. Preserves every v1 behavior (screen on/off triggers with
 * 800/450ms delays, USER_PRESENT cancel, timer, START_STICKY, wake lock,
 * REFRESH_SETTINGS / CLEAR_CACHE / APPLY_NOW commands) and adds:
 * per-target providers, taste queries, a no-repeat bag, a fallback chain,
 * bounded decode with center-crop to the fixed 1080x1920 target, and a
 * persisted runtime state file.
 */
public class LockGlympsService extends Service {
    private static final String TAG = "LockGlympsService";
    private static final int SCREEN_ON_DELAY = 800;
    private static final int SCREEN_OFF_DELAY = 450;
    private static final int MAX_CACHE_SIZE = 10;
    private static final int BAG_SIZE = 100;
    /** Soft disk budget for the wallpaper cache, in megabytes. */
    private static final int CACHE_QUOTA_MB = 150;
    private static final String CMD_SAVE_CURRENT = "SAVE_CURRENT";
    private static final String CMD_BAN_CURRENT = "BAN_CURRENT";
    private static final String LAST_IMAGE = "last_wallpaper.webp";

    // Existing keys (v1)
    private static final String KEY_ENABLED = "lock_glymps_enabled";
    private static final String KEY_SOURCE = "lock_glymps_source";
    private static final String KEY_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String KEY_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String KEY_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String KEY_CHANGE_ON = "lock_glymps_change_on";
    private static final String KEY_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String KEY_TARGET = "lock_glymps_wallpaper_target";
    // New keys
    private static final String KEY_PROVIDER_LOCK = "lock_glymps_provider_lock";
    private static final String KEY_PROVIDER_HOME = "lock_glymps_provider_home";
    private static final String KEY_TASTE = "lock_glymps_taste";
    private static final String KEY_CURRENT = "lock_glymps_current";

    private static final int TARGET_LOCK_SCREEN = 0;
    private static final int TARGET_HOME_SCREEN = 1;
    private static final int TARGET_BOTH = 2;

    private WallpaperManager mWallpaperManager;
    private ExecutorService mExecutor;
    private Handler mHandler;
    private Random mRandom;
    private ScreenStateReceiver mScreenReceiver;
    private PowerManager.WakeLock mWakeLock;
    private Runnable mTimerRunnable;
    private Runnable mPendingWallpaperChange;

    // Settings are read on the main thread and consumed on the executor thread.
    private volatile boolean mEnabled = false;
    private volatile boolean mWifiOnly = true;
    private volatile int mCacheSize = 5;
    private volatile int mChangeOn = 1;
    private volatile long mTimerInterval = 1800000L;
    private volatile int mWallpaperTarget = TARGET_LOCK_SCREEN;

    private volatile String mProviderLock = GlympsProviders.DEFAULT_ID;
    private volatile String mProviderHome = GlympsProviders.DEFAULT_ID;
    private volatile JSONObject mTaste = new JSONObject();
    private File mCacheDir;
    private File mStateFile;
    private File mLastFile;

    @Override
    public void onCreate() {
        super.onCreate();
        mWallpaperManager = WallpaperManager.getInstance(this);
        mExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mRandom = new Random();
        mCacheDir = new File(getCacheDir(), "lock_glymps");
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
        mStateFile = new File(getFilesDir(), "glymps_state.json");
        mLastFile = new File(getFilesDir(), LAST_IMAGE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WallpaperChange");
        }
        loadSettings();
        registerScreenReceiver();
        if (mEnabled) {
            mExecutor.execute(this::preFetch);
            if (mChangeOn == 2) {
                startTimer();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("REFRESH_SETTINGS".equals(action)) {
                int oldChangeOn = mChangeOn;
                loadSettings();
                if (oldChangeOn != mChangeOn) {
                    registerScreenReceiver();
                    if (oldChangeOn == 2 && mChangeOn != 2) stopTimer();
                    else if (oldChangeOn != 2 && mChangeOn == 2) startTimer();
                }
                if (mChangeOn == 2) {
                    stopTimer();
                    startTimer();
                }
                if (mEnabled) {
                    mExecutor.execute(this::preFetch);
                }
            } else if ("CLEAR_CACHE".equals(action)) {
                mExecutor.execute(this::clearCache);
            } else if ("APPLY_NOW".equals(action)) {
                if (mEnabled && mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(() -> changeWallpaper(mWallpaperTarget));
                }
            } else if (CMD_SAVE_CURRENT.equals(action)) {
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(this::saveCurrentToGallery);
                }
            } else if (CMD_BAN_CURRENT.equals(action)) {
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(this::banCurrent);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTimer();
        if (mScreenReceiver != null) {
            try {
                unregisterReceiver(mScreenReceiver);
            } catch (Exception e) {
                // ignored
            }
            mScreenReceiver = null;
        }
        if (mPendingWallpaperChange != null) {
            mHandler.removeCallbacks(mPendingWallpaperChange);
            mPendingWallpaperChange = null;
        }
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mWakeLock = null;
        mWallpaperManager = null;
        mHandler = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadSettings() {
        try {
            mEnabled = Settings.System.getInt(getContentResolver(), KEY_ENABLED, 0) == 1;
            mWifiOnly = Settings.System.getInt(getContentResolver(), KEY_WIFI_ONLY, 1) == 1;
            mCacheSize = Settings.System.getInt(getContentResolver(), KEY_CACHE_SIZE, 5);
            mChangeOn = Settings.System.getInt(getContentResolver(), KEY_CHANGE_ON, 1);
            mTimerInterval = Settings.System.getLong(getContentResolver(), KEY_TIMER_INTERVAL, 1800000L);
            mWallpaperTarget = Settings.System.getInt(getContentResolver(), KEY_TARGET, TARGET_LOCK_SCREEN);

            mProviderLock = migrateProvider(
                    Settings.System.getString(getContentResolver(), KEY_PROVIDER_LOCK),
                    Settings.System.getInt(getContentResolver(), KEY_SOURCE, 0));
            mProviderHome = migrateProvider(
                    Settings.System.getString(getContentResolver(), KEY_PROVIDER_HOME),
                    Settings.System.getInt(getContentResolver(), KEY_SOURCE, 0));

            String taste = Settings.System.getString(getContentResolver(), KEY_TASTE);
            mTaste = taste != null ? new JSONObject(taste) : new JSONObject();

            String urls = Settings.System.getString(getContentResolver(), KEY_CUSTOM_URLS);
            List<String> list = new ArrayList<>();
            if (urls != null) {
                for (String u : urls.split(",")) {
                    if (!u.trim().isEmpty()) {
                        list.add(u.trim());
                    }
                }
            }
            Object custom = GlympsProviders.get(GlympsProviders.CUSTOM);
            if (custom instanceof CustomUrlsProvider) {
                ((CustomUrlsProvider) custom).setUrls(list);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
        }
    }

    /** Maps v1 source (0 picsum / 1 custom / 2 local) when no provider was chosen yet. */
    private static String migrateProvider(String stored, int oldSource) {
        if (stored != null && GlympsProviders.isKnown(stored)) {
            return stored;
        }
        if (oldSource == 1) return GlympsProviders.CUSTOM;
        if (oldSource == 2) return GlympsProviders.LOCAL;
        return GlympsProviders.DEFAULT_ID;
    }

    private void registerScreenReceiver() {
        if (mScreenReceiver != null) {
            try {
                unregisterReceiver(mScreenReceiver);
            } catch (Exception e) {
                // ignored
            }
            mScreenReceiver = null;
        }
        if (mChangeOn == 0 || mChangeOn == 1) {
            mScreenReceiver = new ScreenStateReceiver();
            IntentFilter filter = new IntentFilter();
            if (mChangeOn == 0) {
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_USER_PRESENT);
            } else {
                filter.addAction(Intent.ACTION_SCREEN_OFF);
            }
            registerReceiver(mScreenReceiver, filter);
        }
    }

    private void startTimer() {
        if (mHandler == null || mTimerInterval <= 0) return;
        stopTimer();
        mTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(() -> changeWallpaper(mWallpaperTarget));
                }
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

    private boolean shouldDownload() {
        if (!mWifiOnly) return true;
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo network = cm.getActiveNetworkInfo();
        return network != null && network.isConnected()
                && network.getType() == android.net.ConnectivityManager.TYPE_WIFI;
    }

    /**
     * Fills the wallpaper cache like v1: each entry is a PNG already cropped to
     * the 1080x1920 target plus a JSON sidecar carrying the candidate metadata
     * (provider, attribution, license, source page) for LOCK_GLYMPS_CURRENT.
     */
    private void preFetch() {
        if (!shouldDownload()) return;
        // Prefetching is a convenience: never spend battery saver budget on it.
        // On-demand changes keep working, they just skip the warm pool.
        if (isPowerSaveMode()) return;
        int count = cachedImages().length;
        while (count < mCacheSize) {
            Bitmap bitmap = null;
            try {
                GlympsCandidate candidate = pickCandidate(mProviderLock, mWallpaperTarget);
                if (candidate == null || candidate.url.startsWith("file:")) break;
                bitmap = decodeBounded(candidate.url);
                if (bitmap == null) break;
                Bitmap cropped = cropToTarget(bitmap);
                // cropToTarget() may hand back the source itself when it is
                // already at the target size; recycle each bitmap exactly once.
                if (cropped != bitmap) {
                    bitmap.recycle();
                }
                bitmap = null;
                String stamp = String.valueOf(System.currentTimeMillis());
                File file = cacheFileFor(stamp);
                try {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        cropped.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out);
                    }
                } finally {
                    cropped.recycle();
                }
                writeSidecar(sidecarOf(file), candidate);
                count++;
            } catch (Exception e) {
                break;
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        }
        cleanOldCache();
    }

    /**
     * Cache entries sorted chronologically (file names embed the timestamp).
     * Both WebP (current) and PNG (pre-WebP cache) entries are picked up.
     */
    private File[] cachedImages() {
        File[] files = mCacheDir.listFiles((dir, name) -> isCacheImage(name));
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    private static boolean isCacheImage(String name) {
        return name.endsWith(".webp") || name.endsWith(".png");
    }

    private File cacheFileFor(String stamp) {
        return new File(mCacheDir, "wallpaper_" + stamp + ".webp");
    }

    private static File sidecarOf(File imageFile) {
        String name = imageFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(imageFile.getParentFile(), base + ".json");
    }

    private static void writeSidecar(File file, GlympsCandidate candidate) {
        try {
            JSONObject sidecar = new JSONObject();
            sidecar.put("provider", candidate.providerId);
            sidecar.put("externalId", candidate.externalId);
            sidecar.put("url", candidate.url);
            sidecar.put("attribution", candidate.attribution);
            sidecar.put("license", candidate.license);
            sidecar.put("sourcePage", candidate.sourcePage);
            java.nio.file.Files.write(file.toPath(),
                    sidecar.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "sidecar save", e);
        }
    }

    private static GlympsCandidate readSidecar(File pngFile) {
        try {
            File sidecar = sidecarOf(pngFile);
            if (!sidecar.exists()) {
                return null;
            }
            JSONObject sidecar = new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(sidecar.toPath()), "UTF-8"));
            return new GlympsCandidate(
                    sidecar.optString("provider"),
                    sidecar.optString("externalId"),
                    sidecar.optString("url"),
                    0, 0,
                    sidecar.optString("attribution"),
                    sidecar.optString("license"),
                    sidecar.optString("sourcePage"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Chooses a not-recently-used candidate for the given target via fallback chain. */
    private GlympsCandidate pickCandidate(String providerId, int target) {
        JSONObject state = loadState();
        List<String> chain = new ArrayList<>();
        chain.add(providerId);
        for (String alt : new String[]{GlympsProviders.WALLHAVEN, GlympsProviders.COMMONS,
                GlympsProviders.OPENVERSE, GlympsProviders.PICSUM}) {
            if (!chain.contains(alt)) chain.add(alt);
        }
        chain.add(GlympsProviders.LOCAL);
        for (String id : chain) {
            GlympsProvider provider = GlympsProviders.get(id);
            if (provider.needsConfiguration(this)) continue;
            if (provider.isNetworkSource() && !shouldDownload()) continue;
            try {
                GlympsProvider.GlympsQuery query = buildQuery(id, target);
                int page = nextPage(state, id);
                List<GlympsProvider.GlympsCandidate> candidates =
                        provider.fetch(query, page);
                List<GlympsProvider.GlympsCandidate> fresh = new ArrayList<>();
                JSONArray bag = recentBag(state, id);
                JSONArray banned = state.optJSONArray("banned");
                for (GlympsProvider.GlympsCandidate c : candidates) {
                    if (!c.fitsTarget() || contains(bag, c.externalId)) continue;
                    if (banned != null && contains(banned, id + "|" + c.externalId)) continue;
                    fresh.add(c);
                }
                if (fresh.isEmpty()) {
                    setPage(state, id, page + 1);
                    continue;
                }
                GlympsProvider.GlympsCandidate chosen =
                        fresh.get(mRandom.nextInt(fresh.size()));
                pushBag(state, id, chosen.externalId);
                saveState(state);
                return chosen;
            } catch (GlympsProvider.GlympsException e) {
                Log.w(TAG, "Provider " + id + " failed: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "Provider " + id + " error", e);
            }
        }
        return null;
    }

    private GlympsProvider.GlympsQuery buildQuery(String providerId, int target) {
        GlympsProvider.GlympsQuery query = new GlympsProvider.GlympsQuery();
        query.seed = "evoglymps-" + providerId;
        JSONObject taste = mTaste.optJSONObject(target == TARGET_HOME_SCREEN ? "home" : "lock");
        if (taste != null) {
            query.topics = taste.optString("topics");
            query.categories = taste.optString("categories");
            query.color = taste.optString("color");
            query.preset = taste.optString("preset");
        }
        JSONObject mood = dayNightOverlay();
        if (mood != null && !mood.optString("topics").isEmpty()) {
            // Day/night only steers the topic, so a user who never filled the
            // extra block keeps exactly the taste they already chose.
            query.topics = mood.optString("topics");
        }
        return query;
    }

    /** Night block from 18:00 to 06:00 when the user turned day/night on. */
    private JSONObject dayNightOverlay() {
        try {
            if (mTaste == null || !mTaste.optBoolean("dayNight", false)) return null;
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean night = hour < 6 || hour >= 18;
            return mTaste.optJSONObject(night ? "night" : "day");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPowerSaveMode() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isPowerSaveMode();
    }

    private void changeWallpaper(int target) {
        if (!mEnabled) return;
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire(10000);
            } catch (Exception e) {
                Log.e(TAG, "wake lock", e);
            }
        }
        try {
            Bitmap bitmap = null;
            GlympsCandidate candidate = null;

            // 1) Cache-first, mirroring v1: consume the oldest cached wallpaper
            //    and re-fill the pool afterwards.
            File cachedFile = nextCachedFile();
            if (cachedFile != null) {
                bitmap = decodeFileBounded(cachedFile);
                candidate = readSidecar(cachedFile);
                // Consume the entry either way so a corrupt PNG cannot block
                // the pool forever.
                cachedFile.delete();
                File sidecar = sidecarOf(cachedFile);
                if (sidecar.exists()) {
                    sidecar.delete();
                }
            }

            // 2) Fresh download when the cache is empty and the network allows.
            if (bitmap == null && shouldDownload()) {
                String providerId = target == TARGET_HOME_SCREEN ? mProviderHome : mProviderLock;
                candidate = pickCandidate(providerId, target);
                if (candidate != null && !candidate.url.startsWith("file:")) {
                    bitmap = decodeBounded(candidate.url);
                }
            }

            // 3) Last resort: local folder direct pick, mirroring v1 behavior.
            if (bitmap == null) {
                GlympsProvider local = GlympsProviders.get(GlympsProviders.LOCAL);
                if (local instanceof LocalFolderProvider) {
                    GlympsCandidate localPick = ((LocalFolderProvider) local).pickRandom();
                    if (localPick != null) {
                        bitmap = BitmapFactory.decodeFile(
                                localPick.url.substring("file:".length()));
                        candidate = localPick;
                    }
                }
            }

            if (bitmap != null) {
                Bitmap cropped = cropToTarget(bitmap);
                if (cropped != bitmap) {
                    bitmap.recycle();
                }
                int flags = getFlags(target);
                mWallpaperManager.setBitmap(cropped, null, false, flags);
                writeLastImage(cropped);
                markSuccess();
                saveCurrent(candidate);
                cropped.recycle();
                if (mExecutor != null && !mExecutor.isShutdown()) {
                    mExecutor.execute(this::preFetch);
                }
            } else {
                markFailure("No candidate could be downloaded");
            }
        } catch (Exception e) {
            Log.e(TAG, "changeWallpaper", e);
            markFailure(e.getMessage());
        } finally {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                try {
                    mWakeLock.release();
                } catch (Exception e) {
                    Log.e(TAG, "wake lock release", e);
                }
            }
        }
    }

    private int getFlags(int target) {
        if (target == TARGET_HOME_SCREEN) return WallpaperManager.FLAG_SYSTEM;
        if (target == TARGET_BOTH) {
            return WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM;
        }
        return WallpaperManager.FLAG_LOCK;
    }

    /** Reads all bytes, decodes bounds, picks inSampleSize, then decodes. */
    private Bitmap decodeBounded(String url) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = null;
        try {
            HttpURLConnection connection = GlympsHttp.open(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                return null;
            }
            input = connection.getInputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                    // ignored
                }
            }
        }
        byte[] data = bytes.toByteArray();
        Bitmap decoded = decodeCover(data);
        if (decoded != null) {
            return decoded;
        }
        // Fallback for formats or images the platform decoder rejects.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /**
     * Decodes straight to the smallest size that still covers 1080x1920.
     * ImageDecoder applies the scale inside the decoder, so a 4K source never
     * allocates its full pixel buffer just to be scaled down and recycled.
     * Software allocation is required because WallpaperManager re-encodes the
     * bitmap and hardware bitmaps cannot be read back.
     */
    private Bitmap decodeCover(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
            return ImageDecoder.decodeBitmap(source, this::applyCoverSize);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyCoverSize(ImageDecoder decoder, ImageDecoder.ImageInfo info,
            ImageDecoder.Source source) {
        int width = info.getSize().getWidth();
        int height = info.getSize().getHeight();
        if (width <= 0 || height <= 0) return;
        float scale = Math.max((float) GlympsProvider.TARGET_WIDTH / width,
                (float) GlympsProvider.TARGET_HEIGHT / height);
        int targetWidth = Math.max(GlympsProvider.TARGET_WIDTH, Math.round(width * scale));
        int targetHeight = Math.max(GlympsProvider.TARGET_HEIGHT, Math.round(height * scale));
        decoder.setTargetSize(targetWidth, targetHeight);
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / 2 >= GlympsProvider.TARGET_WIDTH * 2
                && height / 2 >= GlympsProvider.TARGET_HEIGHT * 2) {
            width /= 2;
            height /= 2;
            sample *= 2;
        }
        return sample;
    }

    /** Scales to cover 1080x1920, then center-crops to exactly that size. */
    private static Bitmap cropToTarget(Bitmap source) {
        int tw = GlympsProvider.TARGET_WIDTH;
        int th = GlympsProvider.TARGET_HEIGHT;
        float scale = Math.max((float) tw / source.getWidth(),
                (float) th / source.getHeight());
        int scaledWidth = Math.round(source.getWidth() * scale);
        int scaledHeight = Math.round(source.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        int x = (scaledWidth - tw) / 2;
        int y = (scaledHeight - th) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, tw, th);
        if (cropped != scaled) {
            scaled.recycle();
        }
        return cropped;
    }

    /** Oldest cached wallpaper (v1 consumed entries in order and deleted them). */
    private File nextCachedFile() {
        File[] images = cachedImages();
        return images.length == 0 ? null : images[0];
    }

    /**
     * Trims the pool to the entry limit and to the disk budget, oldest first.
     * A WebP entry is roughly 300-800KB, so the budget rarely kicks in before
     * the entry limit; it protects users who raise the cache above ten.
     */
    private void cleanOldCache() {
        File[] images = cachedImages();
        long budget = CACHE_QUOTA_MB * 1024L * 1024L;
        long total = 0;
        for (File image : images) {
            total += image.length();
        }
        int keep = Math.min(images.length, MAX_CACHE_SIZE);
        for (int i = 0; i < images.length; i++) {
            boolean overCount = (images.length - i) > keep;
            if (!overCount && total <= budget) break;
            total -= images[i].length();
            images[i].delete();
            File sidecar = sidecarOf(images[i]);
            if (sidecar.exists()) {
                sidecar.delete();
            }
        }
    }

    private void clearCache() {
        File[] files = mCacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    // ---- runtime state (filesDir/glymps_state.json) ----

    private JSONObject loadState() {
        try {
            return new JSONObject(
                    new String(java.nio.file.Files.readAllBytes(mStateFile.toPath()), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveState(JSONObject state) {
        try {
            java.nio.file.Files.write(mStateFile.toPath(),
                    state.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "state save", e);
        }
    }

    private static JSONArray recentBag(JSONObject state, String providerId) {
        JSONObject bags = state.optJSONObject("recent");
        if (bags == null) {
            bags = new JSONObject();
            try {
                state.put("recent", bags);
            } catch (Exception e) {
                // ignored
            }
        }
        JSONArray bag = bags.optJSONArray(providerId);
        if (bag == null) {
            bag = new JSONArray();
            try {
                bags.put(providerId, bag);
            } catch (Exception e) {
                // ignored
            }
        }
        return bag;
    }

    private static boolean contains(JSONArray bag, String value) {
        for (int i = 0; i < bag.length(); i++) {
            if (value.equals(bag.optString(i))) return true;
        }
        return false;
    }

    private static void pushBag(JSONObject state, String providerId, String externalId) {
        JSONArray bag = recentBag(state, providerId);
        bag.put(externalId);
        while (bag.length() > BAG_SIZE) {
            bag.remove(0);
        }
    }

    private static int nextPage(JSONObject state, String providerId) {
        JSONObject pages = state.optJSONObject("page");
        if (pages == null) {
            pages = new JSONObject();
            try {
                state.put("page", pages);
            } catch (Exception e) {
                // ignored
            }
        }
        int page = pages.optInt(providerId, 1);
        try {
            pages.put(providerId, page);
        } catch (Exception e) {
            // ignored
        }
        return Math.max(1, page);
    }

    private static void setPage(JSONObject state, String providerId, int page) {
        try {
            state.getJSONObject("page").put(providerId, page);
        } catch (Exception e) {
            // ignored
        }
    }

    private void saveCurrent(GlympsCandidate candidate) {
        try {
            JSONObject current = new JSONObject();
            if (candidate == null) {
                current.put("provider", "unknown");
                current.put("externalId", "");
                current.put("attribution", "");
                current.put("license", "");
                current.put("sourcePage", "");
            } else {
                current.put("provider", candidate.providerId);
                current.put("externalId", candidate.externalId);
                current.put("attribution", candidate.attribution);
                current.put("license", candidate.license);
                current.put("sourcePage", candidate.sourcePage);
            }
            current.put("time", System.currentTimeMillis());
            appendStats(current);
            appendCacheStats(current);
            Settings.System.putString(getContentResolver(), KEY_CURRENT, current.toString());
        } catch (Exception e) {
            Log.w(TAG, "current save", e);
        }
    }

    /** Publishes cache occupancy so the settings screen can show it. */
    private void appendCacheStats(JSONObject target) {
        try {
            File[] images = cachedImages();
            long bytes = 0;
            for (File image : images) {
                bytes += image.length();
            }
            target.put("cacheCount", images.length);
            target.put("cacheMb", Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0);
        } catch (Exception e) {
            // cosmetic only
        }
    }

    /** Adds the persisted success/failure counters to a metadata object. */
    private void appendStats(JSONObject target) {
        try {
            JSONObject stats = loadState().optJSONObject("stats");
            target.put("success", stats == null ? 0 : stats.optInt("success"));
            target.put("fail", stats == null ? 0 : stats.optInt("fail"));
        } catch (Exception e) {
            // counters are cosmetic; never fail a wallpaper change over them
        }
    }

    /** Keeps the last applied image so it can be saved to the gallery later. */
    private void writeLastImage(Bitmap bitmap) {
        if (bitmap == null || mLastFile == null) return;
        try (FileOutputStream out = new FileOutputStream(mLastFile)) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out);
        } catch (Exception e) {
            Log.w(TAG, "last image", e);
        }
    }

    /** Bounded decode for cache entries (they are already at the target size). */
    private Bitmap decodeFileBounded(File file) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(source, this::applyCoverSize);
        } catch (Exception e) {
            // fall through to the BitmapFactory path
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Copies the last applied wallpaper into Pictures/Glymps through MediaStore. */
    private void saveCurrentToGallery() {
        if (mLastFile == null || !mLastFile.exists() || mLastFile.length() == 0) {
            putCurrentResult("savedAt", "saveError", false, "No applied wallpaper yet");
            return;
        }
        ContentResolver resolver = getContentResolver();
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "glymps_" + System.currentTimeMillis() + ".webp");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/webp");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Glymps");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                putCurrentResult("savedAt", "saveError", false, "MediaStore refused the insert");
                return;
            }
            try (InputStream in = new FileInputStream(mLastFile)) {
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) throw new IOException("No output stream");
                try (OutputStream target = out) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        target.write(buffer, 0, read);
                    }
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            putCurrentResult("savedAt", "saveError", true, "");
        } catch (Exception e) {
            Log.w(TAG, "save to gallery", e);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                    // best effort cleanup of a half written pending row
                }
            }
            putCurrentResult("savedAt", "saveError", false, e.getMessage());
        }
    }

    /**
     * Adds the current wallpaper to the persistent ban list so the bag never
     * offers it again, and drops any cached copies of it.
     */
    private void banCurrent() {
        try {
            JSONObject current = readCurrentJson();
            String provider = current.optString("provider", "");
            String externalId = current.optString("externalId", "");
            if (provider.isEmpty() || "unknown".equals(provider) || externalId.isEmpty()) {
                putCurrentResult("bannedAt", "banError", false, "Nothing to hide yet");
                return;
            }
            JSONObject state = loadState();
            JSONArray banned = state.optJSONArray("banned");
            if (banned == null) {
                banned = new JSONArray();
                state.put("banned", banned);
            }
            String token = provider + "|" + externalId;
            if (!contains(banned, token)) {
                banned.put(token);
            }
            while (banned.length() > 500) {
                banned.remove(0);
            }
            saveState(state);
            dropCached(provider, externalId);
            putCurrentResult("bannedAt", "banError", true, "");
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.execute(this::preFetch);
            }
        } catch (Exception e) {
            Log.w(TAG, "ban current", e);
            putCurrentResult("bannedAt", "banError", false, e.getMessage());
        }
    }

    /** Deletes cached copies of one wallpaper (image and sidecar). */
    private void dropCached(String provider, String externalId) {
        for (File image : cachedImages()) {
            GlympsCandidate cached = readSidecar(image);
            if (cached == null || !externalId.equals(cached.externalId)) continue;
            if (provider != null && !provider.isEmpty()
                    && !provider.equals(cached.providerId)) continue;
            image.delete();
            File sidecar = sidecarOf(image);
            if (sidecar.exists()) {
                sidecar.delete();
            }
        }
    }

    private JSONObject readCurrentJson() {
        try {
            String json = Settings.System.getString(getContentResolver(), KEY_CURRENT);
            if (json != null && !json.isEmpty()) {
                return new JSONObject(json);
            }
        } catch (Exception e) {
            // fall through to an empty object
        }
        return new JSONObject();
    }

    /** Publishes the outcome of a user action next to the current metadata. */
    private void putCurrentResult(String timeKey, String errorKey, boolean ok, String message) {
        try {
            JSONObject current = readCurrentJson();
            current.put(timeKey, ok ? System.currentTimeMillis() : 0);
            current.put(errorKey, ok ? "" : (message == null ? "" : message));
            Settings.System.putString(getContentResolver(), KEY_CURRENT, current.toString());
        } catch (Exception e) {
            Log.w(TAG, "current result", e);
        }
    }

    private void markSuccess() {
        JSONObject state = loadState();
        JSONObject stats = state.optJSONObject("stats");
        if (stats == null) stats = new JSONObject();
        try {
            state.put("stats", stats);
            stats.put("success", stats.optInt("success") + 1);
            stats.put("lastSuccess", System.currentTimeMillis());
            stats.put("lastError", "");
        } catch (Exception e) {
            // ignored
        }
        saveState(state);
    }

    private void markFailure(String message) {
        JSONObject state = loadState();
        JSONObject stats = state.optJSONObject("stats");
        if (stats == null) stats = new JSONObject();
        try {
            state.put("stats", stats);
            stats.put("fail", stats.optInt("fail") + 1);
            stats.put("lastError", message == null ? "" : message);
        } catch (Exception e) {
            // ignored
        }
        saveState(state);
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
                if (mPendingWallpaperChange != null) {
                    mHandler.removeCallbacks(mPendingWallpaperChange);
                    mPendingWallpaperChange = null;
                }
            }
        }
    }

    private void scheduleWallpaperChange() {
        if (!mEnabled) return;
        if (mPendingWallpaperChange != null) {
            mHandler.removeCallbacks(mPendingWallpaperChange);
        }
        final int delay = mChangeOn == 0 ? SCREEN_ON_DELAY : SCREEN_OFF_DELAY;
        mPendingWallpaperChange = () -> {
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.execute(() -> changeWallpaper(mWallpaperTarget));
            }
            mPendingWallpaperChange = null;
        };
        mHandler.postDelayed(mPendingWallpaperChange, delay);
    }
}
