/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.os.UserHandle
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.data.repository.QsPanelStyleRepository
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsIconBacking
import com.android.systemui.qs.panels.shared.model.QsMediaArt
import com.android.systemui.qs.panels.shared.model.QsMediaBar
import com.android.systemui.qs.panels.shared.model.QsMediaButtons
import com.android.systemui.qs.panels.shared.model.QsMediaPlace
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.shared.model.QsOffFill
import com.android.systemui.qs.panels.shared.model.QsOnFill
import com.android.systemui.qs.panels.shared.model.QsSliderLayout
import com.android.systemui.qs.panels.shared.model.QsSliderPercent
import com.android.systemui.qs.panels.shared.model.QsSliderPlace
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTileFont
import com.android.systemui.qs.panels.shared.model.QsTileDepth
import com.android.systemui.qs.panels.shared.model.QsTileLabel
import com.android.systemui.qs.panels.shared.model.QsTileSizing
import com.android.systemui.qs.panels.shared.model.QsTileTweak
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.QsTweaksCodec
import com.android.systemui.qs.panels.shared.model.QsVolumeLook
import com.android.systemui.qs.panels.shared.model.QsVolumeShow
import com.android.systemui.qs.panels.shared.model.QsVolumeSize
import com.android.systemui.qs.panels.shared.model.QsVolumeSound
import com.android.systemui.qs.panels.shared.model.QsVolumeTap
import com.android.systemui.qs.panels.ui.compose.qsTileColumnSpacing
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R
import lineageos.providers.LineageSettings
import kotlin.math.roundToInt

internal enum class CustomizerTab(@StringRes val label: Int) {
    Theme(R.string.qs_custom_tab_theme),
    Layout(R.string.qs_custom_tab_layout),
    Tiles(R.string.qs_custom_tab_tiles),
    Color(R.string.qs_custom_tab_color),
    Mix(R.string.qs_custom_tab_mix),
    Slider(R.string.qs_custom_tab_sliders),
    Add(R.string.qs_custom_tab_add),
}

@get:StringRes
private val QsStyle.label: Int
    get() =
        when (this) {
            QsStyle.STOCK -> R.string.qs_custom_theme_stock
            QsStyle.REMIX -> R.string.qs_custom_theme_remix
            QsStyle.COMPACT -> R.string.qs_custom_theme_compact
            QsStyle.GLASS -> R.string.qs_custom_theme_glass
            QsStyle.CIRCLES -> R.string.qs_custom_theme_circles
            QsStyle.CLASSIC -> R.string.qs_custom_theme_classic
            QsStyle.CARDS -> R.string.qs_custom_theme_cards
            QsStyle.NEON -> R.string.qs_custom_theme_neon
            QsStyle.BENTO -> R.string.qs_custom_theme_bento
            QsStyle.SHAPES -> R.string.qs_custom_theme_shapes
            QsStyle.BLUEPRINT -> R.string.qs_custom_theme_blueprint
            QsStyle.DUO -> R.string.qs_custom_theme_duo
            QsStyle.SOFT -> R.string.qs_custom_theme_soft
        }

/**
 * The customizer at the bottom of the Remix QS editor. It stays a slim bar until it is [open], so
 * the panel above it stays usable. Every change goes through [repository], which saves it with the
 * changes of the selected look.
 *
 * @param columns the columns the panel is laid out in, so the slider starts where the panel is.
 * @param settingsRows the rows a page has in Settings, which a look follows until it sets its own.
 * @param allTiles every tile that can be on the panel, the Add tab offers those that are not.
 * @param onAddTile adds a tile at the end of the panel.
 * @param tab the open tab, which the editor owns so the plus tile can open the Add tab.
 * @param tile the picked tile. Its options replace the tabs until [onCloseTile].
 * @param onRemoveTile removes the picked tile, when it can be removed.
 * @param currentTiles every tile on the panel, in order, which the Sliders tab places.
 * @param onSetTiles sets the tiles of the panel in the order given.
 */
