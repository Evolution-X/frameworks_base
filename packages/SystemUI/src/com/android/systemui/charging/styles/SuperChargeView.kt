/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class SuperChargeView(context: Context) : ChargingStyleView(context) {

    override val durationMs = 5200L

    private val k get() = min(width / 150f, height / 300f)
    private val core =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = GREEN
        }
    private val hot =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = HOT
        }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val aura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = GREEN }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HOT }
    private val streak =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(-1f, 0f, 0f, 0f, STREAK, STREAK_AT, Shader.TileMode.CLAMP)
        }
    private val glyph = Path()
    private val ring = RectF()
    private val period = FloatArray(STREAKS) { 1500f + 900f * hash(it, 1) }
    private val flight = FloatArray(STREAKS) { 700f + 350f * hash(it, 2) }
    private val delay = FloatArray(STREAKS) { 250f + it * 115f + 60f * hash(it, 3) }
    private var blurred: Bitmap? = null
    private var cx = 0f
    private var cy = 0f
    private var rr = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val k = k
        cx = w / 2f
        cy = h / 2f - 30f * k
        rr = 34f * k
        if (rr <= 0f) return
        ring.set(cx - rr, cy - rr, cx + rr, cy + rr)
        core.strokeWidth = 1.6f * k
        hot.strokeWidth = 0.45f * k
        halo.strokeWidth = 14f * k
        val edge = rr + 7f * k
        halo.shader =
            RadialGradient(
                cx,
                cy,
                edge,
                intArrayOf(CLEAR, CLEAR, HALO, CLEAR),
                floatArrayOf(0f, (rr - 7f * k) / edge, rr / edge, 1f),
                Shader.TileMode.CLAMP,
            )
        inner.shader = RadialGradient(cx, cy, rr, INNER, INNER_AT, Shader.TileMode.CLAMP)
        glyph.rewind()
        glyph.moveTo(4.5f * k, -18f * k)
        glyph.lineTo(-11.5f * k, 2f * k)
        glyph.lineTo(-0.5f * k, 2f * k)
        glyph.lineTo(-3.5f * k, 18f * k)
        glyph.lineTo(11.5f * k, -2f * k)
        glyph.lineTo(0.5f * k, -2f * k)
        glyph.close()
        val blur = 6f * k
        val bw = (23f * k + 6f * blur).roundToInt()
        val bh = (36f * k + 6f * blur).roundToInt()
        blurred =
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8).also {
                val c = Canvas(it)
                c.translate(bw / 2f, bh / 2f)
                c.drawPath(
                    glyph,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) },
                )
            }
    }

    override fun drawFrame(canvas: Canvas) {
        if (rr <= 0f) return
        val k = k
        val sp = tempo(1f, 1.5f, 2.1f)
        val thick = dp(1.3f)
        val mid = dp(2.6f)
        val wide = dp(5f)
        var hit = 0f
        for (i in 0 until STREAKS) {
            val pd = period[i] / sp
            val run = t - delay[i]
            if (run <= 0f) continue
            val n = floor(run / pd)
            val p = (run - n * pd) * sp / flight[i]
            if (p >= 1f) continue
            val seed = n.toInt() * 7 + 3
            val ang = (if (i % 2 == 0) 180f else 0f) + (hash(i, seed) - 0.5f) * 76f
            val far = (66f + 16f * hash(i, seed + 1)) * k
            val dist = far + (0.7f * rr - far) * p * p
            val fade = min(1f, p * 5f) * ((dist - 0.8f * rr) / (0.35f * rr)).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            hit += max(0f, 1f - abs(dist - rr) / (0.3f * rr))
            val len = (14f + 12f * hash(i, seed + 2)) * k * (0.55f + 0.9f * p * p)
            val a = ang * DEG
            canvas.save()
            canvas.translate(cx + dist * cos(a), cy + dist * sin(a))
            canvas.rotate(ang + 180f)
            canvas.save()
            canvas.scale(len, 1f)
            streak.alpha = (45 * fade).roundToInt()
            canvas.drawRect(-1f, -wide, 0f, wide, streak)
            streak.alpha = (100 * fade).roundToInt()
            canvas.drawRect(-1f, -mid, 0f, mid, streak)
            streak.alpha = (255 * fade).roundToInt()
            canvas.drawRect(-1f, -thick, 0f, thick, streak)
            canvas.restore()
            tip.alpha = streak.alpha
            canvas.drawCircle(0f, 0f, thick * 1.25f, tip)
            canvas.restore()
        }
        val lit = ramp(300f, 900f)
        if (lit > 0f) {
            inner.alpha = (255 * lit).roundToInt()
            canvas.drawCircle(cx, cy, rr, inner)
        }
        val drawn = easeInOut(ramp(150f, 850f))
        if (drawn > 0f) {
            val sweep = 360f * drawn
            val pulse = 0.62f + 0.38f * min(1f, hit) + 0.08f * sin(TAU * sp * t / 1400f)
            halo.alpha = (255 * drawn * min(1f, pulse)).roundToInt()
            canvas.drawArc(ring, -90f, sweep, false, halo)
            canvas.drawArc(ring, -90f, sweep, false, core)
            canvas.drawArc(ring, -90f, sweep, false, hot)
        }
        val pop = spring(ramp(450f, 900f))
        if (pop > 0f) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(pop, pop)
            blurred?.let {
                aura.alpha = (255 * min(1f, pop) * (0.7f + 0.3f * sin(TAU * sp * t / 1100f))).roundToInt()
                canvas.drawBitmap(it, -it.width / 2f, -it.height / 2f, aura)
            }
            canvas.drawPath(glyph, bolt)
            canvas.restore()
        }
        val base = height / 2f + 41f * k
        drawPercent(canvas, cx, base, 22f * k, counted(720f, 2400f), ramp(600f, 950f))
        drawLabel(canvas, cx, base + dp(30f), dp(15f), ramp(840f, 1320f))
    }

    private fun spring(x: Float): Float {
        val y = x - 1f
        return 1f + 2.2f * y * y * y + 1.2f * y * y
    }

    private companion object {
        const val STREAKS = 14
        const val TAU = 6.2831855f
        const val DEG = 0.017453292f
        const val GREEN = 0xFF36E0A0.toInt()
        const val HOT = 0xFFD2FFEC.toInt()
        const val HALO = 0x9936E0A0.toInt()
        const val CLEAR = 0x0036E0A0
        val INNER = intArrayOf(0x4036E0A0, 0x1436E0A0, CLEAR)
        val INNER_AT = floatArrayOf(0f, 0.65f, 1f)
        val STREAK = intArrayOf(CLEAR, 0xB336E0A0.toInt(), 0xFF8CFFCF.toInt())
        val STREAK_AT = floatArrayOf(0f, 0.7f, 1f)

        fun hash(i: Int, s: Int): Float {
            var h = i * 374761393 + s * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
        }
    }
}
