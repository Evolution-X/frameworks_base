/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.systemui.globalactions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import com.android.axion.blur.AxBlurBackgroundRenderer;
import com.android.axion.blur.AxBlurColors;
import com.android.systemui.common.ui.view.LaunchableConstraintLayout;

public class GlobalActionsBlurConstraintLayout extends LaunchableConstraintLayout {
    private final AxBlurBackgroundRenderer mBackdropBlur;

    public GlobalActionsBlurConstraintLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBackdropBlur = new AxBlurBackgroundRenderer(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackdropBlur.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        mBackdropBlur.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return mBackdropBlur.verifyDrawable(who) || super.verifyDrawable(who);
    }

    @Override
    public void draw(Canvas canvas) {
        mBackdropBlur.drawBackground(
                canvas,
                getBackground(),
                AxBlurColors.surfaceContainerTint(getContext()));
        super.draw(canvas);
    }
}
