/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSSpring
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How far the shown fraction of the panel has to move before it counts as a direction. */
private const val ColorOSLeavingSlop = 0.004f

/** The modules of the panel start 16ms after each other, and slower the further down they are. */
private const val ColorOSRevealStaggerMs = 16L
private const val ColorOSRevealFrom = 0.8f
private const val ColorOSRevealResponse = 0.2f
private const val ColorOSRevealResponseStep = 0.01f
private const val ColorOSHideResponse = 0.1f

/** Critically damped, which is a response of about 0.1s. */
private const val ColorOSGlassFadeStiffness = 3948f

/**
 * Whether the panel is on its way out: the shown fraction of it ([expansion], 0 when closed and 1
 * when open) is going down, or it is closed. It is true at rest while closed, so the modules wait
 * hidden and are revealed when the shade starts to open.
 */
@Composable
internal fun rememberColorOSLeaving(expansion: () -> Float): State<Boolean> {
    val currentExpansion by rememberUpdatedState(expansion)
    val leaving = remember { mutableStateOf(Snapshot.withoutReadObservation { expansion() } <= 0f) }
    LaunchedEffect(Unit) {
        var last = currentExpansion()
        snapshotFlow { currentExpansion() }
            .collect { value ->
                when {
                    value <= 0f || value < last - ColorOSLeavingSlop -> {
                        leaving.value = true
                        last = value
                    }
                    value > last + ColorOSLeavingSlop -> {
                        leaving.value = false
                        last = value
                    }
                }
            }
    }
    return leaving
}

/**
 * The glass of the panel is drawn by SurfaceFlinger and does not follow the modules as they fade,
 * so it goes out with a stiff spring as soon as the panel is [leaving], and comes back with it.
 */
@Composable
internal fun rememberColorOSGlassFade(leaving: State<Boolean>): State<Float> {
    val isLeaving = leaving.value
    val fade = remember { Animatable(if (isLeaving) 0f else 1f) }
    LaunchedEffect(isLeaving) {
        fade.animateTo(
            if (isLeaving) 0f else 1f,
            spring(dampingRatio = 1f, stiffness = ColorOSGlassFadeStiffness),
        )
    }
    return fade.asState()
}

/**
 * Scales a module of the panel from 0.8 to 1 while it fades in, [index] places down the panel, and
 * back out quickly when the panel is [leaving].
 */
@Composable
internal fun Modifier.colorOSModuleReveal(index: Int, leaving: State<Boolean>): Modifier {
    val isLeaving = leaving.value
    val scale = remember { Animatable(if (isLeaving) ColorOSRevealFrom else 1f) }
    val alpha = remember { Animatable(if (isLeaving) 0f else 1f) }
    LaunchedEffect(isLeaving) {
        if (isLeaving) {
            launch { scale.animateTo(ColorOSRevealFrom, colorOSSpring(0f, ColorOSHideResponse)) }
            alpha.animateTo(0f, colorOSSpring(0f, ColorOSHideResponse))
        } else {
            delay(index * ColorOSRevealStaggerMs)
            val response = ColorOSRevealResponse + index * ColorOSRevealResponseStep
            launch { scale.animateTo(1f, colorOSSpring(0f, response)) }
            alpha.animateTo(1f, colorOSSpring(0f, response))
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }
}
