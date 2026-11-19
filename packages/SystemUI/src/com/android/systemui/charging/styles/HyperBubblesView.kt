/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class HyperBubblesView(context: Context) : ChargingStyleView(context) {

    override val durationMs = 5200L

    private val k get() = min(width / 150f, height / 300f)
    private val liquid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path().apply { fillType = Path.FillType.WINDING }
    private val zoom = Matrix()
    private val px = FloatArray(POINTS)
    private val py = FloatArray(POINTS)
    private val bx = FloatArray(BUBBLES)
    private val by = FloatArray(BUBBLES)
    private val br = FloatArray(BUBBLES)
    private val size = FloatArray(BUBBLES) { 3f + 4f * hash(it, 1) }
    private val period = FloatArray(BUBBLES) { CYCLE * (0.92f + 0.16f * hash(it, 2)) }
    private val delay = FloatArray(BUBBLES) { it * CYCLE / BUBBLES + 70f * hash(it, 3) }
    private val drift = FloatArray(BUBBLES) { 20f * hash(it, 4) - 10f }
    private val sway = FloatArray(BUBBLES) { 1.5f + 2.5f * hash(it, 5) }
    private val wave = FloatArray(BUBBLES) { 1f + hash(it, 6) }
    private val phase = FloatArray(BUBBLES) { TAU * hash(it, 7) }
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var zoomed = -1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f - 30f * k
        r = 38f * k
        if (r <= 0f) return
        liquid.shader = RadialGradient(cx - 0.2f * r, cy - 0.3f * r, 4.6f * r, LIQUID, LIQUID_AT, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(cx, cy, 1.75f * r, GLOW, GLOW_AT, Shader.TileMode.CLAMP)
        zoomed = -1f
    }

    override fun drawFrame(canvas: Canvas) {
        if (r <= 0f) return
        val k = k
        val grow = spring(ramp(0f, 650f))
        val sp = tempo(1f, 1.35f, 1.8f)
        val rb = r * grow
        var swell = 0f
        for (i in 0 until BUBBLES) {
            br[i] = 0f
            val pd = period[i] / sp
            val run = t - delay[i]
            if (run <= 0f) continue
            val p = run / pd - floor(run / pd)
            val e = p * (0.6f + 0.4f * p)
            val rad = size[i] * k * min(1f, p * 9f) * (1f - 0.25f * e)
            if (rad <= 0f) continue
            val x = portXPx + (cx - portXPx) * e + drift[i] * k * sin(PI * e) +
                sway[i] * k * sin(TAU * wave[i] * e + phase[i]) * (1f - e)
            val y = portYPx + rad + (cy - portYPx - rad) * e
            val d = hypot(x - cx, y - cy)
            if (d + rad < rb * 0.9f) continue
            swell += rad / r * sin(PI * ((rb + rad - d) / (2f * rad)).coerceIn(0f, 1f))
            bx[i] = x
            by[i] = y
            br[i] = rad
        }
        val rt = rb * (1f + 0.12f * swell)
        val z = max(grow, 0.05f)
        if (z != zoomed) {
            zoomed = z
            zoom.setScale(z, z, cx, cy)
            liquid.shader?.setLocalMatrix(zoom)
        }
        if (grow > 0f) {
            canvas.save()
            canvas.scale(rt / r, rt / r, cx, cy)
            glow.alpha = (255 * min(1f, grow)).roundToInt()
            canvas.drawCircle(cx, cy, 1.75f * r, glow)
            canvas.restore()
        }
        val s = t / 1000f
        path.rewind()
        if (rt > 0f) {
            for (j in 0 until POINTS) {
                val a = TAU * j / POINTS
                val rr = rt * (1f + wobble(a, s))
                px[j] = cx + rr * cos(a)
                py[j] = cy + rr * sin(a)
            }
            path.moveTo(px[0], py[0])
            for (j in 0 until POINTS) {
                val j0 = (j + POINTS - 1) % POINTS
                val j2 = (j + 1) % POINTS
                val j3 = (j + 2) % POINTS
                path.cubicTo(
                    px[j] + (px[j2] - px[j0]) / 6f,
                    py[j] + (py[j2] - py[j0]) / 6f,
                    px[j2] - (px[j3] - px[j]) / 6f,
                    py[j2] - (py[j3] - py[j]) / 6f,
                    px[j2],
                    py[j2],
                )
            }
            path.close()
        }
        for (i in 0 until BUBBLES) {
            if (br[i] <= 0f) continue
            path.addCircle(bx[i], by[i], br[i], Path.Direction.CW)
            if (rt > 0f) neck(bx[i], by[i], br[i], rt, s)
        }
        canvas.drawPath(path, liquid)
        val big = 22f * k
        drawPercent(canvas, cx, cy + 0.36f * big, big, counted(720f, 2400f), ramp(600f, 950f))
        drawLabel(canvas, cx, cy - r - dp(28f), dp(15f), ramp(840f, 1320f))
    }

    private fun neck(x: Float, y: Float, r2: Float, rb: Float, s: Float) {
        val d = hypot(x - cx, y - cy)
        val ac = atan2(y - cy, x - cx)
        val r1 = rb * (1f + wobble(ac, s))
        val reach = r1 + 1.6f * r2
        if (d >= reach || d <= abs(r1 - r2)) return
        val touching = d < r1 + r2
        val v = if (touching) 0.5f else 0.5f * smooth((reach - d) / (reach - r1 - r2))
        val u1 = if (touching) acos(((r1 * r1 + d * d - r2 * r2) / (2f * r1 * d)).coerceIn(-1f, 1f)) else 0f
        val u2 = if (touching) acos(((r2 * r2 + d * d - r1 * r1) / (2f * r2 * d)).coerceIn(-1f, 1f)) else 0f
        val spread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
        val a1 = ac + u1 + (spread - u1) * v
        val a2 = ac - u1 - (spread - u1) * v
        val a3 = ac + PI - u2 - (PI - u2 - spread) * v
        val a4 = ac - PI + u2 + (PI - u2 - spread) * v
        val e1 = rb * (1f + wobble(a1, s))
        val e2 = rb * (1f + wobble(a2, s))
        val x1 = cx + e1 * cos(a1)
        val y1 = cy + e1 * sin(a1)
        val x2 = cx + e2 * cos(a2)
        val y2 = cy + e2 * sin(a2)
        val x3 = x + r2 * cos(a3)
        val y3 = y + r2 * sin(a3)
        val x4 = x + r2 * cos(a4)
        val y4 = y + r2 * sin(a4)
        val h = min(2.4f * v, hypot(x1 - x3, y1 - y3) / (r1 + r2)) * min(1f, 2f * d / (r1 + r2))
        val l1 = r1 * h
        val l2 = r2 * h
        path.moveTo(x2, y2)
        path.cubicTo(
            x2 + l1 * cos(a2 + HALF),
            y2 + l1 * sin(a2 + HALF),
            x4 + l2 * cos(a4 - HALF),
            y4 + l2 * sin(a4 - HALF),
            x4,
            y4,
        )
        path.lineTo(x3, y3)
        path.cubicTo(
            x3 + l2 * cos(a3 + HALF),
            y3 + l2 * sin(a3 + HALF),
            x1 + l1 * cos(a1 - HALF),
            y1 + l1 * sin(a1 - HALF),
            x1,
            y1,
        )
        path.close()
    }

    private fun wobble(a: Float, s: Float) =
        0.016f * sin(3f * a + 1.9f * s) + 0.016f * sin(2f * a - 1.3f * s + 1.3f) +
            0.008f * sin(4f * a + 2.7f * s + 0.4f)

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private fun spring(x: Float): Float {
        val y = x - 1f
        return 1f + 2.2f * y * y * y + 1.2f * y * y
    }

    private companion object {
        const val BUBBLES = 11
        const val POINTS = 12
        const val CYCLE = 2300f
        const val PI = 3.1415927f
        const val HALF = 1.5707964f
        const val TAU = 6.2831855f
        val LIQUID =
            intArrayOf(
                0xFF7FF5C9.toInt(),
                0xFF19C08A.toInt(),
                0xFF1477D8.toInt(),
                0xFF1AA3FF.toInt(),
                0xFF3EE6A8.toInt(),
            )
        val LIQUID_AT = floatArrayOf(0f, 0.174f, 0.385f, 0.522f, 1f)
        val GLOW = intArrayOf(0x9928DCA0.toInt(), 0x9928DCA0.toInt(), 0x4D28DCA0, 0x1428DCA0, 0x0028DCA0)
        val GLOW_AT = floatArrayOf(0f, 0.55f, 0.72f, 0.87f, 1f)

        fun hash(i: Int, s: Int): Float {
            var h = i * 374761393 + s * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
        }
    }
}
