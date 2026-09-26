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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;

import com.android.systemui.cutoutprogress.CutoutProgressSettings;

import java.util.Objects;

public final class CutoutRingView extends View {

    private static final long BURN_IN_HIDE_MS = 10_000L;

    private static final long CHARGING_PULSE_DURATION_MS = 900L;

    private static final int SOURCE_NONE = 0;
    private static final int SOURCE_DOWNLOAD = 1;
    private static final int SOURCE_MUSIC = 2;
    private static final int SOURCE_CHARGING = 3;
    private static final int SOURCE_BATTERY = 4;
    private static final int SOURCE_TIMER = 5;

    private static final int[] RAINBOW_COLORS = {
            0xFFFF0000,
            0xFFFF7F00,
            0xFFFFFF00,
            0xFF00FF00,
            0xFF00FFFF,
            0xFF0000FF,
            0xFF8B00FF,
            0xFFFF0000,
    };

    private float mDp;
    private float mScaledDensity;
    private final CameraCutoutGeometryResolver mGeometryResolver;

    private final Path mCutoutPath = new Path();
    private final Path mScaledPath = new Path();
    private final Matrix mScaleMatrix = new Matrix();
    private final Matrix mShaderMatrix = new Matrix();
    private final float[] mEffectPosition = new float[2];
    private final float[] mEffectNormal = new float[2];
    private final float[] mRotatedOffset = new float[2];
    private final RectF mPathBounds = new RectF();
    private final RectF mArcBounds = new RectF();
    private boolean mHasCutout = false;
    private boolean mAutoGeometryActive = false;
    private boolean mResolvedPillLike = false;
    private int mGeometryRotation = Surface.ROTATION_0;
    private int mGeometrySource = CameraCutoutGeometryResolver.SOURCE_NONE;

    private final OverlayAnimationHelper mAnim;
    private RingViewRenderer mRenderer;
    private CountBadgePainter mBadge;

    private final Paint mRingPaint = makePaint();
    private final Paint mShinePaint = makePaint();
    private final Paint mErrorPaint = makePaint();
    private final Paint mAnimPaint = makePaint();
    private final Paint mBgPaint = makePaint();
    private final Paint mChargingPaint = makePaint();
    private final TextPaint mPercentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mFilenamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Paint mRainbowPaint = makePaint();
    private SweepGradient mRainbowShader = null;
    private float mRainbowCx = Float.NaN;
    private float mRainbowCy = Float.NaN;
    private final Paint mMusicPaint = makePaint();
    private final Paint mMusicWavePaint = makePaint();
    private final Paint mTimerPaint = makePaint();
    private final Paint mFlamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAuroraPaint = makePaint();
    private SweepGradient mAuroraShader = null;
    private float mAuroraCx = Float.NaN;
    private float mAuroraCy = Float.NaN;

    private int mProgress = 0;
    private int mDownloadCount = 0;
    private String mFilenameHint = null;

    private long mDownloadStartMs = 0L;
    private long mLastProgressMs = 0L;
    private Runnable mPendingFinish = null;

    private boolean mIsCharging = false;
    private int mBatteryPct = 0;
    private boolean mChargingPulseEnabled = true;
    private float mChargingPulsePhase = 0f;
    private boolean mChargingPulseScheduled = false;
    private long mChargingPulseEpochMs = 0L;
    private float mChargingDisplayPct = 0f;
    private ValueAnimator mChargingLevelAnim = null;

    private boolean mIsBatteryIndicatorActive = false;
    private int mBatteryIndicatorPct = 0;
    private float mBatteryDisplayPct = 0f;
    private ValueAnimator mBatteryLevelAnim = null;

    private boolean mMusicPlaying = false;
    private float mMusicFraction = 0f;
    private float mMusicWavePhase = 0f;
    private boolean mMusicWaveScheduled = false;
    private long mMusicWaveEpochMs = 0L;

    private boolean mTimerActive = false;
    private float mTimerFraction = 0f;

    private boolean mAuroraCallActive = false;
    private boolean mAuroraRecordingActive = false;
    private long mNotificationAuroraUntilMs = 0L;
    private int mNotificationAuroraColor = 0;
    private float mVisualEffectPhase = 0f;
    private boolean mVisualEffectScheduled = false;
    private long mVisualEffectEpochMs = 0L;

    private int sCfgRingColorMode;
    private int sCfgRingColor;
    private int sCfgErrorColor;
    private int sCfgFlashColor;
    private float sCfgStrokeDp;
    private float sCfgRingGap;
    private int sCfgOpacity;
    private boolean sCfgClockwise;
    private String sCfgFinishStyle;
    private int sCfgFinishHoldMs;
    private int sCfgFinishExitMs;
    private boolean sCfgFinishFlash;
    private boolean sCfgPulse;
    private boolean sCfgAutoGeometry = true;
    private boolean sCfgPathMode;
    private float sCfgScaleX;
    private float sCfgScaleY;
    private float sCfgOffsetXDp;
    private float sCfgOffsetYDp;
    private boolean sCfgBgRing;
    private int sCfgBgColor;
    private int sCfgBgOpacity;
    private boolean sCfgMinVis;
    private int sCfgMinVisMs;
    private boolean sCfgShowBadge;
    private float sCfgBadgeOffXDp;
    private float sCfgBadgeOffYDp;
    private float sCfgBadgeSp;
    private boolean sCfgPct;
    private float sCfgPctSp;
    private boolean sCfgPctBold;
    private String sCfgPctPos;
    private float sCfgPctOffXDp;
    private float sCfgPctOffYDp;
    private boolean sCfgFname;
    private float sCfgFnameSp;
    private boolean sCfgFnameBold;
    private String sCfgFnamePos;
    private float sCfgFnameOffXDp;
    private float sCfgFnameOffYDp;
    private int sCfgFnameMaxChars;
    private String sCfgFnameTruncate;
    private String sCfgEasing;
    private boolean sCfgChargingRing;
    private boolean sCfgChargingPulse;
    private boolean sCfgGlowEnabled;
    private float sCfgGlowRadiusDp;

    private boolean sCfgMusicRingEnabled = false;
    private int sCfgMusicOpacity = 85;
    private float sCfgMusicStrokeDp = 2f;
    private boolean sCfgMusicClockwise = true;
    private boolean sCfgMusicShowOnAod = false;
    private int sCfgMusicColor = 0xFF9C27B0;
    private int sCfgDownloadPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
    private int sCfgMusicPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
    private int sCfgPrimaryPriority = CutoutProgressSettings.PRIMARY_PRIORITY_DOWNLOAD;
    private float sCfgMultiRingSpacingDp = 5f;
    private boolean sCfgMusicWaveEnabled = false;
    private float sCfgMusicWaveAmplitudeDp = 2.5f;
    private int sCfgMusicWaveDensity = 48;
    private int sCfgMusicWaveSpeed = 100;

    private boolean sCfgTimerEnabled = false;
    private int sCfgTimerPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
    private int sCfgTimerColorMode = CutoutProgressSettings.RING_COLOR_MODE_ACCENT;
    private int sCfgTimerColor = 0xFFFF8A00;
    private int sCfgTimerOpacity = 95;
    private float sCfgTimerStrokeDp = 2f;
    private boolean sCfgTimerClockwise = true;
    private boolean sCfgTimerFlameEnabled = true;
    private int sCfgTimerFlameColor = 0xFFFF6D00;
    private float sCfgTimerFlameSizeDp = 3.5f;

    private boolean sCfgAuroraEnabled = false;
    private boolean sCfgAuroraCalls = true;
    private boolean sCfgAuroraMusic = true;
    private boolean sCfgAuroraRecording = true;
    private boolean sCfgAuroraNotifications = true;
    private int sCfgAuroraColorMode = CutoutProgressSettings.AURORA_COLOR_MODE_SPECTRUM;
    private int sCfgAuroraColor = 0xFF7C4DFF;
    private int sCfgAuroraNotificationColorMode =
            CutoutProgressSettings.AURORA_NOTIFICATION_COLOR_NOTIFICATION;
    private float sCfgAuroraSpreadDp = 8f;
    private int sCfgAuroraOpacity = 85;
    private int sCfgAuroraSpeed = 100;

