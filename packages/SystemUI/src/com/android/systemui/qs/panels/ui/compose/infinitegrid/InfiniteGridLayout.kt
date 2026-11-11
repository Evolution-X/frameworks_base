/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.bluetooth.connecteddevices.ConnectedBluetoothDevicesInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.grid.ui.compose.PlacedGridItem
import com.android.systemui.grid.ui.compose.TwoDimensionalSpannedGrid
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.grid.ui.compose.calculate2DPlacements
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShadeFlag
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Gap between tiles and rows in the ColorOS Quick Settings style (ColorOS 17). */
val ColorOSGap = 17.dp

/**
 * Gap between the rows of round icon tiles in the ColorOS style. The label lives inside the tile
 * cell, so ColorOS rows are packed much closer than the 17dp used between the wide cards.
 */
val ColorOSIconRowGap = 4.dp

/**
 * Gap between the rows of round icon tiles in the ColorOS style: the tight [ColorOSIconRowGap] with
 * the labels in the cells, the [ColorOSGap] of the wide cards without them.
 */
fun colorOSIconRowGap(labelsHidden: Boolean): Dp =
    if (labelsHidden) ColorOSGap else ColorOSIconRowGap

/**
 * Round tiles per page in the ColorOS Quick Settings style. Fewer rows than stock so the whole
 * panel fits without vertical scrolling; extra tiles go to the next page (swipe sideways).
 */
const val ColorOSGridRows = 3
const val ColorOSGridColumns = 4

/** Fewest rows of round tiles per page the ColorOS panel falls back to on a short screen. */
const val ColorOSMinGridRows = 2

/**
 * Rows of round tiles per page in the ColorOS panel. The panel lowers it from [ColorOSGridRows]
 * when the screen is too short for all of them.
 */
internal val LocalColorOSGridRows = compositionLocalOf { ColorOSGridRows }

/** Height of the pager footer (build number or data usage, page dots, edit button). */
val ColorOSGridFooterHeight = 48.dp

/**
 * Room above the first row of round tiles inside the pager, so what a tile draws outside its
 * bounds is not clipped by the pager.
 */
