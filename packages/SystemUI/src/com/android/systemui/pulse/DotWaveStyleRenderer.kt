package com.android.systemui.pulse

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal class DotWaveStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var barRects: Array<RectF> = emptyArray()
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var dotRadius = 4f
    private var lastColor = 0
    private val smoothing = 0.2f

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        val count = settings.getBarCount()
        if (barRects.size != count) {
            barRects = Array(count) { RectF() }
            currentHeights = FloatArray(count) { 2f }
            targetHeights  = FloatArray(count) { 2f }
        }

        val gap = settings.getBarGapPx()
        val totalGap = (count - 1) * gap
        val barWidth = if (count > 0) max(0f, viewWidth - totalGap) / count else 0f
        val fullBarWidth = barWidth + gap
        val baseY = viewHeight.toFloat()

        for (i in 0 until count) {
            val left = i * fullBarWidth
            val right = left + barWidth
            barRects[i].set(left, baseY, right, baseY)
        }

        dotRadius = if (count > 0) {
            min(barWidth / 2f, viewHeight / 2f).coerceAtLeast(1f)
        } else {
            1f
        }
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            paint.color = color
            lastColor = color
        }
    }

    override fun onData(heights: FloatArray) {
        if (heights.size != targetHeights.size) {
            targetHeights = FloatArray(heights.size)
            currentHeights = FloatArray(heights.size) { 2f }
        }
        System.arraycopy(heights, 0, targetHeights, 0, heights.size)
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        val count = barRects.size

        for (i in 0 until count) {
            val rect = barRects[i]
            val target = targetHeights.getOrElse(i) { 2f }
            val current = currentHeights.getOrElse(i) { 2f }
            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            val maxH = rect.bottom
            if (h > maxH) h = maxH
            currentHeights[i] = h
            rect.top = rect.bottom - h

            val centerX = (rect.left + rect.right) / 2f
            val centerY = rect.top.coerceIn(dotRadius, viewHeight.toFloat())

            canvas.drawCircle(centerX, centerY, dotRadius, paint)
        }
    }

    override fun cleanup() {
        barRects = emptyArray()
        currentHeights = FloatArray(0)
        targetHeights  = FloatArray(0)
    }
}
