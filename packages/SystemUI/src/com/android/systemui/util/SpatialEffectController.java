/*
 * Copyright (C) 2026 MistOS
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

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;

public final class SpatialEffectController implements SensorEventListener {

    private static final String SPATIAL_SENSITIVITY_KEY = "system:depth_wallpaper_spatial_sensitivity";

    private static final int SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME;

    private static final float LOW_PASS_ALPHA = 0.15f;
    private static final float STABLE_SPEED_THRESHOLD = 0.08f;
    private static final float ADAPT_ALPHA_STABLE = 0.05f;
    private static final float ADAPT_ALPHA_MOVING = 0.003f;
    private static final float DEAD_ZONE_DEGREES = 0.4f;
    private static final float MAX_TILT_DEGREES = 18.0f;
    private static final float MAX_X_DP = 24f;
    private static final float MAX_Y_DP = 18f;
    private static final float BACKGROUND_MULTIPLIER = 0.50f;
    private static final float FOREGROUND_MULTIPLIER = 0.60f;
    private static final float SPRING_FACTOR = 0.16f;
    private static final float SETTLE_THRESHOLD_PX = 0.25f;
    private static final long RESET_DURATION_MS = 300;

    private static final int SENSOR_MODE_GAME_ROTATION_VECTOR = 0;
    private static final int SENSOR_MODE_ROTATION_VECTOR = 1;
    private static final int SENSOR_MODE_ACCELEROMETER = 2;
    private static final int SENSOR_MODE_NONE = 3;

    private final Context mContext;
    private final SensorManager mSensorManager;
    private final WindowManager mWindowManager;
    private final TunerService mTunerService;

    private View mBackgroundView;
    private View mSubjectView;

    private Sensor mActiveSensor;
    private int mSensorMode = SENSOR_MODE_NONE;

    private boolean mActive = false;
    private boolean mSensorRegistered = false;
    private boolean mFrameScheduled = false;

    private final float[] mRotationMatrix = new float[9];
    private final float[] mOrientationOut = new float[3];

    private float mReferenceTiltXDeg = 0f;
    private float mReferenceTiltYDeg = 0f;
    private float mLastTiltXDeg = 0f;
    private float mLastTiltYDeg = 0f;
    private boolean mFirstSampleReady = false;

    private float mFilteredTiltXDeg = 0f;
    private float mFilteredTiltYDeg = 0f;

    private int mCachedDisplayRotation = Surface.ROTATION_0;

    private int mSensitivityPercent = 100;
    private float mSensitivityScale = 0.50f;

    private float mBgCurrentTransX = 0f;
    private float mBgCurrentTransY = 0f;
    private float mFgCurrentTransX = 0f;
    private float mFgCurrentTransY = 0f;
    private float mTargetTransX = 0f;
    private float mTargetTransY = 0f;

    private float mMaxXPx;
    private float mMaxYPx;

    private boolean mResetAnimating = false;

    private final TunerService.Tunable mTunable = (key, newValue) -> {
        if (SPATIAL_SENSITIVITY_KEY.equals(key)) {
            mSensitivityPercent = TunerService.parseInteger(newValue, 100);
            updateSensitivityScale();
        }
    };

    private final Choreographer.FrameCallback mSpringCallback = frameTimeNanos -> {
        mFrameScheduled = false;
        if (mResetAnimating) {
            return;
        }
        float bgTargetX = mTargetTransX * BACKGROUND_MULTIPLIER;
        float bgTargetY = mTargetTransY * BACKGROUND_MULTIPLIER;
        float fgTargetX = mTargetTransX * FOREGROUND_MULTIPLIER;
        float fgTargetY = mTargetTransY * FOREGROUND_MULTIPLIER;

        mBgCurrentTransX += (bgTargetX - mBgCurrentTransX) * SPRING_FACTOR;
        mBgCurrentTransY += (bgTargetY - mBgCurrentTransY) * SPRING_FACTOR;
        mFgCurrentTransX += (fgTargetX - mFgCurrentTransX) * SPRING_FACTOR;
        mFgCurrentTransY += (fgTargetY - mFgCurrentTransY) * SPRING_FACTOR;

        if (mBackgroundView != null) {
            mBackgroundView.setTranslationX(mBgCurrentTransX);
            mBackgroundView.setTranslationY(mBgCurrentTransY);
        }
        if (mSubjectView != null) {
            mSubjectView.setTranslationX(mFgCurrentTransX);
            mSubjectView.setTranslationY(mFgCurrentTransY);
        }

        boolean bgSettled = Math.abs(bgTargetX - mBgCurrentTransX) <= SETTLE_THRESHOLD_PX
                && Math.abs(bgTargetY - mBgCurrentTransY) <= SETTLE_THRESHOLD_PX;
        boolean fgSettled = Math.abs(fgTargetX - mFgCurrentTransX) <= SETTLE_THRESHOLD_PX
                && Math.abs(fgTargetY - mFgCurrentTransY) <= SETTLE_THRESHOLD_PX;

        if (!bgSettled || !fgSettled) {
            scheduleFrame();
        }
    };

    public SpatialEffectController(Context context) {
        mContext = context.getApplicationContext();
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mTunerService = Dependency.get(TunerService.class);
        if (mTunerService != null) {
            mTunerService.addTunable(mTunable, SPATIAL_SENSITIVITY_KEY);
        }
        updateSensitivityScale();
        recalculateMaxOffsets();
        selectBestSensor();
    }

    private void updateSensitivityScale() {
        float s = Math.max(0f, Math.min(100f, (float) mSensitivityPercent)) / 100f;
        mSensitivityScale = 1.0f - 0.50f * (0.7f * s * s + 0.3f * s);
    }

    public void setSensitivity(int sensitivityPercent) {
        mSensitivityPercent = sensitivityPercent;
        updateSensitivityScale();
    }

    public void attachBackgroundView(View view) {
        mBackgroundView = view;
        recalculateMaxOffsets();
    }

    public void attachSubjectView(View view) {
        mSubjectView = view;
        recalculateMaxOffsets();
    }

    public void onDisplayRotationChanged(int rotation) {
        mCachedDisplayRotation = rotation;
    }

    public void setActive(boolean active) {
        if (mActive == active) {
            return;
        }
        mActive = active;
        if (mActive) {
            mBgCurrentTransX = 0f;
            mBgCurrentTransY = 0f;
            mFgCurrentTransX = 0f;
            mFgCurrentTransY = 0f;
            mTargetTransX = 0f;
            mTargetTransY = 0f;
            if (mBackgroundView != null) {
                mBackgroundView.setTranslationX(0f);
                mBackgroundView.setTranslationY(0f);
            }
            if (mSubjectView != null) {
                mSubjectView.setTranslationX(0f);
                mSubjectView.setTranslationY(0f);
            }
            refreshCachedDisplayRotation();
            startSensing();
        } else {
            stopSensingAndReset();
        }
    }

    public void destroy() {
        mActive = false;
        stopSensing();
        if (mTunerService != null) {
            mTunerService.removeTunable(mTunable);
        }
        mBackgroundView = null;
        mSubjectView = null;
    }

    private void selectBestSensor() {
        if (mSensorManager == null) {
            mActiveSensor = null;
            mSensorMode = SENSOR_MODE_NONE;
            return;
        }
        Sensor gameRv = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (gameRv != null) {
            mActiveSensor = gameRv;
            mSensorMode = SENSOR_MODE_GAME_ROTATION_VECTOR;
            return;
        }
        Sensor rv = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            mActiveSensor = rv;
            mSensorMode = SENSOR_MODE_ROTATION_VECTOR;
            return;
        }
        Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            mActiveSensor = accel;
            mSensorMode = SENSOR_MODE_ACCELEROMETER;
            return;
        }
        mActiveSensor = null;
        mSensorMode = SENSOR_MODE_NONE;
    }

    private void startSensing() {
        if (mSensorRegistered || mSensorMode == SENSOR_MODE_NONE || mActiveSensor == null) {
            return;
        }
        boolean registered = mSensorManager.registerListener(this, mActiveSensor, SENSOR_DELAY);
        if (registered) {
            mSensorRegistered = true;
            mFirstSampleReady = false;
        }
    }

    private void stopSensing() {
        if (!mSensorRegistered || mSensorManager == null) {
            return;
        }
        mSensorManager.unregisterListener(this);
        mSensorRegistered = false;
        mFirstSampleReady = false;
    }

    private void stopSensingAndReset() {
        stopSensing();
        mTargetTransX = 0f;
        mTargetTransY = 0f;
        mResetAnimating = true;
        final Runnable onEnd = () -> {
            mBgCurrentTransX = 0f;
            mBgCurrentTransY = 0f;
            mFgCurrentTransX = 0f;
            mFgCurrentTransY = 0f;
            mResetAnimating = false;
        };
        int pendingAnimations = 0;
        if (mBackgroundView != null) {
            pendingAnimations++;
        }
        if (mSubjectView != null) {
            pendingAnimations++;
        }
        if (pendingAnimations == 0) {
            mBgCurrentTransX = 0f;
            mBgCurrentTransY = 0f;
            mFgCurrentTransX = 0f;
            mFgCurrentTransY = 0f;
            mResetAnimating = false;
            return;
        }
        final int[] remaining = {pendingAnimations};
        final Runnable sharedEnd = () -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                onEnd.run();
            }
        };
        if (mBackgroundView != null) {
            mBackgroundView.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(RESET_DURATION_MS)
                    .withEndAction(sharedEnd)
                    .start();
        }
        if (mSubjectView != null) {
            mSubjectView.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(RESET_DURATION_MS)
                    .withEndAction(sharedEnd)
                    .start();
        }
    }

    private void refreshCachedDisplayRotation() {
        if (mWindowManager == null) {
            return;
        }
        try {
            mCachedDisplayRotation = mWindowManager.getDefaultDisplay().getRotation();
        } catch (Exception ignored) {
        }
    }

    private void recalculateMaxOffsets() {
        DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        mMaxXPx = MAX_X_DP * dm.density;
        mMaxYPx = MAX_Y_DP * dm.density;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mActive || mResetAnimating) {
            return;
        }
        if (mBackgroundView == null && mSubjectView == null) {
            return;
        }
        float rawTiltXDeg;
        float rawTiltYDeg;
        if (mSensorMode == SENSOR_MODE_GAME_ROTATION_VECTOR
                || mSensorMode == SENSOR_MODE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
            SensorManager.getOrientation(mRotationMatrix, mOrientationOut);
            rawTiltXDeg = extractTiltX(mOrientationOut);
            rawTiltYDeg = extractTiltY(mOrientationOut);
        } else {
            rawTiltXDeg = extractAccelTiltX(event.values);
            rawTiltYDeg = extractAccelTiltY(event.values);
        }
        if (!mFirstSampleReady) {
            mFilteredTiltXDeg = rawTiltXDeg;
            mFilteredTiltYDeg = rawTiltYDeg;
            mReferenceTiltXDeg = rawTiltXDeg;
            mReferenceTiltYDeg = rawTiltYDeg;
            mLastTiltXDeg = rawTiltXDeg;
            mLastTiltYDeg = rawTiltYDeg;
            mFirstSampleReady = true;
            return;
        }
        mFilteredTiltXDeg += LOW_PASS_ALPHA * (rawTiltXDeg - mFilteredTiltXDeg);
        mFilteredTiltYDeg += LOW_PASS_ALPHA * (rawTiltYDeg - mFilteredTiltYDeg);

        float speedX = Math.abs(mFilteredTiltXDeg - mLastTiltXDeg);
        float speedY = Math.abs(mFilteredTiltYDeg - mLastTiltYDeg);
        mLastTiltXDeg = mFilteredTiltXDeg;
        mLastTiltYDeg = mFilteredTiltYDeg;

        float speed = (float) Math.hypot(speedX, speedY);
        float adaptAlpha = speed < STABLE_SPEED_THRESHOLD ? ADAPT_ALPHA_STABLE : ADAPT_ALPHA_MOVING;

        mReferenceTiltXDeg += adaptAlpha * (mFilteredTiltXDeg - mReferenceTiltXDeg);
        mReferenceTiltYDeg += adaptAlpha * (mFilteredTiltYDeg - mReferenceTiltYDeg);

        float deltaTiltX = mFilteredTiltXDeg - mReferenceTiltXDeg;
        float deltaTiltY = mFilteredTiltYDeg - mReferenceTiltYDeg;

        float effectiveX = Math.abs(deltaTiltX) < DEAD_ZONE_DEGREES
                ? 0f
                : (deltaTiltX - Math.signum(deltaTiltX) * DEAD_ZONE_DEGREES);
        float effectiveY = Math.abs(deltaTiltY) < DEAD_ZONE_DEGREES
                ? 0f
                : (deltaTiltY - Math.signum(deltaTiltY) * DEAD_ZONE_DEGREES);

        float normalizedX = clamp(effectiveX / MAX_TILT_DEGREES);
        float normalizedY = clamp(effectiveY / MAX_TILT_DEGREES);

        float effMaxX = mMaxXPx * mSensitivityScale;
        float effMaxY = mMaxYPx * mSensitivityScale;

        mTargetTransX = clamp(normalizedX * effMaxX, -mMaxXPx, mMaxXPx);
        mTargetTransY = clamp(normalizedY * effMaxY, -mMaxYPx, mMaxYPx);

        scheduleFrame();
    }

    private float extractTiltX(float[] orientation) {
        switch (mCachedDisplayRotation) {
            case Surface.ROTATION_90:
                return (float) Math.toDegrees(-orientation[1]);
            case Surface.ROTATION_180:
                return (float) Math.toDegrees(-orientation[2]);
            case Surface.ROTATION_270:
                return (float) Math.toDegrees(orientation[1]);
            default:
                return (float) Math.toDegrees(orientation[2]);
        }
    }

    private float extractTiltY(float[] orientation) {
        switch (mCachedDisplayRotation) {
            case Surface.ROTATION_90:
                return (float) Math.toDegrees(orientation[2]);
            case Surface.ROTATION_180:
                return (float) Math.toDegrees(-orientation[1]);
            case Surface.ROTATION_270:
                return (float) Math.toDegrees(-orientation[2]);
            default:
                return (float) Math.toDegrees(orientation[1]);
        }
    }

    private float extractAccelTiltX(float[] values) {
        float ax = values[0];
        float az = values[2];
        float norm = (float) Math.sqrt(ax * ax + az * az);
        if (norm < 0.01f) return 0f;
        return (float) Math.toDegrees(Math.asin(clamp(ax / norm)));
    }

    private float extractAccelTiltY(float[] values) {
        float ay = values[1];
        float az = values[2];
        float norm = (float) Math.sqrt(ay * ay + az * az);
        if (norm < 0.01f) return 0f;
        return (float) Math.toDegrees(Math.asin(clamp(-ay / norm)));
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void scheduleFrame() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(mSpringCallback);
        }
    }
}
