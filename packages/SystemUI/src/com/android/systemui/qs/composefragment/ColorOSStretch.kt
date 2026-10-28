/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSSpring
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.launch

private fun rubber(distance: Float, ratio: Float, limit: Float): Float =
    (1f - 1f / (distance * ratio / limit + 1f)) * limit

private fun maxStretch(width: Float, height: Float): Float {
    val area = width * height
    val small = min(width, height)
    if (area <= 38416f || small <= 0f) return 1.2f
    val damp = min(0.015f * (max(width, height) / small - 1f) + 0.35f * ln(area / 38416f), 1f)
    return max(0.17f * (1f - damp) + 1.03f, 1.03f)
}

/**
 * Stretches the element along its height while a finger that went down on it is dragged past its
 * top or bottom edge, and springs it back on release, like the ColorOS sliders. It only watches
 * the pointers, so the element still gets every event.
 */
@Composable
internal fun Modifier.colorOSStretch(): Modifier {
    val density = LocalDensity.current
    val deformLimit = with(density) { 150f * this.density }
    val followLimit = 30f
    val stretch = remember { Animatable(1f) }
    val follow = remember { Animatable(0f) }
    val origin = remember { floatArrayOf(1f) }
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val maxScale = maxStretch(size.width.toFloat(), size.height.toFloat())
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val y = change.position.y
                    val over =
                        when {
                            y < 0f -> y
                            y > size.height -> y - size.height
                            else -> 0f
                        }
                    if (over != 0f) origin[0] = if (over < 0f) 1f else 0f
                    val target =
                        1f + (maxScale - 1f) * rubber(abs(over), 0.55f, deformLimit) / deformLimit
                    val shift = sign(over) * rubber(abs(over), 0.05f, followLimit)
                    scope.launch { stretch.animateTo(target, colorOSSpring(0.15f, 0.15f)) }
                    scope.launch { follow.animateTo(shift, colorOSSpring(0.15f, 0.15f)) }
                }
                scope.launch { stretch.animateTo(1f, colorOSSpring(0.65f, 0.42f)) }
                scope.launch { follow.animateTo(0f, colorOSSpring(0.65f, 0.42f)) }
            }
        }
        .graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, origin[0])
            scaleY = stretch.value
            scaleX = 1f / stretch.value
            translationY = follow.value
        }
}
