/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * How the ColorOS press light looks: the radius it grows from and to, its height above the glass
 * and how strong it is.
 */
internal class ColorOSSpotLightParams(
    val intensity: Float,
    val startRadius: Float,
    val endRadius: Float,
    val height: Float,
    val ambient: Float,
    val diffuse: Float,
    val noise: Float,
    val blend: BlendMode = BlendMode.Overlay,
)

internal val ColorOSSpotLightSmall = ColorOSSpotLightParams(1f, 100f, 400f, 200f, 0.4f, 0.4f, 0.08f)

internal val ColorOSSpotLightMedium = ColorOSSpotLightParams(0.3f, 100f, 500f, 200f, 0.4f, 0.4f, 0.08f)

/**
 * Draws a soft light over the element while a finger that went down on it is held, following the
 * finger and fading out on release, like the ColorOS tiles. It only watches the pointers, so the
 * element still gets every event, and it is clipped to [shape].
 */
@Composable
internal fun Modifier.colorOSSpotLight(
    shape: Shape,
    params: ColorOSSpotLightParams = ColorOSSpotLightSmall,
): Modifier {
    val shader = remember { lazy(LazyThreadSafetyMode.NONE) { RuntimeShader(SPOT_LIGHT_SHADER) } }
    val progress = remember { Animatable(0f) }
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    var fading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch {
                    if (progress.value == 0f) {
                        x.snapTo(down.position.x)
                        y.snapTo(down.position.y)
                    }
                    fading = false
                    progress.animateTo(1f, colorOSSpring(0f, 0.15f))
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val to = change.position
                    scope.launch {
                        x.animateTo(to.x.coerceIn(0f, size.width.toFloat()), colorOSSpring(0.15f, 0.35f))
                    }
                    scope.launch {
                        y.animateTo(to.y.coerceIn(0f, size.height.toFloat()), colorOSSpring(0.15f, 0.35f))
                    }
                }
                scope.launch {
                    fading = true
                    progress.animateTo(0f, colorOSSpring(0f, 0.4f))
                    fading = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p <= 0f) return@drawWithContent
            var radius = params.startRadius + (params.endRadius - params.startRadius) * p
            if (fading) radius += (1f - p) * 2f * params.endRadius
            val shader = shader.value
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("u_lightPosAndHeight", x.value, y.value, radius, params.intensity * p)
            shader.setFloatUniform("u_lightColorAndAmbient", 1f, 1f, 1f, params.ambient)
            shader.setFloatUniform("u_lightingParams", params.diffuse, params.height, params.noise, 0f)
            val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
            clipPath(clip) { drawRect(ShaderBrush(shader), blendMode = params.blend) }
        }
}

private const val SPOT_LIGHT_SHADER =
    """
    uniform vec4 u_lightPosAndHeight;
    uniform vec4 u_lightColorAndAmbient;
    uniform vec4 u_lightingParams;
    uniform vec2 resolution;

    float luminance(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

    float max3(vec3 v) { return max(max(v.r, v.g), v.b); }

    float noise(vec2 p) {
        return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
    }

    float softLight(float A, float B) {
        float l = B - (1.0 - 2.0 * A) * B * (1.0 - B);
        float h = B + (2.0 * A - 1.0) * (sqrt(B) - B);
        return mix(l, h, step(0.5, A));
    }

    float dither(vec2 coord, float ditherN) {
        float n1 = fract(sin(dot(coord, vec2(12.9898, 78.233))) * 43758.5453);
        float n2 = fract(sin(dot(coord + vec2(1.0), vec2(12.9898, 78.233))) * 43758.5453);
        return (n1 + n2 - 1.0) * ditherN / 255.0;
    }

    half4 main(float2 fragCoord) {
        float2 lightP = u_lightPosAndHeight.xy;
        float radius = u_lightPosAndHeight.z;
        float intensity = u_lightPosAndHeight.w;
        vec3 lightCol = u_lightColorAndAmbient.rgb;
        float ambientStr = u_lightColorAndAmbient.w;
        float diffuseStr = u_lightingParams.x;
        float lightH = u_lightingParams.y;
        float noise_exp = u_lightingParams.z;
        float ditherN = u_lightingParams.w;
        const float lightIntensity = 1.5;

        vec2 delta = fragCoord - lightP;
        vec3 L = vec3(delta, lightH);
        float dist = length(L);
        L = normalize(L);
        vec3 N = vec3(0.0, 0.0, 1.0);
        float diff = max(dot(N, L), 0.0);
        vec3 lighting = (ambientStr + diffuseStr * diff) * lightCol;
        if (dist >= radius) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }
        float att = smoothstep(1.0, 0.0, dist / radius) * step(dist, radius);
        lighting *= att * lightIntensity * intensity;
        float alpha = clamp(luminance(lighting), 0.0, 1.0);

        float postOn = max(step(0.001, noise_exp), step(0.001, ditherN));
        vec3 postLighting = lighting;
        float postAlpha = alpha;
        float noiseOn = step(0.001, noise_exp);
        vec2 uv_pos = fragCoord * 9.259e-4;
        float n = clamp((noise(uv_pos) - 0.5) * noise_exp + 0.5, 0.0, 1.0);
        float mask = max3(postLighting);
        float fadeAlpha = clamp(softLight(n, mask), 0.0, 1.0);
        float noiseDelta = clamp(abs(fadeAlpha - mask), 0.0, 1.0);
        postLighting += vec3(noiseDelta) * noiseOn;
        float ditherOn = step(0.001, ditherN);
        float d = dither(fragCoord, ditherN);
        postLighting += vec3(d) * ditherOn;
        postAlpha = mix(postAlpha, clamp(postAlpha + d, 0.0, 1.0), ditherOn);
        lighting = mix(lighting, postLighting, postOn);
        alpha = mix(alpha, postAlpha, postOn);
        return half4(lighting, alpha);
    }
    """