val ColorOSGridHeadroom = 4.dp

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    private val connectedBluetoothDevicesInteractor: ConnectedBluetoothDevicesInteractor,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val columns = viewModel.columnsWithMediaViewModel.columns
        val largeTilesSpan = viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        val colorOSStyle = LocalColorOSQsStyle.current
        val nothingStyle = LocalNothingQsStyle.current

        if (nothingStyle) {
            NothingTileGrid(
                pageTiles = tiles,
                largeTiles = largeTiles,
                viewModel = viewModel,
                textFeedbackViewModel = textFeedbackViewModel,
                modifier = modifier,
                listening = listening,
                enableRevealEffect = enableRevealEffect,
            )
            return
        }

        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan, colorOSStyle) {
                // ColorOS style: the fixed wide tiles (Wi-Fi, mobile data, Bluetooth) are drawn
                // separately by the ColorOS layout, so every tile in this grid is a round icon
                // tile.
                tiles.map {
                    val wide = !colorOSStyle && largeTiles.contains(it.spec)
                    SizedTileImpl(it, if (wide) largeTilesSpan.coerceAtMost(columns) else 1)
                }
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val classicStyle = rememberQSPanelStyle()
        val labelsHidden = rememberQSTileLabelHide()

        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }
        VerticalSpannedGrid(
            columns = columns,
            columnSpacing =
                if (colorOSStyle) ColorOSGap
                else dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing =
                if (colorOSStyle) colorOSIconRowGap(labelsHidden)
                else dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = spans,
            keys = { sizedTiles[it].tile.spec },
            modifier = modifier,
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = sizedTiles[spanIndex]

            Element(it.tile.spec.toElementKey(), Modifier) {
                Tile(
                    tile = it.tile,
                    iconOnly =
                        colorOSStyle ||
                            classicStyle ||
                            iconTilesViewModel.isIconTile(it.tile.spec),
                    squishiness = { squishiness },
                    tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                    coroutineScope = scope,
                    bounceableInfo =
                        bounceables.bounceableInfo(
                            it,
                            index = spanIndex,
                            column = column,
                            columns = columns,
                            isFirstInRow = isFirstInColumn,
                            isLastInRow = isLastInColumn,
                        ),
                    detailsViewModel = detailsViewModel,
                    isVisible = listening,
                    requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                    enableRevealEffect = enableRevealEffect,
                )
            }
        }

        TileListener(tiles, listening)
    }

    /**
     * Nothing style: places the tiles of a page on a 4 column grid of square cells. A tile is a
     * circle (1x1), a pill (2x1) or a quad (2x2), at the saved row and column when it has one.
     */
    @Composable
    private fun ContentScope.NothingTileGrid(
        pageTiles: List<TileViewModel>,
        largeTiles: Set<TileSpec>,
        viewModel: com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel,
        textFeedbackViewModel: TextFeedbackContentViewModel,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val nothingLayout = rememberNothingLayout()
        val columns = rememberNothingQSColumns()
        val (quadTiles, savedTileLayout) = nothingLayout
        val tileLayout = savedTileLayout.forNothingColumns(columns)
        val pagePlacements = LocalNothingPagePlacements.current
        // A pager already ordered the tiles of its pages, a lone grid orders its own.
        val tiles =
            remember(pageTiles, savedTileLayout, columns, pagePlacements) {
                if (pagePlacements == null) {
                    pageTiles.inNothingLayoutOrder(savedTileLayout, columns) { it.spec }
                } else {
                    pageTiles
                }
            }
        val noBounce = remember { BounceableInfo(BounceableTileViewModel(), null, null, false) }
        // Sliders are drawn by the host. Without a pager the first page is the only one.
        val sliderContent = LocalNothingSliderContent.current
        val sliders =
            if (sliderContent == null) {
                emptyList()
            } else {
                LocalNothingPageSliders.current
                    ?: nothingLayout.sliders.forNothingColumns(columns).filter { it.row < NOTHING_ROWS }
            }
        val sliderRowHeight = dimensionResource(R.dimen.qs_nothing_brightness_slider_height)
        val sliderRowHeights =
            remember(sliders, sliderRowHeight) {
                sliders.associate { it.row to sliderRowHeight }
            }

        val nothingSpans =
            remember(tiles, largeTiles, quadTiles) {
                tiles.map {
                    when {
                        quadTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 2)
                        largeTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 1)
                        else -> GridSpan(colSpan = 1, rowSpan = 1)
                    }
                }
            }
        val preferredPlacements =
            remember(tiles, tileLayout, pagePlacements) {
                tiles
                    .mapIndexedNotNull { index, tile ->
                        val placed =
                            pagePlacements?.get(tile.spec)
                                ?: tileLayout[tile.spec]?.let { (r, c) -> r % NOTHING_ROWS to c }
                        placed?.let { index to it }
                    }
                    .toMap()
            }

        // Slider cells are held back for the tiles, then added as items after the last tile
        val sliderPlacements =
            remember(sliders, tiles.size) {
                sliders.mapIndexed { i, slider ->
                    PlacedGridItem(tiles.size + i, slider.row, slider.col, slider.span, 1)
                }
            }
        val placements =
            remember(nothingSpans, preferredPlacements, sliderPlacements, columns) {
                calculate2DPlacements(
                    nothingSpans,
                    columns,
                    maxRows = NOTHING_ROWS,
                    preferredPlacements = preferredPlacements,
                    reserved = sliderPlacements,
                    fillGaps = isNothingReflow(columns),
                ) + sliderPlacements
            }

        TwoDimensionalSpannedGrid(
            columns = columns,
            columnSpacing = NOTHING_SPACING,
            rowSpacing = NOTHING_SPACING,
            customPlacements = placements,
            spans = nothingSpans,
            maxRows = NOTHING_ROWS,
            rowHeights = sliderRowHeights,
            useSquareCells = true,
            maxCellSize = rememberNothingQSMaxCellSize(),
            keys = {
                if (it >= tiles.size) sliders[it - tiles.size].kind.spec else tiles[it].spec
            },
            modifier = modifier,
        ) { spanIndex, _ ->
            if (spanIndex >= tiles.size) {
                sliderContent?.invoke(sliders[spanIndex - tiles.size].kind)
                return@TwoDimensionalSpannedGrid
            }
            val it = tiles[spanIndex]
            Element(it.spec.toElementKey(), Modifier) {
                CompositionLocalProvider(
                    LocalQSQuadTiles provides quadTiles,
                    LocalQSBluetoothDevices provides connectedBluetoothDevicesInteractor.devices,
                ) {
                    Tile(
                        tile = it,
                        iconOnly = !largeTiles.contains(it.spec) && !quadTiles.contains(it.spec),
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo = noBounce,
                        tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                        detailsViewModel = detailsViewModel,
                        isVisible = listening,
                        requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                        enableRevealEffect = enableRevealEffect,
                    )
                }
            }
        }

        TileListener(tiles, listening)
    }

    /**
     * ColorOS style: draws a single wide tile outside the grid (used for the fixed Wi-Fi, mobile
     * data and Bluetooth slots of the ColorOS Quick Settings layout). The caller is responsible
     * for making the tile listen, e.g. with [TileListener].
     */
    @Composable
    fun ContentScope.ColorOSWideTile(
        tile: TileViewModel,
        listening: () -> Boolean,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.ColorOSWideTile", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }
        val scope = rememberCoroutineScope()
        val bounceable = remember(tile) { BounceableTileViewModel() }
        Element(tile.spec.toElementKey(), modifier) {
            Tile(
                tile = tile,
                iconOnly = false,
                squishiness = { 1f },
                tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                coroutineScope = scope,
                bounceableInfo =
                    BounceableInfo(
                        bounceable = bounceable,
                        previousTile = null,
                        nextTile = null,
                        bounceEnd = false,
                    ),
                detailsViewModel = detailsViewModel,
                isVisible = listening,
                requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                enableRevealEffect = false,
            )
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val resetResolver = LocalContext.current.contentResolver
        val resetNothingStyle by rememberUpdatedState(LocalNothingQsStyle.current)
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // The saved NothingOS positions, quad tiles and sliders belong to the old tiles
                    if (resetNothingStyle) {
                        putSecureStringIfChanged(resetResolver, "qs_tile_layout", "")
                        putSecureStringIfChanged(resetResolver, "qs_tile_quad_specs", "bt")
                    }

                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val showDualShadeSetting =
            DualShadeFlag.isEnabled &&
                LocalResources.current.getBoolean(
                    com.android.settingslib.R.bool.config_useDualShadeSetting
                )
        val actions =
            remember(topBarActionsViewModel, showDualShadeSetting) {
                topBarActionsViewModel.actions(showDualShadeSetting).toMutableStateList()
            }
        val context = LocalContext.current
        val isNothingStyle = LocalNothingQsStyle.current
        val (quadTiles, tileLayout, nothingSliders) =
            if (isNothingStyle) rememberNothingLayout() else NothingLayout.None
        val columns = if (isNothingStyle) 4 else columnsViewModel.columns
        val largeTilesSpan = columnsViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })
        val listState =
            remember(columns, largeTilesSpan, isNothingStyle) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                    isNothingStyle = isNothingStyle,
                    initialQuadTiles = quadTiles,
                    initialPositions = tileLayout,
                    initialSliders = nothingSliders,
                )
            }
        LaunchedEffect(currentTiles, largeTiles, quadTiles, tileLayout, nothingSliders, isNothingStyle) {
            if (isNothingStyle) {
                listState.updateTiles(
                    currentTiles,
                    largeTiles,
                    quadTiles,
                    tileLayout,
                    nothingSliders,
                )
            } else {
                listState.updateTiles(currentTiles, largeTiles, emptySet(), emptyMap())
            }
        }

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            scrollState = scrollState,
            snapshotViewModel = snapshotViewModel,
            onStopEditing = onStopEditing,
            topBarActions = actions,
        ) { action ->
            // Opening the dialog doesn't require a snapshot
            if (action != EditAction.ResetGrid) {
                snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
            }

            when (action) {
                is EditAction.AddTile -> {
                    onAddTile(action.tileSpec, listState.tileSpecs().size)
                }
                is EditAction.InsertTile -> {
                    onAddTile(action.tileSpec, action.position)
                }
                is EditAction.RemoveTile -> {
                    if (isNothingStyle) {
                        // The tiles behind the removed one move forward into its place
                        saveQSTileLayout(
                            context.contentResolver,
                            listState.positionsWithoutTile(action.tileSpec),
                            listState.sliders,
                        )
                    }
                    onRemoveTile(action.tileSpec)
                }
                EditAction.ResetGrid -> {
                    dialogDelegate.showDialog()
                }
                is EditAction.ResizeTile -> {
                    if (isNothingStyle) {
                        listState.resizeTile(action.tileSpec, action.toIcon, action.toQuad)
                    } else {
                        iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                    }
                }
                is EditAction.SetTiles -> {
                    if (isNothingStyle) {
                        val resolver = context.contentResolver
                        if (action.tilePositions != null) {
                            saveQSTileLayout(resolver, action.tilePositions, listState.sliders)
                        }
                        val newQuads = listState.quadTilesSpecs.map { it.spec }.joinToString(",")
                        putSecureStringIfChanged(resolver, "qs_tile_quad_specs", newQuads)
                        for (spec in listState.tileSpecs()) {
                            val isLarge = listState.largeTilesSpecs.contains(spec) || listState.quadTilesSpecs.contains(spec)
                            iconTilesViewModel.resize(spec, toIcon = !isLarge)
                        }
                    }
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

private const val NOTHING_ROWS = 4
private val NOTHING_SPACING = 16.dp
