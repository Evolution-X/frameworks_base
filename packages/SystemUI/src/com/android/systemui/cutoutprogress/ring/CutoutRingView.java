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
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;

import com.android.systemui.cutoutprogress.CutoutProgressSettings;

import java.util.Objects;

public final class CutoutRingView extends View {

    private static final long BURN_IN_HIDE_MS = 10_000L;

    private static final long CHARGING_PULSE_INTERVAL_MS = 1500L;
    private static final long CHARGING_PULSE_DURATION_MS = 900L;

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

    private final float mDp;

    private final Path mCutoutPath = new Path();
    private final Path mScaledPath = new Path();
    private final Matrix mScaleMatrix = new Matrix();
    private final RectF mPathBounds = new RectF();
    private final RectF mArcBounds = new RectF();
    private boolean mHasCutout = false;

    private final OverlayAnimationHelper mAnim;
    private RingViewRenderer mRenderer;
    private final CountBadgePainter mBadge;

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
    private ValueAnimator mChargingPulseAnim = null;
    private float mChargingDisplayPct = 0f;
    private ValueAnimator mChargingLevelAnim = null;

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
    private String  sCfgFnameTruncate;
    private String  sCfgEasing;
    private boolean sCfgChargingRing;
    private boolean sCfgChargingPulse;

    public CutoutRingView(Context ctx) {
        super(ctx);
        mDp = ctx.getResources().getDisplayMetrics().density;
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

        boolean needPath = sCfgPathMode;
        if (needPath && !(mRenderer instanceof CapsuleRingRenderer)) {
            mRenderer = new CapsuleRingRenderer();
        } else if (!needPath && !(mRenderer instanceof CircleRingRenderer)) {
            mRenderer = new CircleRingRenderer();
        }

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
        invalidate();
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
        int base = resolved ? tv.data : 0xFF2196F3;

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
        Matrix m = new Matrix();
        m.setRotate(-90f, cx, cy);
        shader.setLocalMatrix(m);
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

        if (mChargingPulseEnabled && sCfgChargingPulse && mChargingPulseAnim == null) {
            startChargingPulse();
        }
        invalidate();
    }

    public void setChargingPulseEnabled(boolean enabled) {
        mChargingPulseEnabled = enabled;
        sCfgChargingPulse = enabled;
        if (!enabled) {
            stopChargingPulse();
        } else if (mIsCharging && mChargingPulseAnim == null) {
            startChargingPulse();
        }
    }

