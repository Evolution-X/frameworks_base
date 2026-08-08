/*
 * Copyright (C) 2018-2025 crDroid Android Project
 * Copyright (C) 2018-2019 AICP
 * Copyright (C) 2024-2026 Lunaris AOSP
 * Copyright (C) 2025-2026 RisingOS (revived) Android Project
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

package com.android.systemui.android.logo;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.util.android.OmniJawsClient;
import com.android.settingslib.Utils;
import com.android.settingslib.drawable.CircleFramedDrawable;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;

import java.util.ArrayList;

public abstract class LogoImage extends ImageView implements DarkReceiver,
        OmniJawsClient.OmniJawsObserver {

    private static final String TAG = "LogoImage";

    public static final int LOGO_STYLE_CUSTOM = 34;
    public static final int LOGO_STYLE_WEATHER = 35;

    private static final long WEATHER_RETRY_DELAY_MS = 2000;

    private Context mContext;
    private Handler mHandler;

    private boolean mAttached;

    private boolean mShowLogo;
    public int mLogoPosition;
    private int mLogoStyle;
    private int mTintColor = Color.WHITE;
    private int mLogoColor;
    private int mLogoColorCustom;

    private String mCustomImagePath;
    private String mCurrentCustomImagePath;
    private boolean mCustomImageLoaded = false;
    private Drawable mCustomImageDrawable = null;

    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private boolean mWeatherClientInitialized = false;
    private int mLogoSize;
    private boolean mLogoSizeInitialized = false;
    private final Runnable mWeatherRetryRunnable = () -> {
        if (mShowLogo && isLogoVisible() && mLogoStyle == LOGO_STYLE_WEATHER) {
            updateLogo();
        }
    };

    private ContentObserver mSettingsObserver;

    private boolean mUserUnlocked = false;
    private BroadcastReceiver mUserUnlockedReceiver;
    private boolean mReceiverRegistered = false;

    public LogoImage(Context context) {
        this(context, null);
    }

    public LogoImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogoImage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mHandler = new Handler();
        mWeatherClient = OmniJawsClient.get();
    }

    protected abstract boolean isLogoVisible();

    private boolean isUserUnlocked() {
        try {
            UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        } catch (Exception e) {
            Log.w(TAG, "Could not query UserManager lock state, assuming locked", e);
            return false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) return;
        mAttached = true;

        mUserUnlocked = isUserUnlocked();

        mSettingsObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (mUserUnlocked) {
                    updateSettings();
                } else {
                    Log.d(TAG, "Settings changed but user is locked, deferring");
                }
            }
        };

        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_POSITION),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_STYLE),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_COLOR),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_COLOR_PICKER),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI),
                false, mSettingsObserver, UserHandle.USER_ALL);

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        initializeLogoSize();

        if (mUserUnlocked) {
            initializeWeatherClient();
            updateSettings();
        } else {
            Log.w(TAG, "CE storage locked on attach, deferring settings load until unlock");
            setImageDrawable(null);
            setVisibility(View.GONE);
            registerUserUnlockedReceiver();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached) return;
        mAttached = false;

        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);

        unregisterUserUnlockedReceiver();
        cleanupWeatherClient();
        mHandler.removeCallbacks(mWeatherRetryRunnable);

        mCustomImageDrawable = null;
    }

    private void initializeLogoSize() {
        if (!mLogoSizeInitialized) {
            mLogoSize = (int) mContext.getResources()
                    .getDimension(R.dimen.status_bar_system_icons_height);
            mLogoSizeInitialized = true;
        }
    }

    private void initializeWeatherClient() {
        if (!mWeatherClientInitialized && mWeatherClient != null) {
            mWeatherClient.addObserver(mContext, this);
            mWeatherClientInitialized = true;

            if (mWeatherClient.isOmniJawsEnabled(mContext)) {
                mWeatherClient.queryWeather(mContext);
                mWeatherInfo = mWeatherClient.getWeatherInfo();
            }
        }
    }

    private void cleanupWeatherClient() {
        if (mWeatherClientInitialized && mWeatherClient != null) {
            mWeatherClient.removeObserver(mContext, this);
            mWeatherClientInitialized = false;
        }
    }

    private void registerUserUnlockedReceiver() {
        if (mReceiverRegistered) return;

        mUserUnlockedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) return;

                Log.d(TAG, "User unlocked, initialising logo settings");
                mUserUnlocked = true;

                unregisterUserUnlockedReceiver();

                post(() -> {
                    initializeWeatherClient();
                    updateSettings();
                });
            }
        };

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            mContext.registerReceiver(mUserUnlockedReceiver, filter);
            mReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register user unlocked receiver", e);
            mUserUnlockedReceiver = null;
        }
    }

    private void unregisterUserUnlockedReceiver() {
        if (!mReceiverRegistered || mUserUnlockedReceiver == null) return;
        try {
            mContext.unregisterReceiver(mUserUnlockedReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering user unlocked receiver", e);
        } finally {
            mUserUnlockedReceiver = null;
            mReceiverRegistered = false;
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(areas, this, tint);
        if (mShowLogo && isLogoVisible()) {
            updateLogo();
        }
    }

    @Override
    public void weatherUpdated() {
        if (mWeatherClient == null) return;
        mWeatherInfo = mWeatherClient.getWeatherInfo();
        if (mShowLogo && isLogoVisible() && mLogoStyle == LOGO_STYLE_WEATHER) {
            updateLogo();
        }
    }

    @Override
    public void weatherError(int errorReason) {
        mWeatherInfo = null;
        if (mShowLogo && isLogoVisible() && mLogoStyle == LOGO_STYLE_WEATHER) {
            updateLogo();
        }
    }

    public void updateLogo() {
        if (mLogoStyle == LOGO_STYLE_CUSTOM) {
            updateCustomLogo();
            return;
        }
        if (mLogoStyle == LOGO_STYLE_WEATHER) {
            updateWeatherLogo();
            return;
        }
        Drawable drawable = null;
        switch (mLogoStyle) {
            case 0:
            default:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_evolution_logo);
                break;
            case 1:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_android_logo);
                break;
            case 2:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_adidas);
                break;
            case 3:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_alien);
                break;
            case 4:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_apple_logo);
                break;
            case 5:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_avengers);
                break;
            case 6:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_batman);
                break;
            case 7:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_batman_tdk);
                break;
            case 8:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_beats);
                break;
            case 9:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_biohazard);
                break;
            case 10:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_blackberry);
                break;
            case 11:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_cannabis);
                break;
            case 12:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_cool);
                break;
            case 13:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_devil);
                break;
            case 14:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_fire);
                break;
            case 15:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_heart);
                break;
            case 16:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_nike);
                break;
            case 17:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_pac_man);
                break;
            case 18:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_puma);
                break;
            case 19:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_rog);
                break;
            case 20:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_spiderman);
                break;
            case 21:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_superman);
                break;
            case 22:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_windows);
                break;
            case 23:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_xbox);
                break;
            case 24:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ghost);
                break;
            case 25:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ninja);
                break;
            case 26:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_robot);
                break;
            case 27:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ironman);
                break;
            case 28:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_captain_america);
                break;
            case 29:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_flash);
                break;
            case 30:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_tux_logo);
                break;
            case 31:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ubuntu_logo);
                break;
            case 32:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_mint_logo);
                break;
            case 33:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_amogus);
                break;
        }

        if (drawable == null) return;

        if (mLogoColor == 0) {
            drawable.setTint(mTintColor);
        } else if (mLogoColor == 1) {
            ColorStateList colorAccent = Utils.getColorAccent(mContext);
            setImageTintList(colorAccent);
        } else {
            setColorFilter(mLogoColorCustom, PorterDuff.Mode.SRC_IN);
        }
        setImageDrawable(drawable);
    }

    private void updateCustomLogo() {
        if (mCustomImagePath != null && !mCustomImagePath.equals(mCurrentCustomImagePath)) {
            mCurrentCustomImagePath = mCustomImagePath;
            mCustomImageLoaded = false;
            mCustomImageDrawable = null;
        }

        if (!mCustomImageLoaded) {
            loadCustomImage();
        }

        if (mCustomImageDrawable != null) {
            clearColorFilter();
            setImageTintList(null);
            setImageDrawable(mCustomImageDrawable);
        } else {
            setImageDrawable(
                    mContext.getResources().getDrawable(R.drawable.ic_android_logo));
        }
    }

    private void loadCustomImage() {
        if (mCurrentCustomImagePath == null || mCurrentCustomImagePath.isEmpty()) return;

        if (!mUserUnlocked) {
            Log.d(TAG, "Skipping custom image load - CE storage not yet available");
            return;
        }

        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(mCurrentCustomImagePath);
            if (bitmap != null) {
                int targetSize = (int) mContext.getResources()
                        .getDimension(R.dimen.status_bar_system_icons_height);
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                        bitmap, targetSize, targetSize, true);
                try (java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream()) {
                    scaledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 90, stream);
                    byte[] byteArray = stream.toByteArray();
                    Bitmap compressedBitmap = BitmapFactory.decodeByteArray(
                            byteArray, 0, byteArray.length);
                    mCustomImageDrawable = new CircleFramedDrawable(compressedBitmap, targetSize);
                    scaledBitmap.recycle();
                    compressedBitmap.recycle();
                    mCustomImageLoaded = true;
                }
            } else {
                Log.w(TAG, "Failed to decode custom logo bitmap: " + mCurrentCustomImagePath);
                mCustomImageLoaded = false;
                mCustomImageDrawable = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading custom logo image", e);
            mCustomImageLoaded = false;
            mCustomImageDrawable = null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private void updateWeatherLogo() {
        mHandler.removeCallbacks(mWeatherRetryRunnable);

        if (mWeatherClient == null || !mWeatherClient.isOmniJawsEnabled(mContext)) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        }

        if (mWeatherInfo == null) {
            mWeatherClient.queryWeather(mContext);
            mWeatherInfo = mWeatherClient.getWeatherInfo();
        }

        Drawable drawable = mWeatherInfo != null
                ? mWeatherClient.getWeatherConditionImage(mContext, mWeatherInfo.conditionCode)
                : null;

        if (drawable == null) {
            // Weather data isn't available yet (e.g. still fetching); retry shortly
            // rather than leaving a stale or blank icon indefinitely.
            setImageDrawable(null);
            setVisibility(View.GONE);
            mHandler.postDelayed(mWeatherRetryRunnable, WEATHER_RETRY_DELAY_MS);
            return;
        }

        initializeLogoSize();
        clearColorFilter();
        setImageTintList(null);
        setImageDrawable(drawable);
        setVisibility(View.VISIBLE);
    }

    public void updateSettings() {
        if (!mUserUnlocked) {
            Log.d(TAG, "updateSettings() skipped - CE storage locked");
            return;
        }

        try {
            mShowLogo = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO, 0,
                    UserHandle.USER_CURRENT) != 0;
            mLogoPosition = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_POSITION, 0,
                    UserHandle.USER_CURRENT);
            int newLogoStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_STYLE, 0,
                    UserHandle.USER_CURRENT);
            boolean enteringWeatherStyle = newLogoStyle == LOGO_STYLE_WEATHER
                    && mLogoStyle != LOGO_STYLE_WEATHER;
            mLogoStyle = newLogoStyle;

            if (enteringWeatherStyle) {
                if (!mWeatherClientInitialized) {
                    initializeWeatherClient();
                }
                if (mWeatherClient.isOmniJawsEnabled(mContext)) {
                    mWeatherClient.queryWeather(mContext);
                    mWeatherInfo = mWeatherClient.getWeatherInfo();
                }
            }
            mLogoColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_COLOR, 0,
                    UserHandle.USER_CURRENT);
            mLogoColorCustom = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_COLOR_PICKER, 0xff1a73e8,
                    UserHandle.USER_CURRENT);

            String newCustomPath = Settings.System.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI,
                    UserHandle.USER_CURRENT);
            if (newCustomPath != null && !newCustomPath.equals(mCustomImagePath)) {
                mCustomImageLoaded = false;
                mCustomImageDrawable = null;
            }
            mCustomImagePath = newCustomPath;

        } catch (Exception e) {
            Log.e(TAG, "Error reading logo settings from CE storage", e);
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        }

        if (!mShowLogo || !isLogoVisible()) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        }

        updateLogo();
        setVisibility(View.VISIBLE);
    }
}
