/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.android.compose.modifiers.thenIf
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/** What the customizer does with the tiles of the preview. */
interface QsPreviewHost {
    fun onTap(spec: TileSpec)

    /** The plus tile was tapped. */
    fun onAdd()

    fun onDragStart(spec: TileSpec)

    /** The finger holding a tile moved to [pointer], in root coordinates. */
    fun onDragTo(pointer: Offset)

    fun onDragEnd(cancelled: Boolean)
}

/**
 * The tiles inside the preview: which one is picked, their order while one is dragged, and where
 * each sits on screen. The customizer owns it; the grid reads it and reports gestures.
 */
@Stable
class QsPreviewTiles {
    lateinit var host: QsPreviewHost

    /** The tile whose sheet is open. */
    var selected by mutableStateOf<TileSpec?>(null)

    /** A tile the pager should scroll to once, such as one just added. */
    var reveal by mutableStateOf<TileSpec?>(null)

    /** The tiles in the order the panel shows them, moved live while one is dragged. */
    var order by mutableStateOf<List<TileSpec>>(emptyList())

    var dragging by mutableStateOf<TileSpec?>(null)

    /** A drag or a handle resize is on or just ended: the other tiles glide to their places. */
    var settling by mutableStateOf(false)

    /** Where the dragged tile's top start corner is drawn, in root coordinates. */
    var dragCorner by mutableStateOf(Offset.Unspecified)

    /** How far the finger was from that corner when the tile was lifted. */
    var grab = Offset.Zero

    /** The finger is over the top bar: letting go removes the tile. */
    var removeArmed by mutableStateOf(false)

    /** Root bounds of every composed tile, to find the one under a finger. */
    val bounds = HashMap<TileSpec, Rect>()

    /** Root bounds of the area the tiles scroll in: only tiles inside it are targets. */
    var pager = Rect.Zero

    fun onTap(spec: TileSpec) = host.onTap(spec)

    fun tileAt(point: Offset): TileSpec? =
        if (!pager.contains(point)) {
            null
        } else {
            bounds.entries.firstOrNull { it.value.contains(point) }?.key
        }
}

val LocalQsPreviewTiles = compositionLocalOf<QsPreviewTiles?> { null }

/**
 * One tile of the preview: a tap picks it and a hold lifts it to move. It is drawn from where
 * [QsPreviewTiles.dragCorner] is while it is lifted.
 */
@Composable
fun QsPreviewTile(
    preview: QsPreviewTiles,
    spec: TileSpec,
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val selected = preview.selected == spec
    val dragging = preview.dragging == spec
    DisposableEffect(preview, spec) { onDispose { preview.bounds.remove(spec) } }
    val ring = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .onGloballyPositioned {
                preview.bounds[spec] =
                    it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    val corner = preview.dragCorner
                    val here = coordinates
                    if (dragging && corner.isSpecified && here != null && here.isAttached) {
                        val at = here.localPositionOf(here.findRootCoordinates(), corner)
                        placeable.placeWithLayer(0, 0) {
                            translationX = at.x
                            translationY = at.y
                            scaleX = LiftScale
                            scaleY = LiftScale
                            shadowElevation = LiftElevation.toPx()
                            this.shape = shape
                        }
                    } else {
                        placeable.place(0, 0)
                    }
                }
            }
            .thenIf(selected && !dragging) { Modifier.border(2.dp, ring, shape) }
    ) {
        content()
        Box(
            Modifier.matchParentSize().pointerInput(preview, spec) {
                detectTapGestures(onTap = { preview.host.onTap(spec) })
            }
        )
    }
}

fun Modifier.qsPreviewDrag(preview: QsPreviewTiles, area: () -> LayoutCoordinates?): Modifier =
    pointerInput(preview) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start =
                area()?.takeIf { it.isAttached }?.localToRoot(down.position)
                    ?: return@awaitEachGesture
            val spec = preview.tileAt(start) ?: return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val quit =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var over = false
                    while (!over) {
                        val change =
                            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                                it.id == down.id
                            }
                        over =
                            change == null ||
                                !change.pressed ||
                                (change.position - down.position).getDistance() > slop
                    }
                    true
                }
            val slot = preview.bounds[spec]
            if (quit != null || slot == null) return@awaitEachGesture
            preview.grab = start - slot.topLeft
            preview.host.onDragStart(spec)
            preview.host.onDragTo(start)
            var dropped = false
            try {
                while (!dropped) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val lifted = !change.pressed && !change.isConsumed
                    event.changes.forEach { it.consume() }
                    if (lifted) {
                        dropped = true
                    } else if (!change.pressed) {
                        break
                    } else {
                        area()?.takeIf { it.isAttached }?.let {
                            preview.host.onDragTo(it.localToRoot(change.position))
                        }
                    }
                }
            } finally {
                preview.host.onDragEnd(cancelled = !dropped)
            }
        }
    }

/** The dashed plus after the last tile, which opens the tray of tiles to add. */
@Composable
fun QsPlusTile(preview: QsPreviewTiles, radius: Dp, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    val label = stringResource(R.string.qs_custom_add_tile)
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .clickable(onClickLabel = label) { preview.host.onAdd() }
            .drawBehind {
                val stroke =
                    Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    )
                val inset = stroke.width / 2
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke.width, size.height - stroke.width),
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = stroke,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Add, contentDescription = label, tint = color)
    }
}

/** Slides a child of a custom layout to each new spot it is placed at. */
fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this.onPlaced { target = it.positionInParent().round() }
        .offset {
            val wanted = target ?: return@offset IntOffset.Zero
            val anim =
                animatable
                    ?: Animatable(wanted, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != wanted) {
                scope.launch {
                    anim.animateTo(wanted, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            anim.value - wanted
        }
}

private const val LiftScale = 1.04f
private val LiftElevation = 12.dp