@Composable
internal fun QsCustomizerDock(
    repository: QsPanelStyleRepository,
    columns: Int,
    settingsRows: Int,
    allTiles: List<EditTileViewModel>,
    onAddTile: (TileSpec) -> Unit,
    tab: CustomizerTab,
    onTabChange: (CustomizerTab) -> Unit,
    tile: EditTileViewModel?,
    onRemoveTile: (EditTileViewModel) -> Unit,
    onCloseTile: () -> Unit,
    currentTiles: List<TileSpec>,
    onSetTiles: (List<TileSpec>) -> Unit,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val edit: ((QsTweaks) -> QsTweaks) -> Unit = repository::editTweaks

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (tile != null) {
            TileOptions(tile, columns, edit, currentTiles, onSetTiles, onRemoveTile, onCloseTile)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onOpenChange(!open) },
            ) {
                Text(
                    text =
                        stringResource(
                            if (open) R.string.qs_custom_title_open else R.string.qs_custom_title
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        stringResource(
                            if (open) R.string.qs_custom_done else R.string.qs_custom_open
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            if (open) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CustomizerTab.entries.forEach { entry ->
                        TabButton(
                            text = stringResource(entry.label),
                            selected = entry == tab,
                            modifier = Modifier.weight(1f),
                        ) {
                            onTabChange(entry)
                        }
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                ) {
                    when (tab) {
                        CustomizerTab.Theme -> ThemeTab(repository)
                        CustomizerTab.Layout -> LayoutTab(columns, settingsRows, edit)
                        CustomizerTab.Tiles -> TilesTab(edit)
                        CustomizerTab.Color -> ColorTab(edit)
                        CustomizerTab.Mix -> MixTab(edit)
                        CustomizerTab.Slider -> SlidersTab(edit, currentTiles, onSetTiles)
                        CustomizerTab.Add -> AddTab(allTiles, onAddTile)
                    }
                }
            }
        }
    }
}

/** The tiles not on the panel, by category; a tap adds one at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTab(allTiles: List<EditTileViewModel>, onAddTile: (TileSpec) -> Unit) {
    val available = allTiles.filter { !it.isCurrent }
    if (available.isEmpty()) {
        Text(
            text = stringResource(R.string.qs_custom_all_added),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val grouped = remember(available) { groupAndSort(available) }
    grouped.forEach { (category, tiles) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RowTitle(category.label.load() ?: "")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tiles.forEach { tile ->
                    key(tile.tileSpec) { TrayChip(tile) { onAddTile(tile.tileSpec) } }
                }
            }
        }
    }
}

@Composable
private fun TrayChip(tile: EditTileViewModel, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label =
        stringResource(R.string.accessibility_qs_edit_named_tile_add_action, tile.label.text)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainerHighest)
                .clickable(onClickLabel = label, onClick = onClick)
                .padding(start = 10.dp, end = 14.dp),
    ) {
        SmallTileContent(
            iconProvider = { tile.icon },
            color = scheme.onSurface,
            size = { 20.dp },
            animateToEnd = true,
        )
        Text(
            text = tile.label.text,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The options of the picked tile, under its name with the ways out: remove it, or let it go. The
 * player and the volume tile have sheets of their own, and the brightness and sound tiles open the
 * Sliders tab.
 */
@Composable
private fun TileOptions(
    tile: EditTileViewModel,
    columns: Int,
    edit: ((QsTweaks) -> QsTweaks) -> Unit,
    currentTiles: List<TileSpec>,
    onSetTiles: (List<TileSpec>) -> Unit,
    onRemoveTile: (EditTileViewModel) -> Unit,
    onCloseTile: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
    ) {
        Text(
            text = tile.label.text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tile.isRemovable) {
            ActionChip(stringResource(R.string.qs_custom_remove)) { onRemoveTile(tile) }
            Box(Modifier.width(6.dp))
        }
        Text(
            text = stringResource(R.string.qs_custom_done),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onCloseTile)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
    val spec = tile.tileSpec.spec
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
    ) {
        when (spec) {
            QsMediaTileSize.TILE_SPEC -> MediaSheet(edit)
            QsVolumeSize.TILE_SPEC -> VolumeSheet(edit)
            QsVolumeSize.BRIGHTNESS_SPEC,
            QsVolumeSize.SOUND_SPEC -> SlidersTab(edit, currentTiles, onSetTiles)
            else -> TileSheet(spec, columns, edit)
        }
    }
}

/**
 * The tweaks of one tile. A tile with none is drawn as the panel draws it, and a tweak that is put
 * back to what it was leaves nothing in the saved look.
 */
@Composable
private fun TileSheet(spec: String, columns: Int, edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tile = LocalQsTweaks.current.tiles[spec]
    fun change(change: (QsTileTweak) -> QsTileTweak) {
        edit { it.withTile(spec, change) }
    }
    ThemeChips(
        stringResource(R.string.qs_custom_look),
        tile?.look,
        none = stringResource(R.string.qs_custom_same_as_panel),
    ) { look ->
        change { it.copy(look = look) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_tile_width),
        options =
            listOf<Pair<Int?, String>>(null to stringResource(R.string.qs_custom_auto)) +
                (1..columns).map { it to it.toString() },
        selected = tile?.columns,
    ) { width ->
        change { it.copy(columns = width) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_tile_height),
        options = (1..4).map { it to it.toString() },
        selected = tile?.rows ?: 1,
    ) { height ->
        change { it.copy(rows = height.takeIf { rows -> rows > 1 }) }
    }
    SwatchRow(stringResource(R.string.qs_custom_tile_color), tile?.color) { color ->
        change { it.copy(color = color) }
    }
    IconRow(tile?.icon) { icon -> change { it.copy(icon = icon) } }
    ChipRow(
        title = stringResource(R.string.qs_custom_label),
        options =
            listOf(
                false to stringResource(R.string.qs_custom_show),
                true to stringResource(R.string.qs_custom_hide),
            ),
        selected = tile?.hideLabel == true,
    ) { hide ->
        change { it.copy(hideLabel = hide) }
    }
}

