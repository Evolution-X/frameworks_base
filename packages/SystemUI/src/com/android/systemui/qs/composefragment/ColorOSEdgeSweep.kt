/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp

/**
 * A light that runs once around the edge of the ColorOS media card when the track changes: a soft
 * glow and a thin line rise on the edge, travel round it and fade out. Drawn over the card, in
 * white.
 */
internal class ColorOSEdgeSweep {
    private val glow = Layer(GLOW_CURVE, GLOW_CURVE_MIX, GLOW_SPREAD)
    private val line = Layer(LINE_CURVE, LINE_CURVE_MIX, LINE_SPREAD)
    private var startTurn = 0.25f

    fun setStartAngle(turn: Float) {
        startTurn = turn
    }

    fun setProgress(progress: Float) {
        val turn =
            if (progress < TRAVEL_END) startTurn + (1f - startTurn) * ease(progress / TRAVEL_END)
            else 1f

        val glowLevel =
            when {
                progress < RISE_END -> ease(progress / RISE_END)
                progress < HOLD_END -> 1f
                progress < TRAVEL_END -> 1f - ease((progress - HOLD_END) / (TRAVEL_END - HOLD_END))
                else -> 0f
            }
        glow.set(turn, GLOW_BAND, GLOW_BAND, GLOW_NEAR_ALPHA * glowLevel, GLOW_FAR_ALPHA * glowLevel)

        val lineLevel =
            when {
                progress < RISE_END -> ease(progress / RISE_END)
                progress < HOLD_END -> 1f
                else -> 1f - ease((progress - HOLD_END) / (1f - HOLD_END))
            }
        val lineWidth =
            when {
                progress < RISE_END -> LINE_THIN + ease(progress / RISE_END) * LINE_GROWTH
                progress < HOLD_END -> LINE_THIN + LINE_GROWTH
                progress < NARROW_END ->
                    LINE_THIN + LINE_GROWTH -
                        ease((progress - HOLD_END) / (NARROW_END - HOLD_END)) * LINE_GROWTH
                else -> LINE_THIN
            }
        line.set(turn, LINE_INSET, lineWidth, lineLevel, lineLevel)
    }

    fun draw(canvas: Canvas, width: Int, height: Int, cornerRadius: Float) {
        if (width <= 0 || height <= 0) return
        glow.draw(canvas, width.toFloat(), height.toFloat(), cornerRadius)
        line.draw(canvas, width.toFloat(), height.toFloat(), cornerRadius)
    }

    private class Layer(curve: Float, curveMix: Float, spread: FloatArray) {
        private val shader = RuntimeShader(SOURCE)
        private val paint = Paint().apply { shader = this@Layer.shader }

        init {
            shader.setFloatUniform("uCurve", curve, curveMix)
            shader.setFloatUniform("uSpread", spread)
        }

        fun set(turn: Float, inset: Float, width: Float, nearAlpha: Float, farAlpha: Float) {
            shader.setFloatUniform("uTurn", turn)
            shader.setFloatUniform("uBands", inset, inset + width, inset, inset + width)
            shader.setFloatUniform("uAlpha", nearAlpha.coerceIn(0f, 1f), farAlpha.coerceIn(0f, 1f))
        }

        fun draw(canvas: Canvas, width: Float, height: Float, radius: Float) {
            shader.setFloatUniform("uSize", width, height)
            shader.setFloatUniform("uRadius", radius)
            canvas.drawRect(0f, 0f, width, height, paint)
        }
    }

    companion object {
        const val DURATION_MS = 1200

        private val EASE = PathInterpolator(0.33f, 0f, 0.67f, 1f)

        private fun ease(t: Float) = EASE.getInterpolation(t)

        private const val RISE_END = 0.125f
        private const val HOLD_END = 0.5f
        private const val NARROW_END = 0.683f
        private const val TRAVEL_END = 0.833f

        private const val GLOW_BAND = 60f
        private const val GLOW_NEAR_ALPHA = 0.4f
        private const val GLOW_FAR_ALPHA = 0.5f
        private val GLOW_SPREAD = floatArrayOf(0f, 0.8f, 0f, 0.6f)
        private const val GLOW_CURVE = 4.33f
        private const val GLOW_CURVE_MIX = 0.704f

        private const val LINE_INSET = 1f
        private const val LINE_THIN = 2f
        private const val LINE_GROWTH = 10f
        private val LINE_SPREAD = floatArrayOf(0f, 0.4f, 0f, 0.2f)
        private const val LINE_CURVE = 9.23f
        private const val LINE_CURVE_MIX = 0.678f

        private const val ANTI_ALIAS = 2f

        private val SOURCE =
            """
            uniform float2 uSize;
            uniform float uRadius;
            uniform float uTurn;
            uniform float4 uBands;
            uniform float4 uSpread;
            uniform float2 uAlpha;
            uniform float2 uCurve;

            ${ColorOSLightShaders.ROUNDED_BOX}
            ${ColorOSLightShaders.PERIMETER_LIGHT}

            half4 main(float2 xy) {
                float2 extent = uSize * 0.5;
                float2 pos = xy - extent;
                float d = roundedBox(pos, extent, clamp(uRadius, 0.0, min(extent.x, extent.y)));
                float shape = smoothstep(0.0, -$ANTI_ALIAS, d);
                if (shape <= 0.001 || d < -max(uBands.y, uBands.w)) {
                    return half4(0.0);
                }
                float2 light =
                    perimeterLight(pos, extent, d, uTurn, uBands, uSpread, uCurve.x, uCurve.y);
                float a = clamp(uAlpha.x * light.x + uAlpha.y * light.y, 0.0, 1.0);
                return half4(a, a, a, a) * shape;
            }
            """
                .trimIndent()
    }
}

/**
 * Runs the [ColorOSEdgeSweep] round the edge of the card, which has the corner radius [corner],
 * each time [trigger] changes. The value it starts with plays nothing, so a card that is just
 * shown does not flash, and neither does a [trigger] of two nulls or empty strings.
 */
@Composable
internal fun Modifier.colorOSStatusGlow(trigger: Pair<String?, String?>, corner: Dp): Modifier {
    val sweep = remember { runCatching { ColorOSEdgeSweep() }.getOrNull() } ?: return this
    val progress = remember { Animatable(1f) }
    val seen = remember { arrayOf(trigger) }

    LaunchedEffect(trigger) {
        if (trigger == seen[0]) return@LaunchedEffect
        seen[0] = trigger
        if (trigger.first.isNullOrEmpty() && trigger.second.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(ColorOSEdgeSweep.DURATION_MS, easing = LinearEasing))
    }

    return drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f) {
            sweep.setProgress(p)
            drawIntoCanvas {
                sweep.draw(
                    it.nativeCanvas,
                    size.width.toInt(),
                    size.height.toInt(),
                    minOf(corner.toPx(), size.minDimension / 2),
                )
            }
        }
    }
}
