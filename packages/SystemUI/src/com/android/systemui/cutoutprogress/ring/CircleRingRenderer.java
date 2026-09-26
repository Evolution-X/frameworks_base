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
import android.graphics.Paint;
import android.graphics.RectF;

public final class CircleRingRenderer implements RingViewRenderer {

    private final RectF mBounds = new RectF();

    @Override
    public void updateBounds(RectF bounds) {
        if (bounds == null || bounds.isEmpty()) {
            mBounds.setEmpty();
        } else {
            mBounds.set(bounds);
        }
    }

    @Override
    public void drawFullRing(Canvas canvas, Paint paint) {
        canvas.drawArc(mBounds, 0f, 360f, false, paint);
    }

    @Override
    public void drawProgress(Canvas canvas, float sweepFraction,
                             boolean clockwise, Paint paint) {
        float sweep  = 360f * Math.max(0f, Math.min(1f, sweepFraction));
        float actual = clockwise ? sweep : -sweep;
        canvas.drawArc(mBounds, -90f, actual, false, paint);
    }

    @Override
    public void drawSymmetricProgress(Canvas canvas, float sweepFraction, Paint paint) {
        float fraction = Math.max(0f, Math.min(1f, sweepFraction));
        if (fraction <= 0f) return;
        float sweep = fraction * 180f;
        canvas.drawArc(mBounds, 90f - sweep, sweep, false, paint);
        canvas.drawArc(mBounds, 90f, sweep, false, paint);
    }

    @Override
    public boolean getPointAndOutwardNormal(float fraction, float[] position, float[] normal) {
        if (position == null || position.length < 2 || normal == null || normal.length < 2
                || mBounds.isEmpty()) return false;
        float f = fraction - (float) Math.floor(fraction);
        double angle = -Math.PI / 2.0 + Math.PI * 2.0 * f;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float a = mBounds.width() / 2f;
        float b = mBounds.height() / 2f;
        if (a <= 0f || b <= 0f) return false;
        position[0] = mBounds.centerX() + a * cos;
        position[1] = mBounds.centerY() + b * sin;
        float nx = cos / a;
        float ny = sin / b;
        float len = (float) Math.hypot(nx, ny);
        if (len <= 0f) return false;
        normal[0] = nx / len;
        normal[1] = ny / len;
        return true;
    }

    @Override
    public void drawSegmented(Canvas canvas,
                              int segments, float gapDeg, float arcDeg,
                              int highlight,
                              Paint basePaint, Paint shinePaint, float alpha) {
        if (mBounds.isEmpty() || segments <= 0) return;
        float safeArcDeg = Math.max(0f, arcDeg);
        float safeGapDeg = Math.max(0f, gapDeg);
        if (safeArcDeg + safeGapDeg <= 0f) return;

        for (int i = 0; i < segments; i++) {
            float startAngle = -90f + i * (safeArcDeg + safeGapDeg);
            if (i == highlight || i == highlight - 1) {
                Paint tmp = new Paint(shinePaint);
                tmp.setAlpha((int)(255 * Math.max(0f, Math.min(1f, alpha))));
                canvas.drawArc(mBounds, startAngle, safeArcDeg, false, tmp);
            } else {
                canvas.drawArc(mBounds, startAngle, safeArcDeg, false, basePaint);
            }
        }
    }
}
