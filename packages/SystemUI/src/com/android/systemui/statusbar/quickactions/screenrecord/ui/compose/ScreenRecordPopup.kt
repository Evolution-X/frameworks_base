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

package com.android.systemui.statusbar.quickactions.screenrecord.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.ui.compose.rememberElapsedDurationText
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded screen-recording status card for the dynamic island. */
@Composable
fun ScreenRecordPopup(
    model: ScreenRecordPopupModel,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFFFF5A5F)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PopupShape,
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(min = 280.dp, max = 360.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(14.dp).background(accent, CircleShape),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.screenrecord_ongoing_screen_only),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            when (model) {
                                is ScreenRecordPopupModel.Starting ->
                                    stringResource(
                                        R.string.dynamic_island_screen_record_countdown,
                                        model.secondsUntilStarted,
                                    )
                                is ScreenRecordPopupModel.Recording ->
                                    stringResource(R.string.dynamic_island_screen_record_active)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.73f),
                    )
                }
            }

            when (model) {
                is ScreenRecordPopupModel.Starting -> {
                    Text(
                        text = model.secondsUntilStarted.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }

                is ScreenRecordPopupModel.Recording -> {
                    Text(
                        text = rememberElapsedDurationText(model.startElapsedRealtimeMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Box(
                        modifier =
                            Modifier.background(
                                    color = accent.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                                .clickable(onClick = model.stopRecording)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.screenrecord_stop_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}
