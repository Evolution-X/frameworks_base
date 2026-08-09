/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2024-2026 Lunaris AOSP
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
package com.android.systemui.pulse

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var renderer: PulseRenderer? = null
    private var engine: PulseEngine? = null
    private var isAttached = false
    private var isVisible = false
    private var isRenderingAllowed = false 
    private var settingsRepo: PulseSettingsRepository? = null
    private var visibilityAnimator: ViewPropertyAnimator? = null
    private var captureMode: PulseAudioDataProcessor.CaptureMode =
        PulseAudioDataProcessor.CaptureMode.FFT
    
    private var fadeAnimator: ValueAnimator? = null
    private val fadeInterpolator = DecelerateInterpolator()

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Start with alpha 0 for smooth fade in
        alpha = 0f
    }

    fun initialize(settingsRepo: PulseSettingsRepository) {
        this.settingsRepo = settingsRepo
        captureMode = settingsRepo.getCaptureMode()

        renderer = PulseRenderer(context, settingsRepo)
        engine = PulseEngine(context, settingsRepo) { processedHeights ->
            renderer?.updateHeights(processedHeights)
            postInvalidate()
        }
    }

    fun onCaptureModeChanged(mode: PulseAudioDataProcessor.CaptureMode) {
        captureMode = mode
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        visibilityAnimator?.cancel()
        fadeAnimator?.cancel()
        fadeAnimator = null
        engine?.stop()
        renderer?.cleanup()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isAttached && isRenderingAllowed) {
            renderer?.onDraw(canvas, width, height)
            postInvalidateOnAnimation()
        }
    }

    fun updateVisualizerData(data: PulseData) {
        if (isAttached && isVisible && data.isDataValid) {
            when (captureMode) {
                PulseAudioDataProcessor.CaptureMode.FFT ->
                    data.fftBytes?.let { engine?.processFFT(it) }
                PulseAudioDataProcessor.CaptureMode.WAVEFORM ->
                    data.waveformBytes?.let { engine?.processWaveform(it) }
            }
        }
    }

    fun onMediaColorsChanged(color: Int) {
        post { renderer?.onMediaColorsChanged(color) }
    }

    fun setVisibility(visible: Boolean, animate: Boolean = true, durationMs: Long = 300L) {
        if (isVisible == visible) return
        isVisible = visible

        visibilityAnimator?.cancel()

        if (!animate) {
            alpha = if (visible) 1f else 0f
            visibility = if (visible) VISIBLE else GONE
            isRenderingAllowed = visible
            return
        }

        if (visible) {
            isRenderingAllowed = true
            visibility = VISIBLE
            alpha = 0f
            visibilityAnimator = animate()
                .alpha(1f)
                .setDuration(durationMs)
                .withEndAction { visibilityAnimator = null }
            visibilityAnimator?.start()
        } else {
            visibilityAnimator = animate()
                .alpha(0f)
                .setDuration(durationMs)
                .withEndAction {
                    isRenderingAllowed = false
                    visibility = GONE
                    visibilityAnimator = null
                }
            visibilityAnimator?.start()
        }
    }

    fun fadeIn(durationMs: Long) {
        fadeAnimator?.cancel()
        setVisibility(true)
        fadeAnimator = ValueAnimator.ofFloat(alpha, 1f).apply {
            duration = durationMs
            interpolator = fadeInterpolator
            addUpdateListener { animation ->
                alpha = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    alpha = 1f
                    fadeAnimator = null
                }
                
                override fun onAnimationCancel(animation: Animator) {
                    fadeAnimator = null
                }
            })
            start()
        }
    }

    fun fadeOut(durationMs: Long, onComplete: (() -> Unit)? = null) {
        fadeAnimator?.cancel()
        
        fadeAnimator = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = durationMs
            interpolator = fadeInterpolator
            addUpdateListener { animation ->
                alpha = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    alpha = 0f
                    setVisibility(false)
                    fadeAnimator = null
                    onComplete?.invoke()
                }
                
                override fun onAnimationCancel(animation: Animator) {
                    setVisibility(false)
                    fadeAnimator = null
                    onComplete?.invoke()
                }
            })
            start()
        }
    }
}
