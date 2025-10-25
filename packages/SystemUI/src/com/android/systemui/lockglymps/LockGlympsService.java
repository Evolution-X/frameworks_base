/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LockGlympsService extends Service {
    private static final String TAG = "LockGlympsService";
    private static final int MAX_CACHE_SIZE = 10;
    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    private static final int SCREEN_ON_DELAY = 800; // Delay for screen-on mode
    private static final int SCREEN_OFF_DELAY = 450; // Delay for screen-off mode
    
    private static final String LOCK_GLYMPS_ENABLED = "lock_glymps_enabled";
    private static final String LOCK_GLYMPS_SOURCE = "lock_glymps_source";
    private static final String LOCK_GLYMPS_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String LOCK_GLYMPS_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String LOCK_GLYMPS_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String LOCK_GLYMPS_CHANGE_ON = "lock_glymps_change_on";
    
    private static final String STORAGE_FOLDER = "Glymps";
    
    private WallpaperManager mWallpaperManager;
    private ExecutorService mExecutor;
    private Handler mHandler;
    private File mCacheDir;
    private Random mRandom;
    private ScreenStateReceiver mScreenReceiver;
    private PowerManager.WakeLock mWakeLock;
    
    private boolean mEnabled = false;
    private int mWallpaperSource = 0;
    private boolean mWifiOnly = true;
    private int mCacheSize = 5;
    private int mChangeOn = 1;
    private List<String> mCustomUrls = new ArrayList<>();
    
    private int mCurrentIndex = 0;
    private Runnable mPendingWallpaperChange = null;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "LockGlympsService onCreate");
        
        mWallpaperManager = WallpaperManager.getInstance(this);
        mExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mRandom = new Random();
        
        mCacheDir = new File(getCacheDir(), "lock_glymps");
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
        
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WallpaperChange");
        }
        
        loadSettings();
        registerScreenReceiver();
        
        if (mEnabled) {
            mExecutor.execute(this::preFetchWallpapers);
        }
    }
    
    private void registerScreenReceiver() {
        if (mScreenReceiver != null) {
            try {
                unregisterReceiver(mScreenReceiver);
            } catch (Exception e) {
            }
            mScreenReceiver = null;
        }
        
        mScreenReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();
        
        if (mChangeOn == 0) {
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
        } else {
            filter.addAction(Intent.ACTION_SCREEN_OFF);
        }
        
        registerReceiver(mScreenReceiver, filter);
        Log.d(TAG, "Screen receiver registered for: " + (mChangeOn == 0 ? "SCREEN_ON" : "SCREEN_OFF"));
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
                }
                
                if (mEnabled) {
                    mExecutor.execute(this::preFetchWallpapers);
                }
            } else if ("CLEAR_CACHE".equals(action)) {
                mExecutor.execute(this::clearCache);
            }
        }
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "LockGlympsService onDestroy");
        
        if (mScreenReceiver != null) {
            try {
                unregisterReceiver(mScreenReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
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
        
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            mWakeLock = null;
        }
        
        mWallpaperManager = null;
        mHandler = null;
        mCacheDir = null;
        mRandom = null;
        mCustomUrls.clear();
        mCustomUrls = null;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void loadSettings() {
        try {
            mEnabled = Settings.System.getInt(getContentResolver(), LOCK_GLYMPS_ENABLED, 0) == 1;
            mWallpaperSource = Settings.System.getInt(getContentResolver(), LOCK_GLYMPS_SOURCE, 0);
            mWifiOnly = Settings.System.getInt(getContentResolver(), LOCK_GLYMPS_WIFI_ONLY, 1) == 1;
            mCacheSize = Settings.System.getInt(getContentResolver(), LOCK_GLYMPS_CACHE_SIZE, 5);
            mChangeOn = Settings.System.getInt(getContentResolver(), LOCK_GLYMPS_CHANGE_ON, 1);
            
            String urls = Settings.System.getString(getContentResolver(), LOCK_GLYMPS_CUSTOM_URLS);
            mCustomUrls.clear();
            if (urls != null && !urls.isEmpty()) {
                String[] urlArray = urls.split(",");
                for (String url : urlArray) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) {
                        mCustomUrls.add(trimmed);
                    }
                }
            }
            
            Log.d(TAG, "Settings loaded - Enabled: " + mEnabled + ", Source: " + mWallpaperSource + 
                ", ChangeOn: " + (mChangeOn == 0 ? "screen-on" : "screen-off"));
        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
        }
    }
    
    private boolean shouldDownload() {
        if (!mWifiOnly) return true;
        
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && 
               activeNetwork.isConnected() && 
               activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
    }
    
    private void preFetchWallpapers() {
        if (mWallpaperSource == 2) {
            Log.d(TAG, "Using local folder source, skipping cache");
            return;
        }
        
        if (!shouldDownload()) {
            Log.d(TAG, "WiFi not available, skipping pre-fetch");
            return;
        }
        
        File[] cachedFiles = mCacheDir.listFiles();
        int currentCacheCount = cachedFiles != null ? cachedFiles.length : 0;
        
        Log.d(TAG, "Current cache: " + currentCacheCount + ", Target: " + mCacheSize);
        
        while (currentCacheCount < mCacheSize) {
            Bitmap wallpaper = downloadWallpaper();
            if (wallpaper != null) {
                File cacheFile = new File(mCacheDir, "wallpaper_" + System.currentTimeMillis() + ".png");
                if (saveBitmapToFile(wallpaper, cacheFile)) {
                    currentCacheCount++;
                    Log.d(TAG, "Cached wallpaper: " + cacheFile.getName());
                }
                wallpaper.recycle();
            } else {
                break;
            }
        }
        
        cleanOldCache();
    }
    
    private Bitmap downloadWallpaper() {
        String url = getWallpaperUrl();
        if (url == null) {
            Log.w(TAG, "No URL available");
            return null;
        }
        
        Log.d(TAG, "Downloading from: " + url);
        
        HttpURLConnection connection = null;
        InputStream input = null;
        
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setDoInput(true);
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("User-Agent", "LockGlymps/1.0");
            connection.connect();
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error code: " + responseCode);
                return null;
            }
            
            input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            
            if (bitmap != null) {
                Log.d(TAG, "Downloaded bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error downloading wallpaper", e);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream", e);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    private String getWallpaperUrl() {
        switch (mWallpaperSource) {
            case 0:
                return "https://picsum.photos/1080/1920";
                
            case 1:
                if (mCustomUrls.isEmpty()) {
                    Log.w(TAG, "Custom URLs list is empty");
                    return null;
                }
                return mCustomUrls.get(mRandom.nextInt(mCustomUrls.size()));
                
            case 2:
                return null;
                
            default:
                return "https://picsum.photos/1080/1920";
        }
    }
    
    private File getLocalWallpaper() {
        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
        
        if (!storageDir.exists() || !storageDir.isDirectory()) {
            Log.w(TAG, "Local folder doesn't exist: " + storageDir.getPath());
            return null;
        }
        
        File[] files = storageDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
                   lower.endsWith(".png") || lower.endsWith(".webp");
        });
        
        if (files == null || files.length == 0) {
            Log.w(TAG, "No wallpapers found in: " + storageDir.getPath());
            return null;
        }
        
        return files[mRandom.nextInt(files.length)];
    }
    
    private boolean saveBitmapToFile(Bitmap bitmap, File file) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error saving bitmap to file", e);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing file output stream", e);
                }
            }
        }
    }
    
    private void scheduleWallpaperChange() {
        if (!mEnabled) {
            Log.d(TAG, "Feature disabled");
            return;
        }
        
        if (mPendingWallpaperChange != null) {
            mHandler.removeCallbacks(mPendingWallpaperChange);
        }
        
        final int delay = mChangeOn == 0 ? SCREEN_ON_DELAY : SCREEN_OFF_DELAY;
        
        mPendingWallpaperChange = () -> {
            if (mExecutor != null && !mExecutor.isShutdown()) {
                mExecutor.execute(this::changeWallpaper);
            }
            mPendingWallpaperChange = null;
        };
        
        mHandler.postDelayed(mPendingWallpaperChange, delay);
        Log.d(TAG, "Wallpaper change scheduled in " + delay + "ms");
    }
    
    private void changeWallpaper() {
        if (!mEnabled) {
            Log.d(TAG, "Feature disabled");
            return;
        }
        
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire(10000);
            } catch (Exception e) {
                Log.e(TAG, "Failed to acquire wake lock", e);
            }
        }
        
        Bitmap bitmap = null;
        
        try {
            if (mWallpaperSource == 2) {
                File localFile = getLocalWallpaper();
                if (localFile != null) {
                    bitmap = BitmapFactory.decodeFile(localFile.getPath());
                    if (bitmap != null) {
                        Log.d(TAG, "Loaded wallpaper from local folder: " + localFile.getName());
                    }
                }
            } 
            else {
                File[] cachedFiles = mCacheDir != null ? mCacheDir.listFiles() : null;
                if (cachedFiles == null || cachedFiles.length == 0) {
                    Log.w(TAG, "No cached wallpapers available");
                    if (shouldDownload()) {
                        bitmap = downloadWallpaper();
                    }
                } else {
                    if (mCurrentIndex >= cachedFiles.length) {
                        mCurrentIndex = 0;
                    }
                    File wallpaperFile = cachedFiles[mCurrentIndex];
                    mCurrentIndex++;
                    
                    bitmap = BitmapFactory.decodeFile(wallpaperFile.getPath());
                    if (bitmap != null) {
                        wallpaperFile.delete();
                        if (mExecutor != null && !mExecutor.isShutdown()) {
                            mExecutor.execute(this::preFetchWallpapers);
                        }
                    } else {
                        Log.e(TAG, "Failed to decode cached wallpaper");
                    }
                }
            }
            
            if (bitmap != null) {
                setWallpaperFromBitmap(bitmap);
            } else {
                Log.w(TAG, "No bitmap available to set");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error changing wallpaper", e);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            
            if (mWakeLock != null && mWakeLock.isHeld()) {
                try {
                    mWakeLock.release();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to release wake lock", e);
                }
            }
        }
    }
    
    private void setWallpaperFromBitmap(Bitmap bitmap) {
        if (mWallpaperManager == null) {
            Log.e(TAG, "WallpaperManager is null");
            return;
        }
        
        try {
            int wallpaperId = mWallpaperManager.setBitmap(
                bitmap,
                null,
                false,
                WallpaperManager.FLAG_LOCK
            );
            
            Log.d(TAG, "Wallpaper changed successfully, ID: " + wallpaperId);
            
        } catch (IOException e) {
            Log.e(TAG, "Error setting wallpaper", e);
        }
    }
    
    private void cleanOldCache() {
        if (mCacheDir == null) return;
        
        File[] cachedFiles = mCacheDir.listFiles();
        if (cachedFiles == null || cachedFiles.length <= MAX_CACHE_SIZE) {
            return;
        }
        
        java.util.Arrays.sort(cachedFiles, (f1, f2) -> 
            Long.compare(f1.lastModified(), f2.lastModified()));
        
        int toDelete = cachedFiles.length - MAX_CACHE_SIZE;
        for (int i = 0; i < toDelete; i++) {
            if (cachedFiles[i].delete()) {
                Log.d(TAG, "Deleted old cache: " + cachedFiles[i].getName());
            }
        }
    }
    
    private void clearCache() {
        if (mCacheDir == null) return;
        
        File[] cachedFiles = mCacheDir.listFiles();
        if (cachedFiles != null) {
            for (File file : cachedFiles) {
                file.delete();
            }
        }
        mCurrentIndex = 0;
        Log.d(TAG, "Cache cleared");
    }
    
    private class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            
            String action = intent.getAction();
            
            if (Intent.ACTION_SCREEN_ON.equals(action) && mChangeOn == 0) {
                Log.d(TAG, "Screen turned on, scheduling wallpaper change");
                scheduleWallpaperChange();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action) && mChangeOn == 1) {
                Log.d(TAG, "Screen turned off, scheduling wallpaper change");
                scheduleWallpaperChange();
            } else if (Intent.ACTION_USER_PRESENT.equals(action) && mChangeOn == 0) {
                if (mPendingWallpaperChange != null) {
                    Log.d(TAG, "Device unlocked, cancelling pending wallpaper change");
                    mHandler.removeCallbacks(mPendingWallpaperChange);
                    mPendingWallpaperChange = null;
                }
            }
        }
    }
}
