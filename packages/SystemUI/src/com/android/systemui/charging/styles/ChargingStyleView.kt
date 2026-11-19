/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

abstract class ChargingStyleView(context: Context) : View(context) {

    var level = 0
    var speed = SPEED_NORMAL
    var label = ""
    var portX = 0.5f
    var portY = 1f

    protected var t = 0f
        private set

    protected abstract val durationMs: Long

    protected open val scrimAlpha = 0.6f

    private var animator: ValueAnimator? = null
    private var onEnd: Runnable? = null
    private val scrimPaint = Paint()

    protected val density = resources.displayMetrics.density

    protected val percentPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

    protected val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
        }

    val inProgress: Boolean
        get() = animator != null

    protected val u: Float
        get() = min(width / 200f, height / 420f)

    protected val left: Float
        get() = width / 2f - 100f * u

    protected val tileTop: Float
        get() = height / 2f - 210f * u

    protected val portXPx: Float
        get() = width * portX

    protected val portYPx: Float
        get() = height * portY

    fun play(end: Runnable) {
        onEnd = end
        alpha = 1f
        animator?.removeAllListeners()
        animator?.cancel()
        val total = durationMs + FADE_MS
        animator =
            ValueAnimator.ofFloat(0f, total.toFloat()).apply {
                duration = total
                interpolator = LinearInterpolator()
                addUpdateListener {
                    t = it.animatedValue as Float
                    alpha = 1f - ((t - durationMs) / FADE_MS).coerceIn(0f, 1f)
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            finish()
                        }
                    }
                )
                start()
            }
    }

    fun finish() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        val end = onEnd
        onEnd = null
        end?.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        finish()
    }

    final override fun onDraw(canvas: Canvas) {
        scrimPaint.color = Color.argb((scrimAlpha * 255 * ramp(0f, 400f)).roundToInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        drawFrame(canvas)
    }

    protected abstract fun drawFrame(canvas: Canvas)

    protected fun dp(v: Float) = v * density

    protected fun ramp(start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

    protected fun easeOut(x: Float) = 1f - (1f - x).pow(3)

    protected fun easeInOut(x: Float) =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

    protected fun counted(start: Float, end: Float) = (level * easeOut(ramp(start, end))).roundToInt()

    protected fun tempo(normal: Float, fast: Float, superFast: Float) =
        when (speed) {
            SPEED_SUPER -> superFast
            SPEED_FAST -> fast
            else -> normal
        }

    protected fun drawPercent(canvas: Canvas, x: Float, baseline: Float, size: Float, value: Int, a: Float = 1f) {
        if (a <= 0f) return
        percentPaint.textSize = size
        percentPaint.alpha = (255 * a).roundToInt()
        canvas.drawText("$value%", x, baseline, percentPaint)
    }

    protected fun drawLabel(canvas: Canvas, x: Float, baseline: Float, size: Float, a: Float = 1f) {
        if (a <= 0f || label.isEmpty()) return
        labelPaint.textSize = size
        labelPaint.alpha = (217 * a).roundToInt()
        canvas.drawText(label, x, baseline, labelPaint)
    }

    companion object {
        const val SPEED_NORMAL = 0
        const val SPEED_FAST = 1
        const val SPEED_SUPER = 2
        private const val FADE_MS = 450L
    }
}
