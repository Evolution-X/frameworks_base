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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.grid.ui.compose.TwoDimensionalSpannedGrid
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.grid.ui.compose.calculate2DPlacements
import com.android.systemui.media.remedia.ui.compose.QsMediaTile
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.isBeside
import com.android.systemui.qs.panels.shared.model.remixColumns
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalNothingQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQsCollapsed
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalRemixQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSBluetoothDevices
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSQuadTiles
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TilePlacementGrid
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileLook
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileShapeStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.shared.style.LocalGlassBlurAlpha
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingQSMaxCellSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.forNothingColumns
import com.android.systemui.qs.panels.ui.compose.infinitegrid.inNothingLayoutOrder
import com.android.systemui.qs.panels.ui.compose.infinitegrid.isNothingReflow
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingQSColumns
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R

@Composable
fun ContentScope.QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean,
) {
    if (LocalNothingQsStyle.current) {
        NothingQuickQuickSettings(viewModel, modifier, listening)
        return
    }
    if (LocalRemixQsStyle.current) {
        RemixQuickQuickSettings(viewModel, modifier, listening)
        return
    }

    val columns = viewModel.columns
    val sizedTiles = viewModel.tileViewModels
    val tiles = sizedTiles.fastMap { it.tile }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val classicStyle = rememberQSPanelStyle()
    // ColorOS style: QQS shows only the round icons, the labels stay in the expanded panel.
    val colorOSStyle = LocalColorOSQsStyle.current
    // ColorOS style: a row the next, too wide, tile did not fit into hands its free columns to its
    // last icon tile, so QQS does not show a lone circle next to an empty gap.
    val rowTiles =
        remember(sizedTiles, columns, colorOSStyle, classicStyle) {
            if (colorOSStyle && !classicStyle) {
                sizedTiles.widenLastIconTiles(columns, viewModel.largeTilesSpan)
            } else {
                sizedTiles
            }
        }

    Box(modifier = modifier) {
        GridAnchor()

        val bounceables = remember(rowTiles) { List(rowTiles.size) { BounceableTileViewModel() } }
        val spans by remember(rowTiles) { derivedStateOf { rowTiles.fastMap { it.width } } }
        VerticalSpannedGrid(
            columns = columns,
            // Share the QS spacing so tiles don't shift when the panel expands.
            columnSpacing =
                if (colorOSStyle) dimensionResource(R.dimen.qs_tile_margin_horizontal)
                else qsTileColumnSpacing(columns),
            rowSpacing =
                if (colorOSStyle) dimensionResource(R.dimen.qs_tile_margin_vertical)
                else qsTileRowSpacing(columns, classicStyle),
            spans = spans,
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
            // Center compact tiles on the same row as taller large tiles.
            verticalAlignment = Alignment.CenterVertically,
            keys = { rowTiles[it].tile.spec },
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = rowTiles[spanIndex]
            // Tile glass is a window blur region and ignores the element's fade, so a QQS tile
            // faded out over the expanded QS grid would still blur through it.
            val key = it.tile.spec.toElementKey()
            val outerGlass = LocalGlassBlurAlpha.current
            val glass = remember(outerGlass, key) { { outerGlass() * (key.currentAlpha() ?: 1f) } }
            Element(key, Modifier) {
                CompositionLocalProvider(
                    LocalGlassBlurAlpha provides glass,
                    LocalQsCollapsed provides true,
                ) {
                    Tile(
                        tile = it.tile,
                        iconOnly = classicStyle || it.isIcon,
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo =
                            bounceables.bounceableInfo(
                                it,
                                index = spanIndex,
                                column = column,
                                columns = columns,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                                // Compact tiles bounce their content instead of their container.
                                bouncesContainer = { index ->
                                    colorOSStyle || !(classicStyle || rowTiles[index].isIcon)
                                },
                            ),
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        // There should be no QuickQuickSettings when the details view is enabled.
                        detailsViewModel = null,
                        isVisible = listening,
                        hideLabel = colorOSStyle,
                    )
                }
            }
        }
    }

    TileListener(tiles, listening)
}

/**
 * Remix style QQS: the first rows of the first QS page, in the same cells as the expanded panel, so
 * a tile that spans rows, like the player, stays where it is while the panel expands.
 */