    private void animateChargingLevelTo(int targetPct) {
        if (mChargingLevelAnim != null) mChargingLevelAnim.cancel();
        float start = mChargingDisplayPct;
        float end = Math.max(0f, Math.min(100f, targetPct));
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
        mChargingPulseAnim = ValueAnimator.ofFloat(0f, 1f);
        mChargingPulseAnim.setDuration(CHARGING_PULSE_DURATION_MS);
        mChargingPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mChargingPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mChargingPulseAnim.setInterpolator(new LinearInterpolator());
        mChargingPulseAnim.setStartDelay(0);
        mChargingPulseAnim.addUpdateListener(a -> {
            mChargingPulsePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        mChargingPulseAnim.start();
    }

    private void stopChargingPulse() {
        if (mChargingPulseAnim != null) {
            mChargingPulseAnim.cancel();
            mChargingPulseAnim = null;
        }
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

    public void setProgress(int value) {
        int pct = Math.max(0, Math.min(100, value));
        if (mProgress == pct) return;

        int prev = mProgress;
        mProgress = pct;
        mLastProgressMs = System.currentTimeMillis();

        removeCallbacks(mBurnInHide);
        if (pct > 0 && pct < 100) {
            postDelayed(mBurnInHide, BURN_IN_HIDE_MS);
        }

        if (prev == 0 && pct > 0) {
            mDownloadStartMs = System.currentTimeMillis();
            cancelPendingFinish();
        }

        if (pct == 100 && !mAnim.isFinishAnimating) {
            long elapsed = System.currentTimeMillis() - mDownloadStartMs;
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
        mCutoutPath.reset();
        mHasCutout = false;

        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            Path native31 = null;
            try { native31 = cutout.getCutoutPath(); } catch (NoSuchMethodError ignored) {}

            if (native31 != null && !native31.isEmpty()) {
                mCutoutPath.set(native31);
                mHasCutout = true;
            } else if (!cutout.getBoundingRects().isEmpty()) {
                android.graphics.Rect r = cutout.getBoundingRects().get(0);
                float cx = r.exactCenterX();
                float cy = r.exactCenterY();
                float radius = Math.min(r.width(), r.height()) / 2f;
                mCutoutPath.addCircle(cx, cy, radius, Path.Direction.CW);
                mHasCutout = true;
            }
        }

        if (!mHasCutout) {
            float cx = getResources().getDisplayMetrics().widthPixels / 2f;
            float radius = 15f * mDp;
            mCutoutPath.addCircle(cx, radius * 2f, radius, Path.Direction.CW);
            mHasCutout = true;
        }

        mRainbowShader = null;
        mRainbowCx = Float.NaN;

        recalcScaledPath();
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    private void recalcScaledPath() {
        mCutoutPath.computeBounds(mPathBounds, true);
        mScaleMatrix.setScale(sCfgRingGap, sCfgRingGap,
                mPathBounds.centerX(), mPathBounds.centerY());
        mScaledPath.reset();
        mCutoutPath.transform(mScaleMatrix, mScaledPath);
    }

    private final Runnable mBurnInHide = this::invalidate;

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mHasCutout) return;

        if (mAnim.isErrorAnimating) {
            computeArcBounds();
            mRenderer.updateBounds(mArcBounds);
            mErrorPaint.setAlpha((int)(mAnim.errorAlpha * 255));
            mRenderer.drawFullRing(canvas, mErrorPaint);
            return;
        }

        int effectivePct = mAnim.isGeometryPreviewActive() ? 100
                : mAnim.isDynamicPreviewActive() ? mAnim.previewProgress
                : mProgress;

        boolean burnedOut = effectivePct > 0 && effectivePct < 100
                && mLastProgressMs > 0
                && System.currentTimeMillis() - mLastProgressMs >= BURN_IN_HIDE_MS;

        boolean shouldDraw = mAnim.isFinishAnimating
                || mAnim.isGeometryPreviewActive()
                || mAnim.isDynamicPreviewActive()
                || (effectivePct > 0 && effectivePct < 100 && !burnedOut)
                || mPendingFinish != null;

        boolean showCharging = mIsCharging && sCfgChargingRing && !mAnim.isFinishAnimating
                && !mAnim.isErrorAnimating && effectivePct == 0 && mProgress == 0;

        if (!shouldDraw && !showCharging) return;

        if (showCharging) {
            drawChargingRing(canvas);
            return;
        }

        if (mAnim.displayScale != 1f) {
            mScaledPath.computeBounds(mArcBounds, true);
            canvas.save();
            canvas.scale(mAnim.displayScale, mAnim.displayScale,
                    mArcBounds.centerX(), mArcBounds.centerY());
        }

        int activeRingColor = resolveRingColor();

        mAnimPaint.set(mRingPaint);
        mAnimPaint.setColor(activeRingColor);
        mAnimPaint.setStrokeWidth(sCfgStrokeDp * mDp);
        int alpha = (int)(sCfgOpacity * 255f / 100f
                * mAnim.displayAlpha * mAnim.completionPulseAlpha);
        mAnimPaint.setAlpha(alpha);

        computeArcBounds();
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
            clearShader(mAnimPaint);
        }

        boolean isActive = effectivePct > 0 && effectivePct < 100
                || mAnim.isGeometryPreviewActive()
                || mAnim.isDynamicPreviewActive();

        if (sCfgBgRing && !mAnim.isFinishAnimating && isActive) {
            mRenderer.updateBounds(mArcBounds);
            mBgPaint.setAlpha((int)(sCfgBgOpacity * 255 / 100 * mAnim.displayAlpha));
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        mRenderer.updateBounds(mArcBounds);
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
            mScaledPath.computeBounds(mArcBounds, true);
            float badgeCx = mArcBounds.centerX() + sCfgBadgeOffXDp * mDp;
            float badgeTop = mArcBounds.bottom + 4f * mDp + sCfgBadgeOffYDp * mDp;
            int badgeN = mAnim.isGeometryPreviewActive() ? 3 : mDownloadCount;
            mBadge.draw(canvas, badgeCx, badgeTop, badgeN, sCfgOpacity);
        }

        if (mAnim.displayScale != 1f) canvas.restore();
    }

    private void drawChargingRing(Canvas canvas) {
        computeArcBounds();
        mRenderer.updateBounds(mArcBounds);

        int baseAlpha = sCfgOpacity * 255 / 100;

        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int levelColor = chargingColor(mChargingDisplayPct);
        applyStroke(mChargingPaint, levelColor, sCfgStrokeDp * mDp, baseAlpha);

        if (mChargingPulseEnabled && sCfgChargingPulse && mChargingPulseAnim != null) {
            float drawFraction = mChargingPulsePhase * (mChargingDisplayPct / 100f);
            drawSymmetricArc(canvas, drawFraction, mChargingPaint);
        } else {
            drawSymmetricArc(canvas, mChargingDisplayPct / 100f, mChargingPaint);
        }
    }

    private void drawSymmetricArc(Canvas canvas, float fraction, Paint paint) {
        if (fraction <= 0f) return;
        fraction = Math.min(fraction, 1f);
        float sweep = fraction * 180f;

        canvas.drawArc(mArcBounds, 90f - sweep, sweep, false, paint);
        canvas.drawArc(mArcBounds, 90f, sweep, false, paint);
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

    private void computeArcBounds() {
        mScaledPath.computeBounds(mArcBounds, true);
        float[] offRotated = rotateOffset(sCfgOffsetXDp, sCfgOffsetYDp);
        float cx = mArcBounds.centerX() + offRotated[0];
        float cy = mArcBounds.centerY() + offRotated[1];

        float halfW, halfH;
        if (sCfgPathMode) {
            halfW = mArcBounds.width() / 2f * sCfgScaleX;
            halfH = mArcBounds.height() / 2f * sCfgScaleY;
        } else {
            float halfBase = Math.max(mArcBounds.width(), mArcBounds.height()) / 2f;
            halfW = halfBase * sCfgScaleX;
            halfH = halfBase * sCfgScaleY;
        }
        mArcBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
    }

    private float[] rotateOffset(float dx, float dy) {
        int rot = getDisplay() != null ? getDisplay().getRotation() : Surface.ROTATION_0;
        switch (rot) {
            case Surface.ROTATION_90: return new float[]{ dy * mDp, -dx * mDp};
            case Surface.ROTATION_180: return new float[]{-dx * mDp, -dy * mDp};
            case Surface.ROTATION_270: return new float[]{-dy * mDp,  dx * mDp};
            default: return new float[]{ dx * mDp, dy * mDp};
        }
    }

    private void initPaints() {
        sCfgRingColorMode = CutoutProgressSettings.RING_COLOR_MODE_ACCENT;
        sCfgRingColor = 0xFF2196F3;
        sCfgErrorColor = 0xFFF44336;
        sCfgFlashColor = Color.WHITE;
        sCfgStrokeDp = 2f;
        sCfgRingGap = 1.155f;
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
        sCfgScaleX = sCfgScaleY = 1f;
        sCfgBgRing = sCfgMinVis = true;
        sCfgMinVisMs = 500;
        sCfgChargingRing = true;
        sCfgChargingPulse = true;
        refreshPaints();
    }

    private void refreshPaints() {
        float stroke = sCfgStrokeDp * mDp;
        int baseColor = (sCfgRingColorMode == CutoutProgressSettings.RING_COLOR_MODE_CUSTOM)
                ? sCfgRingColor : resolveRingColor();
        applyStroke(mRingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);
        applyStroke(mShinePaint, sCfgFlashColor, stroke * 1.2f, 255);
        applyStroke(mErrorPaint, sCfgErrorColor, stroke * 1.5f, 255);
        applyStroke(mBgPaint, sCfgBgColor, stroke, sCfgBgOpacity * 255 / 100);
        applyStroke(mChargingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);

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

        mBadge.applyConfig(baseColor, sCfgBadgeSp, mDp);
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
        if (s.length() <= max) return s;
        String e = "\u2026";
        int avail = max - 1;
        if (avail <= 0) return e;
        switch (mode) {
            case "start": return e + s.substring(s.length() - avail);
            case "end": return s.substring(0, avail) + e;
            default: {
                int head = (avail + 1) / 2;
                int tail = avail - head;
                return s.substring(0, head) + e + s.substring(s.length() - tail);
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
