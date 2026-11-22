/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsTileLabel
import com.android.systemui.qs.panels.shared.model.TilePlacement
import com.android.systemui.qs.panels.shared.model.cellsOf
import com.android.systemui.qs.panels.shared.model.packTiles
import com.android.systemui.qs.panels.shared.model.paginateTiles
import com.android.systemui.qs.panels.shared.model.remixColumns
import com.android.systemui.qs.panels.shared.model.remixRowsPerPage
import com.android.systemui.qs.panels.ui.compose.infinitegrid.QsCustomizerDock
import com.android.systemui.qs.panels.ui.compose.infinitegrid.RemixTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TilePlacementGrid
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSPanelStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileLook
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileShapeStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.withBacking
import com.android.systemui.qs.panels.ui.compose.infinitegrid.withFills
import com.android.systemui.qs.panels.ui.compose.qsTileColumnSpacing
import com.android.systemui.qs.panels.ui.compose.qsTileRowSpacing
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel

/**
 * The QS editor of the Remix panel style: the tiles of the panel, drawn in the cells and the look
 * of the selected Remix look, and the customizer dock under them. It depends on nothing outside
 * SystemUI, and the stock editor is left as it is.
 *
 * `RemixTileGrid` is a ContentScope extension and edit mode is not one, so the tiles are drawn from
 * their [EditTileViewModel] and not from live tiles. Tiles cannot be picked, moved, added or
 * resized here yet.
 */
@Composable
internal fun RemixEditor(editViewModel: EditModeViewModel, modifier: Modifier = Modifier) {
    var dockOpen by remember { mutableStateOf(false) }

    // The handler registered last runs first, so Back closes the open dock before edit mode.
    BackHandler { editViewModel.stopEditing() }
    BackHandler(enabled = dockOpen) { dockOpen = false }
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

    // A player above or below the tiles is the card, so its tile is left out, as in the pager.
    val tiles =
        remember(allTiles, tweaks.mediaPlace, theme) {
            allTiles.filter {
                it.isCurrent &&
                    (tweaks.mediaPlaceIn(theme) == QsMediaPlace.GRID ||
                        it.tileSpec.spec != QsMediaTileSize.TILE_SPEC)
            }
        }
    val pages =
        remember(tiles, tweaks, theme, largeTiles, largeSpan, columns, pageRows) {
            val cells =
                tiles.map {
                    tweaks.cellsOf(
                        spec = it.tileSpec.spec,
                        theme = theme,
                        isLarge = largeTiles.contains(it.tileSpec),
                        largeSpan = largeSpan,
                        columns = columns,
                    )
                }
            paginateTiles(cells, columns, pageRows).map { page ->
                page.map { index -> tiles[index] } to packTiles(page.map { cells[it] }, columns)
            }
        }

    Column(modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            pages.forEach { (pageTiles, placements) ->
                PreviewPage(pageTiles, placements, columns)
            }
        }
        QsCustomizerDock(
            repository = editViewModel.styleRepository,
            columns = columns,
            settingsRows = settingsRows,
            allTiles = allTiles,
            onAddTile = { editViewModel.addTile(it) },
            open = dockOpen,
            onOpenChange = { dockOpen = it },
        )
    }
}

@Composable
private fun PreviewPage(
    tiles: List<EditTileViewModel>,
    placements: List<TilePlacement>,
    columns: Int,
) {
    val tweaks = LocalQsTweaks.current
    val look = tileLook(tileShapeStyle())
    val classicStyle = rememberQSPanelStyle()
    val columnSpacing = tweaks.spacing?.dp ?: qsTileColumnSpacing(columns)
    val rowSpacing = tweaks.spacing?.dp ?: qsTileRowSpacing(columns, classicStyle)
    TilePlacementGrid(
        placements = placements,
        columns = columns,
        columnSpacing = columnSpacing,
        rowSpacing = rowSpacing,
        keys = { tiles[it].tileSpec },
        modifier = Modifier.fillMaxWidth(),
    ) { index ->
        val place = placements[index]
        PreviewTile(
            tile = tiles[index],
            wide = place.columns > 1,
            height = look.height * place.rows + rowSpacing * (place.rows - 1),
        )
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
    val showLabel = wide && tweaks.label != QsTileLabel.NONE
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
                iconProvider = { tile.icon },
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
