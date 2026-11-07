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

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI

private const val WHITE = -1

private class ConicTarget(
    val height: Float,
    val heightFalloff: Float,
    val coneK: Float,
    val secondConeK: Float,
    val w0: Float,
    val alpha1: Float,
    val alpha2: Float,
    val shapeResponse: Float,
    val alphaResponse: Float,
)

private val Off = ConicTarget(0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0.3f, 0.3f)
private val Playing = ConicTarget(0.6f, 0.6f, 0.2f, 0.2f, 0.008f, 1f, 0.6f, 0.9f, 0.3f)
private val Dragging = ConicTarget(0.2f, 2f, 0f, 0f, 0.014f, 0.3f, 0.3f, 0.4f, 0.4f)

private fun cosSpring(response: Float): AnimationSpec<Float> =
    spring(dampingRatio = 1f, stiffness = (2 * PI / response).let { it * it }.toFloat())

/**
 * Lights the seek bar with a conic glow that trails the progress: wide while playing, narrow while
 * dragging, gone while paused. [color] is the glow, white when it is 0. Draws nothing when the
 * shader cannot be created.
 */
@Composable
internal fun Modifier.cosSeekGlow(
    progress: Float,
    playing: Boolean,
    dragging: Boolean,
    color: Int = 0,
): Modifier {
    val light = remember { runCatching { SeekLight() }.getOrNull() } ?: return this
    val glowColor = color.takeIf { it != 0 } ?: WHITE
    LaunchedEffect(glowColor) { light.setColor(glowColor) }

    val target =
        when {
            dragging -> Dragging
            playing -> Playing
            else -> Off
        }
    val shape = cosSpring(target.shapeResponse)
    val fade = cosSpring(target.alphaResponse)
    val height by animateFloatAsState(target.height, shape, label = "conicHeight")
    val heightFalloff by animateFloatAsState(target.heightFalloff, shape, label = "conicFalloff")
    val coneK by animateFloatAsState(target.coneK, shape, label = "conicConeK")
    val secondConeK by animateFloatAsState(target.secondConeK, shape, label = "conicConeK2")
    val w0 by animateFloatAsState(target.w0, shape, label = "conicW0")
    val alpha1 by animateFloatAsState(target.alpha1, fade, label = "conicAlpha1")
    val alpha2 by animateFloatAsState(target.alpha2, fade, label = "conicAlpha2")

    return drawWithContent {
        drawContent()
        if (alpha1 < 0.001f && alpha2 < 0.001f) return@drawWithContent
        light.setShape(
            SeekLight.Shape(height, heightFalloff, coneK, secondConeK, w0, alpha1, alpha2)
        )
        drawIntoCanvas {
            light.draw(
                it.nativeCanvas,
                0f,
                size.height / 2,
                size.width,
                progress.coerceIn(0f, 1f),
                layoutDirection == LayoutDirection.Rtl,
            )
        }
    }
}
