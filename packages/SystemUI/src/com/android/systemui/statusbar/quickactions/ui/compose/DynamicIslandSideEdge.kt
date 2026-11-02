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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Display edge that holds the camera cutout the Dynamic Island is docked to. */
enum class DynamicIslandEdge {
    /** Camera on the top edge: the island lives in the status bar. */
    TOP,
    /** Camera on the left edge (display rotated 90 degrees). */
    LEFT,
    /** Camera on the right edge (display rotated 270 degrees). */
    RIGHT,
}

/** The edge the camera cutout sits on in the current rotation and the hole's bounds there. */
data class DynamicIslandCutoutEdge(val edge: DynamicIslandEdge, val hole: Rect) {
    /**
     * Clockwise quarter turns that carry the portrait pill onto [edge], so the island keeps the
     * orientation it has when the device is held upright and stays glued to the camera.
     */
    val quarterTurns: Int
        get() =
            when (edge) {
                DynamicIslandEdge.TOP -> 0
                DynamicIslandEdge.LEFT -> 3
                DynamicIslandEdge.RIGHT -> 1
            }

    /**
     * Carries an offset given for the upright portrait pill ([x] along it, [y] down from the top
     * edge) onto this edge by the same quarter turns as the pill, so a positive [y] still moves the
     * pill away from the camera edge and a positive [x] along it.
     */
    fun rotateOffset(x: Int, y: Int): IntOffset =
        when (quarterTurns) {
            1 -> IntOffset(-y, x)
            3 -> IntOffset(y, -x)
            else -> IntOffset(x, y)
        }
}

/**
 * Finds the edge holding the camera cutout in the current rotation. A top cutout is resolved
 * exactly as before. Only when there is none, the left and right cutouts of the display are
 * considered; the status bar window is too short to receive those in its own insets, so the
 * display-level cutout is used.
 */
fun resolveDynamicIslandCutoutEdge(view: View): DynamicIslandCutoutEdge? {
    val displayCutout = view.display?.cutout
    val top = (view.rootWindowInsets?.displayCutout ?: displayCutout)?.dynamicIslandHoleRect()
    if (top != null) return DynamicIslandCutoutEdge(DynamicIslandEdge.TOP, top)
    val cutout = displayCutout ?: return null
    cutout.getBoundingRectLeft().takeUnless { it.isEmpty }?.let {
        return DynamicIslandCutoutEdge(DynamicIslandEdge.LEFT, cutout.dynamicIslandHoleWithin(it))
    }
    cutout.getBoundingRectRight().takeUnless { it.isEmpty }?.let {
        return DynamicIslandCutoutEdge(DynamicIslandEdge.RIGHT, cutout.dynamicIslandHoleWithin(it))
    }
    return null
}

/**
 * Clockwise quarter turns that the pill is currently turned by, as seen from inside it. Leaf
 * content (artwork, visualizer, icons, text) reads this through [UprightContent] and turns back by
 * the same amount, so it faces the same way as the rest of the system UI while the pill itself
 * stays laid out along the camera edge.
 */
val LocalDynamicIslandPillTurns = compositionLocalOf { 0 }

/**
 * Keeps [content] upright on screen when the pill around it is turned onto a side edge. The pill
 * layout still sees the content's turned size, so a wide label makes the pill thicker and a tall
 * one makes it longer along the edge. A no-op for a top camera.
 */
@Composable
fun UprightContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val turns = LocalDynamicIslandPillTurns.current.mod(4)
    if (turns == 0) {
        content()
    } else {
        Box(modifier = modifier.rotatedLayout(-turns)) { content() }
    }
}

/** Provides [turns] to [UprightContent] below. */
@Composable
fun ProvideDynamicIslandPillTurns(turns: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDynamicIslandPillTurns provides turns, content = content)
}

/**
 * Lays the content out turned by [quarterTurns] clockwise quarter turns. Unlike a plain
 * `graphicsLayer { rotationZ }`, the measured size is swapped too, so the parent, the bounds
 * reported by `onGloballyPositioned` and the camera gap all follow the rotated pill.
 */
fun Modifier.rotatedLayout(quarterTurns: Int): Modifier {
    val turns = quarterTurns.mod(4)
    if (turns == 0) return this
    val swapped = turns % 2 == 1
    return layout { measurable, constraints ->
        val inner =
            if (swapped) {
                Constraints(
                    minWidth = constraints.minHeight,
                    maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth,
                    maxHeight = constraints.maxWidth,
                )
            } else {
                constraints
            }
        val placeable = measurable.measure(inner)
        val width = if (swapped) placeable.height else placeable.width
        val height = if (swapped) placeable.width else placeable.height
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                rotationZ = turns * 90f
            }
        }
    }
}

private val DynamicIslandSideEdgeMargin = 4.dp

/**
 * Centers [content] on the camera [edge] in a full-screen window: the pill is centered on the
 * hole and kept inside the screen. Everything outside the pill is empty.
 */
@Composable
fun DynamicIslandSideEdgeLayout(
    edge: DynamicIslandCutoutEdge,
    modifier: Modifier = Modifier,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable () -> Unit,
) {
    val marginPx = with(LocalDensity.current) { DynamicIslandSideEdgeMargin.roundToPx() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable =
            measurables.firstOrNull()?.measure(Constraints(maxWidth = width, maxHeight = height))
        layout(width, height) {
            placeable?.let {
                val x =
                    (edge.hole.exactCenterX() - it.width / 2f)
                        .roundToInt()
                        .plus(offset.x)
                        .coerceIn(marginPx, (width - it.width - marginPx).coerceAtLeast(marginPx))
                val y =
                    (edge.hole.exactCenterY() - it.height / 2f)
                        .roundToInt()
                        .plus(offset.y)
                        .coerceIn(0, (height - it.height).coerceAtLeast(0))
                it.place(x, y)
            }
        }
    }
}

/**
 * Vertical shift, in px, that moves an island laid out centred in [host] onto the camera [hole].
 * [host] centres the island vertically, so the shift is the distance between the hole's centre
 * and the host's centre in the status bar window. Layout positions are summed instead of read
 * from the screen so a status bar that is sliding in or out does not skew the result. Returns 0
 * without a camera or before the host is laid out, and is capped so an odd cutout cannot push the
 * island off the status bar.
 */
fun dynamicIslandCameraCenterOffsetYPx(
    host: View,
    statusBar: View,
    hole: android.graphics.Rect?,
    density: Float,
): Float {
    if (hole == null || host.height <= 0) return 0f
    var top = 0
    var current: View? = host
    while (current != null && current !== statusBar) {
        top += current.top
        current = current.parent as? View
    }
    if (current == null) return 0f
    val limit = DynamicIslandCameraAlignMaxOffset.value * density
    return (hole.exactCenterY() - (top + host.height / 2f)).coerceIn(-limit, limit)
}

private val DynamicIslandCameraAlignMaxOffset = 24.dp
