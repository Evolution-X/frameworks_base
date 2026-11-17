/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

object GlassBackdrop {
    data class Region(
        val bounds: Rect,
        val corner: Float,
        val alpha: Float,
        val motion: Motion = Motion.None,
    ) {
        /** Where the region is drawn this frame. */
        fun liveBounds(): Rect = bounds.translate(0f, motion.dy())

        fun liveAlpha(): Float = alpha * motion.fade().coerceIn(0f, 1f)
    }

    /**
     * A vertical shift and fade the region's owner takes from a graphics layer, which moves it
     * without a relayout. Read as the backdrop draws, so the glass follows its owner.
     */
    class Motion(val dy: () -> Float, val fade: () -> Float) {
        companion object {
            val None = Motion({ 0f }, { 1f })
        }
    }

    val regions = mutableStateMapOf<Any, Region>()

    val drawing = mutableStateOf(false)

    fun update(key: Any, region: Region?) {
        if (region == null || region.alpha <= 0f) {
            regions.remove(key)
        } else if (regions[key] != region) {
            regions[key] = region
        }
    }
}

@Composable
fun Modifier.glassBackdropRegion(
    corner: Dp,
    alpha: Float = 1f,
    motion: GlassBackdrop.Motion = GlassBackdrop.Motion.None,
): Modifier {
    val key = remember { Any() }
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(key) { onDispose { GlassBackdrop.update(key, null) } }
    SideEffect {
        bounds.value?.let {
            GlassBackdrop.update(key, GlassBackdrop.Region(it, cornerPx, alpha, motion))
        }
    }
    return onGloballyPositioned {
        // Kept without the shift the layer had at layout time; the backdrop adds the live one.
        val rect = it.boundsInWindow().translate(0f, -motion.dy())
        bounds.value = rect
        GlassBackdrop.update(key, GlassBackdrop.Region(rect, cornerPx, alpha, motion))
    }
}
