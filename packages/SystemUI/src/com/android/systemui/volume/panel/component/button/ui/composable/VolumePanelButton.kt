/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.panel.component.button.ui.composable

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.axion.blur.AxBlurSurfaceDefaults

/** Button with a label below it */
@Composable
fun VolumePanelButton(
    label: String,
    icon: Icon?,
    isActive: Boolean,
    onClick: (expandable: Expandable) -> Unit,
    semanticsRole: Role,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Expandable(
            modifier =
                Modifier.fillMaxWidth().height(56.dp).semantics {
                    role = semanticsRole
                    contentDescription = label
                },
            color =
                if (isEnabled && isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    AxBlurSurfaceDefaults.surfaceColor()
                },
            shape = RoundedCornerShape(20.dp),
            contentColor =
                when {
                    !isEnabled -> MaterialTheme.colorScheme.outline
                    isActive -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            onClick = { onClick(it) },
        ) {
            icon?.let {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(modifier = Modifier.size(24.dp), icon = it)
                }
            }
        }
        Text(
            modifier = Modifier.clearAndSetSemantics {}.basicMarquee(),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
        )
    }
}
