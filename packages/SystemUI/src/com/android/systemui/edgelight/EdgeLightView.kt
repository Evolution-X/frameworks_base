/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.internal.graphics.EdgeLightRenderer
import com.android.systemui.res.R

class EdgeLightView(context: Context) : FrameLayout(context) {
    var userStrokeWidth = 8
    var userSpread = EDGE_LIGHT_DEFAULT_SPREAD
    var userIntensity = EDGE_LIGHT_DEFAULT_INTENSITY
    var edgeStyle = STYLE_DEFAULT
    var animationEffect = EFFECT_NONE
    var userPulseCount = 3
    var animationSpeed = 100
    var designLength = 35
    var designSize = 30
    var designDensity = 50
    var animationDirection = 0
    var paintColor = Color.WHITE
    var rainbow = false
    var onPulseFinished: (() -> Unit)? = null

    private val renderer = EdgeLightRenderer()
    private val totalPulseDuration =
        resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()
    private var pulseAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null
    private var motionProgress = 0f
    private val cornerRadii = FloatArray(8)
    private var cutoutBounds: List<Rect> = emptyList()
    private val fallbackRadius = try {
        resources.getDimension(
            resources.getIdentifier("rounded_corner_radius", "dimen", "android")
        )
    } catch (_: Exception) {
        32f * resources.displayMetrics.density
    }

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    var pulseRunning: Boolean
        get() = pulseAnimator?.isRunning == true
        set(value) {
            if (value && !pulseRunning) startPulse()
            else if (!value) stopPulse()
        }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        isClickable = false
        isLongClickable = false
        setWillNotDraw(false)
        visible = false
        cornerRadii.fill(fallbackRadius)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val positions = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
        )
        positions.forEachIndexed { index, position ->
            val radius = insets.getRoundedCorner(position)?.radius?.toFloat() ?: fallbackRadius
            cornerRadii[index * 2] = radius
            cornerRadii[index * 2 + 1] = radius
        }
        cutoutBounds = insets.displayCutout?.boundingRects ?: emptyList()
        invalidate()
        return insets
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.configure(
            edgeStyle, animationEffect,
            userStrokeWidth.coerceIn(2, 32) * resources.displayMetrics.density,
            designLength, designSize, designDensity, animationDirection,
            userSpread, userIntensity,
        )
        renderer.setColor(paintColor, rainbow)
        renderer.setBounds(width.toFloat(), height.toFloat(), cornerRadii)
        val save = canvas.save()
        for (bounds in cutoutBounds) canvas.clipOutRect(bounds)
        renderer.draw(canvas, motionProgress)
        canvas.restoreToCount(save)
    }

    private fun startPulse() {
        visible = true
        alpha = 0f
        motionProgress = 0f
        if (EdgeLightRenderer.isDesign(edgeStyle) || animationEffect != EFFECT_NONE || rainbow) {
            motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2000L * 100 / animationSpeed.coerceIn(25, 200)
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    motionProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalPulseDuration
            interpolator = LinearInterpolator()
            addUpdateListener {
                alpha = EdgeLightRenderer.pulseAlpha(it.animatedValue as Float, userPulseCount)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pulseAnimator = null
                    stopMotion()
                    visible = false
                    onPulseFinished?.invoke()
                }
            })
            start()
        }
    }

    private fun stopMotion() {
        motionAnimator?.removeAllUpdateListeners()
        motionAnimator?.cancel()
        motionAnimator = null
        motionProgress = 0f
    }

    private fun stopPulse() {
        pulseAnimator?.removeAllListeners()
        pulseAnimator?.removeAllUpdateListeners()
        pulseAnimator?.cancel()
        pulseAnimator = null
        stopMotion()
        alpha = 0f
        visible = false
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    companion object {
        const val STYLE_DEFAULT = "default"
        const val STYLE_ROUNDED = "rounded"
        const val EFFECT_NONE = "none"
        const val EFFECT_BREATHING = "breathing"
        const val EFFECT_WAVE = "wave"
        const val EFFECT_SPARKLE = "sparkle"
        const val EFFECT_CHASE = "chase"
        const val EFFECT_COMET = "comet"
    }
}
