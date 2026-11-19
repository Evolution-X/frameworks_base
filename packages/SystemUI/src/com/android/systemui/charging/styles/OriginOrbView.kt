/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class OriginOrbView(context: Context) : ChargingStyleView(context) {

    override val durationMs = 5200L

    private val k get() = min(width / 150f, height / 300f)
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = HALO
            strokeWidth = dp(6f)
        }
    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = RING
            strokeWidth = dp(1.6f)
        }
    private val tail =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = TAIL
            strokeWidth = dp(2.4f)
        }
    private val spark =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f, SPARK, null, Shader.TileMode.CLAMP)
        }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val oval = RectF()
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f - 30f * k
        r = 30f * k
        if (r <= 0f) return
        orb.shader = RadialGradient(cx - 0.3f * r, cy - 0.4f * r, 1.91f * r, ORB, ORB_AT, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(cx, cy, 2.1f * r, GLOW, GLOW_AT, Shader.TileMode.CLAMP)
    }

    override fun drawFrame(canvas: Canvas) {
        if (r <= 0f) return
        val k = k
        val sp = tempo(1f, 1.35f, 1.8f)
        val pop = spring(ramp(0f, 700f))
        val open = easeOut(ramp(250f, 950f))
        val spin1 = 360f * sp * t / 2600f
        val spin2 = -360f * sp * t / 3400f
        val run1 = 360f * sp * t / 1900f
        val run2 = 160f - 360f * sp * t / 2300f
        if (pop > 0f) {
            val b = pop * (1f + 0.035f * sin(TAU * t / 1800f))
            canvas.save()
            canvas.scale(b, b, cx, cy)
            glow.alpha = (255 * ramp(0f, 500f)).roundToInt()
            canvas.drawCircle(cx, cy, 2.1f * r, glow)
            canvas.restore()
        }
        orbit(canvas, spin1, cy, run1, 1f, open, false)
        orbit(canvas, spin2, cy + 2f * k, run2, -1f, open, false)
        if (pop > 0f) {
            canvas.save()
            canvas.scale(pop, pop, cx, cy)
            canvas.drawCircle(cx, cy, r, orb)
            canvas.restore()
        }
        orbit(canvas, spin1, cy, run1, 1f, open, true)
        orbit(canvas, spin2, cy + 2f * k, run2, -1f, open, true)
        val base = height / 2f + 41f * k
        drawPercent(canvas, cx, base, 22f * k, counted(720f, 2400f), ramp(600f, 950f))
        drawLabel(canvas, cx, base + dp(30f), dp(15f), ramp(840f, 1320f))
    }

    private fun orbit(canvas: Canvas, spin: Float, oy: Float, run: Float, dir: Float, open: Float, front: Boolean) {
        if (open <= 0f) return
        val g = 0.5f + 0.5f * open
        val rx = 48f * k * g
        val ry = 8f * k * g
        val depth = if (front) open else 0.38f * open
        oval.set(cx - rx, oy - ry, cx + rx, oy + ry)
        canvas.save()
        canvas.rotate(spin, cx, oy)
        if (front) {
            canvas.clipRect(cx - 2f * rx, oy, cx + 2f * rx, oy + 2f * rx)
        } else {
            canvas.clipRect(cx - 2f * rx, oy - 2f * rx, cx + 2f * rx, oy)
        }
        halo.alpha = (64 * depth).roundToInt()
        canvas.drawOval(oval, halo)
        line.alpha = (220 * depth).roundToInt()
        canvas.drawOval(oval, line)
        val head = run - 360f * floor(run / 360f)
        tail.alpha = (78 * depth).roundToInt()
        for (j in 1..TRAIL) {
            val sweep = SEG * j
            canvas.drawArc(oval, if (dir > 0f) head - sweep else head, sweep, false, tail)
        }
        val a = head * DEG
        val z = dp(10f) * (0.75f + 0.35f * sin(a))
        canvas.translate(cx + rx * cos(a), oy + ry * sin(a))
        canvas.scale(z, z)
        spark.alpha = (255 * depth).roundToInt()
        canvas.drawCircle(0f, 0f, 1f, spark)
        dot.alpha = spark.alpha
        canvas.drawCircle(0f, 0f, 0.26f, dot)
        canvas.restore()
    }

    private fun spring(x: Float): Float {
        val y = x - 1f
        return 1f + 2.2f * y * y * y + 1.2f * y * y
    }

    private companion object {
        const val TRAIL = 5
        const val SEG = 9f
        const val TAU = 6.2831855f
        const val DEG = 0.017453292f
        const val RING = 0xFFA0D2FF.toInt()
        const val HALO = 0xFF6EB4FF.toInt()
        const val TAIL = 0xFFCDE9FF.toInt()
        val SPARK = intArrayOf(0xFFFFFFFF.toInt(), 0x99A9D8FF.toInt(), 0x00A9D8FF)
        val ORB = intArrayOf(0xFFC9F1FF.toInt(), 0xFF4AA8FF.toInt(), 0xFF2340D8.toInt())
        val ORB_AT = floatArrayOf(0f, 0.45f, 1f)
        val GLOW = intArrayOf(0xB34AA8FF.toInt(), 0xB34AA8FF.toInt(), 0x664AA8FF, 0x1F4AA8FF, 0x004AA8FF)
        val GLOW_AT = floatArrayOf(0f, 0.47f, 0.62f, 0.8f, 1f)
    }
}
