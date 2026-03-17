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

package com.android.systemui.cutoutprogress;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.TypedValue;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class MusicRingColorManager {

    public static final int MODE_ALBUM_ICON = 0;
    public static final int MODE_ACCENT = 1;
    public static final int MODE_ALBUM_ART = 2;
    public static final int MODE_CUSTOM = 3;

    public interface ColorCallback {
        void onMusicRingColorChanged(int argb);
    }

    private static final int DEFAULT_FALLBACK = 0xFF2196F3;
    private static final int ICON_SAMPLE_DIM = 24;
    private static final int ART_SAMPLE_DIM = 64;
    private static final float MIN_SATURATION = 0.25f;
    private static final float MIN_LIGHTNESS = 0.15f;
    private static final float MAX_LIGHTNESS = 0.90f;


    private final Context mContext;
    private final Handler mMainHandler;

    private final Executor mBgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicRingPalette");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private ColorCallback mCallback;
    private int mMode = MODE_ALBUM_ICON;
    private int mCustomColor = DEFAULT_FALLBACK;

    private String mCachedTrackId = null;
    private int mCachedColor = DEFAULT_FALLBACK;
    private Drawable mLastArt = null;

    private int mCachedAccent = DEFAULT_FALLBACK;
    private int mLastUiMode = -1;

    private int mCurrentColor = DEFAULT_FALLBACK;

    public MusicRingColorManager(Context context, Handler mainHandler) {
        mContext = context.getApplicationContext();
        mMainHandler = mainHandler;
    }

    public void setCallback(ColorCallback cb) { mCallback = cb; }

    public void setMode(int mode) {
        if (mMode == mode) return;
        mMode = mode;
        invalidateCache();
        resolve(mLastArt, mCachedTrackId);
    }

    public void setCustomColor(int argb) {
        mCustomColor = argb;
        if (mMode == MODE_CUSTOM) emit(argb);
    }

    public int getMode() {
        return mMode;
    }

    public int getCurrentColor() {
        return mCurrentColor;
    }

    public void onTrackChanged(String trackId, Drawable art) {
        boolean sameTrack = trackId.equals(mCachedTrackId);
        boolean sameArt = (art == mLastArt);
        if (sameTrack && sameArt) return;

        if (!sameTrack) {
            mCachedTrackId = trackId;
            mCachedColor = DEFAULT_FALLBACK;
        }
        if (art != null) mLastArt = art;
        resolve(mLastArt, mCachedTrackId);
    }

    public void onAlbumArtChanged(Drawable art) {
        if (art == mLastArt) return;
        mLastArt = art;
        resolve(art, mCachedTrackId);
    }

    private void resolve(Drawable art, String trackId) {
        switch (mMode) {
            case MODE_CUSTOM: 
                emit(mCustomColor); 
                break;
            case MODE_ACCENT: 
                emit(resolveAccent()); 
                break;
            case MODE_ALBUM_ICON: 
                resolveAlbumIcon(art, trackId); 
                break;
            case MODE_ALBUM_ART: 
                resolveFullPalette(art, trackId); 
                break;
        }
    }

    private int resolveAccent() {
        int curMode = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (curMode == mLastUiMode) return mCachedAccent;
        mLastUiMode = curMode;
        TypedValue tv = new TypedValue();
        boolean ok = mContext.getTheme()
                .resolveAttribute(android.R.attr.colorAccent, tv, true);
        int accent = ok ? tv.data : DEFAULT_FALLBACK;
        mCachedAccent = ensureVisible(accent,
                curMode == Configuration.UI_MODE_NIGHT_YES);
        return mCachedAccent;
    }

    private void resolveAlbumIcon(Drawable art, String trackId) {
        if (art == null) { emitFallback(); return; }

        if (art == mLastArt
                && trackId != null && trackId.equals(mCachedTrackId)
                && mCachedColor != DEFAULT_FALLBACK) {
            emit(mCachedColor);
            return;
        }

        Bitmap bmp = drawableToBitmap(art, ICON_SAMPLE_DIM);
        if (bmp == null) { emitFallback(); return; }

        int dominant = dominantColor(bmp);
        bmp.recycle();

        boolean dark = isDarkMode();
        int finalColor = ensureVisible(dominant, dark);
        if (trackId != null) mCachedColor = finalColor;
        emit(finalColor);
    }

    private void resolveFullPalette(Drawable art, String trackId) {
        if (art == null) { emitFallback(); return; }

        if (art == mLastArt
                && trackId != null && trackId.equals(mCachedTrackId)
                && mCachedColor != DEFAULT_FALLBACK) {
            emit(mCachedColor);
            return;
        }

        Bitmap sampled = drawableToBitmap(art, ART_SAMPLE_DIM);
        if (sampled == null) { emitFallback(); return; }

        final String capturedId = trackId;
        final boolean dark = isDarkMode();

        mBgExecutor.execute(() -> {
            int color;
            try {
                Palette palette = Palette.from(sampled).maximumColorCount(16).generate();
                color = pickBestSwatch(palette);
            } finally {
                sampled.recycle();
            }
            int finalColor = ensureVisible(color, dark);
            mMainHandler.post(() -> {
                if (capturedId != null) mCachedColor = finalColor;
                emit(finalColor);
            });
        });
    }

    private void emit(int argb) {
        mCurrentColor = argb;
        if (mCallback != null) mCallback.onMusicRingColorChanged(argb);
    }

    private void emitFallback() {
        emit(resolveAccent());
    }

    private void invalidateCache() {
        mCachedColor = DEFAULT_FALLBACK;
        mLastArt = null;
    }

    private boolean isDarkMode() {
        return (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int pickBestSwatch(Palette palette) {
        Palette.Swatch[] order = {
                palette.getVibrantSwatch(),
                palette.getMutedSwatch(),
                palette.getDominantSwatch(),
                palette.getDarkVibrantSwatch(),
                palette.getDarkMutedSwatch(),
        };
        for (Palette.Swatch s : order) {
            if (s == null) continue;
            float[] hsl = s.getHsl();
            if (hsl[1] >= MIN_SATURATION
                    && hsl[2] >= MIN_LIGHTNESS
                    && hsl[2] <= MAX_LIGHTNESS) {
                return s.getRgb();
            }
        }
        Palette.Swatch dom = palette.getDominantSwatch();
        return dom != null ? dom.getRgb() : DEFAULT_FALLBACK;
    }

    private static int dominantColor(Bitmap bmp) {
        int n = bmp.getWidth() * bmp.getHeight();
        if (n == 0) return DEFAULT_FALLBACK;
        int[] pixels = new int[n];
        bmp.getPixels(pixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
        long r = 0, g = 0, b = 0;
        for (int px : pixels) { r += Color.red(px); g += Color.green(px); b += Color.blue(px); }
        return Color.rgb((int)(r/n), (int)(g/n), (int)(b/n));
    }

    private static Bitmap drawableToBitmap(Drawable d, int dim) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled())
                return Bitmap.createScaledBitmap(src, dim, dim, false);
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, dim, dim);
        d.draw(canvas);
        return bmp;
    }

    private static int ensureVisible(int color, boolean darkBackground) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (darkBackground) {
            if (hsl[2] < 0.45f) hsl[2] = 0.55f;
        } else {
            if (hsl[2] > 0.65f) hsl[2] = 0.45f;
        }
        return ColorUtils.HSLToColor(hsl);
    }
}
