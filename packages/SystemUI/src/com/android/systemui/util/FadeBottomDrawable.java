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
package com.android.systemui.util;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class FadeBottomDrawable extends Drawable {

    private static final int GRADIENT_STOPS = 16;
    private static final float DEFAULT_FADE_CURVE_EXPONENT = 0.45f;

    private final Bitmap mBitmap;
    private final Paint mBitmapPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private int mFadeHeightPx;
    private float mFadeCurveExponent = DEFAULT_FADE_CURVE_EXPONENT;
    private boolean mShaderDirty = true;
    private int mBottomInsetPx;

    public FadeBottomDrawable(@NonNull Bitmap bitmap, int fadeHeightPx) {
        mBitmap = bitmap;
        mFadeHeightPx = Math.max(0, fadeHeightPx);
    }

    public void setFadeHeightPx(int fadeHeightPx) {
        fadeHeightPx = Math.max(0, fadeHeightPx);
        if (mFadeHeightPx != fadeHeightPx) {
            mFadeHeightPx = fadeHeightPx;
            mShaderDirty = true;
            invalidateSelf();
        }
    }

    public int getFadeHeightPx() {
        return mFadeHeightPx;
    }

    public void setBottomInsetPx(int bottomInsetPx) {
        bottomInsetPx = Math.max(0, bottomInsetPx);
        if (mBottomInsetPx != bottomInsetPx) {
            mBottomInsetPx = bottomInsetPx;
            mShaderDirty = true;
            invalidateSelf();
        }
    }

    public int getBottomInsetPx() {
        return mBottomInsetPx;
    }

    public void setFadeCurveExponent(float exponent) {
        exponent = Math.max(0.05f, exponent);
        if (Float.compare(mFadeCurveExponent, exponent) != 0) {
            mFadeCurveExponent = exponent;
            mShaderDirty = true;
            invalidateSelf();
        }
    }

    public float getFadeCurveExponent() {
        return mFadeCurveExponent;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mShaderDirty = true;
    }

    private void updateShader() {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || mBitmap == null || mBitmap.getWidth() == 0
                || mBitmap.getHeight() == 0) {
            mBitmapPaint.setShader(null);
            return;
        }
        final float clipLine = bounds.bottom - mBottomInsetPx;
        BitmapShader bitmapShader =
                new BitmapShader(mBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(
                bounds.width() / (float) mBitmap.getWidth(),
                bounds.height() / (float) mBitmap.getHeight());
        matrix.postTranslate(bounds.left, bounds.top);
        bitmapShader.setLocalMatrix(matrix);

        if (mFadeHeightPx <= 0) {
            buildClippedShader(bitmapShader, bounds, clipLine, clipLine);
            return;
        }

        float fadeStart = clipLine - mFadeHeightPx;
        buildClippedShader(bitmapShader, bounds, fadeStart, clipLine);
    }

    private void buildClippedShader(BitmapShader bitmapShader, Rect bounds, float fadeStart,
            float clipLine) {
        int[] colors = new int[GRADIENT_STOPS + 1];
        float[] positions = new float[GRADIENT_STOPS + 1];
        float bandHeight = Math.max(1f, clipLine - fadeStart);
        for (int i = 0; i <= GRADIENT_STOPS; i++) {
            float t = (float) i / GRADIENT_STOPS;
            positions[i] = t;
            float visibility = (1f - (float) Math.pow(t, mFadeCurveExponent));
            int alpha = Math.round(visibility * 255f);
            colors[i] = Color.argb(alpha, 255, 255, 255);
        }

        colors[GRADIENT_STOPS] = Color.argb(0, 255, 255, 255);

        LinearGradient maskGradient = new LinearGradient(
                bounds.left, fadeStart,
                0f, clipLine,
                colors, positions,
                Shader.TileMode.CLAMP);

        mBitmapPaint.setShader(
                new ComposeShader(bitmapShader, maskGradient, PorterDuff.Mode.DST_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mBitmap == null || mBitmap.isRecycled()) {
            return;
        }

        if (mShaderDirty) {
            updateShader();
            mShaderDirty = false;
        }
        if (mBitmapPaint.getShader() == null) {
            return;
        }
        canvas.drawRect(getBounds(), mBitmapPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mBitmapPaint.getAlpha() != alpha) {
            mBitmapPaint.setAlpha(alpha);
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mBitmapPaint.getAlpha();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBitmapPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmap != null ? mBitmap.getWidth() : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmap != null ? mBitmap.getHeight() : -1;
    }
}
