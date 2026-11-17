/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur

/** The panel starts to show at this fraction of its expansion, glass and modules together. */
private const val ColorOSExpandRevealStart = 0.25f

/** The panel is fully shown at this fraction of its expansion. */
private const val ColorOSExpandRevealEnd = 0.85f

/**
 * How much of the panel is shown for the given [expansion], 0 when closed and 1 when open. The
 * modules cascade with it and their glass fades with them, so they follow the drag from the quick
 * settings to the panel and are never out of step: the glass is drawn by SurfaceFlinger and has no
 * other way to know how far the panel has come.
 */
internal fun colorOSExpansionReveal(expansion: Float): Float =
    ((expansion - ColorOSExpandRevealStart) / (ColorOSExpandRevealEnd - ColorOSExpandRevealStart))
        .coerceIn(0f, 1f)

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

/** Part of the reveal by which each module starts after the one above it. */
private const val ColorOSCascadeStep = 0.07f

/** Modules the cascade is spread over; later ones start with the last. */
private const val ColorOSCascadeModules = 6

/** The size a module has when it starts to drop in. */
private const val ColorOSCascadeFrom = 0.9f

private val ColorOSCascadeDrop = 24.dp
private val ColorOSCascadeDropStep = 8.dp

private fun colorOSCascadeProgress(shown: Float, index: Int): Float {
    val start = ColorOSCascadeStep * (index - 1).coerceIn(0, ColorOSCascadeModules - 1)
    val span = 1f - ColorOSCascadeStep * (ColorOSCascadeModules - 1)
    return ((shown - start) / span).coerceIn(0f, 1f)
}

private fun colorOSCascadeAlpha(shown: Float, index: Int): Float =
    (colorOSCascadeProgress(shown, index) * 1.6f).coerceAtMost(1f)

/**
 * Like ColorOS, the panel unfolds as a cascade that follows the finger: each module drops in from
 * above, growing and fading in, a little after the one above it and from a little further, so the
 * panel pours down. Closing, the bottom modules lift away first. [reveal] is how much of the panel
 * is shown, from 0 to 1, and [index] counts from 1 at the top.
 */
@Composable
internal fun Modifier.colorOSModuleReveal(index: Int, reveal: () -> Float): Modifier {
    val drop =
        with(LocalDensity.current) {
            (ColorOSCascadeDrop + ColorOSCascadeDropStep * (index - 1)).toPx()
        }
    return graphicsLayer {
        val shown = reveal()
        val eased = LinearOutSlowInEasing.transform(colorOSCascadeProgress(shown, index))
        translationY = -drop * (1f - eased)
        val scale = ColorOSCascadeFrom + (1f - ColorOSCascadeFrom) * eased
        scaleX = scale
        scaleY = scale
        alpha = colorOSCascadeAlpha(shown, index)
    }
}

/**
 * The glass under a module is a window blur, which takes neither the fade nor the movement of the
 * module: it fades with the module here, so each tile keeps its outline while it shows.
 */
@Composable
internal fun ColorOSModuleGlass(index: Int, reveal: () -> Float, content: @Composable () -> Unit) {
    val outer = LocalColorOSGlassBlur.current
    if (outer == null) {
        content()
        return
    }
    val currentReveal by rememberUpdatedState(reveal)
    val blur =
        remember(outer, index) {
            ColorOSGlassBlur { outer.alpha() * colorOSCascadeAlpha(currentReveal(), index) }
        }
    CompositionLocalProvider(LocalColorOSGlassBlur provides blur, content = content)
}
