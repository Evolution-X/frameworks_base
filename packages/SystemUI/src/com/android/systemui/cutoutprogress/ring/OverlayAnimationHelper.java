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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

public final class OverlayAnimationHelper {

    public static final int SEGMENT_COUNT = 12;
    public static final float SEGMENT_GAP_DEG = 6f;
    public static final float SEGMENT_ARC_DEG =
            (360f - SEGMENT_COUNT * SEGMENT_GAP_DEG) / SEGMENT_COUNT;

    public boolean isFinishAnimating = false;
    public boolean isErrorAnimating = false;

    public float displayAlpha = 1f;
    public float displayScale = 1f;
    public int segmentHighlight = -1;
    public float successColorBlend = 0f;
    public float completionPulseAlpha = 1f;
    public float errorAlpha = 0f;

    public enum PreviewMode { NONE, DYNAMIC, GEOMETRY }
    public PreviewMode previewMode = PreviewMode.NONE;
    public int previewProgress = 0;

    public boolean isDynamicPreviewActive() { return previewMode == PreviewMode.DYNAMIC; }
    public boolean isGeometryPreviewActive() { return previewMode == PreviewMode.GEOMETRY; }

    private static final int MAX_ANIM_MS = 800;
    private static final float INTENSITY_POP = 1.5f;
    private static final float POP_SCALE_FACTOR = 0.08f;
    private static final float SEGMENT_SHINE_BLEND = 0.4f;
    private static final float PULSE_MIN_ALPHA = 0.7f;
    private static final long PULSE_DURATION_MS = 400L;
    private static final long PREVIEW_DEBOUNCE_MS = 300L;
    private static final long GEOMETRY_HOLD_MS = 3000L;

    private final View mHost;

    private ValueAnimator mFinishAnim;
    private ValueAnimator mPulseAnim;
    private ValueAnimator mErrorAnim;
    private ValueAnimator mPreviewAnim;
    private Runnable mPreviewDebounce;
    private Runnable mGeometryHideTask;

    public OverlayAnimationHelper(View host) {
        mHost = host;
    }

    public void startFinish(String style, int holdMs, int exitMs,
                            boolean pulse, Runnable onComplete) {
        cancelFinish();
        isFinishAnimating = true;
        displayAlpha = 1f;
        displayScale = 1f;
        segmentHighlight = -1;
        successColorBlend = 1f;
        completionPulseAlpha = 1f;

        Runnable runStyle = () -> {
            switch (style) {
                case "snap":
                    isFinishAnimating = false;
                    resetFinishState();
                    onComplete.run();
                    break;
                case "segmented":
                    animateSegmented(holdMs, exitMs, INTENSITY_POP, onComplete);
                    break;
                default:
                    animatePop(holdMs, exitMs, INTENSITY_POP, onComplete);
            }
        };

        if (pulse && !"snap".equals(style)) {
            animatePulse(runStyle);
        } else {
            runStyle.run();
        }
    }

    private void animatePop(int holdMs, int exitMs, float intensity, Runnable onComplete) {
        int total = Math.min(holdMs + exitMs, MAX_ANIM_MS);
        long scaleDuration = (long)(total * 0.4f);
        long fadeDuration = total - scaleDuration;

        mFinishAnim = animate(0f, 1f, scaleDuration, new OvershootInterpolator(2f * intensity),
                f -> displayScale = 1f + POP_SCALE_FACTOR * intensity * f,
                () -> mFinishAnim = animate(0f, 1f, fadeDuration,
                        new AccelerateDecelerateInterpolator(),
                        f -> {
                            displayScale = 1f + POP_SCALE_FACTOR * intensity * (1f - f * 0.5f);
                            displayAlpha = 1f - f;
                        },
                        () -> endFinish(onComplete)));
    }

    private void animateSegmented(int holdMs, int exitMs, float intensity, Runnable onComplete) {
        int total = Math.min(holdMs + exitMs, MAX_ANIM_MS);
        long cascadeDuration = (long)(total * 0.6f);
        long fadeDuration = total - cascadeDuration;

        mFinishAnim = animateInt(0, SEGMENT_COUNT + 2, cascadeDuration,
                new LinearInterpolator(),
                seg -> {
                    segmentHighlight  = seg;
                    successColorBlend = intensity * SEGMENT_SHINE_BLEND;
                },
                () -> {
                    segmentHighlight = -1;
                    mFinishAnim = animate(1f, 0f, fadeDuration,
                            new AccelerateDecelerateInterpolator(),
                            f -> displayAlpha = f,
                            () -> endFinish(onComplete));
                });
    }