    private final Runnable mChargingPulseTick = new Runnable() {
        @Override
        public void run() {
            mChargingPulseScheduled = false;
            if (!mIsCharging || !mChargingPulseEnabled || !sCfgChargingPulse
                    || !isAttachedToWindow() || !mHasCutout) {
                return;
            }

            if (isDisplayNonInteractive()) {
                // The non-animated charging ring is drawn from the battery level when no pulse
                // frame is scheduled. Stop here rather than waking SystemUI once per second.
                return;
            }

            long cycle = CHARGING_PULSE_DURATION_MS * 2L;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - mChargingPulseEpochMs);
            float unit = (elapsed % cycle) / (float) CHARGING_PULSE_DURATION_MS;
            mChargingPulsePhase = unit <= 1f ? unit : 2f - unit;
            invalidate();

            mChargingPulseScheduled = true;
            postDelayed(this, 33L);
        }
    };

    private final Runnable mMusicWaveTick = new Runnable() {
        @Override
        public void run() {
            mMusicWaveScheduled = false;
            if (!mMusicPlaying || !sCfgMusicRingEnabled || !sCfgMusicWaveEnabled
                    || isAuroraActiveNow() || !isAttachedToWindow() || !mHasCutout) {
                return;
            }

            boolean nonInteractive = isDisplayNonInteractive();
            boolean visibleWhileIdle = sCfgMusicShowOnAod && isDisplayAod();
            if (nonInteractive && !visibleWhileIdle) {
                // No visible waveform in this display state. Do not keep a 1 Hz wakeup loop;
                // onDisplayStateChanged() will restart the animation when it can be shown again.
                return;
            }

            long duration = musicWaveDurationMs();
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - mMusicWaveEpochMs);
            mMusicWavePhase = (float) ((elapsed % duration)
                    * (Math.PI * 2.0) / duration);
            invalidate();

            long delay = nonInteractive ? 1000L : 33L;
            mMusicWaveScheduled = true;
            postDelayed(this, delay);
        }
    };

    private final Runnable mVisualEffectTick = new Runnable() {
        @Override
        public void run() {
            mVisualEffectScheduled = false;
            long now = SystemClock.elapsedRealtime();
            if (mNotificationAuroraUntilMs > 0L && now >= mNotificationAuroraUntilMs) {
                mNotificationAuroraUntilMs = 0L;
                updateMusicWaveAnimation();
                invalidate();
            }

            if (!isAttachedToWindow() || !mHasCutout || !needsVisualEffectAnimation()) {
                return;
            }

            boolean nonInteractive = isDisplayNonInteractive();
            if (nonInteractive) {
                // Aurora is intentionally suppressed while the display is non-interactive.
                // A timer flame does not need an idle 1 Hz animation loop either; keep the
                // current frame stable and let onDisplayStateChanged() restart it on wake.
                return;
            }
            long duration = visualEffectDurationMs();
            long elapsed = Math.max(0L, now - mVisualEffectEpochMs);
            mVisualEffectPhase = (float) ((elapsed % duration)
                    * (Math.PI * 2.0) / duration);
            invalidate();

            mVisualEffectScheduled = true;
            long delay = nonInteractive ? 1000L : (isAuroraActiveNow() ? 33L : 83L);
            postDelayed(this, delay);
        }
    };

    public CutoutRingView(Context ctx) {
        super(ctx);
        mDp = ctx.getResources().getDisplayMetrics().density;
        mScaledDensity = ctx.getResources().getDisplayMetrics().scaledDensity;
        mGeometryResolver = new CameraCutoutGeometryResolver(ctx);
        mAnim = new OverlayAnimationHelper(this);
        mRenderer = new CircleRingRenderer();
        mBadge = new CountBadgePainter(mDp);
        initPaints();
    }

    public void applySettings(CutoutProgressSettings s) {
        sCfgRingColorMode = s.getRingColorMode();
        sCfgRingColor = s.getRingColor();
        sCfgErrorColor = s.getErrorColor();
        sCfgFlashColor = s.getFinishFlashColor();
        sCfgStrokeDp = s.getStrokeWidthDp();
        sCfgRingGap = s.getRingGap();
        sCfgOpacity = s.getOpacity();
        sCfgClockwise = s.isClockwise();
        sCfgFinishStyle  = s.getFinishStyle();
        sCfgFinishHoldMs = s.getFinishHoldMs();
        sCfgFinishExitMs = s.getFinishExitMs();
        sCfgFinishFlash = s.isFinishUseFlash();
        sCfgPulse = s.isCompletionPulse();
        sCfgAutoGeometry = s.isAutoGeometryEnabled();
        sCfgPathMode = s.isPathMode();
        sCfgScaleX = s.getRingScaleX();
        sCfgScaleY = s.getRingScaleY();
        sCfgOffsetXDp = s.getRingOffsetXDp();
        sCfgOffsetYDp = s.getRingOffsetYDp();
        sCfgBgRing = s.isBgRingEnabled();
        sCfgBgColor = s.getBgRingColor();
        sCfgBgOpacity = s.getBgRingOpacity();
        sCfgMinVis = s.isMinVisEnabled();
        sCfgMinVisMs = s.getMinVisMs();
        sCfgShowBadge = s.isShowCountBadge();
        sCfgBadgeOffXDp = s.getBadgeOffsetXDp();
        sCfgBadgeOffYDp = s.getBadgeOffsetYDp();
        sCfgBadgeSp = s.getBadgeTextSizeSp();
        sCfgPct = s.isPercentEnabled();
        sCfgPctSp = s.getPercentTextSizeSp();
        sCfgPctBold = s.isPercentBold();
        sCfgPctPos = s.getPercentPosition();
        sCfgPctOffXDp = s.getPercentOffsetXDp();
        sCfgPctOffYDp = s.getPercentOffsetYDp();
        sCfgFname = s.isFilenameEnabled();
        sCfgFnameSp = s.getFilenameTextSizeSp();
        sCfgFnameBold = s.isFilenameBold();
        sCfgFnamePos = s.getFilenamePosition();
        sCfgFnameOffXDp = s.getFilenameOffsetXDp();
        sCfgFnameOffYDp = s.getFilenameOffsetYDp();
        sCfgFnameMaxChars= s.getFilenameMaxChars();
        sCfgFnameTruncate= s.getFilenameTruncateMode();
        sCfgEasing = s.getProgressEasing();
        sCfgChargingRing = s.isChargingRingEnabled();
        sCfgChargingPulse = s.isChargingPulseEnabled();
        sCfgGlowEnabled = s.isGlowEnabled();
        sCfgGlowRadiusDp = s.getGlowRadiusDp();
        sCfgDownloadPresentation = s.getDownloadPresentation();
        sCfgMusicPresentation = s.getMusicPresentation();
        sCfgPrimaryPriority = s.getPrimaryPriority();
        sCfgMultiRingSpacingDp = s.getMultiRingSpacingDp();
        sCfgMusicRingEnabled = s.isMusicRingEnabled();
        sCfgMusicWaveEnabled = s.isMusicWaveEnabled();
        sCfgMusicWaveAmplitudeDp = s.getMusicWaveAmplitudeDp();
        sCfgMusicWaveDensity = s.getMusicWaveDensity();
        sCfgMusicWaveSpeed = s.getMusicWaveSpeed();
        sCfgMusicShowOnAod = s.isMusicShowOnAod();

        sCfgTimerEnabled = s.isTimerEnabled();
        sCfgTimerPresentation = s.getTimerPresentation();
        sCfgTimerColorMode = s.getTimerColorMode();
        sCfgTimerColor = s.getTimerCustomColor();
        sCfgTimerOpacity = s.getTimerOpacity();
        sCfgTimerStrokeDp = s.getTimerStrokeWidthDp();
        sCfgTimerClockwise = s.isTimerClockwise();
        sCfgTimerFlameEnabled = s.isTimerFlameEnabled();
        sCfgTimerFlameColor = s.getTimerFlameColor();
        sCfgTimerFlameSizeDp = s.getTimerFlameSizeDp();

        sCfgAuroraEnabled = s.isAuroraEnabled();
        sCfgAuroraCalls = s.isAuroraCallsEnabled();
        sCfgAuroraMusic = s.isAuroraMusicEnabled();
        sCfgAuroraRecording = s.isAuroraRecordingEnabled();
        sCfgAuroraNotifications = s.isAuroraNotificationsEnabled();
        sCfgAuroraColorMode = s.getAuroraColorMode();
        sCfgAuroraColor = s.getAuroraCustomColor();
        sCfgAuroraNotificationColorMode = s.getAuroraNotificationColorMode();
        sCfgAuroraSpreadDp = s.getAuroraSpreadDp();
        sCfgAuroraOpacity = s.getAuroraOpacity();
        sCfgAuroraSpeed = s.getAuroraSpeed();

        updateRendererForGeometry();

        if (!sCfgChargingRing && mIsCharging) {
            stopChargingAnimations();
        }
        mChargingPulseEnabled = sCfgChargingPulse;

        if (sCfgRingColorMode != CutoutProgressSettings.RING_COLOR_MODE_RAINBOW) {
            mRainbowShader = null;
            mRainbowCx = Float.NaN;
        }

        refreshPaints();
        recalcScaledPath();
        applyMusicSettings(
                s.getMusicOpacity(),
                s.getMusicStrokeWidthDp(),
                s.isMusicClockwise(),
                sCfgMusicColor);
        restartMusicWaveAnimation();
        restartVisualEffectAnimation();
        requestApplyInsets();
        invalidate();
    }

    public void applyMusicSettings(int opacityPct, float strokeDp,
                                   boolean clockwise, int color) {
        sCfgMusicOpacity = opacityPct;
        sCfgMusicStrokeDp = strokeDp;
        sCfgMusicClockwise = clockwise;
        sCfgMusicColor = color;
        refreshMusicPaint();
        invalidate();
    }

    public void setMusicRingColor(int argb) {
        if (sCfgMusicColor == argb) return;
        sCfgMusicColor = argb;
        refreshMusicPaint();
        if (shouldDrawMusicNow()) invalidate();
    }

    public void setMusicProgress(float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (mMusicFraction == fraction) return;
        mMusicFraction = fraction;
        if (shouldDrawMusicNow()) invalidate();
    }

    public void setMusicPlaying(boolean playing) {
        if (mMusicPlaying == playing) return;
        mMusicPlaying = playing;
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
        if (mHasCutout) invalidate();
    }

    public void setTimerState(boolean active, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        boolean changed = mTimerActive != active || Math.abs(mTimerFraction - fraction) > 0.0005f;
        mTimerActive = active;
        mTimerFraction = active ? fraction : 0f;
        if (!active) {
            updateVisualEffectAnimation();
        } else if (sCfgTimerFlameEnabled) {
            updateVisualEffectAnimation();
        }
        if (changed && mHasCutout) invalidate();
    }

    public void setAuroraCallActive(boolean active) {
        if (mAuroraCallActive == active) return;
        mAuroraCallActive = active;
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
        if (mHasCutout) invalidate();
    }

    public void setAuroraRecordingActive(boolean active) {
        if (mAuroraRecordingActive == active) return;
        mAuroraRecordingActive = active;
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
        if (mHasCutout) invalidate();
    }

    public void showNotificationAurora(int color, long durationMs) {
        if (!sCfgAuroraEnabled || !sCfgAuroraNotifications) return;
        mNotificationAuroraColor = color;
        mNotificationAuroraUntilMs = SystemClock.elapsedRealtime()
                + Math.max(250L, durationMs);
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
        if (mHasCutout) invalidate();
    }

    public void onDisplayStateChanged() {
        if (isDisplayNonInteractive()) {
            stopVisualEffectAnimation();
            if (!sCfgMusicShowOnAod || !isDisplayAod()) {
                stopMusicWaveAnimation();
            } else {
                restartMusicWaveAnimation();
            }
            if (mIsCharging) stopChargingPulse();
        } else {
            if (mIsCharging && mChargingPulseEnabled && sCfgChargingPulse) {
                startChargingPulse();
            }
            restartMusicWaveAnimation();
            restartVisualEffectAnimation();
        }
        invalidate();
    }

    public void clearTransientEffects() {
        mAuroraCallActive = false;
        mAuroraRecordingActive = false;
        mNotificationAuroraUntilMs = 0L;
        mNotificationAuroraColor = 0;
        setTimerState(false, 0f);
        stopVisualEffectAnimation();
        updateMusicWaveAnimation();
        invalidate();
    }

    private void restartMusicWaveAnimation() {
        stopMusicWaveAnimation();
        updateMusicWaveAnimation();
    }

    private void updateMusicWaveAnimation() {
        boolean nonInteractive = isDisplayNonInteractive();
        boolean visibleWhileIdle = sCfgMusicShowOnAod && isDisplayAod();
        if (!mMusicPlaying || !sCfgMusicRingEnabled || !sCfgMusicWaveEnabled
                || isAuroraActiveNow() || !isAttachedToWindow() || !mHasCutout
                || (nonInteractive && !visibleWhileIdle)) {
            stopMusicWaveAnimation();
            return;
        }
        if (mMusicWaveScheduled) return;
        if (mMusicWaveEpochMs <= 0L) mMusicWaveEpochMs = SystemClock.elapsedRealtime();
        mMusicWaveScheduled = true;
        post(mMusicWaveTick);
    }

    private void stopMusicWaveAnimation() {
        removeCallbacks(mMusicWaveTick);
        mMusicWaveScheduled = false;
        mMusicWaveEpochMs = 0L;
        mMusicWavePhase = 0f;
    }

    private long musicWaveDurationMs() {
        long duration = (long) (2600f * 100f / Math.max(25, sCfgMusicWaveSpeed));
        return Math.max(700L, Math.min(8000L, duration));
    }

    private boolean isNotificationAuroraActive() {
        return sCfgAuroraEnabled && sCfgAuroraNotifications
                && mNotificationAuroraUntilMs > SystemClock.elapsedRealtime();
    }

    private boolean isAuroraActiveNow() {
        if (!sCfgAuroraEnabled || isDisplayNonInteractive()) return false;
        return (sCfgAuroraCalls && mAuroraCallActive)
                || (sCfgAuroraMusic && mMusicPlaying)
                || (sCfgAuroraRecording && mAuroraRecordingActive)
                || isNotificationAuroraActive();
    }

    private boolean needsVisualEffectAnimation() {
        return isAuroraActiveNow()
                || (mTimerActive && sCfgTimerEnabled && sCfgTimerFlameEnabled
                    && sCfgTimerPresentation != CutoutProgressSettings.PRESENTATION_DISABLED);
    }

    private long visualEffectDurationMs() {
        int speed = isAuroraActiveNow() ? Math.max(25, sCfgAuroraSpeed) : 100;
        long duration = (long) (2400f * 100f / speed);
        return Math.max(650L, Math.min(9000L, duration));
    }

    private void restartVisualEffectAnimation() {
        stopVisualEffectAnimation();
        updateVisualEffectAnimation();
    }

    private void updateVisualEffectAnimation() {
        if (!isAttachedToWindow() || !mHasCutout || !needsVisualEffectAnimation()) {
            stopVisualEffectAnimation();
            return;
        }
        if (mVisualEffectScheduled) return;
        mVisualEffectEpochMs = SystemClock.elapsedRealtime();
        mVisualEffectScheduled = true;
        post(mVisualEffectTick);
    }

    private void stopVisualEffectAnimation() {
        removeCallbacks(mVisualEffectTick);
        mVisualEffectScheduled = false;
        mVisualEffectEpochMs = 0L;
        mVisualEffectPhase = 0f;
    }

    private boolean shouldDrawMusicNow() {
        return sCfgMusicRingEnabled && mMusicPlaying && mHasCutout
                && (!isDisplayNonInteractive()
                        || (sCfgMusicShowOnAod && isDisplayAod()));
    }

    private int resolveRingColor() {
        switch (sCfgRingColorMode) {
            case CutoutProgressSettings.RING_COLOR_MODE_ACCENT:
                return resolveAccentColor();
            case CutoutProgressSettings.RING_COLOR_MODE_RAINBOW:
                return RAINBOW_COLORS[0];
            default:
                return sCfgRingColor;
        }
    }

    private int resolveAccentColor() {
        TypedValue tv = new TypedValue();
        boolean resolved = getContext().getTheme()
                .resolveAttribute(android.R.attr.colorAccent, tv, true);
        int base = 0xFF2196F3;
        if (resolved) {
            if (tv.resourceId != 0) {
                try {
                    base = getContext().getColor(tv.resourceId);
                } catch (android.content.res.Resources.NotFoundException ignored) {
                    base = tv.data;
                }
            } else {
                base = tv.data;
            }
        }

        boolean isDark = (getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (isDark) {
            return lightenColor(base, 0.50f);
        } else {
            return base;
        }
    }

    private static int lightenColor(int color, float fraction) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = (int)(r + (255 - r) * fraction);
        g = (int)(g + (255 - g) * fraction);
        b = (int)(b + (255 - b) * fraction);
        return Color.argb(Color.alpha(color),
                Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private SweepGradient requireRainbowShader(float cx, float cy) {
        if (mRainbowShader == null
                || Math.abs(cx - mRainbowCx) > 0.5f
                || Math.abs(cy - mRainbowCy) > 0.5f) {
            mRainbowShader = new SweepGradient(cx, cy, RAINBOW_COLORS, null);
            mRainbowCx = cx;
            mRainbowCy = cy;
        }
        return mRainbowShader;
    }

    private void applyRainbowShader(Paint paint, float cx, float cy) {
        SweepGradient shader = requireRainbowShader(cx, cy);
        mShaderMatrix.reset();
        mShaderMatrix.setRotate(-90f, cx, cy);
        shader.setLocalMatrix(mShaderMatrix);
        paint.setShader(shader);
    }

    private static void clearShader(Paint paint) {
        paint.setShader(null);
    }

    public void setChargingState(boolean charging, int batteryPct) {
        boolean wasCharging = mIsCharging;
        mIsCharging = charging;
        mBatteryPct = batteryPct;

        if (!charging) {
            stopChargingAnimations();
            invalidate();
            return;
        }

        if (!wasCharging) {
            mChargingDisplayPct = 0f;
        }

        animateChargingLevelTo(batteryPct);

        if (mChargingPulseEnabled && sCfgChargingPulse && !mChargingPulseScheduled) {
            startChargingPulse();
        }
        invalidate();
    }

    public void setBatteryIndicatorState(boolean active, int batteryPct) {
        boolean wasActive = mIsBatteryIndicatorActive;
        mIsBatteryIndicatorActive = active;
        mBatteryIndicatorPct = batteryPct;

        if (!active) {
            stopBatteryIndicatorAnim();
            invalidate();
            return;
        }

        if (!wasActive) {
            mBatteryDisplayPct = 0f;
        }

        animateBatteryLevelTo(batteryPct);
        invalidate();
    }

    public void setChargingPulseEnabled(boolean enabled) {
        mChargingPulseEnabled = enabled;
        sCfgChargingPulse = enabled;
        if (!enabled) {
            stopChargingPulse();
        } else if (mIsCharging && !mChargingPulseScheduled) {
            startChargingPulse();
        }
    }

    private void animateChargingLevelTo(int targetPct) {
        if (mChargingLevelAnim != null) mChargingLevelAnim.cancel();
        float start = mChargingDisplayPct;
        float end = Math.max(0f, Math.min(100f, targetPct));
        if (isDisplayNonInteractive()) {
            mChargingDisplayPct = end;
            invalidate();
            return;
        }
        if (Math.abs(end - start) < 0.5f) {
            mChargingDisplayPct = end;
            invalidate();
            return;
        }
        long dur = (long)(Math.abs(end - start) * 12f);
        dur = Math.max(200L, Math.min(dur, 1200L));
        mChargingLevelAnim = ValueAnimator.ofFloat(start, end);
        mChargingLevelAnim.setDuration(dur);
        mChargingLevelAnim.setInterpolator(new LinearInterpolator());
        mChargingLevelAnim.addUpdateListener(a -> {
            mChargingDisplayPct = (float) a.getAnimatedValue();
            invalidate();
        });
        mChargingLevelAnim.start();
    }

    private void startChargingPulse() {
        stopChargingPulse();
        if (!mIsCharging || !mChargingPulseEnabled || !sCfgChargingPulse
                || !isAttachedToWindow() || isDisplayNonInteractive()) {
            return;
        }
        mChargingPulseEpochMs = SystemClock.elapsedRealtime();
        mChargingPulseScheduled = true;
        post(mChargingPulseTick);
    }

    private void stopChargingPulse() {
        removeCallbacks(mChargingPulseTick);
        mChargingPulseScheduled = false;
        mChargingPulseEpochMs = 0L;
        mChargingPulsePhase = 0f;
    }

    private void stopChargingAnimations() {
        stopChargingPulse();
        if (mChargingLevelAnim != null) {
            mChargingLevelAnim.cancel();
            mChargingLevelAnim = null;
        }
        mChargingDisplayPct = 0f;
    }

    private void animateBatteryLevelTo(int targetPct) {
        if (mBatteryLevelAnim != null) mBatteryLevelAnim.cancel();
        float start = mBatteryDisplayPct;
        float end   = Math.max(0f, Math.min(100f, targetPct));
        if (isDisplayNonInteractive()) {
            mBatteryDisplayPct = end;
            invalidate();
            return;
        }
        if (Math.abs(end - start) < 0.5f) {
            mBatteryDisplayPct = end;
            invalidate();
            return;
        }
        long dur = (long)(Math.abs(end - start) * 12f);
        dur = Math.max(200L, Math.min(dur, 1200L));
        mBatteryLevelAnim = ValueAnimator.ofFloat(start, end);
        mBatteryLevelAnim.setDuration(dur);
        mBatteryLevelAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mBatteryLevelAnim.addUpdateListener(a -> {
            mBatteryDisplayPct = (float) a.getAnimatedValue();
            invalidate();
        });
        mBatteryLevelAnim.start();
    }

    private void stopBatteryIndicatorAnim() {
        if (mBatteryLevelAnim != null) {
            mBatteryLevelAnim.cancel();
            mBatteryLevelAnim = null;
        }
        mBatteryDisplayPct = 0f;
    }

    public void setProgress(int value) {
        int pct = Math.max(0, Math.min(100, value));
        if (mProgress == pct) return;

        int prev = mProgress;
        mProgress = pct;
        mLastProgressMs = SystemClock.elapsedRealtime();

        removeCallbacks(mBurnInHide);
        if (pct > 0 && pct < 100) {
            postDelayed(mBurnInHide, BURN_IN_HIDE_MS);
        }

        if (pct > 0 && pct < 100) {
            // Any live progress supersedes a delayed completion from the previous transfer.
            cancelPendingFinish();
        }
        if ((prev == 0 || prev == 100) && pct > 0 && pct < 100) {
            // A new transfer can arrive while the previous 100% completion is still pending or
            // animating. Give the new transfer its own minimum-visible window instead of reusing
            // the previous download's start timestamp.
            mDownloadStartMs = SystemClock.elapsedRealtime();
        }

        if (pct == 100 && !mAnim.isFinishAnimating) {
            long elapsed = SystemClock.elapsedRealtime() - mDownloadStartMs;
            long remaining = (sCfgMinVis ? sCfgMinVisMs : 0) - elapsed;
            if (remaining > 0 && mDownloadStartMs > 0) {
                mPendingFinish = () -> { mPendingFinish = null; beginFinishAnim(); };
                postDelayed(mPendingFinish, remaining);
            } else {
                beginFinishAnim();
            }
        } else if (pct > 0 && pct < 100 && mAnim.isFinishAnimating) {
            mAnim.cancelFinish();
        } else if (pct == 0) {
            mDownloadStartMs = 0L;
            cancelPendingFinish();
        }

        invalidate();
    }

    public void setDownloadCount(int count) {
        if (mDownloadCount != count) { mDownloadCount = count; invalidate(); }
    }

    public void setFilenameHint(String hint) {
        if (!Objects.equals(mFilenameHint, hint)) { mFilenameHint = hint; invalidate(); }
    }

    public void showError() {
        mAnim.startError(() -> setProgress(0));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        refreshDensityIfNeeded();
        mCutoutPath.reset();
        mScaledPath.reset();
        mHasCutout = false;
        mAutoGeometryActive = false;
        mResolvedPillLike = false;
        mGeometrySource = CameraCutoutGeometryResolver.SOURCE_NONE;

        DisplayCutout cutout = insets.getDisplayCutout();
        Display display = getDisplay();
        mGeometryRotation = cutout != null
                ? resolveCutoutRotation(cutout)
                : display != null ? display.getRotation() : Surface.ROTATION_0;

        if (sCfgAutoGeometry) {
            // Camera-protection resources may exist even when an under-display camera does not
            // expose a DisplayCutout. Let the resolver try the OEM/SystemUI geometry first.
            CameraCutoutGeometryResolver.ResolvedGeometry geometry =
                    mGeometryResolver.resolve(cutout);
            if (geometry != null) {
                mCutoutPath.set(geometry.path);
                mHasCutout = true;
                mAutoGeometryActive = true;
                mResolvedPillLike = geometry.pillLike;
                mGeometryRotation = geometry.rotation;
                mGeometrySource = geometry.source;
            }
        }

        if (!mHasCutout && cutout != null) {
            mHasCutout = extractPreferredCutout(cutout);
            mResolvedPillLike = sCfgPathMode;
            mGeometrySource = CameraCutoutGeometryResolver.SOURCE_DISPLAY_CUTOUT_PATH;
        }

        updateRendererForGeometry();
        mRainbowShader = null;
        mRainbowCx = Float.NaN;
        mRainbowCy = Float.NaN;
        mAuroraShader = null;
        mAuroraCx = Float.NaN;
        mAuroraCy = Float.NaN;

        if (mHasCutout) {
            recalcScaledPath();
            if (mIsCharging && mChargingPulseEnabled && sCfgChargingPulse
                    && !mChargingPulseScheduled) {
                startChargingPulse();
            }
        } else {
            stopChargingPulse();
        }
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    private boolean extractPreferredCutout(DisplayCutout cutout) {
        Rect target = cutout.getBoundingRectTop();
        if (target == null || target.isEmpty()) {
            target = chooseSmallestCutout(cutout);
        }

        Path nativePath = null;
        try {
            nativePath = cutout.getCutoutPath();
        } catch (NoSuchMethodError ignored) {
        }

        if (nativePath != null && !nativePath.isEmpty()) {
            if (target != null && !target.isEmpty()) {
                Path selected = new Path(nativePath);
                Path clip = new Path();
                clip.addRect(new RectF(target), Path.Direction.CW);
                if (selected.op(clip, Path.Op.INTERSECT) && !selected.isEmpty()) {
                    mCutoutPath.set(selected);
                    return true;
                }
            } else {
                mCutoutPath.set(nativePath);
                return true;
            }
        }

        if (target != null && !target.isEmpty()) {
            RectF rect = new RectF(target);
            float radius = Math.min(rect.width(), rect.height()) / 2f;
            mCutoutPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            return true;
        }

        return false;
    }

    private Rect chooseSmallestCutout(DisplayCutout cutout) {
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect == null || rect.isEmpty()) continue;
            long area = (long) rect.width() * rect.height();
            if (area > 0 && area < bestArea) {
                best = rect;
                bestArea = area;
            }
        }
        return best;
    }

    private int resolveCutoutRotation(DisplayCutout cutout) {
        try {
            return cutout.getCutoutPathParserInfo().getRotation();
        } catch (Throwable ignored) {
            Display display = getDisplay();
            return display != null ? display.getRotation() : Surface.ROTATION_0;
        }
    }

    private void updateRendererForGeometry() {
        boolean exactPath = mAutoGeometryActive
                && mGeometrySource != CameraCutoutGeometryResolver.SOURCE_DISPLAY_CUTOUT_BOUNDS
                && PathRingRenderer.canTracePath(mCutoutPath);
        if (exactPath) {
            if (!(mRenderer instanceof PathRingRenderer)) {
                mRenderer = new PathRingRenderer();
            }
            return;
        }

        boolean needCapsule = mAutoGeometryActive ? mResolvedPillLike : sCfgPathMode;
        if (needCapsule && !(mRenderer instanceof CapsuleRingRenderer)) {
            mRenderer = new CapsuleRingRenderer();
        } else if (!needCapsule && !(mRenderer instanceof CircleRingRenderer)) {
            mRenderer = new CircleRingRenderer();
        }
    }

    private void refreshDensityIfNeeded() {
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        if (!Float.isFinite(density) || density <= 0f) density = 1f;
        if (!Float.isFinite(scaledDensity) || scaledDensity <= 0f) scaledDensity = density;

        boolean densityChanged = Math.abs(density - mDp) >= 0.001f;
        boolean scaledDensityChanged = Math.abs(scaledDensity - mScaledDensity) >= 0.001f;
        if (!densityChanged && !scaledDensityChanged) return;

        mDp = density;
        mScaledDensity = scaledDensity;
        if (densityChanged) {
            mBadge = new CountBadgePainter(mDp);
        }
        refreshPaints();
        refreshMusicPaint();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshDensityIfNeeded();
        requestApplyInsets();
        if (mIsCharging) {
            animateChargingLevelTo(mBatteryPct);
            if (mChargingPulseEnabled && sCfgChargingPulse) startChargingPulse();
        }
        if (mIsBatteryIndicatorActive) {
            animateBatteryLevelTo(mBatteryIndicatorPct);
        }
        updateMusicWaveAnimation();
        updateVisualEffectAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mBurnInHide);
        cancelPendingFinish();
        mAnim.cancelAll();
        stopChargingAnimations();
        stopBatteryIndicatorAnim();
        stopMusicWaveAnimation();
        stopVisualEffectAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshDensityIfNeeded();
        requestApplyInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            requestApplyInsets();
        }
    }

    private void recalcScaledPath() {
        mCutoutPath.computeBounds(mPathBounds, true);
        float scaleX = sCfgRingGap;
        float scaleY = sCfgRingGap;

        if (mAutoGeometryActive) {
            if (mGeometrySource == CameraCutoutGeometryResolver.SOURCE_CAMERA_PROTECTION) {
                // Device camera-protection paths already include the OEM/SystemUI safety margin.
                scaleX = 1f;
                scaleY = 1f;
            } else {
                // Raw DisplayCutout geometry describes the non-functional area itself. Expand by
                // a small absolute margin instead of a percentage, so tiny and large holes get
                // visually consistent spacing across densities and display resolutions.
                float padPx = (Math.max(0.5f, sCfgStrokeDp * 0.5f) + 0.5f) * mDp;
                if (mPathBounds.width() > 0f) {
                    scaleX = (mPathBounds.width() + 2f * padPx) / mPathBounds.width();
                } else {
                    scaleX = 1f;
                }
                if (mPathBounds.height() > 0f) {
                    scaleY = (mPathBounds.height() + 2f * padPx) / mPathBounds.height();
                } else {
                    scaleY = 1f;
                }
            }
        }

        mScaleMatrix.setScale(scaleX, scaleY,
                mPathBounds.centerX(), mPathBounds.centerY());
        mScaledPath.reset();
        mCutoutPath.transform(mScaleMatrix, mScaledPath);
        if (mRenderer instanceof PathRingRenderer) {
            ((PathRingRenderer) mRenderer).setBasePath(mScaledPath);
        }
    }

    private final Runnable mBurnInHide = this::invalidate;

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mHasCutout) return;

        int effectivePct = mAnim.isGeometryPreviewActive() ? 100
                : mAnim.isDynamicPreviewActive() ? mAnim.previewProgress
                : mProgress;

        boolean preview = mAnim.isGeometryPreviewActive() || mAnim.isDynamicPreviewActive();
        boolean burnedOut = !preview
                && mDownloadCount == 0
                && effectivePct > 0 && effectivePct < 100
                && mLastProgressMs > 0
                && SystemClock.elapsedRealtime() - mLastProgressMs >= BURN_IN_HIDE_MS;

        boolean downloadActive = preview
                || mAnim.isErrorAnimating
                || mAnim.isFinishAnimating
                || (effectivePct > 0 && effectivePct < 100 && !burnedOut)
                || mPendingFinish != null;
        if (!preview && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_DISABLED) {
            downloadActive = false;
        }

        boolean musicActive = sCfgMusicRingEnabled && mMusicPlaying
                && (!isDisplayNonInteractive()
                        || (sCfgMusicShowOnAod && isDisplayAod()))
                && sCfgMusicPresentation != CutoutProgressSettings.PRESENTATION_DISABLED;

        boolean timerActive = mTimerActive && sCfgTimerEnabled
                && mTimerFraction > 0f
                && sCfgTimerPresentation != CutoutProgressSettings.PRESENTATION_DISABLED;

        boolean downloadPrimary = preview || (downloadActive
                && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY);
        boolean musicPrimary = musicActive
                && sCfgMusicPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY;
        boolean timerPrimary = timerActive
                && sCfgTimerPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY;

        boolean forceDownload = downloadPrimary && (mAnim.isErrorAnimating
                || mAnim.isFinishAnimating || mPendingFinish != null);
        int primarySource = SOURCE_NONE;
        if (forceDownload) {
            primarySource = SOURCE_DOWNLOAD;
        } else {
            int preferred = priorityToSource();
            if (preferred == SOURCE_DOWNLOAD && downloadPrimary) {
                primarySource = SOURCE_DOWNLOAD;
            } else if (preferred == SOURCE_MUSIC && musicPrimary) {
                primarySource = SOURCE_MUSIC;
            } else if (preferred == SOURCE_TIMER && timerPrimary) {
                primarySource = SOURCE_TIMER;
            } else if (downloadPrimary) {
                primarySource = SOURCE_DOWNLOAD;
            } else if (musicPrimary) {
                primarySource = SOURCE_MUSIC;
            } else if (timerPrimary) {
                primarySource = SOURCE_TIMER;
            }
        }

        if (primarySource == SOURCE_NONE && mIsCharging && sCfgChargingRing) {
            primarySource = SOURCE_CHARGING;
        } else if (primarySource == SOURCE_NONE
                && mIsBatteryIndicatorActive && !mIsCharging) {
            primarySource = SOURCE_BATTERY;
        }

        boolean downloadIndependent = !preview && downloadActive
                && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        boolean musicIndependent = musicActive
                && sCfgMusicPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        boolean timerIndependent = timerActive
                && sCfgTimerPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        boolean auroraActive = isAuroraActiveNow();

        if (primarySource == SOURCE_NONE && !downloadIndependent && !musicIndependent
                && !timerIndependent && !auroraActive) {
            return;
        }

        if (primarySource != SOURCE_NONE) {
            drawSource(canvas, primarySource, effectivePct, 0f);
        }

        float outermostLaneDp = 0f;
        float outermostStrokeDp = primarySource != SOURCE_NONE
                ? sourceStrokeWidthDp(primarySource) : 0f;
        int preferred = priorityToSource();

        if (preferred == SOURCE_DOWNLOAD && downloadIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_DOWNLOAD);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_DOWNLOAD, effectivePct, outermostLaneDp);
        } else if (preferred == SOURCE_MUSIC && musicIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_MUSIC);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_MUSIC, effectivePct, outermostLaneDp);
        } else if (preferred == SOURCE_TIMER && timerIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_TIMER);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_TIMER, effectivePct, outermostLaneDp);
        }

        if (preferred != SOURCE_DOWNLOAD && downloadIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_DOWNLOAD);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_DOWNLOAD, effectivePct, outermostLaneDp);
        }
        if (preferred != SOURCE_MUSIC && musicIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_MUSIC);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_MUSIC, effectivePct, outermostLaneDp);
        }
        if (preferred != SOURCE_TIMER && timerIndependent) {
            float stroke = sourceStrokeWidthDp(SOURCE_TIMER);
            outermostLaneDp = nextLaneOffsetDp(
                    outermostLaneDp, outermostStrokeDp, stroke);
            outermostStrokeDp = stroke;
            drawSource(canvas, SOURCE_TIMER, effectivePct, outermostLaneDp);
        }

        if (auroraActive) {
            drawAurora(canvas, outermostLaneDp, outermostStrokeDp);
        }
    }

    private float sourceStrokeWidthDp(int source) {
        switch (source) {
            case SOURCE_MUSIC:
                return Math.max(0f, sCfgMusicStrokeDp);
            case SOURCE_TIMER:
                return Math.max(0f, sCfgTimerStrokeDp);
            case SOURCE_DOWNLOAD:
            case SOURCE_CHARGING:
            case SOURCE_BATTERY:
                return Math.max(0f, sCfgStrokeDp);
            default:
                return 0f;
        }
    }

    private float nextLaneOffsetDp(
            float previousOffsetDp, float previousStrokeDp, float currentStrokeDp) {
        // Treat the configured spacing as the preferred center-line distance, but never allow
        // thick rings to intersect. Keep a small optical clearance between their painted edges.
        float nonOverlapStep = (Math.max(0f, previousStrokeDp)
                + Math.max(0f, currentStrokeDp)) * 0.5f + 0.75f;
        return previousOffsetDp + Math.max(sCfgMultiRingSpacingDp, nonOverlapStep);
    }

    private int priorityToSource() {
        switch (sCfgPrimaryPriority) {
            case CutoutProgressSettings.PRIMARY_PRIORITY_MUSIC:
                return SOURCE_MUSIC;
            case CutoutProgressSettings.PRIMARY_PRIORITY_TIMER:
                return SOURCE_TIMER;
            default:
                return SOURCE_DOWNLOAD;
        }
    }

    private boolean isDisplayAod() {
        Display display = getDisplay();
        if (display == null) return false;
        int state = display.getState();
        return state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
    }

    private boolean isDisplayNonInteractive() {
        Display display = getDisplay();
        if (display == null) return false;
        int state = display.getState();
        return state == Display.STATE_OFF
                || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND
                || state == Display.STATE_ON_SUSPEND;
    }

    private void drawSource(Canvas canvas, int source, int effectivePct, float laneOffsetDp) {
        switch (source) {
            case SOURCE_DOWNLOAD:
                drawDownloadRing(canvas, effectivePct, laneOffsetDp);
                break;
            case SOURCE_MUSIC:
                drawMusicRing(canvas, laneOffsetDp);
                break;
            case SOURCE_CHARGING:
                drawChargingRing(canvas, laneOffsetDp);
                break;
            case SOURCE_BATTERY:
                drawBatteryIndicatorRing(canvas, laneOffsetDp);
                break;
            case SOURCE_TIMER:
                drawTimerRing(canvas, laneOffsetDp);
                break;
            default:
                break;
        }
    }

    private void drawDownloadRing(Canvas canvas, int effectivePct, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        if (mAnim.isErrorAnimating) {
            mErrorPaint.setAlpha((int) (mAnim.errorAlpha * 255));
            mRenderer.drawFullRing(canvas, mErrorPaint);
            return;
        }

        boolean scaled = mAnim.displayScale != 1f;
        if (scaled) {
            canvas.save();
            canvas.scale(mAnim.displayScale, mAnim.displayScale,
                    mArcBounds.centerX(), mArcBounds.centerY());
        }

        int activeRingColor = resolveRingColor();
        mAnimPaint.set(mRingPaint);
        mAnimPaint.setColor(activeRingColor);
        mAnimPaint.setStrokeWidth(sCfgStrokeDp * mDp);
        int alpha = (int) (sCfgOpacity * 255f / 100f
                * mAnim.displayAlpha * mAnim.completionPulseAlpha);
        mAnimPaint.setAlpha(alpha);
        mAnimPaint.setShadowLayer(
                sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                0f, 0f, activeRingColor);

        if (sCfgRingColorMode == CutoutProgressSettings.RING_COLOR_MODE_RAINBOW) {
            applyRainbowShader(mAnimPaint, mArcBounds.centerX(), mArcBounds.centerY());
        } else {
            clearShader(mAnimPaint);
        }

        if (mAnim.successColorBlend > 0f) {
            int flashColor = sCfgFinishFlash ? sCfgFlashColor
                    : brighten(activeRingColor, mAnim.successColorBlend);
            mAnimPaint.setColor(blendColors(activeRingColor, flashColor,
                    mAnim.successColorBlend));
            mAnimPaint.setShadowLayer(
                    sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                    0f, 0f, mAnimPaint.getColor());
            clearShader(mAnimPaint);
        }

        boolean isActive = effectivePct > 0 && effectivePct < 100
                || mAnim.isGeometryPreviewActive()
                || mAnim.isDynamicPreviewActive();

        if (sCfgBgRing && !mAnim.isFinishAnimating && isActive) {
            mBgPaint.setAlpha((int) (sCfgBgOpacity * 255 / 100 * mAnim.displayAlpha));
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        if (mAnim.isFinishAnimating) {
            drawFinish(canvas, mAnimPaint);
        } else {
            float sweep = eased(effectivePct, sCfgEasing);
            mRenderer.drawProgress(canvas, sweep, sCfgClockwise, mAnimPaint);
            if (isActive) drawLabels(canvas, effectivePct, activeRingColor);
        }

        boolean showBadge = !mAnim.isDynamicPreviewActive() && sCfgShowBadge
                && (mDownloadCount > 1 || mAnim.isGeometryPreviewActive());
        if (showBadge) {
            float badgeCx = mArcBounds.centerX() + sCfgBadgeOffXDp * mDp;
            float badgeTop = mArcBounds.bottom + 4f * mDp + sCfgBadgeOffYDp * mDp;
            int badgeN = mAnim.isGeometryPreviewActive() ? 3 : mDownloadCount;
            mBadge.draw(canvas, badgeCx, badgeTop, badgeN, sCfgOpacity);
        }

        if (scaled) canvas.restore();
    }

    private void drawMusicRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        mMusicPaint.setAlpha(sCfgMusicOpacity * 255 / 100);
        mRenderer.drawProgress(canvas, mMusicFraction, sCfgMusicClockwise, mMusicPaint);
        if (sCfgMusicWaveEnabled && !isAuroraActiveNow()) {
            drawMusicWave(canvas);
        }
    }

    private void drawMusicWave(Canvas canvas) {
        int density = Math.max(16, Math.min(96, sCfgMusicWaveDensity));
        float amplitudeBase = Math.max(0.5f, sCfgMusicWaveAmplitudeDp) * mDp;
        float pad = (sCfgMusicStrokeDp * 0.65f + 0.8f) * mDp;
        mMusicWavePaint.setColor(sCfgMusicColor);
        mMusicWavePaint.setAlpha(sCfgMusicOpacity * 255 / 100);
        float[] position = mEffectPosition;
        float[] normal = mEffectNormal;

        for (int i = 0; i < density; i++) {
            float fraction = i / (float) density;
            if (!mRenderer.getPointAndOutwardNormal(fraction, position, normal)) continue;
            float t = (float) (Math.PI * 2.0 * fraction);
            float wave = 0.55f
                    + 0.25f * (float) Math.sin(t * 3f + mMusicWavePhase)
                    + 0.20f * (float) Math.sin(t * 7f - mMusicWavePhase * 1.7f);
            wave = Math.max(0.12f, Math.min(1f, wave));
            float amplitude = amplitudeBase * wave;
            canvas.drawLine(
                    position[0] + normal[0] * pad,
                    position[1] + normal[1] * pad,
                    position[0] + normal[0] * (pad + amplitude),
                    position[1] + normal[1] * (pad + amplitude),
                    mMusicWavePaint);
        }
    }

    private void drawTimerRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int timerColor = resolveTimerColor();
        applyStroke(mTimerPaint, timerColor, sCfgTimerStrokeDp * mDp,
                sCfgTimerOpacity * 255 / 100);
        if (sCfgTimerColorMode == CutoutProgressSettings.RING_COLOR_MODE_RAINBOW) {
            applyRainbowShader(mTimerPaint, mArcBounds.centerX(), mArcBounds.centerY());
        } else {
            clearShader(mTimerPaint);
        }

        mRenderer.drawProgress(canvas, mTimerFraction, sCfgTimerClockwise, mTimerPaint);
        clearShader(mTimerPaint);

        if (sCfgTimerFlameEnabled && mTimerFraction > 0.002f) {
            drawTimerFlame(canvas, sCfgTimerFlameColor);
        }
    }

    private int resolveTimerColor() {
        switch (sCfgTimerColorMode) {
            case CutoutProgressSettings.RING_COLOR_MODE_ACCENT:
                return resolveAccentColor();
            case CutoutProgressSettings.RING_COLOR_MODE_RAINBOW:
                return 0xFFFF8A00;
            default:
                return sCfgTimerColor;
        }
    }

    private void drawTimerFlame(Canvas canvas, int timerColor) {
        float[] position = mEffectPosition;
        float[] normal = mEffectNormal;
        float endpoint = sCfgTimerClockwise ? mTimerFraction : 1f - mTimerFraction;
        endpoint = endpoint - (float) Math.floor(endpoint);
        if (!mRenderer.getPointAndOutwardNormal(endpoint, position, normal)) return;

        float flameSize = Math.max(1f, sCfgTimerFlameSizeDp) * mDp;
        float flicker = 0.88f + 0.12f * (float) Math.sin(mVisualEffectPhase * 3.1f);
        float wick = Math.max(sCfgTimerStrokeDp * 0.55f * mDp, flameSize * 0.22f);
        float fx = position[0] + normal[0] * (wick + flameSize * 0.35f);
        float fy = position[1] + normal[1] * (wick + flameSize * 0.35f);

        mFlamePaint.setStyle(Paint.Style.STROKE);
        mFlamePaint.setStrokeCap(Paint.Cap.ROUND);
        mFlamePaint.setStrokeWidth(Math.max(1f, sCfgTimerStrokeDp * 0.45f * mDp));
        mFlamePaint.setColor(timerColor);
        mFlamePaint.setAlpha(sCfgTimerOpacity * 220 / 100);
        canvas.drawLine(position[0], position[1],
                position[0] + normal[0] * wick,
                position[1] + normal[1] * wick, mFlamePaint);

        mFlamePaint.setStyle(Paint.Style.FILL);
        mFlamePaint.setColor(timerColor);
        mFlamePaint.setAlpha(sCfgTimerOpacity * 190 / 100);
        canvas.drawCircle(fx, fy, flameSize * 0.58f * flicker, mFlamePaint);

        int inner = blendColors(timerColor, Color.WHITE, 0.58f);
        mFlamePaint.setColor(inner);
        mFlamePaint.setAlpha(sCfgTimerOpacity * 230 / 100);
        canvas.drawCircle(
                fx - normal[0] * flameSize * 0.12f,
                fy - normal[1] * flameSize * 0.12f,
                flameSize * 0.34f * (1.06f - 0.06f * flicker), mFlamePaint);

        mFlamePaint.setColor(Color.WHITE);
        mFlamePaint.setAlpha(sCfgTimerOpacity * 210 / 100);
        canvas.drawCircle(
                fx - normal[0] * flameSize * 0.20f,
                fy - normal[1] * flameSize * 0.20f,
                flameSize * 0.13f, mFlamePaint);
    }

    private void drawAurora(
            Canvas canvas, float outermostLaneDp, float outermostStrokeDp) {
        boolean notificationActive = isNotificationAuroraActive();
        boolean useNotificationColor = notificationActive
                && sCfgAuroraNotificationColorMode
                == CutoutProgressSettings.AURORA_NOTIFICATION_COLOR_NOTIFICATION
                && mNotificationAuroraColor != 0;

        int baseColor;
        boolean spectrum;
        if (useNotificationColor) {
            baseColor = mNotificationAuroraColor;
            spectrum = false;
        } else {
            spectrum = sCfgAuroraColorMode == CutoutProgressSettings.AURORA_COLOR_MODE_SPECTRUM;
            if (sCfgAuroraColorMode == CutoutProgressSettings.AURORA_COLOR_MODE_CUSTOM) {
                baseColor = sCfgAuroraColor;
            } else {
                baseColor = resolveAuroraSourceColor();
            }
        }

        final int layers = 8;
        float spread = Math.max(2f, sCfgAuroraSpreadDp);
        for (int i = 0; i < layers; i++) {
            float t = i / (float) (layers - 1);
            float widthDp = Math.max(0.65f, 1.65f - 0.8f * t);
            // Position the Aurora center-line beyond the painted edge of the outermost ring.
            // This keeps the effect outside thick music/timer rings instead of overlapping them.
            float offset = outermostLaneDp + Math.max(0f, outermostStrokeDp) * 0.5f
                    + 0.8f + widthDp * 0.5f + t * spread;
            computeArcBounds(offset);
            mRenderer.updateBounds(mArcBounds);

            float shimmer = 0.86f + 0.14f * (float) Math.sin(
                    mVisualEffectPhase + t * Math.PI * 2.3f);
            float falloff = (1f - t);
            falloff *= falloff;
            int alpha = (int) (sCfgAuroraOpacity * 255f / 100f
                    * (0.18f + 0.82f * falloff) * shimmer);
            float width = widthDp * mDp;
            int layerColor = spectrum ? Color.WHITE
                    : blendColors(baseColor, Color.WHITE, 0.20f * (1f - t));

            applyStroke(mAuroraPaint, layerColor, width, Math.max(0, Math.min(255, alpha)));
            mAuroraPaint.setStrokeCap(Paint.Cap.ROUND);
            if (spectrum) {
                applyAuroraSpectrumShader(mAuroraPaint,
                        mArcBounds.centerX(), mArcBounds.centerY());
            } else {
                clearShader(mAuroraPaint);
            }
            mRenderer.drawFullRing(canvas, mAuroraPaint);
        }
        clearShader(mAuroraPaint);
    }

    private int resolveAuroraSourceColor() {
        if (isNotificationAuroraActive() && mNotificationAuroraColor != 0) {
            return mNotificationAuroraColor;
        }
        if (sCfgAuroraCalls && mAuroraCallActive) {
            return 0xFF4CAF50;
        }
        if (sCfgAuroraRecording && mAuroraRecordingActive) {
            return 0xFFF44336;
        }
        if (sCfgAuroraMusic && mMusicPlaying) {
            return sCfgMusicColor;
        }
        return resolveAccentColor();
    }

    private void applyAuroraSpectrumShader(Paint paint, float cx, float cy) {
        if (mAuroraShader == null
                || Math.abs(cx - mAuroraCx) > 0.5f
                || Math.abs(cy - mAuroraCy) > 0.5f) {
            mAuroraShader = new SweepGradient(cx, cy, RAINBOW_COLORS, null);
            mAuroraCx = cx;
            mAuroraCy = cy;
        }
        mShaderMatrix.reset();
        float degrees = -90f + (float) Math.toDegrees(mVisualEffectPhase);
        mShaderMatrix.setRotate(degrees, cx, cy);
        mAuroraShader.setLocalMatrix(mShaderMatrix);
        paint.setShader(mAuroraShader);
    }

    private void drawChargingRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        int baseAlpha = sCfgOpacity * 255 / 100;
        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int levelColor = chargingColor(mChargingDisplayPct);
        applyStroke(mChargingPaint, levelColor, sCfgStrokeDp * mDp, baseAlpha);
        if (mChargingPulseEnabled && sCfgChargingPulse && mChargingPulseScheduled) {
            float drawFraction = mChargingPulsePhase * (mChargingDisplayPct / 100f);
            mRenderer.drawSymmetricProgress(canvas, drawFraction, mChargingPaint);
        } else {
            mRenderer.drawSymmetricProgress(canvas, mChargingDisplayPct / 100f, mChargingPaint);
        }
    }

    private void drawBatteryIndicatorRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        int baseAlpha = sCfgOpacity * 255 / 100;
        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int levelColor = chargingColor(mBatteryDisplayPct);
        applyStroke(mChargingPaint, levelColor, sCfgStrokeDp * mDp, baseAlpha);
        mRenderer.drawProgress(canvas, mBatteryDisplayPct / 100f, true, mChargingPaint);
    }

    private static int chargingColor(float pct) {
        if (pct < 30f) return 0xFFF44336;
        if (pct < 60f) return 0xFFFF9800;
        return 0xFF4CAF50;
    }

    private void drawFinish(Canvas canvas, Paint paint) {
        if ("segmented".equals(sCfgFinishStyle)) {
            mRenderer.drawSegmented(canvas,
                    OverlayAnimationHelper.SEGMENT_COUNT,
                    OverlayAnimationHelper.SEGMENT_GAP_DEG,
                    OverlayAnimationHelper.SEGMENT_ARC_DEG,
                    mAnim.segmentHighlight,
                    paint, mShinePaint, mAnim.displayAlpha);
        } else {
            mRenderer.drawFullRing(canvas, paint);
        }
    }

    private void drawLabels(Canvas canvas, int pct, int ringColor) {
        float pad = 4f * mDp;
        int alpha = sCfgOpacity * 255 / 100;

        if (sCfgPct) {
            String text = pct + "%";
            float tw = mPercentPaint.measureText(text);
            float[] pos = labelXY(sCfgPctPos, pad, mPercentPaint.getTextSize(), tw);
            mPercentPaint.setColor(ringColor);
            mPercentPaint.setAlpha(alpha);
            canvas.drawText(text, pos[0] + sCfgPctOffXDp * mDp,
                    pos[1] + sCfgPctOffYDp * mDp, mPercentPaint);
        }

        boolean geoPreview = mAnim.isGeometryPreviewActive();
        String fname = mFilenameHint != null ? mFilenameHint
                : geoPreview ? "EvolutionX-16.0-arm64.zip" : null;

        if (sCfgFname && fname != null && (mDownloadCount <= 1 || geoPreview)) {
            String display = truncate(fname, sCfgFnameMaxChars, sCfgFnameTruncate);
            float[] pos = labelXY(sCfgFnamePos, pad, mFilenamePaint.getTextSize(), null);
            mFilenamePaint.setColor(ringColor);
            mFilenamePaint.setAlpha(alpha);
            canvas.drawText(display, pos[0] + sCfgFnameOffXDp * mDp,
                    pos[1] + sCfgFnameOffYDp * mDp, mFilenamePaint);
        }
    }

    private float[] labelXY(String position, float pad, float textHeight, Float textW) {
        switch (position) {
            case "left":
                return new float[]{
                        textW != null ? mArcBounds.left - textW / 2f - pad
                                      : mArcBounds.left - pad,
                        mArcBounds.centerY() + textHeight / 3f};
            case "top":
                return new float[]{mArcBounds.centerX(), mArcBounds.top - pad};
            case "bottom":
                return new float[]{mArcBounds.centerX(),
                        mArcBounds.bottom + textHeight + pad};
            case "top_left":
                return new float[]{mArcBounds.left - pad, mArcBounds.top - pad};
            case "top_right":
                return new float[]{mArcBounds.right + pad, mArcBounds.top - pad};
            case "bottom_left":
                return new float[]{mArcBounds.left - pad,
                        mArcBounds.bottom + textHeight + pad};
            case "bottom_right":
                return new float[]{mArcBounds.right + pad,
                        mArcBounds.bottom + textHeight + pad};
            default:
                return new float[]{
                        textW != null ? mArcBounds.right + textW / 2f + pad
                                      : mArcBounds.right + pad,
                        mArcBounds.centerY() + textHeight / 3f};
        }
    }

    private void computeArcBounds(float laneOffsetDp) {
        mScaledPath.computeBounds(mArcBounds, true);
        float cx = mArcBounds.centerX();
        float cy = mArcBounds.centerY();
        float halfW;
        float halfH;

        if (mAutoGeometryActive) {
            // Automatic geometry is already expressed in the current logical display coordinate
            // space. Do not rotate, stretch or offset it again.
            halfW = mArcBounds.width() / 2f;
            halfH = mArcBounds.height() / 2f;
        } else {
            rotateOffset(sCfgOffsetXDp, sCfgOffsetYDp, mRotatedOffset);
            cx += mRotatedOffset[0];
            cy += mRotatedOffset[1];

            float scaleX = sCfgScaleX;
            float scaleY = sCfgScaleY;
            if (mGeometryRotation == Surface.ROTATION_90
                    || mGeometryRotation == Surface.ROTATION_270) {
                // User calibration is defined in natural display axes. Rotate the calibration
                // with the hardware instead of re-applying portrait X/Y to landscape axes.
                float tmp = scaleX;
                scaleX = scaleY;
                scaleY = tmp;
            }

            if (sCfgPathMode) {
                halfW = mArcBounds.width() / 2f * scaleX;
                halfH = mArcBounds.height() / 2f * scaleY;
            } else {
                float halfBase = Math.min(mArcBounds.width(), mArcBounds.height()) / 2f;
                halfW = halfBase * scaleX;
                halfH = halfBase * scaleY;
            }
        }

        mArcBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        if (laneOffsetDp > 0f) {
            float px = laneOffsetDp * mDp;
            mArcBounds.inset(-px, -px);
        }
    }

    private void rotateOffset(float dx, float dy, float[] out) {
        switch (mGeometryRotation) {
            case Surface.ROTATION_90:
                out[0] = dy * mDp;
                out[1] = -dx * mDp;
                break;
            case Surface.ROTATION_180:
                out[0] = -dx * mDp;
                out[1] = -dy * mDp;
                break;
            case Surface.ROTATION_270:
                out[0] = -dy * mDp;
                out[1] = dx * mDp;
                break;
            default:
                out[0] = dx * mDp;
                out[1] = dy * mDp;
                break;
        }
    }

    private void initPaints() {
        sCfgRingColorMode = CutoutProgressSettings.RING_COLOR_MODE_ACCENT;
        sCfgRingColor = 0xFF2196F3;
        sCfgErrorColor = 0xFFF44336;
        sCfgFlashColor = Color.WHITE;
        sCfgStrokeDp = 2f;
        sCfgRingGap = 1.160f;
        sCfgOpacity = 90;
        sCfgBgColor = 0xFF808080;
        sCfgBgOpacity = 30;
        sCfgPctSp = 8f;
        sCfgPctBold = true;
        sCfgFnameSp = 7f;
        sCfgBadgeSp = 10f;
        sCfgPctPos = "right";
        sCfgFnamePos = "top_right";
        sCfgFnameTruncate = "middle";
        sCfgFnameMaxChars = 20;
        sCfgEasing = "linear";
        sCfgClockwise = true;
        sCfgFinishStyle= "pop";
        sCfgAutoGeometry = true;
        sCfgScaleX = 1.05f;
        sCfgScaleY = 0.60f;
        sCfgOffsetXDp = 0f;
        sCfgOffsetYDp = 1.5f;
        sCfgDownloadPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
        sCfgMusicPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
        sCfgPrimaryPriority = CutoutProgressSettings.PRIMARY_PRIORITY_DOWNLOAD;
        sCfgMultiRingSpacingDp = 5f;
        sCfgMusicRingEnabled = false;
        sCfgMusicWaveEnabled = false;
        sCfgMusicWaveAmplitudeDp = 2.5f;
        sCfgMusicWaveDensity = 48;
        sCfgMusicWaveSpeed = 100;
        sCfgTimerEnabled = false;
        sCfgTimerPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
        sCfgTimerColorMode = CutoutProgressSettings.RING_COLOR_MODE_ACCENT;
        sCfgTimerColor = 0xFFFF8A00;
        sCfgTimerOpacity = 95;
        sCfgTimerStrokeDp = 2f;
        sCfgTimerClockwise = true;
        sCfgTimerFlameEnabled = true;
        sCfgTimerFlameColor = 0xFFFF6D00;
        sCfgTimerFlameSizeDp = 3.5f;
        sCfgAuroraEnabled = false;
        sCfgAuroraCalls = true;
        sCfgAuroraMusic = true;
        sCfgAuroraRecording = true;
        sCfgAuroraNotifications = true;
        sCfgAuroraColorMode = CutoutProgressSettings.AURORA_COLOR_MODE_SPECTRUM;
        sCfgAuroraColor = 0xFF7C4DFF;
        sCfgAuroraNotificationColorMode =
                CutoutProgressSettings.AURORA_NOTIFICATION_COLOR_NOTIFICATION;
        sCfgAuroraSpreadDp = 8f;
        sCfgAuroraOpacity = 85;
        sCfgAuroraSpeed = 100;
        sCfgBgRing = sCfgMinVis = true;
        sCfgMinVisMs = 500;
        sCfgChargingRing = true;
        sCfgChargingPulse = true;
        sCfgGlowEnabled = false;
        sCfgGlowRadiusDp = 4f;
        refreshPaints();
        refreshMusicPaint();
    }

    private void refreshPaints() {
        float stroke = sCfgStrokeDp * mDp;
        int baseColor = (sCfgRingColorMode == CutoutProgressSettings.RING_COLOR_MODE_CUSTOM)
                ? sCfgRingColor : resolveRingColor();
        applyStroke(mRingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);
        mRingPaint.setShadowLayer(
                sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                0f, 0f, baseColor);
        applyStroke(mShinePaint, sCfgFlashColor, stroke * 1.2f, 255);
        applyStroke(mErrorPaint, sCfgErrorColor, stroke * 1.5f, 255);
        applyStroke(mBgPaint, sCfgBgColor, stroke, sCfgBgOpacity * 255 / 100);
        applyStroke(mChargingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);
        applyStroke(mTimerPaint, resolveTimerColor(),
                sCfgTimerStrokeDp * mDp, sCfgTimerOpacity * 255 / 100);
        mFlamePaint.setAntiAlias(true);
        mAuroraPaint.setAntiAlias(true);

        mRainbowPaint.setStyle(Paint.Style.STROKE);
        mRainbowPaint.setAntiAlias(true);
        mRainbowPaint.setStrokeWidth(stroke);
        mRainbowPaint.setStrokeCap(Paint.Cap.BUTT);
        mRainbowPaint.setAlpha(sCfgOpacity * 255 / 100);

        mPercentPaint.setTypeface(sCfgPctBold
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mPercentPaint.setTextSize(spToPx(sCfgPctSp));
        mPercentPaint.setTextAlign(Paint.Align.CENTER);

        mFilenamePaint.setTypeface(sCfgFnameBold
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mFilenamePaint.setTextSize(spToPx(sCfgFnameSp));
        mFilenamePaint.setTextAlign(Paint.Align.LEFT);

        mBadge.applyConfig(baseColor, sCfgBadgeSp, mScaledDensity);
    }

    private void refreshMusicPaint() {
        applyStroke(mMusicPaint,
                sCfgMusicColor,
                sCfgMusicStrokeDp * mDp,
                sCfgMusicOpacity * 255 / 100);
        applyStroke(mMusicWavePaint,
                sCfgMusicColor,
                Math.max(1f, sCfgMusicStrokeDp * 0.45f * mDp),
                sCfgMusicOpacity * 255 / 100);
        mMusicWavePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private static void applyStroke(Paint p, int color, float width, int alpha) {
        p.setStyle(Paint.Style.STROKE);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void beginFinishAnim() {
        mAnim.startFinish(sCfgFinishStyle, sCfgFinishHoldMs, sCfgFinishExitMs,
                sCfgPulse, () -> setProgress(0));
    }

    private void cancelPendingFinish() {
        if (mPendingFinish != null) {
            removeCallbacks(mPendingFinish);
            mPendingFinish = null;
        }
    }

    private static float eased(int pct, String mode) {
        float v = pct / 100f;
        switch (mode) {
            case "accelerate": return v * v;
            case "decelerate": return 1f - (1f - v) * (1f - v);
            case "ease_in_out": return v < .5f ? 2*v*v : 1f - (float)Math.pow(-2*v+2,2)/2f;
            default: return v;
        }
    }

    private static int brighten(int c, float f) {
        return Color.argb(Color.alpha(c),
                Math.min(255, (int)(Color.red(c) + (255 - Color.red(c)) * f)),
                Math.min(255, (int)(Color.green(c) + (255 - Color.green(c)) * f)),
                Math.min(255, (int)(Color.blue(c) + (255 - Color.blue(c)) * f)));
    }

    private static int blendColors(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        return Color.argb(Color.alpha(c1),
                (int)(Color.red(c1)*inv + Color.red(c2)*ratio),
                (int)(Color.green(c1)*inv + Color.green(c2)*ratio),
                (int)(Color.blue(c1)*inv + Color.blue(c2)*ratio));
    }

    private static String truncate(String s, int max, String mode) {
        if (s == null || s.isEmpty() || max <= 0) return "";
        int count = s.codePointCount(0, s.length());
        if (count <= max) return s;
        String e = "\u2026";
        int avail = max - 1;
        if (avail <= 0) return e;
        switch (mode) {
            case "start": {
                int start = s.offsetByCodePoints(0, count - avail);
                return e + s.substring(start);
            }
            case "end": {
                int end = s.offsetByCodePoints(0, avail);
                return s.substring(0, end) + e;
            }
            default: {
                int head = (avail + 1) / 2;
                int tail = avail - head;
                int headEnd = s.offsetByCodePoints(0, head);
                int tailStart = s.offsetByCodePoints(0, count - tail);
                return s.substring(0, headEnd) + e + s.substring(tailStart);
            }
        }
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }

    private static Paint makePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }
}
