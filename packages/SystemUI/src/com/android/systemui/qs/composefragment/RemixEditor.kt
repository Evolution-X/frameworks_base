/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsTileLabel
import com.android.systemui.qs.panels.shared.model.TileCells
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.packTiles
import com.android.systemui.qs.panels.shared.model.paginateTiles
import com.android.systemui.qs.panels.shared.model.remixColumns
import com.android.systemui.qs.panels.shared.model.remixRowsPerPage
import com.android.systemui.qs.panels.shared.model.resized
import com.android.systemui.qs.panels.ui.compose.QsPlusTile
import com.android.systemui.qs.panels.ui.compose.QsPreviewHost
import com.android.systemui.qs.panels.ui.compose.QsPreviewTile
import com.android.systemui.qs.panels.ui.compose.QsPreviewTiles
import com.android.systemui.qs.panels.ui.compose.animatePlacement
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomizerTab
import com.android.systemui.qs.panels.ui.compose.infinitegrid.QsCustomizerDock
import com.android.systemui.qs.panels.ui.compose.infinitegrid.QsTileIcons
import com.android.systemui.qs.panels.ui.compose.infinitegrid.RemixTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TilePlacementGrid
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileLook
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileShapeStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.withBacking
import com.android.systemui.qs.panels.ui.compose.infinitegrid.withFills
import com.android.systemui.qs.panels.ui.compose.qsPreviewDrag
import com.android.systemui.qs.panels.ui.compose.qsTileColumnSpacing
import com.android.systemui.qs.panels.ui.compose.qsTileRowSpacing
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The QS editor of the Remix panel style: the tiles of the panel, drawn in the cells and the look
 * of the selected Remix look, and the customizer dock under them. It depends on nothing outside
 * SystemUI, and the stock editor is left as it is.
 *
 * `RemixTileGrid` is a ContentScope extension and edit mode is not one, so the tiles are drawn from
 * their [EditTileViewModel] and not from live tiles. A tap picks a tile and the dock shows its
 * options, a hold lifts it to move it (dropping it on the bar at the top removes it) and the plus
 * tile opens the Add tab. The corner of the picked tile resizes it.
 */
