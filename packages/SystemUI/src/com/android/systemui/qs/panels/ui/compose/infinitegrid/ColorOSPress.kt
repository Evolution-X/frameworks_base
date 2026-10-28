/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A critically damped spring from the response time (seconds) and bounce of ColorOS motion. */
internal fun <T> colorOSSpring(bounce: Float, response: Float): SpringSpec<T> =
    spring(
        dampingRatio = 1f - bounce,
        stiffness = (2 * PI / response).let { it * it }.toFloat(),
    )

private fun pressEase(x: Float): Float = 3f * x.pow(2f / 3f) - 2f * x

/**
 * How far a ColorOS element shrinks while it is pressed: small elements shrink to 85%, large ones
 * to 97%, and long thin ones in between.
 */
internal fun colorOSPressScale(width: Float, height: Float, dp: Float): Float {
    val area = width * height
    if (area <= 0f) return 1f
    val minArea = (20f * dp).pow(2)
    val maxArea = (150f * dp).pow(2)
    val t = pressEase((area.coerceIn(minArea, maxArea) - minArea) / (maxArea - minArea))
    val areaScale = 0.85f + (0.97f - 0.85f) * t
    val aspect = max(width, height) / min(width, height)
    val scale =
        if (aspect <= 2f) areaScale
        else (areaScale + 0.85f + 0.15f * pressEase(((aspect - 2f) / 13f).coerceIn(0f, 1f))) / 2f
    return scale.coerceIn(0.4f, 1f)
}

/**
 * Scales the element down on a press of [interaction] and springs it back on release, like the
 * ColorOS tiles. Pass `null` for [interaction] to leave the element alone.
 */
@Composable
internal fun Modifier.colorOSPress(
    interaction: InteractionSource?,
    pressedScale: Float? = null,
    press: SpringSpec<Float>? = null,
    release: SpringSpec<Float>? = null,
): Modifier {
    if (interaction == null) return this
    val pressed by interaction.collectIsPressedAsState()
    val dp = LocalDensity.current.density
    var sized by remember { mutableFloatStateOf(0.92f) }
    var long by remember { mutableFloatStateOf(1f) }
    val candy = long > 2f
    val scale by
        animateFloatAsState(
            if (pressed) pressedScale ?: sized else 1f,
            if (pressed) press ?: colorOSSpring(0f, if (candy) 0.3f else 0.25f)
            else release ?: colorOSSpring(if (candy) 0.5f else 0.6f, 0.5f),
            label = "colorOSPress",
        )
    return onSizeChanged {
            sized = colorOSPressScale(it.width.toFloat(), it.height.toFloat(), dp)
            long = max(it.width, it.height).toFloat() / max(1, min(it.width, it.height))
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
