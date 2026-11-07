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

package com.android.systemui.statusbar.quickactions.alarm.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.ui.compose.IslandAccents
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlassCard
import com.android.systemui.statusbar.quickactions.ui.compose.IslandGlyphBadge
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.islandGlassCards
import com.android.systemui.statusbar.quickactions.ui.compose.pressScale

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded next alarm card surfaced in the dynamic island. */
@Composable
fun AlarmPopup(
    model: AlarmPopupModel,
    modifier: Modifier = Modifier,
) {
    val accent = IslandAccents.Alarm
    val breathe = rememberInfiniteTransition(label = "alarm_breathe")
    val breatheScale by
        breathe.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.06f,
            animationSpec =
                infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "alarm_breathe_scale",
        )
    if (islandGlassCards()) {
        IslandGlassCard(
            primary = model.fullTimeText,
            secondary =
                listOf(model.title, model.dayText)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            glyph = {
                StatusBarIcon(
                    icon =
                        Icon.Resource(
                            resId = R.drawable.ic_dynamic_island_alarm,
                            contentDescription = ContentDescription.Resource(R.string.status_bar_alarm),
                        ),
                    modifier =
                        Modifier.size(32.dp).graphicsLayer {
                            scaleX = breatheScale
                            scaleY = breatheScale
                        },
                    tint = accent,
                )
            },
            modifier = modifier,
            onClick = model.onOpen,
            primaryAction =
                model.actions.getOrNull(0)?.let { it.copy(vector = it.vector ?: Icons.Rounded.Snooze) },
        )
        return
    }
    PopupSurface(
        shape = PopupShape,
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 360.dp)
                .pressScale(enabled = model.onOpen != null, pressedScale = 0.97f) {
                    model.onOpen?.invoke()
                },
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
                                resId = R.drawable.ic_dynamic_island_alarm,
                                contentDescription =
                                    ContentDescription.Resource(R.string.status_bar_alarm),
                            ),
                        modifier =
                            Modifier.size(22.dp).graphicsLayer {
                                scaleX = breatheScale
                                scaleY = breatheScale
                            },
                        tint = accent,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = model.dayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.73f),
                    )
                }
            }

            Text(
                text = model.fullTimeText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )

            PopupActionChips(
                actions =
                    buildList {
                        model.actions.forEach(::add)
                        model.onOpen?.let {
                            add(
                                PopupActionModel(
                                    label = stringResource(R.string.dynamic_island_open_clock_action),
                                    onClick = it,
                                )
                            )
                        }
                    },
                accent = accent,
            )
        }
    }
}