@Composable
internal fun RemixEditor(editViewModel: EditModeViewModel, modifier: Modifier = Modifier) {
    var dockOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(CustomizerTab.Theme) }
    var tapped by remember { mutableStateOf<TileSpec?>(null) }

    // The handler registered last runs first: Back closes the picked tile, then the open dock,
    // and then edit mode.
    BackHandler { editViewModel.stopEditing() }
    BackHandler(enabled = dockOpen) { dockOpen = false }
    BackHandler(enabled = tapped != null) { tapped = null }
    DisposableEffect(Unit) { onDispose { editViewModel.stopEditing() } }

    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
    val allTiles by editViewModel.tiles.collectAsStateWithLifecycle(emptyList())
    val largeTiles by editViewModel.iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()
    val settingsRows by editViewModel.paginatedRowsInteractor.rows.collectAsStateWithLifecycle()
    val columnsViewModel =
        rememberViewModel(traceName = "RemixEditor.columns") {
            editViewModel.columnsViewModelFactory.createWithoutMediaTracking()
        }
    val columns = tweaks.remixColumns(theme, columnsViewModel.columns)
    val largeSpan = columnsViewModel.largeSpan
    val pageRows = tweaks.remixRowsPerPage(settingsRows)

    // Every tile on the panel, in order, and those the panel shows in its grid. A player above or
    // below the tiles is the card, so its tile is left out, as in the pager.
    val current = remember(allTiles) { allTiles.filter { it.isCurrent } }
    val tiles =
        remember(current, tweaks.mediaPlace, theme) {
            current.filter {
                tweaks.mediaPlaceIn(theme) == QsMediaPlace.GRID ||
                    it.tileSpec.spec != QsMediaTileSize.TILE_SPEC
            }
        }

    val preview = remember { QsPreviewTiles() }
    val state = remember { RemixPreviewState(preview) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val density = LocalDensity.current
    val edge = with(density) { EdgeScrollZone.toPx() }
    val step = with(density) { EdgeScrollStep.toPx() }
    val currentSpecs by rememberUpdatedState(current.map { it.tileSpec })
    val gridColumns by rememberUpdatedState(columns)
    val currentTheme by rememberUpdatedState(theme)
    val visibleSpecs by rememberUpdatedState(tiles.map { it.tileSpec })
    val removable by
        rememberUpdatedState(tiles.filter { it.isRemovable }.map { it.tileSpec }.toSet())

    DisposableEffect(preview) {
        var settleJob: Job? = null
        fun settle() {
            settleJob?.cancel()
            preview.settling = true
            settleJob =
                scope.launch {
                    delay(SettleMillis)
                    if (preview.dragging == null) preview.settling = false
                }
        }
        preview.host =
            object : QsPreviewHost {
                override fun onTap(spec: TileSpec) {
                    tapped = if (tapped == spec) null else spec
                }

                override fun onAdd() {
                    tapped = null
                    tab = CustomizerTab.Add
                    dockOpen = true
                }

                override fun onDragStart(spec: TileSpec) {
                    preview.order = visibleSpecs
                    preview.dragging = spec
                    preview.removeArmed = false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    settle()
                }

                override fun onDragTo(pointer: Offset) {
                    val moving = preview.dragging ?: return
                    state.pointer = pointer
                    preview.dragCorner = pointer - preview.grab
                    preview.removeArmed = moving in removable && pointer.y < state.dropBottom
                    if (!preview.removeArmed) state.retarget()
                }

                override fun onDragEnd(cancelled: Boolean) {
                    val moving = preview.dragging ?: return
                    val remove = preview.removeArmed && !cancelled
                    val order = preview.order
                    preview.dragging = null
                    preview.dragCorner = Offset.Unspecified
                    preview.removeArmed = false
                    state.pointer = Offset.Unspecified
                    settle()
                    when {
                        remove -> editViewModel.removeTile(moving)
                        cancelled -> preview.order = visibleSpecs
                        order != visibleSpecs ->
                            editViewModel.setTiles(mergeOrder(currentSpecs, visibleSpecs, order))
                    }
                    if (remove && tapped == moving) tapped = null
                }

                override fun onResize(spec: TileSpec, columns: Int, rows: Int) {
                    editViewModel.styleRepository.editTweaks {
                        it.resized(spec.spec, currentTheme, columns, rows, gridColumns)
                    }
                    settle()
                }
            }
        onDispose { settleJob?.cancel() }
    }

    // The order the panel is drawn in: the dragged one while a tile is lifted, then the settings'
    // own once they have caught up with a drop.
    LaunchedEffect(visibleSpecs) { if (preview.dragging == null) preview.order = visibleSpecs }
    val order = preview.order
    val shown =
        remember(tiles, order) {
            val bySpec = tiles.associateBy { it.tileSpec }
            order.mapNotNull { bySpec[it] }.takeIf { it.size == tiles.size } ?: tiles
        }
    val selected = shown.find { it.tileSpec == tapped }
    SideEffect { preview.selected = selected?.tileSpec }

    // The plus tile is one more cell after the last tile, so it moves on to a new page with them.
    val pages =
        remember(shown, tweaks, theme, largeTiles, largeSpan, columns, pageRows) {
            val cells =
                shown.map {
                    tweaks.cellsOf(
                        spec = it.tileSpec.spec,
                        theme = theme,
                        isLarge = largeTiles.contains(it.tileSpec),
                        largeSpan = largeSpan,
                        columns = columns,
                    )
                } + TileCells(1, 1)
            paginateTiles(cells, columns, pageRows).map { page ->
                page to packTiles(page.map { cells[it] }, columns)
            }
        }

    var area by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Keeps the finger's page moving while a tile waits at the top or bottom edge of the pages.
    LaunchedEffect(preview.dragging) {
        if (preview.dragging == null) return@LaunchedEffect
        while (isActive) {
            val pointer = state.pointer
            val bounds = preview.pager
            val top = maxOf(bounds.top, state.dropBottom)
            val by =
                when {
                    !pointer.isSpecified || preview.removeArmed -> 0f
                    pointer.y < top + edge -> -step
                    pointer.y > bounds.bottom - edge -> step
                    else -> 0f
                }
            if (by != 0f) {
                scrollState.scrollBy(by)
                state.retarget()
            }
            delay(ScrollFrameMillis)
        }
    }

    // Brings a tile just added into view once it has been laid out.
    LaunchedEffect(preview.reveal, tiles.size) {
        val spec = preview.reveal ?: return@LaunchedEffect
        if (tiles.none { it.tileSpec == spec }) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val bounds = preview.bounds[spec]
        val within = preview.pager
        if (bounds != null) {
            when {
                bounds.bottom > within.bottom ->
                    scrollState.animateScrollBy(bounds.bottom - within.bottom + RevealMargin)
                bounds.top < within.top ->
                    scrollState.animateScrollBy(bounds.top - within.top - RevealMargin)
            }
        }
        preview.reveal = null
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier =
                    Modifier.fillMaxSize()
                        .onGloballyPositioned {
                            area = it
                            preview.pager = it.boundsInRoot()
                        }
                        .qsPreviewDrag(preview) { area }
                        .verticalScroll(scrollState),
            ) {
                pages.forEach { (page, placements) ->
                    PreviewPage(page, placements, shown, columns, preview)
                }
            }
            if (preview.dragging in removable) {
                RemoveStrip(
                    armed = preview.removeArmed,
                    onBottom = { state.dropBottom = it },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        QsCustomizerDock(
            repository = editViewModel.styleRepository,
            columns = columns,
            settingsRows = settingsRows,
            allTiles = allTiles,
            onAddTile = {
                editViewModel.addTile(it)
                preview.reveal = it
            },
            tab = tab,
            onTabChange = { tab = it },
            tile = selected,
            onRemoveTile = {
                tapped = null
                editViewModel.removeTile(it.tileSpec)
            },
            onCloseTile = { tapped = null },
            currentTiles = currentSpecs,
            onSetTiles = editViewModel::setTiles,
            open = dockOpen,
            onOpenChange = { dockOpen = it },
        )
    }
}

/** What the drag of the preview knows besides the tiles: where the finger is, and the drop bar. */
@Stable
private class RemixPreviewState(private val preview: QsPreviewTiles) {
    /** The finger holding a tile, in root coordinates. */
    var pointer by mutableStateOf(Offset.Unspecified)

    /** The bottom of the bar that removes a tile, in root coordinates; 0 while it is hidden. */
    var dropBottom = 0f

    /** Moves the lifted tile in [QsPreviewTiles.order] to where the finger is. */
    fun retarget() {
        val moving = preview.dragging ?: return
        if (!pointer.isSpecified) return
        val (target, after) = preview.targetFor(moving, pointer) ?: return
        val order = preview.order.toMutableList()
        if (!order.remove(moving)) return
        val at = order.indexOf(target) + (if (after) 1 else 0)
        order.add(at.coerceIn(0, order.size), moving)
        if (order != preview.order) preview.order = order
    }
}

/**
 * The tile under [pointer] and whether [moving] goes after it. In free space that is the last tile
 * in order that sits before the pointer, or the first tile on screen to go before.
 */
private fun QsPreviewTiles.targetFor(moving: TileSpec, pointer: Offset): Pair<TileSpec, Boolean>? {
    val onScreen =
        bounds.entries.filter { (spec, rect) -> spec != moving && pager.contains(rect.center) }
    onScreen
        .firstOrNull { it.value.contains(pointer) }
        ?.let {
            return it.key to (pointer.x > it.value.center.x)
        }
    onScreen
        .filter { (_, rect) ->
            rect.bottom <= pointer.y || (rect.top <= pointer.y && rect.right <= pointer.x)
        }
        .maxByOrNull { order.indexOf(it.key) }
        ?.let {
            return it.key to true
        }
    return onScreen.minByOrNull { order.indexOf(it.key) }?.let { it.key to false }
}

/**
 * The tiles of the panel with those the grid shows in their new [order]. A tile the grid leaves out
 * (the player above or below the tiles) keeps its place.
 */
private fun mergeOrder(
    all: List<TileSpec>,
    visible: List<TileSpec>,
    order: List<TileSpec>,
): List<TileSpec> {
    val shown = visible.toSet()
    val next = order.iterator()
    return all.map { if (it in shown && next.hasNext()) next.next() else it }
}

@Composable
private fun PreviewPage(
    page: List<Int>,
    placements: List<TilePlacement>,
    tiles: List<EditTileViewModel>,
    columns: Int,
    preview: QsPreviewTiles,
) {
    val tweaks = LocalQsTweaks.current
    val look = tileLook(tileShapeStyle())
    val classicStyle = rememberQSPanelStyle()
    val columnSpacing = tweaks.spacing?.dp ?: qsTileColumnSpacing(columns)
    val rowSpacing = tweaks.spacing?.dp ?: qsTileRowSpacing(columns, classicStyle)
    // One grid cell with its gap, in pixels, for the resize handle to snap to.
    val density = LocalDensity.current
    var gridWidth by remember { mutableStateOf(0f) }
    val gap = with(density) { columnSpacing.toPx() }
    val cellWidth = if (gridWidth > 0f) (gridWidth - gap * (columns - 1)) / columns + gap else 0f
    val rowHeight = with(density) { (look.height + rowSpacing).toPx() }
    fun tileAt(index: Int) = tiles.getOrNull(page[index])
    // The page with the lifted tile is drawn over the others, so it is not hidden under them.
    val moving = preview.dragging
    val lifted = moving != null && page.any { tiles.getOrNull(it)?.tileSpec == moving }
    TilePlacementGrid(
        placements = placements,
        columns = columns,
        columnSpacing = columnSpacing,
        rowSpacing = rowSpacing,
        keys = { tileAt(it)?.tileSpec ?: PlusKey },
        modifier =
            Modifier.fillMaxWidth()
                .onSizeChanged { gridWidth = it.width.toFloat() }
                .zIndex(if (lifted) 1f else 0f),
        itemModifier = { index ->
            Modifier.zIndex(if (tileAt(index)?.tileSpec == preview.dragging) 1f else 0f)
                .thenIf(preview.settling) { Modifier.animatePlacement() }
        },
    ) { index ->
        val place = placements[index]
        val height = look.height * place.rows + rowSpacing * (place.rows - 1)
        val tile = tileAt(index)
        if (tile == null) {
            QsPlusTile(
                preview = preview,
                radius = look.inactiveRadius,
                modifier = Modifier.fillMaxWidth().height(height),
            )
        } else {
            QsPreviewTile(
                preview = preview,
                spec = tile.tileSpec,
                columns = place.columns,
                rows = place.rows,
                maxColumns = columns,
                cellWidth = cellWidth,
                rowHeight = rowHeight,
                radius = look.inactiveRadius,
                modifier = Modifier.fillMaxWidth().height(height),
            ) {
                PreviewTile(tile = tile, wide = place.columns > 1, height = height)
            }
        }
    }
}

/** A tile as an off tile: its icon, and its label when it is wider than a column. */
@Composable
private fun PreviewTile(tile: EditTileViewModel, wide: Boolean, height: Dp) {
    val tweaks = LocalQsTweaks.current
    val look = tileLook(tileShapeStyle())
    val colors =
        RemixTileDefaults.inactiveTileColors()
            .withFills(tweaks, STATE_INACTIVE, null, false, false)
            .withBacking(tweaks.iconBacking, false, null)
    val own = tweaks.tiles[tile.tileSpec.spec]
    val showLabel = wide && tweaks.label != QsTileLabel.NONE && own?.hideLabel != true
    val ownIcon = own?.icon?.let { QsTileIcons[it] }?.let { Icon.Resource(it, null) }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(look.inactiveRadius))
                .background(colors.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                if (showLabel) Modifier.padding(horizontal = 14.dp)
                else Modifier.align(Alignment.Center),
        ) {
            SmallTileContent(
                iconProvider = { ownIcon ?: tile.icon },
                color = colors.icon,
                size = { look.smallIconSize },
            )
            if (showLabel) {
                Text(
                    text = tile.label.text,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The bar a lifted tile is dropped on to remove it. [onBottom] gets its bottom edge in root. */
@Composable
private fun RemoveStrip(armed: Boolean, onBottom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    DisposableEffect(Unit) { onDispose { onBottom(0f) } }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(8.dp)
                .fillMaxWidth()
                .height(48.dp)
                .onGloballyPositioned { onBottom(it.boundsInRoot().bottom) }
                .clip(RoundedCornerShape(24.dp))
                .background(if (armed) scheme.errorContainer else scheme.surfaceContainerHigh),
    ) {
        Text(
            text = stringResource(R.string.qs_custom_drop_remove),
            style = MaterialTheme.typography.labelLarge,
            color = if (armed) scheme.onErrorContainer else scheme.onSurfaceVariant,
        )
    }
}

private const val PlusKey = "remix_plus_tile"
private val EdgeScrollZone = 72.dp
private val EdgeScrollStep = 14.dp
private const val ScrollFrameMillis = 16L
private const val RevealMargin = 24f

/** How long the tiles keep gliding after a drop. */
private const val SettleMillis = 500L
