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

package com.android.systemui.statusbar.quickactions.stopwatch.ui.compose

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.quickactions.stopwatch.shared.model.StopwatchPopupModel
import com.android.systemui.statusbar.quickactions.ui.compose.PopupActionChips
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.rememberElapsedDurationText

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded stopwatch card surfaced in the dynamic island. */
@Composable
fun StopwatchPopup(
    model: StopwatchPopupModel,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    PopupSurface(
        shape = PopupShape,
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 360.dp)
                .clickable(enabled = model.onOpen != null) { model.onOpen?.invoke() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                model.icon?.let {
                    Icon(
                        icon = it,
                        modifier = Modifier.size(24.dp),
                        tint = Color.Unspecified,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalContentColor.current,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.73f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Text(
                text =
                    model.elapsedTimeText
                        ?: rememberElapsedDurationText(model.baseElapsedRealtimeMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )

            PopupActionChips(actions = model.actions, accent = accent)
        }
    }
}
