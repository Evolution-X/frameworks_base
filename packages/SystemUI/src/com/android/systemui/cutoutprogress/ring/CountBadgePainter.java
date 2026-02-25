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

package com.android.systemui.cutoutprogress.ring;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.core.graphics.ColorUtils;

public final class CountBadgePainter {

    private static final float H_PAD_DP = 6f;
    private static final float V_PAD_DP = 3f;
    private static final float MIN_WIDTH_DP = 20f;
    private static final float DARKEN_AMOUNT = 0.75f;
    private static final float BRIGHTEN_AMOUNT = 0.80f;
    private static final float LIGHT_THRESHOLD = 0.40f;
    private static final int BG_ALPHA = 230;

    private final float mDp;
    private final Paint mBgPaint;
    private final Paint mTextPaint;
    private final RectF mRect = new RectF();

    private int mLastCount = Integer.MIN_VALUE;
    private String mLastText = "";

    public CountBadgePainter(float density) {
        mDp = density;

        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBgPaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setSubpixelText(true);
        mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void applyConfig(int baseColor, float textSizeSp, float density) {
        double lum = ColorUtils.calculateLuminance(baseColor);
        mBgPaint.setColor(darken(baseColor, DARKEN_AMOUNT));
        int textColor = lum > LIGHT_THRESHOLD
                ? Color.WHITE : brighten(baseColor, BRIGHTEN_AMOUNT);
        mTextPaint.setColor(textColor);
        mTextPaint.setTextSize(textSizeSp * density);
    }

    public void draw(Canvas canvas, float cx, float topY, int count, int opacityPercent) {
        if (count != mLastCount) {
            mLastCount = count;
            mLastText  = String.valueOf(count);
        }

        float textW = mTextPaint.measureText(mLastText);
        float hPad = H_PAD_DP * mDp;
        float vPad = V_PAD_DP * mDp;
        float height = mTextPaint.getTextSize() + vPad * 2f;
        float width = Math.max(textW + hPad * 2f, MIN_WIDTH_DP * mDp);

        mRect.set(cx - width / 2f, topY, cx + width / 2f, topY + height);

        int baseAlpha = clamp(opacityPercent, 0, 100) * 255 / 100;
        mBgPaint.setAlpha(baseAlpha * BG_ALPHA / 255);
        mTextPaint.setAlpha(baseAlpha);

        canvas.drawRoundRect(mRect, height / 2f, height / 2f, mBgPaint);

        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float textY = mRect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mLastText, cx, textY, mTextPaint);
    }

    private static int darken(int color, float fraction) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[2] *= (1f - fraction);
        return ColorUtils.HSLToColor(hsl);
    }

    private static int brighten(int color, float fraction) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[2] += (1f - hsl[2]) * fraction;
        return ColorUtils.HSLToColor(hsl);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
