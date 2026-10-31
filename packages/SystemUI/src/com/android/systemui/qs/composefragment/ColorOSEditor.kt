/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.os.UserHandle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSIconRowGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSTileRim
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlin.math.roundToInt

private const val QS_TILES_SETTING = "sysui_qs_tiles"

/** The value of [QS_TILES_SETTING] that stands for the default tiles, not for a tile. */
private const val QS_TILES_DEFAULT = "default"

private val EditorBadge = 22.dp
private val EditorCellMin = 88.dp
private val EditorBadgeFill = Color(0xFF5B5B60)
private val EditorAccent = Color(0xFF4D8BFF)

/**
 * The ColorOS editor on the edit model of Quick Settings, so every shade that edits tiles shows
 * the same one. The tiles the panel pins as cards (see [colorOSCardSpecsOf]) stay out of the grid
 * and are written back in front of the rest when the order is saved.
 */
@Composable
internal fun ColorOSEditor(editViewModel: EditModeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BackHandler { editViewModel.stopEditing() }
    // Dismissing the shade disposes the editor, don't reopen Quick Settings still editing.
    DisposableEffect(Unit) { onDispose { editViewModel.stopEditing() } }

    val tiles by editViewModel.tiles.collectAsStateWithLifecycle(emptyList())
    val current = remember(tiles) { tiles.filter { it.isCurrent } }
    val cards = remember(current) { colorOSCardSpecsOf(current.map { it.tileSpec.spec }.toSet()) }
    val gridTiles = remember(current, cards) { current.filterNot { it.tileSpec.spec in cards } }
    val available = remember(tiles) { tiles.filterNot { it.isCurrent } }
    val bySpec = remember(tiles) { tiles.associateBy { it.tileSpec } }

    var order by remember { mutableStateOf(gridTiles.map { it.tileSpec }) }
    var dragging by remember { mutableStateOf<TileSpec?>(null) }
    LaunchedEffect(gridTiles) { if (dragging == null) order = gridTiles.map { it.tileSpec } }

    fun commit(grid: List<TileSpec>) {
        val cardSpecs = current.map { it.tileSpec }.filter { it.spec in cards }
        // Tiles that are saved but not available on this device are not in the edit model, keep
        // them where the saved list has them.
        val hidden =
            Settings.Secure.getStringForUser(
                    context.contentResolver,
                    QS_TILES_SETTING,
                    UserHandle.USER_CURRENT,
                )
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != QS_TILES_DEFAULT }
                .map(TileSpec::create)
                .filter { it !in bySpec }
        editViewModel.setTiles(cardSpecs + grid + hidden)
    }

    val label = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorTextButton(stringResource(R.string.reset), label.copy(alpha = 0.85f)) {
                val defaults =
                    context.resources
                        .getString(R.string.quick_settings_tiles_default)
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map(TileSpec::create)
                editViewModel.setTiles(defaults)
            }
            Text(
                text = stringResource(R.string.qs_coloros_edit_title),
                color = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            EditorTextButton(stringResource(R.string.quick_settings_done), EditorAccent) {
                editViewModel.stopEditing()
            }
        }
        Text(
            text = stringResource(R.string.qs_coloros_edit_hint),
            color = label.copy(alpha = 0.55f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )

        EditorGrid(
            specs = order,
            bySpec = bySpec,
            dragging = dragging,
            onDragStart = { dragging = it },
            onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
            onDragEnd = {
                dragging = null
                commit(order)
            },
            badge = { tile ->
                if (tile.isRemovable) {
                    EditorBadgeIcon(R.drawable.ic_remove) { editViewModel.removeTile(tile.tileSpec) }
                }
            },
            onTap = null,
        )

        if (available.isNotEmpty()) {
            Text(
                text = stringResource(R.string.qs_coloros_edit_more),
                color = label.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
            )
            EditorGrid(
                specs = available.map { it.tileSpec },
                bySpec = bySpec,
                dragging = null,
                onDragStart = null,
                onMove = { _, _ -> },
                onDragEnd = {},
                badge = { tile ->
                    EditorBadgeIcon(R.drawable.ic_add) { editViewModel.addTile(tile.tileSpec) }
                },
                onTap = { editViewModel.addTile(it.tileSpec) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EditorGrid(
    specs: List<TileSpec>,
    bySpec: Map<TileSpec, EditTileViewModel>,
    dragging: TileSpec?,
    onDragStart: ((TileSpec) -> Unit)?,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    badge: @Composable (EditTileViewModel) -> Unit,
    onTap: ((EditTileViewModel) -> Unit)?,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val columns = (maxWidth / EditorCellMin).toInt().coerceIn(4, 8)
        val cellW = maxWidth / columns
        // A round tile and its label, the way the panel stacks them.
        val cellH =
            CommonTileDefaults.ColorOSTileHeight +
                CommonTileDefaults.ClassicLabelHeight +
                ColorOSIconRowGap
        val rows = (specs.size + columns - 1) / columns
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        var dragOrigin by remember { mutableStateOf(Offset.Zero) }

        fun slotOf(index: Int) = Offset((index % columns) * cellWPx, (index / columns) * cellHPx)

        Box(Modifier.fillMaxWidth().height(cellH * rows)) {
            specs.forEachIndexed { index, spec ->
                val tile = bySpec[spec] ?: return@forEachIndexed
                key(spec) {
                    val isDragged = spec == dragging
                    val slot = slotOf(index)
                    val animated by
                        animateIntOffsetAsState(
                            IntOffset(slot.x.roundToInt(), slot.y.roundToInt()),
                            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                            label = "coloros edit slot",
                        )
                    val latestIndex by rememberUpdatedState(index)
                    val latestSpecs by rememberUpdatedState(specs)
                    Box(
                        Modifier.offset {
                                if (isDragged) {
                                    IntOffset(
                                        (dragOrigin.x + dragOffset.x).roundToInt(),
                                        (dragOrigin.y + dragOffset.y).roundToInt(),
                                    )
                                } else {
                                    animated
                                }
                            }
                            .zIndex(if (isDragged) 1f else 0f)
                            .width(cellW)
                            .height(cellH)
                            .graphicsLayer {
                                val s = if (isDragged) 1.08f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .then(
                                if (onDragStart != null) {
                                    Modifier.pointerInput(spec) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                                dragOrigin = slotOf(latestIndex)
                                                dragOffset = Offset.Zero
                                                onDragStart(spec)
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount
                                                val center =
                                                    dragOrigin +
                                                        dragOffset +
                                                        Offset(cellWPx / 2f, cellHPx / 2f)
                                                val col =
                                                    (center.x / cellWPx)
                                                        .toInt()
                                                        .coerceIn(0, columns - 1)
                                                val row = (center.y / cellHPx).toInt().coerceAtLeast(0)
                                                val target =
                                                    (row * columns + col)
                                                        .coerceIn(0, latestSpecs.lastIndex)
                                                if (target != latestIndex) {
                                                    onMove(latestIndex, target)
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                }
                                            },
                                            onDragEnd = onDragEnd,
                                            onDragCancel = onDragEnd,
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (onTap != null) Modifier.clickable { onTap(tile) } else Modifier
                            )
                    ) {
                        EditorTile(tile, badge)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTile(tile: EditTileViewModel, badge: @Composable (EditTileViewModel) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // There is no blur behind the editor, so the circle is the flat glass of the tiles that are off.
    val fill = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(CommonTileDefaults.ColorOSTileHeight)
                    .clip(CircleShape)
                    .background(fill, CircleShape)
                    .border(1.dp, ColorOSTileRim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = { tile.icon },
                    color = onSurface,
                    size = { CommonTileDefaults.LargeTileIconSize },
                )
            }
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) { badge(tile) }
        }
        Text(
            text = tile.label.text,
            color = onSurface.copy(alpha = 0.9f),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun EditorBadgeIcon(iconRes: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(EditorBadge)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(EditorBadgeFill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun EditorTextButton(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier =
            Modifier.clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