@Composable
private fun ContentScope.RemixQuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier,
    listening: () -> Boolean,
) {
    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
    val columns = tweaks.remixColumns(theme, viewModel.columns)
    val placedTiles by
        remember(viewModel, tweaks, theme, columns) {
            derivedStateOf { viewModel.placedTiles(tweaks, theme, columns) }
        }
    val tiles = placedTiles.fastMap { it.tile }
    val placements = remember(placedTiles) { placedTiles.fastMap { it.place } }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val classicStyle = rememberQSPanelStyle()
    val tileHeight = tileLook(tileShapeStyle()).height
    // Share the QS spacing so tiles don't shift when the panel expands.
    val columnSpacing = tweaks.spacing?.dp ?: qsTileColumnSpacing(columns)
    val rowSpacing = tweaks.spacing?.dp ?: qsTileRowSpacing(columns, classicStyle)
    val rowGap = with(LocalDensity.current) { rowSpacing.roundToPx() }
    val mediaTileSize = tweaks.mediaSize ?: QsMediaTileSize.SQUARE

    Box(modifier = modifier) {
        GridAnchor()

        val bounceables =
            remember(placedTiles) { List(placedTiles.size) { BounceableTileViewModel() } }
        TilePlacementGrid(
            placements = placements,
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            keys = { placedTiles[it].tile.spec },
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
        ) { index ->
            val placed = placedTiles[index]
            val place = placed.place
            val height = tileHeight * place.rows + rowSpacing * (place.rows - 1)
            // Tile glass is a window blur region and ignores the element's fade, so a QQS tile
            // faded out over the expanded QS grid would still blur through it.
            val key = placed.tile.spec.toElementKey()
            val outerGlass = LocalGlassBlurAlpha.current
            val glass = remember(outerGlass, key) { { outerGlass() * (key.currentAlpha() ?: 1f) } }
            Element(key, Modifier) {
                CompositionLocalProvider(
                    LocalGlassBlurAlpha provides glass,
                    LocalQsCollapsed provides true,
                ) {
                    if (placed.tile.spec.spec == QsMediaTileSize.TILE_SPEC) {
                        QsMediaTile(
                            viewModelFactory = viewModel.mediaViewModelFactory,
                            size =
                                when {
                                    !placed.cut -> mediaTileSize
                                    place.rows > 1 && place.columns >= columns ->
                                        QsMediaTileSize.FULL
                                    place.rows > 1 -> QsMediaTileSize.SQUARE
                                    place.columns > 1 -> QsMediaTileSize.WIDE
                                    else -> QsMediaTileSize.SMALL
                                },
                            modifier = Modifier.fillMaxWidth().height(height),
                            squishGaps = { rowGap * (place.rows - 1) },
                            squishiness = { squishiness },
                        )
                    } else {
                        Tile(
                            tile = placed.tile,
                            iconOnly = place.columns == 1,
                            squishiness = { squishiness },
                            coroutineScope = scope,
                            bounceableInfo =
                                bounceables.bounceableInfo(
                                    SizedTileImpl(placed.tile, place.columns),
                                    index = index,
                                    column = place.column,
                                    columns = columns,
                                    isFirstInRow = !placements.isBeside(index, index - 1),
                                    isLastInRow = !placements.isBeside(index, index + 1),
                                    // Icon tiles bounce their content instead of their
                                    // container, and the player does not bounce.
                                    bouncesContainer = { other ->
                                        placements[other].columns > 1 &&
                                            tiles[other].spec.spec != QsMediaTileSize.TILE_SPEC
                                    },
                                ),
                            tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                            // There should be no QuickQuickSettings when the details view is
                            // enabled.
                            detailsViewModel = null,
                            isVisible = listening,
                            rows = place.rows,
                            height = height,
                        )
                    }
                }
            }
        }
    }

    TileListener(tiles, listening)
}

/**
 * Hands the free columns of every row of this [columns] wide grid to the last icon tile of the row,
 * widened up to [largeSpan], so a row never ends in a gap that the next tile did not fit into.
 */