    private void animatePulse(Runnable then) {
        if (mPulseAnim != null) mPulseAnim.cancel();
        mPulseAnim = animate(1f, PULSE_MIN_ALPHA, PULSE_DURATION_MS,
                new AccelerateDecelerateInterpolator(),
                f -> completionPulseAlpha = f, null);
        ValueAnimator second = animate(PULSE_MIN_ALPHA, 1f, PULSE_DURATION_MS,
                new AccelerateDecelerateInterpolator(),
                f -> completionPulseAlpha = f, then);
        mPulseAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { second.start(); }
        });
        mPulseAnim.start();
    }

    private void endFinish(Runnable onComplete) {
        isFinishAnimating = false;
        resetFinishState();
        onComplete.run();
        mHost.invalidate();
    }

    public void cancelFinish() {
        cancel(mFinishAnim); mFinishAnim = null;
        cancel(mPulseAnim);  mPulseAnim  = null;
        isFinishAnimating = false;
        resetFinishState();
    }

    private void resetFinishState() {
        displayAlpha = 1f;
        displayScale = 1f;
        segmentHighlight = -1;
        successColorBlend = 0f;
        completionPulseAlpha = 1f;
    }

    public void startError(Runnable onComplete) {
        if (isFinishAnimating || isErrorAnimating) return;
        isErrorAnimating = true;
        cancel(mErrorAnim);

        mErrorAnim = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f);
        mErrorAnim.setDuration(600);
        mErrorAnim.setInterpolator(new LinearInterpolator());
        mErrorAnim.addUpdateListener(a -> {
            errorAlpha = (float) a.getAnimatedValue();
            mHost.invalidate();
        });
        mErrorAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                isErrorAnimating = false;
                errorAlpha = 0f;
                if (onComplete != null) onComplete.run();
                mHost.invalidate();
            }
        });
        mErrorAnim.start();
    }

    public void cancelError() {
        cancel(mErrorAnim); mErrorAnim = null;
        isErrorAnimating = false;
        errorAlpha = 0f;
    }

    public void startDynamicPreview(String finishStyle, int holdMs,
                                    int exitMs, boolean pulse) {
        mHost.removeCallbacks(mPreviewDebounce);
        mPreviewDebounce = () -> runDynamicPreview(finishStyle, holdMs, exitMs, pulse);
        mHost.postDelayed(mPreviewDebounce, PREVIEW_DEBOUNCE_MS);
    }

    private void runDynamicPreview(String finishStyle, int holdMs,
                                   int exitMs, boolean pulse) {
        cancelDynamicPreview();
        previewMode = PreviewMode.DYNAMIC;
        previewProgress = 0;

        mPreviewAnim = animateInt(0, 100, 800,
                new AccelerateDecelerateInterpolator(),
                p -> previewProgress = p,
                () -> {
                    previewProgress = 100;
                    startFinish(finishStyle, holdMs, exitMs, pulse, () ->
                            mHost.postDelayed(() -> {
                                previewMode     = PreviewMode.NONE;
                                previewProgress = 0;
                                mHost.invalidate();
                            }, 200));
                });
    }

    public void cancelDynamicPreview() {
        mHost.removeCallbacks(mPreviewDebounce);
        mPreviewDebounce = null;
        cancel(mPreviewAnim); mPreviewAnim = null;
        previewMode = PreviewMode.NONE;
        previewProgress = 0;
    }

    public void showGeometryPreview(boolean autoHide) {
        mHost.removeCallbacks(mGeometryHideTask);
        mGeometryHideTask = null;
        previewMode = PreviewMode.GEOMETRY;
        mHost.invalidate();
        if (autoHide) {
            mGeometryHideTask = () -> {
                previewMode = PreviewMode.NONE;
                mGeometryHideTask = null;
                mHost.invalidate();
            };
            mHost.postDelayed(mGeometryHideTask, GEOMETRY_HOLD_MS);
        }
    }

    public void cancelGeometryPreview() {
        mHost.removeCallbacks(mGeometryHideTask);
        mGeometryHideTask = null;
        if (previewMode == PreviewMode.GEOMETRY) {
            previewMode = PreviewMode.NONE;
            mHost.invalidate();
        }
    }

    public void cancelAll() {
        cancelFinish();
        cancelError();
        cancelDynamicPreview();
        cancelGeometryPreview();
    }

    private interface FloatConsumer { void accept(float v); }
    private interface IntConsumer { void accept(int v);   }

    private ValueAnimator animate(float from, float to, long durationMs,
                                  TimeInterpolator interp,
                                  FloatConsumer onUpdate, Runnable onEnd) {
        if (durationMs <= 0) {
            onUpdate.accept(to);
            mHost.invalidate();
            if (onEnd != null) onEnd.run();
            return new ValueAnimator();
        }
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(durationMs);
        va.setInterpolator(interp);
        va.addUpdateListener(a -> {
            onUpdate.accept((float) a.getAnimatedValue());
            mHost.invalidate();
        });
        if (onEnd != null) va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { onEnd.run(); }
        });
        va.start();
        return va;
    }

    private ValueAnimator animateInt(int from, int to, long durationMs,
                                     TimeInterpolator interp,
                                     IntConsumer onUpdate, Runnable onEnd) {
        if (durationMs <= 0) {
            onUpdate.accept(to);
            mHost.invalidate();
            if (onEnd != null) onEnd.run();
            return new ValueAnimator();
        }
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(durationMs);
        va.setInterpolator(interp);
        va.addUpdateListener(a -> {
            onUpdate.accept((int) a.getAnimatedValue());
            mHost.invalidate();
        });
        if (onEnd != null) va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { onEnd.run(); }
        });
        va.start();
        return va;
    }

    private static void cancel(ValueAnimator va) {
        if (va != null) va.cancel();
    }
}
