/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.data.repository.QsPanelStyleRepository
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsIconBacking
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTileFont
import com.android.systemui.qs.panels.shared.model.QsTileLabel
import com.android.systemui.qs.panels.shared.model.QsTileSizing
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.ui.compose.qsTileColumnSpacing
import com.android.systemui.res.R
import kotlin.math.roundToInt

internal enum class CustomizerTab(@StringRes val label: Int) {
    Theme(R.string.qs_custom_tab_theme),
    Layout(R.string.qs_custom_tab_layout),
    Tiles(R.string.qs_custom_tab_tiles),
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
 */
@Composable
internal fun QsCustomizerDock(
    repository: QsPanelStyleRepository,
    columns: Int,
    settingsRows: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(CustomizerTab.Theme) }
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
                text = stringResource(if (open) R.string.qs_custom_done else R.string.qs_custom_open),
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
                        tab = entry
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
                }
            }
        }
    }
}

@Composable
private fun ThemeTab(repository: QsPanelStyleRepository) {
    val tweaks = LocalQsTweaks.current
    val theme = LocalQsStyle.current
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

@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
                .clickable(onClick = onClick)
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