private val Swatches: List<Pair<Long, Int>> =
    listOf(
        0xFF7CACF8 to R.string.qs_custom_color_blue,
        0xFF5FD3BC to R.string.qs_custom_color_teal,
        0xFF8BD48B to R.string.qs_custom_color_green,
        0xFFB69CFF to R.string.qs_custom_color_violet,
        0xFFF28BA8 to R.string.qs_custom_color_rose,
        0xFFFFB870 to R.string.qs_custom_color_amber,
        0xFFE8E8E8 to R.string.qs_custom_color_white,
    )

@Composable
private fun MediaSheet(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
    ChipRow(
        title = stringResource(R.string.qs_custom_size),
        options =
            listOf(
                QsMediaTileSize.SMALL to stringResource(R.string.qs_custom_media_small),
                QsMediaTileSize.WIDE to stringResource(R.string.qs_custom_media_wide),
                QsMediaTileSize.SQUARE to stringResource(R.string.qs_custom_media_square),
                QsMediaTileSize.TALL to stringResource(R.string.qs_custom_media_tall),
                QsMediaTileSize.FULL to stringResource(R.string.qs_custom_media_full),
            ),
        selected = tweaks.mediaSize ?: QsMediaTileSize.SQUARE,
    ) { size ->
        edit { it.copy(mediaSize = size.takeIf { s -> s != QsMediaTileSize.SQUARE }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_place),
        options =
            listOf(
                QsMediaPlace.GRID to stringResource(R.string.qs_custom_media_place_grid),
                QsMediaPlace.ABOVE to stringResource(R.string.qs_custom_media_place_above),
                QsMediaPlace.BELOW to stringResource(R.string.qs_custom_media_place_below),
            ),
        selected = tweaks.mediaPlaceIn(theme),
    ) { place ->
        edit { it.copy(mediaPlace = place) }
    }
    ThemeChips(stringResource(R.string.qs_custom_media_look), tweaks.mediaFrom) { style ->
        edit { it.copy(mediaFrom = style) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_art),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsMediaArt.ROUNDED to stringResource(R.string.qs_custom_rounded),
                QsMediaArt.CIRCLE to stringResource(R.string.qs_custom_circle),
                QsMediaArt.BLOB to stringResource(R.string.qs_custom_blob),
                QsMediaArt.COVER to stringResource(R.string.qs_custom_media_cover),
                QsMediaArt.NONE to stringResource(R.string.qs_custom_none),
            ),
        selected = tweaks.mediaArt,
    ) { art ->
        edit { it.copy(mediaArt = art) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_progress),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsMediaBar.LINE to stringResource(R.string.qs_custom_media_line),
                QsMediaBar.GLOW to stringResource(R.string.qs_custom_media_glow),
                QsMediaBar.BLOCKS to stringResource(R.string.qs_custom_media_blocks),
                QsMediaBar.WAVE to stringResource(R.string.qs_custom_media_wave),
                QsMediaBar.NONE to stringResource(R.string.qs_custom_none),
            ),
        selected = tweaks.mediaBar,
    ) { bar ->
        edit { it.copy(mediaBar = bar) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_buttons),
        options =
            listOf(
                QsMediaButtons.TRIO to stringResource(R.string.qs_custom_media_trio),
                QsMediaButtons.PLAY to stringResource(R.string.qs_custom_media_play),
            ),
        selected = tweaks.mediaButtons ?: QsMediaButtons.TRIO,
    ) { buttons ->
        edit { it.copy(mediaButtons = buttons.takeIf { b -> b != QsMediaButtons.TRIO }) }
    }
}