private fun <T> List<SizedTile<T>>.widenLastIconTiles(
    columns: Int,
    largeSpan: Int,
): List<SizedTile<T>> =
    splitInRowsSequence(this, columns)
        .flatMap { row ->
            val free = columns - row.sumOf { it.width }
            val last = row.indexOfLast { it.isIcon }
            row.mapIndexed { index, tile ->
                if (index == last && free > 0) {
                    SizedTileImpl(tile.tile, (tile.width + free).coerceAtMost(largeSpan))
                } else {
                    tile
                }
            }
        }
        .toList()

/**
 * Nothing style QQS: the first two rows of the same 2D placements as the expanded panel, on 4
 * square columns, where tiles are circles, pills (2x1) or quads (2x2).
 */
@Composable
private fun ContentScope.NothingQuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier,
    listening: () -> Boolean,
) {
    val columns = rememberNothingQSColumns()
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (quadTiles, savedTileLayout) = rememberNothingLayout()
    val tileLayout = savedTileLayout.forNothingColumns(columns)
    val largeTiles by viewModel.iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()
    val noBounce = remember { BounceableInfo(BounceableTileViewModel(), null, null, false) }

    val allTiles =
        viewModel.allTileViewModels
            .map { it.tile }
            .inNothingLayoutOrder(savedTileLayout, columns) { it.spec }
    val allSpans =
        remember(allTiles, largeTiles, quadTiles) {
            allTiles.map {
                when {
                    quadTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 2)
                    largeTiles.contains(it.spec) -> GridSpan(colSpan = 2, rowSpan = 1)
                    else -> GridSpan(colSpan = 1, rowSpan = 1)
                }
            }
        }
    val preferredPlacements =
        remember(allTiles, tileLayout) {
            allTiles
                .mapIndexedNotNull { index, tile -> tileLayout[tile.spec]?.let { index to it } }
                .toMap()
        }
    // Same placements as the first page of the expanded panel, cut to the first rows, so the tiles
    // do not move when the panel expands. Tiles saved further down stay out of the QQS.
    val qqsPlacements =
        remember(allSpans, preferredPlacements, columns) {
            calculate2DPlacements(
                    allSpans,
                    columns,
                    preferredPlacements = preferredPlacements,
                    maxRowsPerPage = NOTHING_ROWS_PER_PAGE,
                    fillGaps = isNothingReflow(columns),
                )
                .filter { it.row + it.rowSpan <= NOTHING_QQS_MAX_ROWS }
        }
    val qqsTiles = remember(qqsPlacements, allTiles) { qqsPlacements.map { allTiles[it.index] } }
    val qqsSpans = remember(qqsPlacements, allSpans) { qqsPlacements.map { allSpans[it.index] } }

    Box(modifier = modifier) {
        GridAnchor()

        TwoDimensionalSpannedGrid(
            columns = columns,
            columnSpacing = NOTHING_SPACING,
            rowSpacing = NOTHING_SPACING,
            spans = qqsSpans,
            customPlacements = qqsPlacements.mapIndexed { idx, p -> p.copy(index = idx) },
            useSquareCells = true,
            maxCellSize = rememberNothingQSMaxCellSize(),
            keys = { qqsTiles[it].spec },
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
        ) { spanIndex, _ ->
            val it = qqsTiles[spanIndex]
            val key = it.spec.toElementKey()
            val outerGlass = LocalGlassBlurAlpha.current
            val glass = remember(outerGlass, key) { { outerGlass() * (key.currentAlpha() ?: 1f) } }
            Element(key, Modifier) {
                CompositionLocalProvider(
                    LocalQSQuadTiles provides quadTiles,
                    LocalQSBluetoothDevices provides viewModel.connectedBluetoothDevices,
                    LocalGlassBlurAlpha provides glass,
                ) {
                    Tile(
                        tile = it,
                        iconOnly = !largeTiles.contains(it.spec) && !quadTiles.contains(it.spec),
                        squishiness = { squishiness },
                        coroutineScope = scope,
                        bounceableInfo = noBounce,
                        tileHapticsViewModelFactory = viewModel.tileHapticsViewModelFactory,
                        detailsViewModel = null,
                        isVisible = listening,
                    )
                }
            }
        }
    }

    TileListener(qqsTiles, listening)
}

private const val NOTHING_QQS_MAX_ROWS = 2
// Keep in sync with the rows per page of the expanded panel in PaginatedGridLayout.
private const val NOTHING_ROWS_PER_PAGE = 4
private val NOTHING_SPACING = 16.dp
