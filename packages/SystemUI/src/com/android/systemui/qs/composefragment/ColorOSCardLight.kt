/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.LinearInterpolator

/**
 * The light of the ColorOS media card: a soft glow in the color of the album art along the edge of
 * the card, a lit rim, and a ripple that spreads from the album art when the art changes.
 */
internal class ColorOSCardLight : Drawable() {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@ColorOSCardLight.shader }
    private var lit = false
    private var night = false
    private var rippleStart = 0L
    private val ripple =
        ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = LinearInterpolator()
            duration = RIPPLE_MS
            addUpdateListener { invalidateSelf() }
        }

    init {
        shader.setFloatUniform("uRadius", 0f)
        shader.setFloatUniform("uRippleWave", RIPPLE_SPEED, RIPPLE_WAVELENGTH)
        shader.setFloatUniform("uRippleAlpha", RIPPLE_ALPHA)
        shader.setFloatUniform("uRippleColor", 1f, 1f, 1f)
        shader.setFloatUniform("uRippleWidth", RIPPLE_WIDTH_NEAR, RIPPLE_WIDTH_FAR)
        shader.setFloatUniform("uEdgeBands", EDGE_NEAR, EDGE_NEAR + EDGE_NEAR_WIDTH, EDGE_FAR, EDGE_FAR + EDGE_FAR_WIDTH)
        shader.setFloatUniform("uEdgeTurn", EDGE_TURN)
        shader.setFloatUniform("uEdgeCurve", EDGE_CURVE_MIX)
        shader.setFloatUniform("uGlowScale", GLOW_SCALE_X, GLOW_SCALE_Y)
        setLit(false)
    }

    fun setCornerRadius(radius: Float) {
        shader.setFloatUniform("uRadius", radius)
        invalidateSelf()
    }

    fun setColor(color: Int, night: Boolean) {
        this.night = night
        val red = Color.red(color) / 255f
        val green = Color.green(color) / 255f
        val blue = Color.blue(color) / 255f
        shader.setFloatUniform("uGlowColor", red, green, blue)
        shader.setFloatUniform("uEdgeColor", red, green, blue)
        shader.setFloatUniform("uGlowAlpha", if (night) GLOW_ALPHA_NIGHT else GLOW_ALPHA)
        shader.setFloatUniform(
            "uEdgeAlpha",
            if (night) EDGE_NEAR_ALPHA_NIGHT else EDGE_NEAR_ALPHA,
            EDGE_FAR_ALPHA,
        )
        setLit(true)
        fitToBounds()
        invalidateSelf()
    }

    fun playRipple(x: Float, y: Float) {
        shader.setFloatUniform("uRippleCenter", x, y)
        ripple.cancel()
        rippleStart = SystemClock.uptimeMillis()
        ripple.start()
        invalidateSelf()
    }

    fun clear() {
        ripple.cancel()
        setLit(false)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val rippling = ripple.isRunning
        if (!lit && !rippling) return
        if (!canvas.isHardwareAccelerated) return
        if (rippling) {
            val elapsed = (SystemClock.uptimeMillis() - rippleStart) / 1000f
            shader.setFloatUniform("uTime", RIPPLE_TIME_OFFSET + elapsed)
            val narrowing = (elapsed * 1000f / RIPPLE_MS).coerceIn(0f, 1f)
            shader.setFloatUniform(
                "uRippleMask",
                RIPPLE_MASK_RADIUS,
                0f,
                RIPPLE_MASK_IN_FROM + (RIPPLE_MASK_IN_TO - RIPPLE_MASK_IN_FROM) * narrowing,
                RIPPLE_MASK_OUT,
            )
        }
        shader.setFloatUniform("uRippleOn", if (rippling) 1f else 0f)
        canvas.drawRect(bounds, paint)
    }

    override fun onBoundsChange(bounds: Rect) {
        shader.setFloatUniform("uSize", bounds.width().toFloat(), bounds.height().toFloat())
        fitToBounds()
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun setLit(lit: Boolean) {
        this.lit = lit
        shader.setFloatUniform("uLit", if (lit) 1f else 0f)
    }

    private fun fitToBounds() {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        shader.setFloatUniform("uGlowLength", GLOW_LENGTH * (width + height) / (DESIGN_SIZE * 2f))
        shader.setFloatUniform("uGlowOffset", GLOW_OFFSET_X, -GLOW_OFFSET_Y * height / DESIGN_SIZE)
        val scale = 2f * width / (width + height)
        shader.setFloatUniform(
            "uEdgeSpread",
            EDGE_NEAR_SPREAD * scale,
            (EDGE_NEAR_SPREAD + EDGE_NEAR_SPREAD_FADE) * scale,
            EDGE_FAR_SPREAD * scale,
            (EDGE_FAR_SPREAD + EDGE_FAR_SPREAD_FADE) * scale,
        )
    }

    private companion object {
        const val DESIGN_SIZE = 483f

        const val GLOW_ALPHA = 0.4f
        const val GLOW_ALPHA_NIGHT = 0.5f
        const val GLOW_LENGTH = 140f
        const val GLOW_OFFSET_X = 2f
        const val GLOW_OFFSET_Y = 70f
        const val GLOW_SCALE_X = 1.43f
        const val GLOW_SCALE_Y = 1.5f

        const val EDGE_NEAR = 3f
        const val EDGE_NEAR_WIDTH = 20f
        const val EDGE_FAR = 2f
        const val EDGE_FAR_WIDTH = 2f
        const val EDGE_NEAR_ALPHA = 0.5f
        const val EDGE_NEAR_ALPHA_NIGHT = 0.3f
        const val EDGE_FAR_ALPHA = 0.2f
        const val EDGE_NEAR_SPREAD = 0.08f
        const val EDGE_NEAR_SPREAD_FADE = 0.33f
        const val EDGE_FAR_SPREAD = 0.08f
        const val EDGE_FAR_SPREAD_FADE = 0.25f
        const val EDGE_TURN = 0.5f
        const val EDGE_CURVE_MIX = 0.6f

        const val RIPPLE_SPEED = 0.6f
        const val RIPPLE_WAVELENGTH = 1000f
        const val RIPPLE_ALPHA = 0.8f
        const val RIPPLE_WIDTH_NEAR = 0.1f
        const val RIPPLE_WIDTH_FAR = -0.3f
        const val RIPPLE_MASK_RADIUS = 1200f
        const val RIPPLE_MASK_IN_FROM = 2000f
        const val RIPPLE_MASK_IN_TO = 200f
        const val RIPPLE_MASK_OUT = 50f

        const val RIPPLE_MS = (RIPPLE_WAVELENGTH / (RIPPLE_SPEED * 1000f) * 1000f).toLong()

        const val RIPPLE_TIME_OFFSET = RIPPLE_WAVELENGTH * 3f / (RIPPLE_SPEED * 4000f)

        val SOURCE =
            """
            uniform float2 uSize;
            uniform float uRadius;
            uniform float uLit;

            uniform float3 uGlowColor;
            uniform float uGlowAlpha;
            uniform float uGlowLength;
            uniform float2 uGlowOffset;
            uniform float2 uGlowScale;

            uniform float3 uEdgeColor;
            uniform float4 uEdgeBands;
            uniform float2 uEdgeAlpha;
            uniform float4 uEdgeSpread;
            uniform float uEdgeTurn;
            uniform float uEdgeCurve;

            uniform float uRippleOn;
            uniform float2 uRippleCenter;
            uniform float2 uRippleWave;
            uniform float uRippleAlpha;
            uniform float3 uRippleColor;
            uniform float2 uRippleWidth;
            uniform float4 uRippleMask;
            uniform float uTime;

            ${ColorOSLightShaders.ROUNDED_BOX}
            ${ColorOSLightShaders.PERIMETER_LIGHT}
            ${ColorOSLightShaders.SOFT_LIGHT}

            float grain(float2 p) {
                return fract(sin(dot(p.yx, float2(79.1214, 78.233))) * 43760.5453);
            }

            float4 over(float4 top, float4 under) {
                return top + under * (1.0 - top.a);
            }

            float4 glow(float2 xy, float2 pos, float2 extent, float radius) {
                float2 p = pos - uGlowOffset;
                float2 glowExtent = extent * uGlowScale;
                float corner = max(min(min(extent.x, extent.y), uGlowLength * 1.2), radius);
                float alpha = 0.0;
                if (abs(p.x) > glowExtent.x - corner || abs(p.y) > glowExtent.y - corner) {
                    float r = min(corner, min(glowExtent.x, glowExtent.y));
                    alpha = smoothstep(-uGlowLength, 0.0, roundedBox(p, glowExtent, r)) * uGlowAlpha;
                }
                float n = clamp((grain(xy / 1080.0) - 0.5) * 0.01 + 0.5, 0.0, 1.0);
                alpha = clamp(softLight(n, alpha), 0.0, 1.0);
                return float4(uGlowColor * alpha, alpha);
            }

            float4 ripple(float2 pos, float4 color) {
                float dist = length(pos - uRippleCenter);
                float4 mask = uRippleMask * (uSize.x * 0.000926);
                float ring = smoothstep(mask.x - mask.z, mask.x, dist)
                    - smoothstep(mask.x + mask.y, mask.x + mask.y + mask.w, dist);
                if (ring < 0.001) {
                    return color;
                }
                float width = mix(uRippleWidth.x, uRippleWidth.y, clamp(dist / uRippleMask.x, 0.0, 1.0));
                float travelled = dist - uTime * uRippleWave.x * 1000.0;
                float wave = clamp(sin(travelled * 6.2831285 / uRippleWave.y) + width, 0.0, 1.0);
                wave = wave * wave * uRippleAlpha * ring;
                if (wave < 0.001) {
                    return color;
                }
                return over(float4(uRippleColor, 1.0) * wave, color);
            }

            float4 edge(float2 pos, float2 extent, float d, float4 color) {
                if (d < -max(uEdgeBands.y, uEdgeBands.w)) {
                    return color;
                }
                float2 light = perimeterLight(pos, extent, d, uEdgeTurn, uEdgeBands, uEdgeSpread,
                    6.0, uEdgeCurve);
                float nearAlpha = light.x * uEdgeAlpha.x;
                float farAlpha = light.y * uEdgeAlpha.y;
                float4 near = float4(uEdgeColor * nearAlpha, nearAlpha);
                float4 far = float4(uEdgeColor * farAlpha, farAlpha);
                return over(over(near, far), color);
            }

            half4 main(float2 xy) {
                float2 extent = uSize * 0.5;
                float2 pos = xy - extent;
                float radius = min(uRadius, min(extent.x, extent.y));
                float d = roundedBox(pos, extent, radius);
                float shape = smoothstep(1.0, -1.0, d);
                if (shape < 0.001) {
                    return half4(0.0);
                }
                float4 color = float4(0.0);
                if (uLit > 0.5) {
                    color = glow(xy, pos, extent, radius);
                }
                if (uRippleOn > 0.5) {
                    color = ripple(pos, color);
                }
                if (uLit > 0.5) {
                    color = edge(pos, extent, d, color);
                }
                return half4(color * shape);
            }
            """
                .trimIndent()
    }
}