@Composable
private fun VolumeSheet(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    val look = tweaks.volumeLook
    ThemeChips(
        stringResource(R.string.qs_custom_look),
        tweaks.tiles[QsVolumeSize.TILE_SPEC]?.look,
        none = stringResource(R.string.qs_custom_same_as_panel),
    ) { theme ->
        edit { it.withTile(QsVolumeSize.TILE_SPEC) { t -> t.copy(look = theme) } }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_size),
        options =
            listOf(
                QsVolumeSize.SMALL to stringResource(R.string.qs_custom_media_small),
                QsVolumeSize.HALF to stringResource(R.string.qs_custom_volume_half),
                QsVolumeSize.WIDE to stringResource(R.string.qs_custom_media_wide),
                QsVolumeSize.TALL to stringResource(R.string.qs_custom_media_tall),
                QsVolumeSize.SQUARE to stringResource(R.string.qs_custom_media_square),
            ),
        selected = tweaks.volumeSize ?: QsVolumeSize.TALL,
    ) { size ->
        edit { it.copy(volumeSize = size, sliderLayout = null) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_look),
        options = sliderLooks() + (QsVolumeLook.STACK to stringResource(R.string.qs_custom_volume_stack)),
        selected = look,
    ) { picked ->
        edit { it.copy(volumeLook = picked) }
    }
    if (look != QsVolumeLook.STACK) {
        ChipRow(
            title = stringResource(R.string.qs_custom_volume_sound),
            options =
                listOf(
                    QsVolumeSound.MEDIA to stringResource(R.string.qs_media_tile_label),
                    QsVolumeSound.RING to stringResource(R.string.qs_custom_volume_ring),
                    QsVolumeSound.ALARM to stringResource(R.string.qs_custom_volume_alarm),
                    QsVolumeSound.CALL to stringResource(R.string.qs_custom_volume_call),
                ),
            selected = tweaks.volumeSound ?: QsVolumeSound.MEDIA,
        ) { sound ->
            edit { it.copy(volumeSound = sound.takeIf { s -> s != QsVolumeSound.MEDIA }) }
        }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_show),
        options =
            listOf(
                QsVolumeShow.ALL to stringResource(R.string.qs_custom_volume_show_all),
                QsVolumeShow.PERCENT to stringResource(R.string.qs_custom_volume_show_percent),
                QsVolumeShow.ICON to stringResource(R.string.qs_custom_volume_show_icon),
            ),
        selected = tweaks.volumeShow ?: QsVolumeShow.ALL,
    ) { show ->
        edit { it.copy(volumeShow = show.takeIf { s -> s != QsVolumeShow.ALL }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_volume_tap),
        options =
            listOf(
                QsVolumeTap.MUTE to stringResource(R.string.qs_custom_volume_mute),
                QsVolumeTap.PANEL to stringResource(R.string.qs_custom_volume_panel),
            ),
        selected = tweaks.volumeTap ?: QsVolumeTap.MUTE,
    ) { tap ->
        edit { it.copy(volumeTap = tap.takeIf { t -> t != QsVolumeTap.MUTE }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_volume_ticks),
        options =
            listOf(
                true to stringResource(R.string.qs_custom_volume_on),
                false to stringResource(R.string.qs_custom_off),
            ),
        selected = tweaks.volumeTicks != false,
    ) { ticks ->
        edit { it.copy(volumeTicks = false.takeIf { !ticks }) }
    }
}

private val MediaSpec = TileSpec.create(QsMediaTileSize.TILE_SPEC)
private val BrightnessSpec = TileSpec.create(QsVolumeSize.BRIGHTNESS_SPEC)
private val VolumeSpec = TileSpec.create(QsVolumeSize.TILE_SPEC)
private val SoundSpec = TileSpec.create(QsVolumeSize.SOUND_SPEC)

/** Where the brightness bar sits when it is not a tile, or the sliders sit by the player. */
private enum class SliderPlace(@StringRes val label: Int) {
    Top(R.string.qs_custom_slider_top),
    Under(R.string.qs_custom_slider_under),
    ByPlayer(R.string.qs_custom_slider_by_player),
    Off(R.string.qs_custom_off),
}

private fun List<TileSpec>.withSound(on: Boolean): List<TileSpec> {
    val rest = filter { it != SoundSpec }
    if (!on) return rest
    val at = rest.indexOfLast { it == VolumeSpec || it == BrightnessSpec }
    return if (at < 0) rest + SoundSpec else rest.take(at + 1) + SoundSpec + rest.drop(at + 1)
}

/**
 * Puts the player and the slider tiles in the order of [layout], at the front of the panel, and
 * gives them the sizes it needs. The other tiles keep their order after them.
 */
private fun applySliderLayout(
    layout: QsSliderLayout,
    current: List<TileSpec>,
    onSetTiles: (List<TileSpec>) -> Unit,
    edit: ((QsTweaks) -> QsTweaks) -> Unit,
) {
    val sound = SoundSpec in current
    val media = MediaSpec.takeIf { it in current }
    val sliders = listOfNotNull(BrightnessSpec, VolumeSpec, SoundSpec.takeIf { sound })
    val front =
        when (layout) {
            QsSliderLayout.PAIR,
            QsSliderLayout.CORNER -> listOfNotNull(media) + sliders
            QsSliderLayout.SPLIT,
            QsSliderLayout.RAILS -> sliders + listOfNotNull(media)
        }
    onSetTiles(front + current.filter { it !in front && it != SoundSpec })
    edit {
        it.copy(
            sliderLayout = layout,
            brightnessSize =
                when (layout) {
                    QsSliderLayout.PAIR -> QsVolumeSize.TALL
                    QsSliderLayout.SPLIT -> QsVolumeSize.HALF
                    QsSliderLayout.RAILS -> QsVolumeSize.WIDE
                    QsSliderLayout.CORNER -> QsVolumeSize.SMALL
                },
            volumeSize =
                when (layout) {
                    QsSliderLayout.PAIR -> QsVolumeSize.TALL
                    QsSliderLayout.SPLIT -> QsVolumeSize.HALF
                    QsSliderLayout.RAILS -> if (sound) QsVolumeSize.HALF else QsVolumeSize.WIDE
                    QsSliderLayout.CORNER -> QsVolumeSize.SMALL
                },
            soundSize = QsVolumeSize.HALF,
            volumeLook = it.volumeLook.takeIf { look -> look != QsVolumeLook.STACK },
            mediaSize =
                if (layout == QsSliderLayout.PAIR || layout == QsSliderLayout.CORNER) {
                    QsMediaTileSize.SQUARE
                } else {
                    it.mediaSize
                },
            mediaPlace = QsMediaPlace.GRID,
            slidersByMedia = false,
        )
    }
}

/**
 * The Sliders tab: a layout (A to D) that places the player and the Brightness, Volume and Sound
 * mode tiles and sizes them, one look and percent setting for both sliders, and the sound mode
 * switch. Without a Brightness tile the brightness bar's place is set here too.
 */
@Composable
private fun SlidersTab(
    edit: ((QsTweaks) -> QsTweaks) -> Unit,
    current: List<TileSpec>,
    onSetTiles: (List<TileSpec>) -> Unit,
) {
    val tweaks = LocalQsTweaks.current
    val sound = SoundSpec in current
    ChipRow(
        title = stringResource(R.string.qs_custom_sliders_layout),
        options =
            listOf(
                QsSliderLayout.PAIR to stringResource(R.string.qs_custom_sliders_pair),
                QsSliderLayout.SPLIT to stringResource(R.string.qs_custom_sliders_split),
                QsSliderLayout.RAILS to stringResource(R.string.qs_custom_sliders_rails),
                QsSliderLayout.CORNER to stringResource(R.string.qs_custom_sliders_corner),
            ),
        selected = tweaks.sliderLayout ?: QsSliderLayout.PAIR,
    ) { layout ->
        applySliderLayout(layout, current, onSetTiles, edit)
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_media_look),
        options = sliderLooks(),
        selected = tweaks.brightnessLook,
    ) { look ->
        edit { it.copy(brightnessLook = look, volumeLook = look) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_sliders_percent),
        options =
            listOf(
                QsSliderPercent.ALWAYS to stringResource(R.string.qs_custom_sliders_always),
                QsSliderPercent.DRAG to stringResource(R.string.qs_custom_sliders_dragging),
                QsSliderPercent.OFF to stringResource(R.string.qs_custom_off),
            ),
        selected = tweaks.sliderPercent ?: QsSliderPercent.ALWAYS,
    ) { percent ->
        edit { it.copy(sliderPercent = percent.takeIf { p -> p != QsSliderPercent.ALWAYS }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_sliders_sound),
        options =
            listOf(
                true to stringResource(R.string.qs_custom_volume_on),
                false to stringResource(R.string.qs_custom_off),
            ),
        selected = sound,
    ) { on ->
        if (on != sound) onSetTiles(current.withSound(on))
    }
    if (BrightnessSpec !in current) {
        RowTitle(stringResource(R.string.qs_custom_sliders_bar))
        SliderTab(edit)
    }
}

/** Where the brightness bar goes: over the tiles, under them, by the player, or nowhere. */
@Composable
private fun SliderTab(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
    val resolver = LocalContext.current.contentResolver
    val place =
        remember(tweaks.sliderPlace, tweaks.slidersByMedia, theme) {
            readSliderPlace(resolver, tweaks.sliderPlace, tweaks.slidersByMediaIn(theme))
        }
    ChipRow(
        title = stringResource(R.string.qs_custom_slider_place),
        options = SliderPlace.entries.map { it to stringResource(it.label) },
        selected = place,
    ) { picked ->
        edit {
            val byPlayer = picked == SliderPlace.ByPlayer
            it.copy(
                sliderPlace =
                    when (picked) {
                        SliderPlace.Top -> QsSliderPlace.TOP
                        SliderPlace.Under -> QsSliderPlace.UNDER
                        SliderPlace.Off -> QsSliderPlace.OFF
                        SliderPlace.ByPlayer ->
                            it.sliderPlace.takeIf { current -> current != QsSliderPlace.OFF }
                                ?: QsSliderPlace.TOP
                    },
                slidersByMedia = byPlayer,
                // The sliders sit next to the full card, which only shows outside the grid.
                mediaPlace =
                    if (byPlayer && it.mediaPlaceIn(theme) == QsMediaPlace.GRID) {
                        QsMediaPlace.BELOW
                    } else {
                        it.mediaPlace
                    },
            )
        }
    }
}

/** The place the panel draws the bar at: the look's own, else the one in Settings. */
private fun readSliderPlace(
    resolver: ContentResolver,
    place: QsSliderPlace?,
    byPlayer: Boolean,
): SliderPlace {
    val show =
        LineageSettings.Secure.getIntForUser(
            resolver,
            LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER,
            1,
            UserHandle.USER_CURRENT,
        )
    val position =
        LineageSettings.Secure.getIntForUser(
            resolver,
            LineageSettings.Secure.QS_BRIGHTNESS_SLIDER_POSITION,
            0,
            UserHandle.USER_CURRENT,
        )
    return when {
        place == QsSliderPlace.OFF || (place == null && show == 0) -> SliderPlace.Off
        byPlayer -> SliderPlace.ByPlayer
        place == QsSliderPlace.TOP || (place == null && position == 0) -> SliderPlace.Top
        else -> SliderPlace.Under
    }
}

@Composable
private fun sliderLooks(): List<Pair<QsVolumeLook?, String>> =
    listOf(
        null to stringResource(R.string.qs_custom_slider_follow),
        QsVolumeLook.FILL to stringResource(R.string.qs_custom_slider_solid),
        QsVolumeLook.SLIDER to stringResource(R.string.qs_custom_slider_knob),
        QsVolumeLook.BLOCKS to stringResource(R.string.qs_custom_media_blocks),
        QsVolumeLook.LIQUID to stringResource(R.string.qs_custom_slider_liquid),
    )

@Composable
private fun ThemeChips(
    title: String,
    selected: QsStyle?,
    none: String = stringResource(R.string.qs_custom_theme),
    onPick: (QsStyle?) -> Unit,
) {
    ChipRow(
        title = title,
        options =
            listOf<Pair<QsStyle?, String>>(null to none) +
                QsStyle.entries.map { it to stringResource(it.label) },
        selected = selected,
        onPick = onPick,
    )
}

@Composable
private fun SwatchRow(title: String, selected: Int?, onPick: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val themeColor = colorResource(android.R.color.system_primary_dark)
    val themeLabel = stringResource(R.string.qs_custom_theme)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowTitle(title)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Swatch(themeColor, themeLabel, selected == null, scheme.onSurface) { onPick(null) }
            Swatches.forEach { (color, label) ->
                val argb = Color(color).toArgb()
                Swatch(Color(color), stringResource(label), selected == argb, scheme.onSurface) {
                    onPick(argb)
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    label: String,
    selected: Boolean,
    ring: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(2.dp, ring, CircleShape) else Modifier)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.RadioButton
                    this.selected = selected
                }
    )
}

