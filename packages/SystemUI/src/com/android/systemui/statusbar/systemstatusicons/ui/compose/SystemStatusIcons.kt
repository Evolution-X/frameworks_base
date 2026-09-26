/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.systemstatusicons.ui.compose

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.icons.ui.compose.EllipsizingRow
import com.android.systemui.statusbar.systemstatusicons.domain.interactor.SystemStatusIconBlocklistInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.compose.MobileIcons
import com.android.systemui.statusbar.pipeline.wifi.ui.compose.WifiIcon
import com.android.systemui.statusbar.shared.ui.compose.StatusBarIcon
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconViewModel
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconsViewModel

/**
 * Composable that displays the system status icons. This does not handle any spacing or alignment.
 * That is expected to be done in a container composable like a Row.
 */
@Composable
fun SystemStatusIcons(
    viewModelFactory: SystemStatusIconsViewModel.Factory,
    systemStatusIconBlocklistInteractor: SystemStatusIconBlocklistInteractor,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel =
        rememberViewModel(traceName = "SystemStatusIcons") {
            viewModelFactory.create(context, systemStatusIconBlocklistInteractor)
        }

    CompositionLocalProvider(LocalContentColor provides tint) {
        val iconSpacing = dimensionResource(R.dimen.status_bar_system_icon_spacing)
        EllipsizingRow(spacing = iconSpacing, modifier = modifier.sysuiResTag("statusIcons")) {
            viewModel.iconViewModels
                .filter { it.visible }
                .forEach { iconViewModel ->
                    when (iconViewModel) {
                        is SystemStatusIconViewModel.Default ->
                            item(key = iconViewModel.slotName) {
                                iconViewModel.icon?.let { StatusBarIcon(icon = it) }
                            }
                        is SystemStatusIconViewModel.External -> {
                            item(key = iconViewModel.slotName) {
                                ExternalSystemStatusIcon(iconViewModel)
                            }
                        }
                        is SystemStatusIconViewModel.Wifi -> {
                            item(key = iconViewModel.slotName) { WifiIcon(iconViewModel) }
                        }

                        is SystemStatusIconViewModel.MobileIcons -> {
                            item(key = iconViewModel.slotName) {
                                MobileIcons(
                                    iconViewModel.mobileIcons,
                                    iconViewModel.stackedMobileIconViewModel,
                                )
                            }
                        }
                    }
                }
        }
    }
}

/** Renders an icon that came from an external process. */
@Composable
private fun ExternalSystemStatusIcon(
    viewModel: SystemStatusIconViewModel.External,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val iconAsDrawable = viewModel.statusBarIcon.toDrawable(context) ?: return
    val contentDescription =
        viewModel.statusBarIcon.contentDescription?.let { ContentDescription.Loaded(it.toString()) }

    StatusBarIcon(
        icon = IconModel.Loaded(drawable = iconAsDrawable, contentDescription = contentDescription),
        modifier = modifier,
    )
}

/**
 * Transforms the [StatusBarIcon] sent from the external process into an icon displayable in
 * SystemUI.
 *
 * Equivalent to StatusBarIconView#loadDrawable.
 */
private fun StatusBarIcon.toDrawable(context: Context): Drawable? {
    // TODO(b/475251350): Downscale the icon (see StatusBarIconView#getIcon).
    // TODO(b/475251350): Verify icon levels work.
    val preloaded = this.preloadedIcon
    return if (preloaded != null) {
        val cached: Drawable.ConstantState? = preloaded.constantState
        cached?.newDrawable(context.resources)?.mutate() ?: preloaded.mutate()
    } else {
        val userId =
            if (this.user.identifier == UserHandle.USER_ALL) {
                UserHandle.USER_SYSTEM
            } else {
                this.user.identifier
            }
        this.icon.loadDrawableAsUser(context, userId)
    }
}