/** Shader pieces of [ColorOSCardLight]. */
private object ColorOSLightShaders {
    const val ROUNDED_BOX =
        """
        float roundedBox(float2 p, float2 extent, float r) {
            float2 q = abs(p) - extent + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }
        """

    const val PERIMETER_LIGHT =
        """
        float2 perimeterLight(float2 pos, float2 extent, float d, float turn, float4 bands,
                float4 spread, float curve, float curveMix) {
            float perimeter = (extent.x + extent.y) * 2.0;
            float lightAt = mod(turn + extent.x / perimeter * 0.5 + 0.5, 1.0);
            float2 dir = normalize(pos);
            float2 dirAbs = abs(dir);
            float2 onBox =
                min(dirAbs.xy * extent.yx / max(float2(0.001), dirAbs.yx), extent) * sign(dir);
            float side = (lightAt <= 0.5 ? 1.0 : -1.0) * dot(float2(-extent.y, extent.x), onBox);
            float lightDistance = perimeter * min(lightAt, 1.0 - lightAt) * 2.0;
            float2 fromCorner = abs(onBox - extent);
            float here = fromCorner.x + fromCorner.y;
            float toNear = side >= 0.0 ? abs(lightDistance - here) : lightDistance + here;
            toNear = min(2.0 * perimeter - toNear, toNear);

            float near = smoothstep(spread.y * perimeter, spread.x * perimeter, toNear);
            float far = smoothstep(spread.w * perimeter, spread.z * perimeter, perimeter - toNear);
            float nearBand = smoothstep(-bands.y, -bands.x, d);
            float farBand = smoothstep(-bands.w, -bands.z, d);
            if (curveMix > 0.001) {
                nearBand = mix(nearBand, pow(nearBand, curve), curveMix);
                farBand = mix(farBand, pow(farBand, curve), curveMix);
            }
            return float2(near * nearBand, far * farBand);
        }
        """

    const val SOFT_LIGHT =
        """
        float softLight(float a, float b) {
            return a <= 0.5
                ? b - (1.0 - 2.0 * a) * b * (1.0 - b)
                : b + (2.0 * a - 1.0) * (sqrt(b) - b);
        }
        """
}