@Composable
private fun IconRow(selected: String?, onPick: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowTitle(stringResource(R.string.qs_custom_tile_icon))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            OptionChip(stringResource(R.string.qs_custom_default), selected == null) {
                onPick(null)
            }
            QsTileIcons.forEach { (name, icon) ->
                val active = name == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (active) scheme.primary else scheme.surfaceContainerHighest
                            )
                            .clickable { onPick(name) },
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = name,
                        tint = if (active) scheme.onPrimary else scheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeTab(repository: QsPanelStyleRepository) {
    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
    val context = LocalContext.current
    val myThemes by repository.myThemes.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<Int?>(null) }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val myThemeName = stringResource(R.string.qs_custom_my_theme_name, myThemes.size + 1)
    ChipRow(
        title = stringResource(R.string.qs_custom_start_from),
        options = QsStyle.entries.map { it to stringResource(it.label) },
        selected = theme,
        onPick = repository::setRemixStyle,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionChip(
            text = stringResource(R.string.qs_custom_reset),
            enabled = tweaks != QsTweaks(),
            onClick = repository::resetTweaks,
        )
        ActionChip(stringResource(R.string.qs_custom_save)) {
            repository.saveMyTheme(myThemeName)
        }
        ActionChip(stringResource(R.string.qs_custom_copy_code)) {
            val code = QsTweaksCodec.toCode(repository.currentAsMyTheme(myThemeName))
            clipboard?.setPrimaryClip(ClipData.newPlainText(myThemeName, code))
            note = R.string.qs_custom_code_copied
        }
        ActionChip(stringResource(R.string.qs_custom_paste_code)) {
            val pasted =
                clipboard
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            val pastedTheme = QsTweaksCodec.fromCode(pasted)
            if (pastedTheme != null) repository.applyMyTheme(pastedTheme)
            note =
                if (pastedTheme != null) {
                    R.string.qs_custom_code_applied
                } else {
                    R.string.qs_custom_code_bad
                }
        }
    }
    note?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (myThemes.isNotEmpty()) {
        RowTitle(stringResource(R.string.qs_custom_my_themes))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            myThemes.forEach { myTheme ->
                OptionChip(
                    text = myTheme.name,
                    selected = false,
                    onLongClick = { repository.deleteMyTheme(myTheme) },
                ) {
                    repository.applyMyTheme(myTheme)
                }
            }
        }
    }
}

