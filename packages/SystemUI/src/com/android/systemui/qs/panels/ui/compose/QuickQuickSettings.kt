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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalNothingQsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSQuadTiles
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberNothingQSMaxCellSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSTileLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQuadTiles
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

    val columns = viewModel.columns
    val sizedTiles = viewModel.tileViewModels
    val tiles = sizedTiles.fastMap { it.tile }
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val classicStyle = rememberQSPanelStyle()
    // ColorOS style: QQS shows only the round icons, the labels stay in the expanded panel.
    val colorOSStyle = LocalColorOSQsStyle.current

    Box(modifier = modifier) {
        GridAnchor()

        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }
        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = spans,
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
            keys = { sizedTiles[it].tile.spec },
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = sizedTiles[spanIndex]
            Element(it.tile.spec.toElementKey(), Modifier) {
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

    TileListener(tiles, listening)
}

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
    val columns = NOTHING_COLUMNS
    val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val quadTiles = rememberQuadTiles()
    val tileLayout = rememberQSTileLayout()
    val largeTiles by viewModel.iconTilesViewModel.largeTiles.collectAsStateWithLifecycle()
    val noBounce = remember { BounceableInfo(BounceableTileViewModel(), null, null, false) }

    val allTiles = viewModel.allTileViewModels.map { it.tile }
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
    val qqsPlacements =
        remember(allSpans, preferredPlacements) {
            calculate2DPlacements(
                allSpans,
                columns,
                preferredPlacements = preferredPlacements,
                maxRows = NOTHING_QQS_MAX_ROWS,
            )
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
            Element(it.spec.toElementKey(), Modifier) {
                CompositionLocalProvider(LocalQSQuadTiles provides quadTiles) {
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

private const val NOTHING_COLUMNS = 4
private const val NOTHING_QQS_MAX_ROWS = 2
private val NOTHING_SPACING = 16.dp
