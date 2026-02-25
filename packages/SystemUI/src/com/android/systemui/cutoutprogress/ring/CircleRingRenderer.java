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
        mBounds.set(bounds);
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
    public void drawSegmented(Canvas canvas,
                              int segments, float gapDeg, float arcDeg,
                              int highlight,
                              Paint basePaint, Paint shinePaint, float alpha) {
        for (int i = 0; i < segments; i++) {
            float startAngle = -90f + i * (arcDeg + gapDeg);
            if (i == highlight || i == highlight - 1) {
                Paint tmp = new Paint(shinePaint);
                tmp.setAlpha((int)(255 * alpha));
                canvas.drawArc(mBounds, startAngle, arcDeg, false, tmp);
            } else {
                canvas.drawArc(mBounds, startAngle, arcDeg, false, basePaint);
            }
        }
    }
}