@Composable
private fun LayoutTab(columns: Int, settingsRows: Int, edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    val themeHeight = themeTileLook(tileShapeStyle()).height.value.roundToInt()
    // A look follows the spacing QS has until it sets its own.
    val themeSpacing = qsTileColumnSpacing(columns).value.roundToInt()
    SliderRow(stringResource(R.string.qs_custom_columns), columns, 3..6) { value ->
        edit { it.copy(columns = value) }
    }
    SliderRow(
        title = stringResource(R.string.qs_custom_rows),
        value = tweaks.rowsPerPage ?: settingsRows,
        range = 2..8,
        enabled = !tweaks.scroll,
    ) { value ->
        edit { it.copy(rowsPerPage = value.takeIf { rows -> rows != settingsRows }) }
    }
    SwitchRow(stringResource(R.string.qs_custom_scroll), tweaks.scroll) { scroll ->
        edit { it.copy(scroll = scroll) }
    }
    SliderRow(
        title = stringResource(R.string.qs_custom_tile_height_dp),
        value = tweaks.tileHeight ?: themeHeight,
        range = 44..112,
        step = 2,
    ) { value ->
        edit { it.copy(tileHeight = value.takeIf { height -> height != themeHeight }) }
    }
    SliderRow(
        title = stringResource(R.string.qs_custom_spacing),
        value = tweaks.spacing ?: themeSpacing,
        range = 0..24,
    ) { value ->
        edit { it.copy(spacing = value.takeIf { spacing -> spacing != themeSpacing }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_sizes),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_mixed),
                QsTileSizing.LONG to stringResource(R.string.qs_custom_all_long),
                QsTileSizing.ICON to stringResource(R.string.qs_custom_all_icons),
            ),
        selected = tweaks.sizing,
    ) { sizing ->
        edit { it.copy(sizing = sizing) }
    }
}

