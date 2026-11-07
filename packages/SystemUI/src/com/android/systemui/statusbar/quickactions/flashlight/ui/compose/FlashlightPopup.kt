/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.flashlight.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.flashlight.ui.composable.VerticalFlashlightSlider
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.ui.compose.IslandAccents
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlassCard
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlyphBadge
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.islandGlassCards

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded flashlight card surfaced in the dynamic island. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashlightPopup(
    model: FlashlightPopupModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    modifier: Modifier = Modifier,
) {
    val accent = IslandAccents.Flashlight
    if (islandGlassCards()) {
        val level = model.level
        val maxLevel = model.maxLevel
        val strengthSlider: (@Composable () -> Unit)? =
            if (level != null && maxLevel != null && maxLevel > 1) {
                {
                    FlashlightGlassSlider(
                        model = model,
                        level = level,
                        maxLevel = maxLevel,
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        accent = accent,
                    )
                }
            } else {
                null
            }
        IslandGlassCard(
            primary = stringResource(R.string.quick_settings_flashlight_label),
            secondary =
                model.strengthPercent?.let {
                    stringResource(R.string.quick_settings_flashlight_tile_level_percentage, it)
                } ?: stringResource(R.string.dynamic_island_flashlight_enabled),
            glyph = {
                StatusBarIcon(
                    icon =
                        Icon.Resource(
                            resId = R.drawable.ic_dynamic_island_flashlight,
                            contentDescription =
                                ContentDescription.Resource(
                                    R.string.quick_settings_flashlight_label
                                ),
                        ),
                    modifier = Modifier.size(32.dp),
                    tint = accent,
                )
            },
            modifier = modifier,
            primaryAction =
                PopupActionModel(
                    label = stringResource(R.string.flashlight_dialog_turn_off),
                    onClick = model.turnOff,
                    emphasized = true,
                    vector = Icons.Rounded.PowerSettingsNew,
                ),
            below = strengthSlider,
        )
        return
    }
    PopupSurface(
        shape = PopupShape,
        modifier = modifier.widthIn(min = 280.dp, max = 340.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IslandGlyphBadge(accent = accent) {
                    StatusBarIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_flashlight,
                                contentDescription =
                                    ContentDescription.Resource(
                                        R.string.quick_settings_flashlight_label
                                    ),
                            ),
                        modifier = Modifier.size(22.dp),
                        tint = accent,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.quick_settings_flashlight_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            model.strengthPercent?.let {
                                stringResource(R.string.quick_settings_flashlight_tile_level_percentage, it)
                            } ?: stringResource(R.string.dynamic_island_flashlight_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.73f),
                    )
                }
            }

            val level = model.level
            val maxLevel = model.maxLevel
            if (level != null && maxLevel != null && maxLevel > 1) {
                val valueRange = 1..maxLevel
                var isDragging by remember(maxLevel) { mutableStateOf(false) }
                var sliderLevel by remember(maxLevel) {
                    mutableIntStateOf(level.coerceIn(valueRange))
                }
                LaunchedEffect(level, maxLevel) {
                    if (!isDragging) sliderLevel = level.coerceIn(valueRange)
                }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VerticalFlashlightSlider(
                        valueRange = valueRange,
                        onValueChange = {
                            isDragging = true
                            sliderLevel = it.coerceIn(valueRange)
                            model.setLevelTemporary(sliderLevel)
                        },
                        onValueChangeFinished = {
                            sliderLevel = it.coerceIn(valueRange)
                            model.setLevel(sliderLevel)
                            isDragging = false
                        },
                        isEnabled = true,
                        levelValue = sliderLevel,
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = accent,
                                activeTrackColor = accent,
                            ),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.dynamic_island_flashlight_short),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }

            PopupActionChips(
                actions =
                    listOf(
                        PopupActionModel(
                            label = stringResource(R.string.flashlight_dialog_turn_off),
                            onClick = model.turnOff,
                            emphasized = true,
                        )
                    ),
                accent = accent,
            )
        }
    }
}

/** The strength slider of the glass card: the same levels and haptics as the vertical one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlashlightGlassSlider(
    model: FlashlightPopupModel,
    level: Int,
    maxLevel: Int,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    accent: Color,
) {
    val valueRange = 1..maxLevel
    val floatValueRange = 1f..maxLevel.toFloat()
    val interactionSource = remember { MutableInteractionSource() }
    var isDragging by remember(maxLevel) { mutableStateOf(false) }
    var sliderLevel by remember(maxLevel) { mutableIntStateOf(level.coerceIn(valueRange)) }
    LaunchedEffect(level, maxLevel) {
        if (!isDragging) sliderLevel = level.coerceIn(valueRange)
    }
    val hapticsViewModel: SliderHapticsViewModel =
        rememberViewModel(traceName = "SliderHapticsViewModel") {
            hapticsViewModelFactory.create(
                interactionSource,
                floatValueRange,
                Orientation.Horizontal,
                SliderHapticFeedbackConfig(maxVelocityToScale = 1f),
                SeekableSliderTrackerConfig(),
            )
        }
    val strengthLabel = stringResource(R.string.flashlight_dialog_title)
    Slider(
        value = sliderLevel.toFloat(),
        onValueChange = {
            hapticsViewModel.onValueChange(it)
            isDragging = true
            sliderLevel = it.toInt().coerceIn(valueRange)
            model.setLevelTemporary(sliderLevel)
        },
        onValueChangeFinished = {
            hapticsViewModel.onValueChangeEnded()
            model.setLevel(sliderLevel)
            isDragging = false
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = strengthLabel },
        valueRange = floatValueRange,
        interactionSource = interactionSource,
        colors =
            SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
    )
}
