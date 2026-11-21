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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.bluetooth.connecteddevices.ConnectedBluetoothDevicesInteractor
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.domain.interactor.QSPaginatedRowsInteractor
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.packTiles
import com.android.systemui.qs.panels.shared.model.paginateTiles
import com.android.systemui.qs.panels.shared.model.remixRowsPerPage
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    qsColumnsViewModelFactory: QSColumnsViewModel.Factory,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    val iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    connectedBluetoothDevicesInteractor: ConnectedBluetoothDevicesInteractor,
    qsRowsInteractor: QSPaginatedRowsInteractor,
    val mediaViewModelFactory: MediaViewModel.Factory,
) : HydratedActivatable() {

    val connectedBluetoothDevices = connectedBluetoothDevicesInteractor.devices

    private val qsColumnsViewModel = qsColumnsViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)
    private val mediaInRowViewModel =
        mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)

    val columns: Int
        get() = qsColumnsViewModel.columns

    private val largeTiles by iconTilesViewModel.largeTiles.hydratedStateOf()

    private val rows: Int
        get() =
            if (mediaInRowViewModel.shouldMediaShowInRow) {
                rowsWithoutMedia * 2
            } else {
                rowsWithoutMedia
            }

    private val rowsWithoutMedia by
        quickQuickSettingsRowInteractor.rows.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultRows
        )

    /** How many columns a large tile spans in quick quick settings. */
    val largeTilesSpan: Int
        get() = qsColumnsViewModel.largeSpan

    /** The rows of a page of the expanded panel, which the first rows of this panel come from. */
    private val pageRows by qsRowsInteractor.rows.hydratedStateOf<Int>()

    private val currentTiles by tilesInteractor.currentTiles.hydratedStateOf()

    /** Every current tile in order, for the Nothing style which places tiles on a 2D grid. */
    val allTileViewModels by derivedStateOf {
        currentTiles.map {
            SizedTileImpl(TileViewModel(it.tile, it.spec, it.expandable), it.spec.width())
        }
    }

    val tileViewModels by derivedStateOf {
        currentTiles
            .map { SizedTileImpl(TileViewModel(it.tile, it.spec, it.expandable), it.spec.width()) }
            .let { splitInRowsSequence(it, columns).take(rows).toList().flatten() }
    }

    /** A tile of the collapsed Remix panel, and whether it lost rows to fit there. */
    data class PlacedTile(val tile: TileViewModel, val place: TilePlacement, val cut: Boolean)

    /**
     * The tiles in the first rows of the first Remix QS page, where QS puts them, so they stay in
     * place while the panel expands. [columns] are the columns of the Remix grid.
     */
    fun placedTiles(tweaks: QsTweaks, theme: QsStyle, columns: Int): List<PlacedTile> {
        val mediaInGrid = tweaks.mediaPlaceIn(theme) == QsMediaPlace.GRID
        val shown =
            currentTiles.filter { mediaInGrid || it.spec.spec != QsMediaTileSize.TILE_SPEC }
        val cells =
            shown.map {
                tweaks.cellsOf(it.spec.spec, theme, largeTiles.contains(it.spec), largeTilesSpan, columns)
            }
        val page =
            paginateTiles(cells, columns, tweaks.remixRowsPerPage(pageRows)).firstOrNull()
                ?: return emptyList()
        var pageCells = page.map { cells[it] }
        val first = pageCells.firstOrNull() ?: return emptyList()
        if (rows > 1 && page.size > 1 && first.columns >= columns && first.rows >= rows) {
            pageCells = listOf(first.copy(rows = 1)) + pageCells.drop(1)
        }
        return packTiles(pageCells, columns).mapIndexedNotNull { index, place ->
            if (place.row >= rows) return@mapIndexedNotNull null
            val tile = shown[page[index]]
            val kept = place.rows.coerceAtMost(rows - place.row)
            PlacedTile(
                TileViewModel(tile.tile, tile.spec, tile.expandable),
                place.copy(rows = kept),
                cut = kept < cells[page[index]].rows,
            )
        }
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { qsColumnsViewModel.activate() }
            launch { mediaInRowViewModel.activate() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }

    private fun TileSpec.width(): Int = if (largeTiles.contains(this)) largeTilesSpan else 1

    companion object {
        /** Behavior of the media carousel in quick quick settings */
        @VisibleForTesting
        val mediaUiBehavior: MediaUiBehavior
            get() =
                MediaUiBehavior(
                    isCarouselDismissible = true,
                    carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                )
    }
}
