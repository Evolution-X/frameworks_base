/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.media.controls.ui.view

import android.animation.ValueAnimator
import android.R as AndroidR
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.PathParser
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min

class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = AndroidR.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {

    private val transparentThumb = TransparentDrawable()
    private val density = resources.displayMetrics.density

    private val backgroundRect = RectF()
    private val thumbRect = RectF()
    private val genericPath = Path()
    private var waveLinePoints = FloatArray(0)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 60
    }

    private var trackHeight = 6f * density
    private var cornerRadius = 8f * density
    private var thumbRadius = 8f * density
    private val stockThumbWidth = 4f * density
    private val stockThumbHeight = 16f * density

    private var waveLut: IntArray? = null
    private var waveHeight = 12f * density
    private var waveLength = 80f * density
    private var waveLayers = 1
    private var fillAlpha = 0

    private var customWaveformEnabled = false
    private var cachedPath: Path? = null
    private var wavePhase = 0f
    private var waveAmplitudeMultiplier = 0f
    private var waveAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var mediaColor = context.getColor(R.color.media_on_background)
    private var shouldAnimateWaveform = false
    private var isAggregatedVisible = false

    var isPlaying = false
        private set

    init {
        loadThemeResources()
    }

    private fun loadThemeResources() {
        val wavePathData = context.resources.getString(R.string.config_qs_waveform_path)
        val lut = context.resources.getIntArray(R.array.config_qs_waveform_lut)
        val hasCustomWaveform = wavePathData.isNotBlank() || lut.isNotEmpty()

        if (!hasCustomWaveform) {
            cachedPath = null
            waveLut = null
            customWaveformEnabled = false
            cancelWaveAnimation()
            restoreStockSeekBar()
            return
        }

        customWaveformEnabled = true
        thumb = transparentThumb
        progressDrawable = null
        splitTrack = false

        waveLut = lut.takeIf { it.isNotEmpty() }
        waveHeight = context.resources.getDimension(R.dimen.config_qs_waveform_height)
        waveLength = context.resources.getDimension(R.dimen.config_qs_waveform_length)
        waveLayers =
            context.resources.getInteger(R.integer.config_qs_waveform_layers).coerceIn(1, 3)
        fillAlpha =
            context.resources.getInteger(R.integer.config_qs_waveform_fill_alpha).coerceIn(0, 255)
        applyMediaColor()
        progressPaint.strokeWidth = trackHeight
        cachedPath = wavePathData.takeIf { it.isNotBlank() }?.let(::createScaledPath)
    }

    private fun createScaledPath(pathData: String): Path? =
        try {
            PathParser.createPathFromPathData(pathData)?.apply {
                val bounds = RectF()
                computeBounds(bounds, true)
                if (bounds.width() <= 0f || bounds.height() <= 0f) {
                    return null
                }
                transform(Matrix().apply {
                    postScale(waveLength / bounds.width(), waveHeight / bounds.height())
                })
            }
        } catch (_: RuntimeException) {
            null
        }

    fun refreshTheme() {
        loadThemeResources()
        syncWaveAnimation()
        invalidate()
    }

    private fun restoreStockSeekBar() {
        thumb = transparentThumb
        progressDrawable =
            context.getDrawable(R.drawable.media_squiggly_progress)?.mutate()?.apply {
                alpha = 255
                (this as? SquigglyProgress)?.configureStockSquiggly()
            }
        thumbTintList = context.getColorStateList(R.color.media_on_background)
        progressTintList = context.getColorStateList(R.color.media_on_background)
        progressBackgroundTintList = context.getColorStateList(AndroidR.color.system_primary_dark)
        splitTrack = false
        thumbPaint.color = mediaColor
    }

    private fun SquigglyProgress.configureStockSquiggly() {
        waveLength =
            resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_wavelength).toFloat()
        lineAmplitude =
            resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_amplitude).toFloat()
        phaseSpeed =
            resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_phase).toFloat()
        strokeWidth =
            resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_stroke_width)
                .toFloat()
    }

    fun setMediaColor(color: Int) {
        mediaColor = color
        if (!customWaveformEnabled) {
            thumbPaint.color = color
            invalidate()
            return
        }
        applyMediaColor()
        invalidate()
    }

    fun startWaveAnimation() {
        if (!customWaveformEnabled || isPlaying && waveAnimator?.isRunning == true) return
        isPlaying = true
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 1f).apply {
            duration = FADE_DURATION_MS
            addUpdateListener {
                waveAmplitudeMultiplier = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
        if (waveAnimator == null) {
            waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = WAVE_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    wavePhase = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }

    fun stopWaveAnimation() {
        shouldAnimateWaveform = false
        stopWaveAnimation(true)
    }

    private fun stopWaveAnimation(animate: Boolean) {
        if (!isPlaying && fadeAnimator?.isRunning != true && waveAmplitudeMultiplier == 0f) return
        isPlaying = false
        waveAnimator?.cancel()
        waveAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        if (animate && isAttachedToWindow && isAggregatedVisible && waveAmplitudeMultiplier > 0f) {
            fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 0f).apply {
                duration = FADE_DURATION_MS
                addUpdateListener {
                    waveAmplitudeMultiplier = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        } else {
            waveAmplitudeMultiplier = 0f
        }
    }

    fun setWaveformPlaying(playing: Boolean) {
        shouldAnimateWaveform = playing
        syncWaveAnimation()
    }

    private fun cancelWaveAnimation() {
        isPlaying = false
        waveAnimator?.cancel()
        waveAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        waveAmplitudeMultiplier = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadThemeResources()
        syncWaveAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelWaveAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isAggregatedVisible = isVisible
        syncWaveAnimation()
    }

    private fun syncWaveAnimation() {
        if (!customWaveformEnabled) return
        if (shouldAnimateWaveform && isAttachedToWindow && isAggregatedVisible) {
            startWaveAnimation()
        } else {
            stopWaveAnimation(!shouldAnimateWaveform && isAttachedToWindow && isAggregatedVisible)
        }
    }

    private fun applyMediaColor() {
        progressPaint.color = mediaColor
        fillPaint.color = mediaColor
        fillPaint.alpha = fillAlpha
        backgroundPaint.color = mediaColor
        backgroundPaint.alpha = BACKGROUND_ALPHA
        thumbPaint.color = mediaColor
    }

    override fun onDraw(canvas: Canvas) {
        if (!customWaveformEnabled) {
            super.onDraw(canvas)
            drawStockThumb(canvas)
            return
        }

        val pLeft = paddingLeft.toFloat()
        val drawWidth = width.toFloat() - pLeft - paddingRight
        if (drawWidth <= 0f) return

        val centerY = height / 2f
        val trackTop = centerY - trackHeight / 2f
        val trackBottom = centerY + trackHeight / 2f
        val progressX = pLeft + drawWidth * progressRatio()

        drawBackground(canvas, pLeft, drawWidth, trackTop, trackBottom)
        if (progressX > pLeft) {
            if (waveAmplitudeMultiplier <= 0f) {
                canvas.drawLine(pLeft, centerY, progressX, centerY, progressPaint)
            } else {
                cachedPath?.let {
                    drawSvgWave(canvas, it, pLeft, progressX, centerY)
                } ?: waveLut?.let {
                    drawLutWave(canvas, it, pLeft, progressX, trackTop, trackBottom)
                }
            }
        }

        if (isEnabled) {
            canvas.drawCircle(progressX, centerY + 2 * density, thumbRadius, thumbShadowPaint)
            canvas.drawCircle(progressX, centerY, thumbRadius, thumbPaint)
        }
    }

    private fun drawStockThumb(canvas: Canvas) {
        if (!isEnabled || transparentThumb.alphaValue <= 0) return
        val pLeft = paddingLeft.toFloat()
        val drawWidth = width.toFloat() - pLeft - paddingRight
        if (drawWidth <= 0f) return
        val progressX = pLeft + drawWidth * progressRatio()
        val centerY = height / 2f
        val halfWidth = stockThumbWidth / 2f
        val halfHeight = stockThumbHeight / 2f
        thumbRect.set(
            progressX - halfWidth,
            centerY - halfHeight,
            progressX + halfWidth,
            centerY + halfHeight,
        )
        val originalAlpha = thumbPaint.alpha
        thumbPaint.alpha = originalAlpha * transparentThumb.alphaValue / 255
        canvas.drawRoundRect(thumbRect, halfWidth, halfWidth, thumbPaint)
        thumbPaint.alpha = originalAlpha
    }

    private fun progressRatio(): Float = if (max > 0) progress.toFloat() / max else 0f

    private fun drawBackground(
        canvas: Canvas,
        startX: Float,
        width: Float,
        top: Float,
        bottom: Float,
    ) {
        backgroundRect.set(startX, top, startX + width, bottom)
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)
    }

    private fun drawLutWave(
        canvas: Canvas,
        lut: IntArray,
        startX: Float,
        endX: Float,
        top: Float,
        bottom: Float,
    ) {
        val drawFill = fillAlpha > 0
        val baselineY = (top + bottom) / 2f
        val edgeFadeWidth = min((endX - startX) / 2f, max(thumbRadius * 2f, waveLength / 8f))
        val step = 2f * density
        val pointCount = max(2, ((endX - startX) / step).toInt() + 2)

        drawLutLineWave(
            canvas,
            lut,
            startX,
            endX,
            baselineY,
            edgeFadeWidth,
            step,
            pointCount,
            drawFill,
        )
    }

    private fun drawLutLineWave(
        canvas: Canvas,
        lut: IntArray,
        startX: Float,
        endX: Float,
        baselineY: Float,
        edgeFadeWidth: Float,
        step: Float,
        pointCount: Int,
        drawFill: Boolean,
    ) {
        val originalProgressAlpha = progressPaint.alpha
        val originalFillAlpha = fillPaint.alpha
        val needed = max(4, (pointCount - 1) * 4)
        if (waveLinePoints.size < needed) {
            waveLinePoints = FloatArray(needed)
        }
        for (layer in 0 until waveLayers) {
            val phaseOffset = layer * LAYER_PHASE_OFFSET
            if (layer > 0) {
                progressPaint.alpha =
                    (originalProgressAlpha * SECONDARY_LAYER_ALPHA_MULTIPLIER).toInt()
                if (drawFill) {
                    fillPaint.alpha = (fillAlpha * SECONDARY_LAYER_ALPHA_MULTIPLIER).toInt()
                }
            }
            if (drawFill) {
                genericPath.rewind()
                genericPath.incReserve(pointCount + 2)
                genericPath.moveTo(startX, baselineY)
            }

            var previousX = startX
            var previousY = baselineY
            var linePointCount = 0
            var x = startX + step
            while (x < endX) {
                val y =
                    calculateLutWaveY(lut, x, startX, endX, phaseOffset, baselineY, edgeFadeWidth)
                if (drawFill) {
                    genericPath.lineTo(x, y)
                }
                linePointCount = addLineSegment(
                    waveLinePoints,
                    linePointCount,
                    previousX,
                    previousY,
                    x,
                    y,
                )
                previousX = x
                previousY = y
                x += step
            }
            if (drawFill) {
                genericPath.lineTo(endX, baselineY)
                genericPath.close()
                canvas.drawPath(genericPath, fillPaint)
            }
            linePointCount = addLineSegment(
                waveLinePoints,
                linePointCount,
                previousX,
                previousY,
                endX,
                baselineY,
            )
            canvas.drawLines(waveLinePoints, 0, linePointCount, progressPaint)
        }
        if (waveLayers > 1) {
            progressPaint.alpha = originalProgressAlpha
            if (drawFill) {
                fillPaint.alpha = originalFillAlpha
            }
        }
    }

    private fun addLineSegment(
        points: FloatArray,
        index: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): Int {
        points[index] = startX
        points[index + 1] = startY
        points[index + 2] = endX
        points[index + 3] = endY
        return index + 4
    }

    private fun calculateLutWaveY(
        lut: IntArray,
        x: Float,
        startX: Float,
        endX: Float,
        phaseOffset: Float,
        baselineY: Float,
        edgeFadeWidth: Float,
    ): Float {
        val progressInPeriod = ((x - startX) / waveLength + wavePhase + phaseOffset) % 1f
        val lutIndex = (progressInPeriod * (lut.size - 1)).toInt().coerceIn(0, lut.size - 1)
        val distanceFromStart = x - startX
        val distanceFromEnd = endX - x
        val edgeMultiplier =
            if (edgeFadeWidth > 0f) {
                min(1f, min(distanceFromStart, distanceFromEnd) / edgeFadeWidth)
            } else {
                0f
            }
        return baselineY - lut[lutIndex] / LUT_SCALE * waveHeight * waveAmplitudeMultiplier *
            edgeMultiplier
    }

    private fun drawSvgWave(canvas: Canvas, path: Path, startX: Float, endX: Float, centerY: Float) {
        val originalStyle = progressPaint.style
        progressPaint.style = Paint.Style.FILL
        var currentX = startX - wavePhase * waveLength
        while (currentX < endX) {
            if (currentX + waveLength > startX) {
                canvas.save()
                canvas.translate(currentX, centerY - waveHeight * waveAmplitudeMultiplier / 2f)
                canvas.clipRect(
                    max(startX, currentX) - currentX,
                    -waveHeight,
                    min(endX, currentX + waveLength) - currentX,
                    waveHeight,
                )
                canvas.drawPath(path, progressPaint)
                canvas.restore()
            }
            currentX += waveLength
        }
        progressPaint.style = originalStyle
    }

    private class TransparentDrawable : Drawable() {
        var alphaValue = 255
            private set

        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }
        override fun getAlpha(): Int = alphaValue
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }

    companion object {
        private const val BACKGROUND_ALPHA = 77
        private const val FADE_DURATION_MS = 300L
        private const val WAVE_DURATION_MS = 3500L
        private const val LAYER_PHASE_OFFSET = 0.2f
        private const val SECONDARY_LAYER_ALPHA_MULTIPLIER = 0.5f
        private const val LUT_SCALE = 1000f
    }
}
