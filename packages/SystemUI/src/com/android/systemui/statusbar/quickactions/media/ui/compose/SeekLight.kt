/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.media.ui.compose

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader

/** The conic light that follows the progress of the ColorOS media card seek bar. */
internal class SeekLight {
    data class Shape(
        val height: Float,
        val heightFalloff: Float,
        val coneK: Float,
        val secondConeK: Float,
        val w0: Float,
        val alpha1: Float,
        val alpha2: Float,
    )

    private val shader = RuntimeShader(SOURCE)
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = this@SeekLight.shader
            blendMode = BlendMode.SCREEN
        }
    private var height = 0.1f
    private var heightFalloff = 0f

    init {
        shader.setFloatUniform("uFalloff", AXIAL_FALLOFF, RADIAL_FALLOFF)
        setColor(Color.WHITE)
        setShape(Shape(0.1f, 0f, 0f, 0f, 0.008f, 0f, 0f))
    }

    fun setColor(color: Int) {
        shader.setFloatUniform(
            "uColor",
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
        )
    }

    fun setShape(shape: Shape) {
        height = shape.height
        heightFalloff = shape.heightFalloff
        shader.setFloatUniform("uCones", shape.coneK, shape.secondConeK)
        shader.setFloatUniform("uAlphas", shape.alpha1, shape.alpha2)
        shader.setFloatUniform("uHeadWidth", shape.w0)
    }

    fun draw(
        canvas: Canvas,
        trackStart: Float,
        centerY: Float,
        length: Float,
        fraction: Float,
        rtl: Boolean,
    ) {
        if (length <= 0f) return
        val grown = if (fraction <= GROW_UNTIL) (fraction / (GROW_UNTIL + 0.01f)).coerceIn(0f, 1f) else 1f
        val width = PADDING * 2f + length
        shader.setFloatUniform("uSize", width, LIGHT_HEIGHT)
        shader.setFloatUniform("uTrack", fraction, length)
        shader.setFloatUniform("uReach", height * grown, heightFalloff * grown)
        shader.setFloatUniform("uRtl", if (rtl) 1f else 0f)
        val save = canvas.save()
        canvas.translate(trackStart - PADDING, centerY - LIGHT_HEIGHT / 2f)
        canvas.drawRect(0f, 0f, width, LIGHT_HEIGHT, paint)
        canvas.restoreToCount(save)
    }

    private companion object {
        const val LIGHT_HEIGHT = 450f
        const val PADDING = 300f
        const val GROW_UNTIL = 0.2f
        const val SPOT_RADIUS = 500f
        const val AXIAL_FALLOFF = 2f
        const val RADIAL_FALLOFF = 1f

        val SOURCE =
            """
            uniform float2 uSize;
            uniform float2 uTrack;
            uniform float2 uReach;
            uniform float2 uCones;
            uniform float2 uAlphas;
            uniform float uHeadWidth;
            uniform float2 uFalloff;
            uniform float uRtl;
            uniform float3 uColor;

            const float SPOT = $SPOT_RADIUS;

            float4 cone(float2 q, float opening, float strength) {
                float behind = max(-q.x, 0.0);
                float halfWidth = opening * behind + uHeadWidth * SPOT;
                float across = abs(q.y);
                float edge = 1.0 - smoothstep(0.0, SPOT * 2.0, across - halfWidth);
                float radial = exp(-uFalloff.y * pow(across / (halfWidth + 1e-4), 2.0));
                float axial = exp(-uFalloff.x * behind / SPOT);

                float reach = SPOT * uReach.x;
                float reached = clamp(behind / (reach + 1e-4), 0.0, 1.0);
                float height = 1.0 - pow(reached, uReach.y);
                height *= exp(-(behind >= reach ? (behind - reach) / SPOT : 0.0) * 3.0);

                float light = edge * radial * height * axial * smoothstep(0.0, 0.03 * SPOT, behind);
                return float4(uColor * light, light * strength);
            }

            half4 main(float2 xy) {
                float2 pos = xy - uSize * 0.5;
                float2 q = pos - float2((uTrack.x - 0.5) * uTrack.y, 0.0);
                q.x *= 1.0 - 2.0 * uRtl;
                if (q.x >= 0.0) {
                    return half4(0.0);
                }

                float4 first = cone(q, uCones.x, uAlphas.x);
                float4 second = cone(q, uCones.y, uAlphas.y);
                float4 both = float4(0.0);
                both.rgb = min(float3(1.0), first.rgb + second.rgb);
                both.a = first.a * (1.0 - second.a) + second.a;
                if (both.a <= 0.0) {
                    return half4(0.0);
                }
                float lean = abs(second.a - first.a) / both.a;
                both.rgb = mix(both.rgb, first.rgb, (second.a <= first.a ? 1.0 : 0.0) * lean);
                both.rgb = mix(both.rgb, second.rgb, (first.a <= second.a ? 1.0 : 0.0) * lean);

                both.rgb = clamp(both.rgb, 0.0, 1.0);
                both.a = clamp(max(both.a, max(max(both.r, both.g), both.b)), 0.0, 1.0);
                return half4(both);
            }
            """
                .trimIndent()
    }
}
