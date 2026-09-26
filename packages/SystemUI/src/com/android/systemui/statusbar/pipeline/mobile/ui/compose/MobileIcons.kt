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

package com.android.systemui.statusbar.pipeline.mobile.ui.compose

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.sp
import com.android.systemui.lifecycle.rememberActivated
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsState
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.StackedMobileIconViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIcon

/** Renders the mobile icons for the status bar. */
@Composable
fun MobileIcons(
    state: MobileIconsState,
    stackedMobileIconViewModel: StackedMobileIconViewModel,
    modifier: Modifier = Modifier,
) {

    // TODO(414653733): The icon size should always be the same as the battery.
    val iconHeightDp = dimensionResource(R.dimen.status_bar_composable_icon_height_sp)

    val isStackable = state.isStackable
    if (isStackable) {
        StackedMobileIcon(
            viewModel = stackedMobileIconViewModel,
            modifier = modifier.height(iconHeightDp),
        )
    } else {
        val mobileSubViewModels = state.mobileSubViewModels
        val iconSpacingSp = 2.sp
        val spacing = with(LocalDensity.current) { iconSpacingSp.toDp() }

        Row(
            horizontalArrangement = spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(iconHeightDp),
        ) {
            mobileSubViewModels.forEach { mobileViewModel ->
                val id = mobileViewModel.subscriptionId
                key(id) {
                    MobileIcon(
                        state =
                            rememberActivated("MobileIconState[$id]") { mobileViewModel.create() }
                    )
                }
            }
        }
    }
}