@Composable
private fun TilesTab(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    val themeLook = themeTileLook(tileShapeStyle())
    val themeRadius = themeLook.activeRadius.value.roundToInt()
    val themeIcon = themeLook.smallIconSize.value.roundToInt()
    val pill = stringResource(R.string.qs_custom_pill)
    SliderRow(
        title = stringResource(R.string.qs_custom_corners),
        value = tweaks.radius ?: themeRadius,
        range = 0..40,
        display = { if (it >= 40) pill else it.toString() },
    ) { value ->
        edit { it.copy(radius = value.takeIf { radius -> radius != themeRadius }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_label),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsTileLabel.INSIDE to stringResource(R.string.qs_custom_label_inside),
                QsTileLabel.UNDER to stringResource(R.string.qs_custom_label_under),
                QsTileLabel.NONE to stringResource(R.string.qs_custom_none),
            ),
        selected = tweaks.label,
    ) { label ->
        edit { it.copy(label = label) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_backing),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsIconBacking.NONE to stringResource(R.string.qs_custom_none),
                QsIconBacking.CIRCLE to stringResource(R.string.qs_custom_circle),
                QsIconBacking.ROUNDED to stringResource(R.string.qs_custom_rounded),
                QsIconBacking.BLOB to stringResource(R.string.qs_custom_blob),
            ),
        selected = tweaks.iconBacking,
    ) { backing ->
        edit { it.copy(iconBacking = backing) }
    }
    SwitchRow(stringResource(R.string.qs_custom_second_line), tweaks.secondLine) { show ->
        edit { it.copy(secondLine = show) }
    }
    SliderRow(
        title = stringResource(R.string.qs_custom_icon_size),
        value = tweaks.iconSize ?: themeIcon,
        range = 16..36,
    ) { value ->
        edit { it.copy(iconSize = value.takeIf { size -> size != themeIcon }) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_font),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsTileFont.SANS to stringResource(R.string.qs_custom_font_sans),
                QsTileFont.MONO to stringResource(R.string.qs_custom_font_mono),
                QsTileFont.CAPS to stringResource(R.string.qs_custom_font_caps),
            ),
        selected = tweaks.font,
    ) { font ->
        edit { it.copy(font = font) }
    }
}

