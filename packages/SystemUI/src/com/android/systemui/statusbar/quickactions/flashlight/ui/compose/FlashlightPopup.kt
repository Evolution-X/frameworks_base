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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips

private val PopupShape = RoundedCornerShape(32.dp)
private val AccentColor = Color(0xFFFFD166)

/** Expanded flashlight card surfaced in the dynamic island. */
@Composable
fun FlashlightPopup(
    model: FlashlightPopupModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PopupShape,
        shadowElevation = 12.dp,
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
                StatusBarIcon(
                    icon =
                        Icon.Resource(
                            resId = R.drawable.ic_dynamic_island_flashlight,
                            contentDescription =
                                ContentDescription.Resource(R.string.quick_settings_flashlight_label),
                        ),
                    modifier = Modifier.size(24.dp),
                    tint = AccentColor,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.quick_settings_flashlight_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            model.levelPercent?.let {
                                stringResource(R.string.quick_settings_flashlight_tile_level_percentage, it)
                            } ?: stringResource(R.string.dynamic_island_flashlight_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.73f),
                    )
                }
            }

            Text(
                text =
                    model.levelPercent?.let {
                        stringResource(R.string.quick_settings_flashlight_tile_level_percentage, it)
                    } ?: stringResource(R.string.dynamic_island_flashlight_short),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AccentColor,
            )

            PopupActionChips(
                actions =
                    listOf(
                        PopupActionModel(
                            label = stringResource(R.string.flashlight_dialog_turn_off),
                            onClick = model.turnOff,
                            emphasized = true,
                        )
                    ),
                accent = AccentColor,
            )
        }
    }
}
