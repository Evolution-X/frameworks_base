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
import android.view.animation.DecelerateInterpolator
import android.view.ViewPropertyAnimator

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var renderer: PulseRenderer? = null
    private var engine: PulseEngine? = null
    private var isAttached = false
    private var isVisible = false
    private var renderingRequested = false 
    private var settingsRepo: PulseSettingsRepository? = null
    
    private var fadeAnimator: ValueAnimator? = null
    private val fadeInterpolator = DecelerateInterpolator()
    private var visibilityAnimator: ViewPropertyAnimator? = null
    private var captureMode: PulseAudioDataProcessor.CaptureMode =
        PulseAudioDataProcessor.CaptureMode.FFT

    private var ownsRenderer = false
    private var currentAnimator: ValueAnimator? = null
    private var targetVisible = false

    private var checkMediaEnabled: Boolean = false 

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
        ownsRenderer = true
        engine = PulseEngine(context, settingsRepo) { processedHeights ->
            renderer?.updateHeights(processedHeights)
            postInvalidate()
        }
    }

    private val isRenderingAllowed: Boolean
        get() {
            if (!renderingRequested) return false
            val repo = settingsRepo ?: return false
            return if (checkMediaEnabled) {
                repo.isPulseEnabled() && repo.isPulseMediaEnabled()
            } else {
                repo.isPulseEnabled()
            }
        }

    fun attachExternalRenderer(renderer: PulseRenderer, settingsRepo: PulseSettingsRepository) {
        this.renderer = renderer
        this.settingsRepo = settingsRepo
        this.checkMediaEnabled = true
        ownsRenderer = false
        alpha = 1f
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
        fadeAnimator?.cancel()
        fadeAnimator = null
        visibilityAnimator?.cancel()
        engine?.stop()
        if (ownsRenderer) {
            renderer?.cleanup()
        }
    
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isAttached && isRenderingAllowed) {
            renderer?.onDraw(canvas, width, height)
            postInvalidateOnAnimation()
        }
    }

    fun updateVisualizerData(data: PulseData) {
        if (isAttached && isRenderingAllowed && data.isDataValid) {
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
        if (targetVisible == visible && (currentAnimator?.isRunning == true || isVisible == visible)) {
            return
        }
        targetVisible = visible
        isVisible = visible

        currentAnimator?.cancel()

        if (!animate) {
            currentAnimator = null
            alpha = if (visible) 1f else 0f
            this.visibility = if (visible) VISIBLE else GONE
            renderingRequested = visible
            return
        }

        if (visible) {
            renderingRequested = true
            this.visibility = VISIBLE
        }

        currentAnimator = ValueAnimator.ofFloat(alpha, if (visible) 1f else 0f).apply {
            duration = durationMs
            interpolator = fadeInterpolator
            addUpdateListener { anim ->
                alpha = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (targetVisible == visible) { // only finalize if nothing superseded us
                        if (!visible) {
                            renderingRequested = false
                            this@PulseView.visibility = GONE
                        }
                    }
                    currentAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    currentAnimator = null
                }
            })
            start()
        }
    }

}
