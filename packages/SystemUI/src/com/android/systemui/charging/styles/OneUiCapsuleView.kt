/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OneUiCapsuleView(context: Context) : ChargingStyleView(context) {

    override val durationMs = 4000L

    override val scrimAlpha = 0.15f

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL }
    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(TEAL, GREEN, LIME), null, Shader.TileMode.CLAMP)
        }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOLT }
    private val digits =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
            textSize = dp(CAP) * 0.47f
        }
    private val sign =
        Paint(digits).apply {
            textAlign = Paint.Align.LEFT
            textSize = dp(CAP) * 0.31f
        }
    private val rise = Rect().let { digits.getTextBounds("0", 0, 1, it); -it.top / 2f }
    private val numbers = Array(101) { it.toString() }
    private val shape = Path()
    private val glyph = Path()
    private val box = RectF()

    init {
        val s = dp(CAP) * 0.0145f
        glyph.moveTo(1.5f * s, -10f * s)
        glyph.lineTo(-7.5f * s, 2f * s)
        glyph.lineTo(-0.5f * s, 2f * s)
        glyph.lineTo(-1.5f * s, 10f * s)
        glyph.lineTo(7.5f * s, -2f * s)
        glyph.lineTo(0.5f * s, -2f * s)
        glyph.close()
    }

    override fun drawFrame(canvas: Canvas) {
        val h = dp(CAP)
        val w = min(width, height) - 2f * dp(SIDE)
        val cx = width / 2f
        val cy = height - dp(LIFT)
        val a = ramp(80f, 300f) * (1f - ramp(3150f, 3450f))
        if (a <= 0f) return
        val grow = spring(ramp(80f, 560f)) * (1f - easeInOut(ramp(3000f, 3450f)))
        val hw = w * (0.42f + 0.58f * grow) / 2f
        val hh = h * (0.62f + 0.38f * grow) / 2f
        box.set(cx - hw, cy - hh, cx + hw, cy + hh)
        pill.alpha = (Color.alpha(PILL) * a).roundToInt()
        canvas.drawRoundRect(box, hh, hh, pill)
        val full = max(h, w * level / 100f)
        val reach = full * easeOut(ramp(320f, 1500f)) * hw * 2f / w
        if (reach > 0f) {
            val tint = easeInOut(ramp(1100f, 2000f)) + 0.9f * easeInOut(ramp(1900f, 3100f)) - 1f
            val g = full / if (tint < 0f) min(tint, -0.02f) else max(tint, 0.02f)
            shape.rewind()
            shape.addRoundRect(box, hh, hh, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(shape)
            canvas.clipRect(box.left, box.top, box.left + reach, box.bottom)
            canvas.translate(box.left, box.bottom)
            canvas.rotate(-18f)
            canvas.scale(g, g)
            fill.alpha = (255 * a).roundToInt()
            canvas.drawPaint(fill)
            canvas.restore()
        }
        val c = ramp(260f, 600f) * (1f - ramp(2850f, 3100f))
        if (c <= 0f) return
        val ink = (255 * c).roundToInt()
        val dx = box.left + hh
        disc.alpha = ink
        canvas.drawCircle(dx, cy, hh * 0.52f, disc)
        bolt.alpha = ink
        canvas.save()
        canvas.translate(dx, cy)
        canvas.drawPath(glyph, bolt)
        canvas.restore()
        val n = numbers[counted(320f, 1350f).coerceIn(0, 100)]
        digits.alpha = ink
        sign.alpha = ink
        canvas.drawText(n, cx, cy + rise, digits)
        canvas.drawText("%", cx + digits.measureText(n) / 2f + dp(1.5f), cy + rise, sign)
    }

    private fun spring(x: Float): Float {
        val y = x - 1f
        return 1f + 2.2f * y * y * y + 1.2f * y * y
    }

    private companion object {
        const val CAP = 64f
        const val SIDE = 76f
        const val LIFT = 56f
        const val PILL = 0xFF4E5871.toInt()
        const val TEAL = 0xFF16B6A0.toInt()
        const val GREEN = 0xFF3DDC84.toInt()
        const val LIME = 0xFFB9DA8C.toInt()
        const val BOLT = 0xFF2ECA82.toInt()
    }
}
