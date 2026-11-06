/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.os.UserHandle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSTileRim
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val QS_TILES_SETTING = "sysui_qs_tiles"
private const val QS_TILES_DEFAULT = "default"

private val EditorBadge = 22.dp
private val EditorHorizontalPadding = 8.dp
private val EditorCellMin = 84.dp
private val EditorBadgeFill = Color(0xFF5B5B60)
private val EditorAccent = Color(0xFF0068FF) // ColorOS Accent Blue
private val EditorRemoveTint = Color(0xFFFF453A)

private enum class DragSection {
    ACTIVE,
    AVAILABLE,
}

/**
 * Encapsulates the live drag-and-drop state across both the active and available tile grids.
 */
private class ColorOSEditDragState {
    var draggingSpec by mutableStateOf<TileSpec?>(null)
    var dragSection by mutableStateOf<DragSection?>(null)
    var initialIndex by mutableIntStateOf(-1)
    var dragItemOffset by mutableStateOf(Offset.Zero)
    var hoverActiveIndex by mutableStateOf<Int?>(null)
    var isHoveringAvailable by mutableStateOf(false)
    var activeGridBounds by mutableStateOf(Rect.Zero)
    var availableGridBounds by mutableStateOf(Rect.Zero)

    val isDragging: Boolean
        get() = draggingSpec != null

    fun reset() {
        draggingSpec = null
        dragSection = null
        initialIndex = -1
        dragItemOffset = Offset.Zero
        hoverActiveIndex = null
        isHoveringAvailable = false
    }
}

/**
 * Native ColorOS Quick Settings Tile Editor.
 *
 * Provides complete tile management matching authentic ColorOS 13/14/15 behavior:
 * - Fluid drag-and-drop reordering within Active tiles.
 * - Dragging an Available tile up into Active to insert at any slot.
 * - Dragging an Active tile down into Available to remove it.
 * - Tapping '-' to remove, tapping '+' or the tile body to add.
 * - Smooth spring animations for non-dragged items dynamically opening/closing insertion slots.
 * - Auto-scrolling when hovering near top/bottom screen edges.
 * - Disabling parent vertical scrolling while dragging to prevent touch gesture conflicts.
 * - Preserving hidden and unmounted tile specifications across SystemUI restarts and device reboots.
 */
