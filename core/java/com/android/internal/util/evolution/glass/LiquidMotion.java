/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution.glass;

import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;

final class LiquidMotion {

    interface Listener {
        void onMotion();
    }

    private final Listener mListener;
    private final float mPressedScale;
    private float mMax;
    private float mTarget = Float.NaN;
    private boolean mReleasePending;

    private float mLastValue = Float.NaN;
    private long mLastNanos;

    private final FloatValueHolder mValue = new FloatValueHolder();
    private final FloatValueHolder mVelocity = new FloatValueHolder();
    private final FloatValueHolder mPress = new FloatValueHolder();
    private final FloatValueHolder mScaleX = new FloatValueHolder();
    private final FloatValueHolder mScaleY = new FloatValueHolder();

    private final SpringAnimation mValueAnim = spring(mValue, 1f, 1000f, 0.001f);
    private final SpringAnimation mVelocityAnim = spring(mVelocity, 0.5f, 300f, 0.01f);
    private final SpringAnimation mPressAnim = spring(mPress, 1f, 1000f, 0.001f);
    private final SpringAnimation mScaleXAnim = spring(mScaleX, 0.6f, 250f, 0.001f);
    private final SpringAnimation mScaleYAnim = spring(mScaleY, 0.7f, 250f, 0.001f);

    LiquidMotion(float pressedScale, Listener listener) {
        mPressedScale = pressedScale;
        mListener = listener;
        mValue.setValue(Float.NaN);
        mScaleX.setValue(1f);
        mScaleY.setValue(1f);
        mValueAnim.addUpdateListener((a, value, v) -> onValueChanged(value));
        mValueAnim.addEndListener((a, canceled, value, v) -> {
            mVelocityAnim.animateToFinalPosition(0f);
            checkRelease();
        });
    }

    private SpringAnimation spring(FloatValueHolder holder, float damping, float stiffness,
            float threshold) {
        SpringAnimation anim = new SpringAnimation(holder);
        anim.setSpring(new SpringForce().setDampingRatio(damping).setStiffness(stiffness));
        anim.setMinimumVisibleChange(threshold);
        anim.addUpdateListener((a, value, v) -> mListener.onMotion());
        return anim;
    }

    void setMax(float max) {
        mMax = Math.max(max, 0f);
    }

    float value() {
        return mValue.getValue();
    }

    float target() {
        return mTarget;
    }

    float velocity() {
        return mVelocity.getValue();
    }

    float press() {
        return mPress.getValue();
    }

    float scaleX() {
        return mScaleX.getValue();
    }

    float scaleY() {
        return mScaleY.getValue();
    }

    void snapTo(float value) {
        mValueAnim.cancel();
        mTarget = clamp(value);
        mValue.setValue(mTarget);
        mLastValue = mTarget;
        mListener.onMotion();
    }

    void pressDown() {
        mReleasePending = false;
        mPressAnim.animateToFinalPosition(1f);
        mScaleXAnim.animateToFinalPosition(mPressedScale);
        mScaleYAnim.animateToFinalPosition(mPressedScale);
    }

    void release() {
        mReleasePending = true;
        checkRelease();
    }

    private void checkRelease() {
        if (!mReleasePending) return;
        float threshold = Math.max(mMax, 1e-3f) * 0.025f;
        if (mValueAnim.isRunning() && Math.abs(mValue.getValue() - mTarget) >= threshold) return;
        mReleasePending = false;
        mPressAnim.animateToFinalPosition(0f);
        mScaleXAnim.animateToFinalPosition(1f);
        mScaleYAnim.animateToFinalPosition(1f);
    }

    void updateValue(float value) {
        if (Float.isNaN(mValue.getValue())) {
            snapTo(value);
            return;
        }
        mTarget = clamp(value);
        mValueAnim.animateToFinalPosition(mTarget);
    }

    void animateToValue(float value) {
        pressDown();
        updateValue(value);
        release();
    }

    private void onValueChanged(float value) {
        long now = System.nanoTime();
        if (!Float.isNaN(mLastValue) && mLastNanos != 0) {
            float seconds = (now - mLastNanos) / 1e9f;
            if (seconds > 0f) {
                float span = Math.max(mMax, 1e-6f);
                mVelocityAnim.cancel();
                mVelocity.setValue((value - mLastValue) / seconds / span);
            }
        }
        mLastValue = value;
        mLastNanos = now;
        checkRelease();
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(mMax, value));
    }

    void cancel() {
        mValueAnim.cancel();
        mVelocityAnim.cancel();
        mPressAnim.cancel();
        mScaleXAnim.cancel();
        mScaleYAnim.cancel();
    }
}
