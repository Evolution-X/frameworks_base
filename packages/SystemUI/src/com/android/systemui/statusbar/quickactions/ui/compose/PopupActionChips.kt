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

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

@Composable
fun PopupActionChips(
    actions: List<PopupActionModel>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) {
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        actions.forEach { action ->
            val backgroundColor =
                if (action.emphasized) accent else Color.White.copy(alpha = 0.11f)
            val contentColor =
                if (action.emphasized) MaterialTheme.colorScheme.onPrimary else Color.White

            Text(
                text = action.label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                modifier =
                    Modifier.background(
                            color = backgroundColor,
                            shape = RoundedCornerShape(22.dp),
                        )
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