@Composable
internal fun ColorOSEditor(editViewModel: EditModeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    BackHandler { editViewModel.stopEditing() }
    DisposableEffect(Unit) { onDispose { editViewModel.stopEditing() } }

    val allTiles by editViewModel.tiles.collectAsStateWithLifecycle(emptyList())
    val currentTiles = remember(allTiles) { allTiles.filter { it.isCurrent } }
    val availableTiles = remember(allTiles) { allTiles.filterNot { it.isCurrent } }
    val bySpec = remember(allTiles) { allTiles.associateBy { it.tileSpec } }

    var activeSpecs by remember { mutableStateOf(currentTiles.map { it.tileSpec }) }
    val dragState = remember { ColorOSEditDragState() }

    // Keep activeSpecs in sync when view model emits and no drag is active
    LaunchedEffect(currentTiles) {
        if (!dragState.isDragging) {
            activeSpecs = currentTiles.map { it.tileSpec }
        }
    }

    fun commit(newActive: List<TileSpec>) {
        val distinctActive = newActive.distinct()
        activeSpecs = distinctActive

        // Preserve unmounted or third-party tile specs in Settings not in bySpec
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

        editViewModel.setTiles((distinctActive + hidden).distinct())
    }

    // The pinned slots: the two cards of the first row and the pill next to the ringer. A slot is
    // picked by tapping it, and then the tile to pin there, which a second tap of the slot or the
    // default button gives back.
    val pinnedChoice by rememberColorOSPinned()
    var selectedPin by remember { mutableStateOf<Int?>(null) }
    val pinned =
        colorOSPinnedOf(activeSpecs.map { it.spec }.toSet(), pinnedChoice)

    fun assignPin(spec: TileSpec) {
        val slot = selectedPin ?: return
        val next = pinnedChoice.toMutableList()
        for (i in next.indices) if (next[i] == spec.spec) next[i] = null
        next[slot] = spec.spec
        writeColorOSPinned(context, next)
        if (spec !in activeSpecs) commit(activeSpecs + spec)
        selectedPin = null
    }

    fun resetPin() {
        val slot = selectedPin ?: return
        writeColorOSPinned(context, pinnedChoice.toMutableList().also { it[slot] = null })
        selectedPin = null
    }

    val scrollState = rememberScrollState()

    // Auto-scroll when dragging near viewport boundaries
    LaunchedEffect(dragState.isDragging, dragState.dragItemOffset) {
        if (dragState.isDragging) {
            val yInViewport = dragState.dragItemOffset.y - scrollState.value
            val topThreshold = with(density) { 90.dp.toPx() }
            val bottomThreshold = with(density) { 520.dp.toPx() }
            if (yInViewport < topThreshold && scrollState.value > 0) {
                val speed = ((topThreshold - yInViewport) / topThreshold).coerceIn(0.1f, 1f)
                scrollState.scrollBy(-18f * speed)
            } else if (yInViewport > bottomThreshold && scrollState.value < scrollState.maxValue) {
                val speed = ((yInViewport - bottomThreshold) / 90f).coerceIn(0.1f, 1f)
                scrollState.scrollBy(18f * speed)
            }
        }
    }

    val labelColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = 4 // ColorOS standard phone grid is 4 columns
        // The grid lives inside the padded scroll column, so size cells from the inner width,
        // otherwise the last column overflows and gets clipped at the screen edge.
        val cellW = (maxWidth - EditorHorizontalPadding * 2) / columns
        val cellH =
            CommonTileDefaults.ColorOSTileHeight +
                CommonTileDefaults.ClassicLabelHeight +
                ColorOSIconRowGap
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }

        fun slotOf(index: Int) = Offset((index % columns) * cellWPx, (index / columns) * cellHPx)

        // Root vertical scroll container. Disabled while dragging to avoid gesture interception.
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(scrollState, enabled = !dragState.isDragging)
                .padding(horizontal = EditorHorizontalPadding)
        ) {
            // Header Bar: Reset | Title | Done
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorTextButton(stringResource(R.string.reset), labelColor.copy(alpha = 0.85f)) {
                    val defaults =
                        context.resources
                            .getString(R.string.quick_settings_tiles_default)
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map(TileSpec::create)
                    commit(defaults)
                }
                Text(
                    text = stringResource(R.string.qs_coloros_edit_title),
                    color = labelColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                EditorTextButton(stringResource(R.string.quick_settings_done), EditorAccent) {
                    editViewModel.stopEditing()
                }
            }

            // Subtitle / Hint
            Text(
                text = stringResource(R.string.qs_coloros_edit_hint),
                color = labelColor.copy(alpha = 0.55f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
            )

            // Pinned slots
            Text(
                text = stringResource(R.string.qs_coloros_edit_pinned),
                color = labelColor.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(COLOROS_PINNED_SLOTS) { slot ->
                    val spec = if (slot < COLOROS_PILL_SLOT) pinned.cards[slot] else pinned.pill
                    val label =
                        spec?.let { bySpec[TileSpec.create(it)]?.label?.text?.toString() }
                            ?: if (slot == COLOROS_PILL_SLOT) {
                                stringResource(R.string.qs_coloros_my_devices)
                            } else {
                                ""
                            }
                    PinnedSlotChip(
                        label = label,
                        selected = selectedPin == slot,
                        modifier = Modifier.weight(1f),
                    ) {
                        selectedPin = if (selectedPin == slot) null else slot
                    }
                }
            }
            val pickedSlot = selectedPin
            if (pickedSlot != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.qs_coloros_edit_pinned_pick),
                        color = EditorAccent,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinnedChoice[pickedSlot] != null) {
                        EditorTextButton(
                            stringResource(R.string.qs_coloros_edit_pinned_default),
                            labelColor.copy(alpha = 0.85f),
                        ) {
                            resetPin()
                        }
                    }
                }
            }

            // Section 1 Header: Added tiles
            Text(
                text = stringResource(R.string.qs_coloros_edit_added),
                color = labelColor.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )

            // Section 1: Active Tiles Grid
            val activeRows = ((activeSpecs.size + (if (dragState.dragSection == DragSection.AVAILABLE && dragState.hoverActiveIndex != null) 1 else 0)) + columns - 1) / columns
            val activeGridHeight = cellH * activeRows.coerceAtLeast(1)

            Box(
                Modifier.fillMaxWidth()
                    .height(activeGridHeight)
                    .onGloballyPositioned { coordinates ->
                        dragState.activeGridBounds = coordinates.boundsInParent()
                    }
            ) {
                if (activeSpecs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.qs_coloros_edit_empty_added),
                        color = labelColor.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    )
                }

                activeSpecs.forEachIndexed { index, spec ->
                    val tile = bySpec[spec] ?: return@forEachIndexed
                    val isBeingDragged = dragState.draggingSpec == spec

                    // Calculate virtual slot accounting for ongoing drag displacements
                    val virtualSlotIndex =
                        when (dragState.dragSection) {
                            DragSection.ACTIVE -> {
                                val from = dragState.initialIndex
                                val hover = dragState.hoverActiveIndex
                                if (isBeingDragged) {
                                    from
                                } else if (dragState.isHoveringAvailable) {
                                    if (index > from) index - 1 else index
                                } else if (hover != null && hover != from) {
                                    if (from < hover) {
                                        if (index in (from + 1)..hover) index - 1 else index
                                    } else {
                                        if (index in hover until from) index + 1 else index
                                    }
                                } else {
                                    index
                                }
                            }
                            DragSection.AVAILABLE -> {
                                val hover = dragState.hoverActiveIndex
                                if (hover != null && index >= hover) index + 1 else index
                            }
                            null -> index
                        }

                    val slot = slotOf(virtualSlotIndex)
                    val animatedOffset by
                        animateIntOffsetAsState(
                            targetValue = IntOffset(slot.x.roundToInt(), slot.y.roundToInt()),
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            label = "coloros active slot $spec",
                        )

                    key(spec) {
                        Box(
                            Modifier.offset { animatedOffset }
                                .width(cellW)
                                .height(cellH)
                                .graphicsLayer {
                                    alpha = if (isBeingDragged) 0.22f else 1f
                                }
                                .pointerInput(spec) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            val startRoot =
                                                dragState.activeGridBounds.topLeft + slotOf(index)
                                            dragState.draggingSpec = spec
                                            dragState.dragSection = DragSection.ACTIVE
                                            dragState.initialIndex = index
                                            dragState.dragItemOffset = startRoot
                                            dragState.hoverActiveIndex = index
                                            dragState.isHoveringAvailable = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragState.dragItemOffset += dragAmount

                                            val centerRoot =
                                                dragState.dragItemOffset +
                                                    Offset(cellWPx / 2f, cellHPx / 2f)

                                            // Check if tile is dragged down past the active grid
                                            if (centerRoot.y > dragState.activeGridBounds.bottom + cellHPx * 0.25f) {
                                                if (!dragState.isHoveringAvailable) {
                                                    dragState.isHoveringAvailable = true
                                                    dragState.hoverActiveIndex = null
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                }
                                            } else {
                                                dragState.isHoveringAvailable = false
                                                val centerActive =
                                                    centerRoot - dragState.activeGridBounds.topLeft
                                                val col =
                                                    (centerActive.x / cellWPx)
                                                        .toInt()
                                                        .coerceIn(0, columns - 1)
                                                val row =
                                                    (centerActive.y / cellHPx)
                                                        .toInt()
                                                        .coerceAtLeast(0)
                                                val target =
                                                    (row * columns + col)
                                                        .coerceIn(0, activeSpecs.lastIndex)

                                                if (target != dragState.hoverActiveIndex) {
                                                    dragState.hoverActiveIndex = target
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (dragState.isHoveringAvailable) {
                                                // Remove tile from active
                                                val newActive = activeSpecs.toMutableList().apply {
                                                    removeAt(dragState.initialIndex)
                                                }
                                                commit(newActive)
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove
                                                )
                                            } else {
                                                val target = dragState.hoverActiveIndex
                                                if (target != null && target != dragState.initialIndex) {
                                                    val newActive = activeSpecs.toMutableList().apply {
                                                        add(target, removeAt(dragState.initialIndex))
                                                    }
                                                    commit(newActive)
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                }
                                            }
                                            dragState.reset()
                                        },
                                        onDragCancel = { dragState.reset() },
                                    )
                                }
                                .clickable(enabled = selectedPin != null) { assignPin(spec) }
                        ) {
                            EditorTile(
                                tile = tile,
                                badge = {
                                    if (tile.isRemovable) {
                                        EditorBadgeIcon(
                                            iconRes = R.drawable.ic_remove,
                                            backgroundColor = EditorBadgeFill,
                                            contentDescription = "Remove ${tile.label.text}",
                                        ) {
                                            val newActive = activeSpecs.toMutableList().apply {
                                                remove(spec)
                                            }
                                            commit(newActive)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Section 2 Header: More tiles / Removal Drop Zone
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 28.dp, bottom = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        if (dragState.isHoveringAvailable) {
                            stringResource(R.string.qs_coloros_edit_remove_hint)
                        } else {
                            stringResource(R.string.qs_coloros_edit_more)
                        },
                    color =
                        if (dragState.isHoveringAvailable) {
                            EditorRemoveTint
                        } else {
                            labelColor.copy(alpha = 0.7f)
                        },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Section 2: Available Tiles Grid
            val availableSpecs = availableTiles.map { it.tileSpec }
            val availableRows = (availableSpecs.size + columns - 1) / columns
            val availableGridHeight = cellH * availableRows.coerceAtLeast(1)

            Box(
                Modifier.fillMaxWidth()
                    .height(availableGridHeight)
                    .border(
                        width = if (dragState.isHoveringAvailable) 2.dp else 0.dp,
                        color = if (dragState.isHoveringAvailable) EditorRemoveTint.copy(alpha = 0.6f) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .onGloballyPositioned { coordinates ->
                        dragState.availableGridBounds = coordinates.boundsInParent()
                    }
            ) {
                if (availableSpecs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.qs_coloros_edit_empty_more),
                        color = labelColor.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    )
                }

                availableSpecs.forEachIndexed { index, spec ->
                    val tile = bySpec[spec] ?: return@forEachIndexed
                    val isBeingDragged = dragState.draggingSpec == spec
                    val slot = slotOf(index)

                    key(spec) {
                        Box(
                            Modifier.offset {
                                    IntOffset(slot.x.roundToInt(), slot.y.roundToInt())
                                }
                                .width(cellW)
                                .height(cellH)
                                .graphicsLayer {
                                    alpha = if (isBeingDragged) 0.22f else 1f
                                }
                                .pointerInput(spec) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            val startRoot =
                                                dragState.availableGridBounds.topLeft + slotOf(index)
                                            dragState.draggingSpec = spec
                                            dragState.dragSection = DragSection.AVAILABLE
                                            dragState.initialIndex = index
                                            dragState.dragItemOffset = startRoot
                                            dragState.hoverActiveIndex = null
                                            dragState.isHoveringAvailable = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragState.dragItemOffset += dragAmount

                                            val centerRoot =
                                                dragState.dragItemOffset +
                                                    Offset(cellWPx / 2f, cellHPx / 2f)

                                            // Check if available tile is dragged UP into the active grid
                                            if (centerRoot.y <= dragState.activeGridBounds.bottom + cellHPx * 0.25f &&
                                                centerRoot.y >= dragState.activeGridBounds.top - cellHPx * 0.25f
                                            ) {
                                                val centerActive =
                                                    centerRoot - dragState.activeGridBounds.topLeft
                                                val col =
                                                    (centerActive.x / cellWPx)
                                                        .toInt()
                                                        .coerceIn(0, columns - 1)
                                                val row =
                                                    (centerActive.y / cellHPx)
                                                        .toInt()
                                                        .coerceAtLeast(0)
                                                val target =
                                                    (row * columns + col)
                                                        .coerceIn(0, activeSpecs.size)

                                                if (target != dragState.hoverActiveIndex) {
                                                    dragState.hoverActiveIndex = target
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                }
                                            } else {
                                                if (dragState.hoverActiveIndex != null) {
                                                    dragState.hoverActiveIndex = null
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val target = dragState.hoverActiveIndex
                                            if (target != null) {
                                                // Insert tile into active at target slot
                                                val newActive = activeSpecs.toMutableList().apply {
                                                    add(target.coerceIn(0, size), spec)
                                                }
                                                commit(newActive)
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove
                                                )
                                            }
                                            dragState.reset()
                                        },
                                        onDragCancel = { dragState.reset() },
                                    )
                                }
                                .clickable {
                                    // Tapping an available tile adds it to the end of active tiles,
                                    // or pins it when a pinned slot is picked
                                    if (selectedPin != null) {
                                        assignPin(spec)
                                    } else if (!activeSpecs.contains(spec)) {
                                        commit(activeSpecs + spec)
                                    }
                                }
                        ) {
                            EditorTile(
                                tile = tile,
                                badge = {
                                    EditorBadgeIcon(
                                        iconRes = R.drawable.ic_add,
                                        backgroundColor = EditorAccent,
                                        contentDescription = "Add ${tile.label.text}",
                                    ) {
                                        if (!activeSpecs.contains(spec)) {
                                            commit(activeSpecs + spec)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(36.dp))
        }

        // Floating Drag Overlay: Renders above all composables when an item is being dragged
        if (dragState.isDragging && dragState.draggingSpec != null) {
            val draggedTile = bySpec[dragState.draggingSpec]
            if (draggedTile != null) {
                Box(
                    Modifier.offset {
                            IntOffset(
                                dragState.dragItemOffset.x.roundToInt(),
                                (dragState.dragItemOffset.y - scrollState.value).roundToInt(),
                            )
                        }
                        .zIndex(200f)
                        .width(cellW)
                        .height(cellH)
                        .graphicsLayer {
                            scaleX = 1.12f
                            scaleY = 1.12f
                            alpha = 0.95f
                        }
                ) {
                    EditorTile(
                        tile = draggedTile,
                        badge = null,
                        isLifted = true,
                    )
                }
            }
        }
    }
}

/**
 * Authentic ColorOS circular Quick Settings tile inside the editor.
 */
@Composable
private fun EditorTile(
    tile: EditTileViewModel,
    badge: (@Composable () -> Unit)?,
    isLifted: Boolean = false,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // Frosted glass appearance matching ColorOS QS tiles
    val fill =
        if (isLifted) {
            if (dark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.85f)
        } else {
            if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f)
        }

    val elevation = if (isLifted) 10.dp else 0.dp

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(CommonTileDefaults.ColorOSTileHeight)
                    .shadow(elevation, CircleShape)
                    .clip(CircleShape)
                    .background(fill, CircleShape)
                    .border(1.dp, colorOSTileRim(), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = { tile.icon },
                    color = onSurface,
                    size = { CommonTileDefaults.LargeTileIconSize },
                )
            }
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) {
                    badge()
                }
            }
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

/** A pinned slot of the panel in the editor, which is picked by a tap. */
@Composable
private fun PinnedSlotChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(
                if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f),
                shape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) EditorAccent else colorOSTileRim(),
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * ColorOS tile badge indicator (- to remove, + to add).
 */
@Composable
private fun EditorBadgeIcon(
    iconRes: Int,
    backgroundColor: Color,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(EditorBadge)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Text button for Reset / Done actions in ColorOS styling.
 */
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
