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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.TypedValue;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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

    private final ExecutorService mBgExecutor = Executors.newSingleThreadExecutor(r -> {
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
    private Drawable mLastResolvedArt = null;
    private volatile int mResolveGeneration = 0;
    private volatile boolean mDestroyed = false;
    private final Object mPaletteTaskLock = new Object();
    private PaletteTask mPendingPaletteTask;

    private int mCurrentColor = DEFAULT_FALLBACK;

    public MusicRingColorManager(Context context, Handler mainHandler) {
        mContext = context.getApplicationContext();
        mMainHandler = mainHandler;
    }

    public void setCallback(ColorCallback cb) {
        if (!mDestroyed) mCallback = cb;
    }

    public void setMode(int mode) {
        if (mDestroyed) return;
        mode = Math.max(MODE_ALBUM_ICON, Math.min(MODE_CUSTOM, mode));
        if (mMode == mode) return;
        mMode = mode;
        invalidateCache();
        resolve(mLastArt, mCachedTrackId);
    }

    public void setCustomColor(int argb) {
        if (mDestroyed) return;
        mCustomColor = argb;
        if (mMode == MODE_CUSTOM) emit(argb);
    }

    public int getMode() {
        return mMode;
    }

    public int getCurrentColor() {
        return mCurrentColor;
    }

    public void onThemeChanged() {
        if (mDestroyed) return;
        // Album colors are visibility-adjusted against light/dark backgrounds as well, so a
        // theme change must invalidate more than just MODE_ACCENT.
        mLastResolvedArt = null;
        resolve(mLastArt, mCachedTrackId);
    }

    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        mResolveGeneration++;
        mCallback = null;
        synchronized (mPaletteTaskLock) {
            if (mPendingPaletteTask != null) {
                mPendingPaletteTask.cancel();
                mPendingPaletteTask = null;
            }
        }
        mBgExecutor.shutdownNow();
    }

    public void onTrackChanged(String trackId, Drawable art) {
        if (mDestroyed) return;
        if (Objects.equals(trackId, mCachedTrackId) && art == mLastArt) return;

        if (!Objects.equals(trackId, mCachedTrackId)) {
            mCachedTrackId = trackId;
            mCachedColor = DEFAULT_FALLBACK;
            mLastResolvedArt = null;
            // Do not reuse artwork from the previous track while metadata for the new track is
            // still arriving.
            mLastArt = art;
        } else if (art != null && art != mLastArt) {
            mLastArt = art;
            mCachedColor = DEFAULT_FALLBACK;
            mLastResolvedArt = null;
        }
        resolve(mLastArt, mCachedTrackId);
    }

    public void onAlbumArtChanged(Drawable art) {
        if (mDestroyed) return;
        if (art == mLastArt) return;
        mLastArt = art;
        mCachedColor = DEFAULT_FALLBACK;
        mLastResolvedArt = null;
        resolve(art, mCachedTrackId);
    }

    private void resolve(Drawable art, String trackId) {
        if (mDestroyed) return;
        final int generation = ++mResolveGeneration;
        // Cancel obsolete queued palette work immediately. Generation checks still guard
        // already-running work, while cancellation recycles samples that have not started.
        synchronized (mPaletteTaskLock) {
            if (mPendingPaletteTask != null) {
                mPendingPaletteTask.cancel();
                mPendingPaletteTask = null;
            }
        }
        switch (mMode) {
            case MODE_CUSTOM:
                emitIfCurrent(generation, mCustomColor);
                break;
            case MODE_ACCENT:
                emitIfCurrent(generation, resolveAccent());
                break;
            case MODE_ALBUM_ICON:
                resolveAlbumIcon(art, trackId, generation);
                break;
            case MODE_ALBUM_ART:
                resolveFullPalette(art, trackId, generation);
                break;
        }
    }

    private int resolveAccent() {
        int curMode = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        TypedValue tv = new TypedValue();
        boolean ok = mContext.getTheme()
                .resolveAttribute(android.R.attr.colorAccent, tv, true);
        int accent = DEFAULT_FALLBACK;
        if (ok) {
            if (tv.resourceId != 0) {
                try {
                    accent = mContext.getColor(tv.resourceId);
                } catch (android.content.res.Resources.NotFoundException ignored) {
                    accent = tv.data;
                }
            } else {
                accent = tv.data;
            }
        }
        return ensureVisible(accent, curMode == Configuration.UI_MODE_NIGHT_YES);
    }

    private void resolveAlbumIcon(Drawable art, String trackId, int generation) {
        if (art == null) {
            emitFallback(generation);
            return;
        }

        if (art == mLastResolvedArt
                && Objects.equals(trackId, mCachedTrackId)
                && mCachedColor != DEFAULT_FALLBACK) {
            emitIfCurrent(generation, mCachedColor);
            return;
        }

        Bitmap bmp = drawableToBitmap(art, ICON_SAMPLE_DIM);
        if (bmp == null) {
            emitFallback(generation);
            return;
        }

        int dominant = dominantColor(bmp);
        bmp.recycle();
        int finalColor = ensureVisible(dominant, isDarkMode());
        if (generation != mResolveGeneration) return;
        mLastResolvedArt = art;
        if (trackId != null) mCachedColor = finalColor;
        emit(finalColor);
    }

    private void resolveFullPalette(Drawable art, String trackId, int generation) {
        if (art == null) {
            emitFallback(generation);
            return;
        }

        if (art == mLastResolvedArt
                && Objects.equals(trackId, mCachedTrackId)
                && mCachedColor != DEFAULT_FALLBACK) {
            emitIfCurrent(generation, mCachedColor);
            return;
        }

        Bitmap sampled = drawableToBitmap(art, ART_SAMPLE_DIM);
        if (sampled == null) {
            emitFallback(generation);
            return;
        }

        final PaletteTask task = new PaletteTask(sampled, generation, trackId, art, isDarkMode());
        synchronized (mPaletteTaskLock) {
            if (mPendingPaletteTask != null) {
                mPendingPaletteTask.cancel();
            }
            mPendingPaletteTask = task;
        }
        try {
            mBgExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            task.cancel();
            clearPaletteTask(task);
        }
    }

    private final class PaletteTask implements Runnable {
        private Bitmap mSample;
        private final int mGeneration;
        private final String mTrackId;
        private final Drawable mArt;
        private final boolean mDark;
        private boolean mStarted;
        private volatile boolean mCancelled;

        PaletteTask(Bitmap sample, int generation, String trackId, Drawable art, boolean dark) {
            mSample = sample;
            mGeneration = generation;
            mTrackId = trackId;
            mArt = art;
            mDark = dark;
        }

        synchronized void cancel() {
            mCancelled = true;
            if (!mStarted) recycleSampleLocked();
        }

        @Override
        public void run() {
            final Bitmap sample;
            final boolean skip;
            synchronized (this) {
                skip = mCancelled || mSample == null;
                if (!skip) {
                    mStarted = true;
                }
                sample = mSample;
            }
            // Never acquire mPaletteTaskLock while holding this task's monitor; cancellation
            // takes the locks in the opposite direction.
            if (skip) {
                clearPaletteTask(this);
                return;
            }

            int color = DEFAULT_FALLBACK;
            try {
                if (!mDestroyed && mGeneration == mResolveGeneration) {
                    Palette palette = Palette.from(sample).maximumColorCount(16).generate();
                    color = pickBestSwatch(palette);
                }
            } catch (RuntimeException ignored) {
                color = DEFAULT_FALLBACK;
            } finally {
                synchronized (this) {
                    recycleSampleLocked();
                }
                clearPaletteTask(this);
            }

            final int finalColor = ensureVisible(color, mDark);
            mMainHandler.post(() -> {
                if (mDestroyed || mCancelled
                        || mGeneration != mResolveGeneration
                        || !Objects.equals(mTrackId, mCachedTrackId)
                        || mArt != mLastArt) {
                    return;
                }
                mLastResolvedArt = mArt;
                if (mTrackId != null) mCachedColor = finalColor;
                emit(finalColor);
            });
        }

        private void recycleSampleLocked() {
            if (mSample != null && !mSample.isRecycled()) {
                mSample.recycle();
            }
            mSample = null;
        }
    }

    private void clearPaletteTask(PaletteTask task) {
        synchronized (mPaletteTaskLock) {
            if (mPendingPaletteTask == task) mPendingPaletteTask = null;
        }
    }

    private void emitIfCurrent(int generation, int argb) {
        if (!mDestroyed && generation == mResolveGeneration) emit(argb);
    }

    private void emit(int argb) {
        if (mDestroyed) return;
        mCurrentColor = argb;
        if (mCallback != null) mCallback.onMusicRingColorChanged(argb);
    }

    private void emitFallback(int generation) {
        emitIfCurrent(generation, resolveAccent());
    }

    private void invalidateCache() {
        mCachedColor = DEFAULT_FALLBACK;
        mLastResolvedArt = null;
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
        long r = 0;
        long g = 0;
        long b = 0;
        long weight = 0;
        for (int px : pixels) {
            int a = Color.alpha(px);
            if (a < 32) continue;
            r += (long) Color.red(px) * a;
            g += (long) Color.green(px) * a;
            b += (long) Color.blue(px) * a;
            weight += a;
        }
        if (weight == 0) return DEFAULT_FALLBACK;
        return Color.rgb((int) (r / weight), (int) (g / weight), (int) (b / weight));
    }

    private static Bitmap drawableToBitmap(Drawable d, int dim) {
        if (d == null || dim <= 0) return null;
        Bitmap bmp = null;
        Rect oldBounds = d.copyBounds();
        try {
            bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, dim, dim);
            d.draw(canvas);
            return bmp;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            return null;
        } finally {
            try {
                d.setBounds(oldBounds);
            } catch (RuntimeException ignored) {
            }
        }
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