@Composable
private fun ColorTab(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    SwatchRow(stringResource(R.string.qs_custom_accent), tweaks.accent) { color ->
        edit { it.copy(accent = color) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_on_fill),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsOnFill.SOLID to stringResource(R.string.qs_custom_fill_solid),
                QsOnFill.GRADIENT to stringResource(R.string.qs_custom_fill_gradient),
                QsOnFill.TINT to stringResource(R.string.qs_custom_fill_tint),
                QsOnFill.OUTLINE to stringResource(R.string.qs_custom_fill_outline),
                QsOnFill.ICON to stringResource(R.string.qs_custom_fill_icon),
            ),
        selected = tweaks.onFill,
    ) { fill ->
        edit { it.copy(onFill = fill) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_off_fill),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsOffFill.SURFACE to stringResource(R.string.qs_custom_fill_surface),
                QsOffFill.CLEAR to stringResource(R.string.qs_custom_fill_clear),
                QsOffFill.DARK to stringResource(R.string.qs_custom_fill_dark),
                QsOffFill.OUTLINE to stringResource(R.string.qs_custom_fill_outline),
            ),
        selected = tweaks.offFill,
    ) { fill ->
        edit { it.copy(offFill = fill) }
    }
    SliderRow(
        title = stringResource(R.string.qs_custom_glow),
        value = tweaks.glow ?: 0,
        range = 0..100,
        step = 5,
    ) { value ->
        edit { it.copy(glow = value) }
    }
    ChipRow(
        title = stringResource(R.string.qs_custom_depth),
        options =
            listOf(
                null to stringResource(R.string.qs_custom_theme),
                QsTileDepth.FLAT to stringResource(R.string.qs_custom_depth_flat),
                QsTileDepth.RAISED to stringResource(R.string.qs_custom_depth_raised),
            ),
        selected = tweaks.depth,
    ) { depth ->
        edit { it.copy(depth = depth) }
    }
}

@Composable
private fun MixTab(edit: ((QsTweaks) -> QsTweaks) -> Unit) {
    val tweaks = LocalQsTweaks.current
    ThemeChips(stringResource(R.string.qs_custom_mix_shape), tweaks.shapeFrom) { style ->
        edit { it.copy(shapeFrom = style) }
    }
    ThemeChips(stringResource(R.string.qs_custom_mix_paint), tweaks.paintFrom) { style ->
        edit { it.copy(paintFrom = style) }
    }
    ThemeChips(stringResource(R.string.qs_custom_mix_slider), tweaks.sliderFrom) { style ->
        edit { it.copy(sliderFrom = style) }
    }
    ThemeChips(stringResource(R.string.qs_custom_mix_media), tweaks.mediaFrom) { style ->
        edit { it.copy(mediaFrom = style) }
    }
    ThemeChips(stringResource(R.string.qs_custom_mix_footer), tweaks.footerFrom) { style ->
        edit { it.copy(footerFrom = style) }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Long lists stay on one line and scroll sideways, so they don't push the rest out of reach. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowTitle(title)
        if (options.size > 6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                options.forEach { (value, label) ->
                    OptionChip(label, value == selected) { onPick(value) }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { (value, label) ->
                    OptionChip(label, value == selected) { onPick(value) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                }
                .padding(horizontal = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, scheme.outline, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
            maxLines = 1,
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    enabled: Boolean = true,
    display: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    // The saved value trails a drag, so the thumb follows the finger on its own.
    var dragging by remember { mutableStateOf<Float?>(null) }
    fun snapped(position: Float) = (position / step).roundToInt() * step
    val shown = dragging?.let(::snapped) ?: value
    Column {
        Row {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = display(shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = (dragging ?: value.toFloat()).coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { position ->
                val before = dragging?.let(::snapped) ?: value
                dragging = position
                val after = snapped(position)
                if (after != before) onChange(after)
            },
            onValueChangeFinished = { dragging = null },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}
