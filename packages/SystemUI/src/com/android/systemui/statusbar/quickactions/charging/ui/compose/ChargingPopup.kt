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

package com.android.systemui.statusbar.quickactions.charging.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.charging.shared.model.ChargingDetail
import com.android.systemui.statusbar.quickactions.charging.shared.model.ChargingPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.ui.compose.IslandAccents
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlyphBadge
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface

private val PopupShape = RoundedCornerShape(32.dp)

@Composable
fun ChargingPopup(model: ChargingPopupModel, modifier: Modifier = Modifier) {
    val accent = IslandAccents.Battery
    val pulse = rememberInfiniteTransition(label = "charging_pulse")
    val pulseScale by
        pulse.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec =
                infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "charging_pulse_scale",
        )

    PopupSurface(shape = PopupShape, modifier = modifier.widthIn(min = 300.dp, max = 360.dp)) {
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
                        icon = Icon.Resource(R.drawable.ic_dynamic_island_charging, null),
                        modifier =
                            Modifier.size(22.dp).graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            },
                        tint = accent,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (model.subtitle.isNotEmpty()) {
                        Text(
                            text = model.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.73f),
                        )
                    }
                }
                Text(
                    text = "${model.level}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }

            LevelBar(level = model.level, accent = accent)

            if (model.details.isNotEmpty()) {
                ChargingDetails(details = model.details)
            }

            PopupActionChips(
                actions =
                    listOfNotNull(
                        model.onOpenSettings?.let {
                            PopupActionModel(
                                label =
                                    stringResource(R.string.dynamic_island_charging_battery_settings),
                                onClick = it,
                            )
                        }
                    ),
                accent = accent,
            )
        }
    }
}

@Composable
private fun LevelBar(level: Int, accent: Color) {
    val fraction by
        animateFloatAsState(
            targetValue = (level / 100f).coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            label = "charging_level",
        )
    val track = RoundedCornerShape(50)
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(8.dp)
                .clip(track)
                .background(IslandAccents.badgeFill(accent))
    ) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent, track))
    }
}

@Composable
private fun ChargingDetails(details: List<ChargingDetail>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        details.forEach { detail ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${detail.value} ${detail.unit}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.73f),
                )
            }
        }
    }
}
