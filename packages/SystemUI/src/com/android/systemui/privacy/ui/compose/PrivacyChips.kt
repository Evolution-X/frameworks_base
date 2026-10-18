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

package com.android.systemui.privacy.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.privacy.ui.compose.PrivacyChips.Colors
import com.android.systemui.privacy.ui.compose.PrivacyChips.Dimensions.betweenIconSpacing
import com.android.systemui.privacy.ui.compose.PrivacyChips.Dimensions.chipHeight
import com.android.systemui.privacy.ui.compose.PrivacyChips.Dimensions.iconSize
import com.android.systemui.privacy.ui.compose.PrivacyChips.Dimensions.multiElementHorizontalPadding
import com.android.systemui.privacy.ui.compose.PrivacyChips.Dimensions.singleElementHorizontalPadding

/** Shows the privacy chip containing the location icon */
@Composable
fun LocationPrivacyChip(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier.size(chipHeight).background(Colors.locationBackground, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(PrivacyType.TYPE_LOCATION.iconId),
            contentDescription = null,
            tint = Colors.locationIcon,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Shows the privacy chip for non-location elements.
 *
 * The icons will be sorted in the natural order of [PrivacyType].
 */
@Composable
fun NonLocationPrivacyChip(privacyItems: Set<PrivacyType>, modifier: Modifier = Modifier) {
    val filteredSortedPrivacyItems =
        remember(privacyItems) {
            privacyItems.filterNot { it == PrivacyType.TYPE_LOCATION }.sorted()
        }
    if (filteredSortedPrivacyItems.isEmpty()) {
        return
    }

    Row(
        modifier =
            modifier
                .height(chipHeight)
                .background(Colors.otherTypesBackground, shape = CircleShape)
                .padding(
                    horizontal =
                        if (filteredSortedPrivacyItems.size == 1) {
                            singleElementHorizontalPadding
                        } else {
                            multiElementHorizontalPadding
                        }
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(betweenIconSpacing),
    ) {
        filteredSortedPrivacyItems.forEach { privacyType ->
            key(privacyType.nameId) {
                Icon(
                    painter = painterResource(privacyType.iconId),
                    contentDescription = null,
                    tint = Colors.otherTypesIcon,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

private object PrivacyChips {
    object Dimensions {
        val chipHeight = 24.dp
        val iconSize = 16.dp
        val betweenIconSpacing = 4.dp
        val singleElementHorizontalPadding = 4.dp
        val multiElementHorizontalPadding = 6.dp
    }

    object Colors {
        val locationBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer

        val locationIcon: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer

        val otherTypesBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer

        val otherTypesIcon: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer
    }
}
