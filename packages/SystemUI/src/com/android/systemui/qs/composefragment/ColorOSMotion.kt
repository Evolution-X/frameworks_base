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

/** A panel that is on its way out is not on its way in again below this shown fraction. */
private const val ColorOSReopenFloor = 0.03f

/** The modules of the panel start 16ms after each other, and slower the further down they are. */
private const val ColorOSRevealStaggerMs = 16L
private const val ColorOSRevealFrom = 0.8f
private const val ColorOSRevealResponse = 0.2f
private const val ColorOSRevealResponseStep = 0.01f
private const val ColorOSHideResponse = 0.1f

/** The launch of an activity from the shade has run this far before the glass starts to go out. */
private const val ColorOSLaunchGlassFadeStart = 0.7f

/** The glass is gone at this point of the launch, the app covers the panel by then. */
private const val ColorOSLaunchGlassFadeEnd = 0.9f

/**
 * How much of the glass is left while an activity opens from the shade, [progress] is how far the
 * launch has run. The glass is drawn by SurfaceFlinger, and it hears that a region is gone with
 * the next frame the window draws. The shade is closed in a single step when the launch ends, and
 * the window does not draw after that, so the regions would stay for a few frames over the app,
 * without the content they belong to. They are taken out while the launch is still drawing
 * frames, which the app covers.
 */
internal fun colorOSLaunchGlassFade(progress: Float): Float =
    1f -
        ((progress - ColorOSLaunchGlassFadeStart) /
                (ColorOSLaunchGlassFadeEnd - ColorOSLaunchGlassFadeStart))
            .coerceIn(0f, 1f)

/** The glass comes back like the modules in the middle of the panel do. */
private const val ColorOSGlassRevealIndex = 2

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
                    // A small bump of the fraction at the floor of a closed panel does not make
                    // it come back.
                    value > last + ColorOSLeavingSlop &&
                        (!leaving.value || value > ColorOSReopenFloor) -> {
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
 * so it goes out with a stiff spring as soon as the panel is [leaving]. It comes back with the
 * modules, after the same delay and with the same spring as the ones in the middle of the panel,
 * so it never shows before the content does.
 */
@Composable
internal fun rememberColorOSGlassFade(leaving: State<Boolean>): State<Float> {
    val isLeaving = leaving.value
    val fade = remember { Animatable(if (isLeaving) 0f else 1f) }
    LaunchedEffect(isLeaving) {
        if (isLeaving) {
            fade.animateTo(0f, spring(dampingRatio = 1f, stiffness = ColorOSGlassFadeStiffness))
        } else {
            delay(ColorOSGlassRevealIndex * ColorOSRevealStaggerMs)
            fade.animateTo(
                1f,
                colorOSSpring(
                    0f,
                    ColorOSRevealResponse + ColorOSGlassRevealIndex * ColorOSRevealResponseStep,
                ),
            )
        }
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
