/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.clock.ClockModernization
import com.android.systemui.clock.ui.composable.Clock
import com.android.systemui.clock.ui.composable.ClockLegacy
import com.android.systemui.clock.ui.viewmodel.AmPmStyle
import com.android.systemui.clock.ui.viewmodel.ClockViewModel
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ChipHighlightModel
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.ShadeHighlightChip
import com.android.systemui.shade.ui.composable.VariableDayDate
import com.android.systemui.statusbar.chips.ui.compose.OngoingActivityChips
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.battery.ui.composable.UnifiedBattery
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.quickactions.ui.compose.QuickActionChipsContainer
import com.android.systemui.statusbar.shared.ui.compose.StatusBarIcon
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIcons
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIconsLegacy
import com.android.systemui.statusbar.systemstatusicons.ui.compose.movableSystemStatusIconsLegacyAndroidView

object DesktopStatusBar {
    object Dimensions {
        val PaddingHorizontal: Dp
            @Composable @ReadOnlyComposable get() = OverlayShade.Dimensions.PanelPaddingHorizontal

        val ElementSpacing = 12.dp
        val ChipInternalSpacing = 6.dp
        val ChipHeight = 24.dp
    }
}

// TODO(b/343358983): Add support for color themes in this composable.
/** Top level composable responsible for all UI shown for the Status Bar for DesktopMode. */
@Composable
fun DesktopStatusBar(
    viewModel: HomeStatusBarViewModel,
    clockViewModelFactory: ClockViewModel.Factory,
    statusBarIconController: StatusBarIconController,
    iconManagerFactory: TintedIconManager.Factory,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    modifier: Modifier = Modifier,
) {
    // TODO(433589833): Update padding values to match UX specs.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            // TODO(b/478352392): Move this padding to clickable Row below (after applying the
            // clickable modifier), so it's included in the click target. Ref: b/494287030.
            modifier.fillMaxSize().padding(start = DesktopStatusBar.Dimensions.PaddingHorizontal),
    ) {
        WithAdaptiveTint(
            highlightModel = ChipHighlightModel.Transparent,
            isDarkProvider = { bounds -> viewModel.areaDark.isDarkTheme(bounds) },
        ) { tint ->
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(
                        DesktopStatusBar.Dimensions.ElementSpacing,
                        Alignment.Start,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = viewModel::onClockClicked),
            ) {
                val clockViewModel =
                    rememberViewModel("HomeStatusBar.Clock") {
                        clockViewModelFactory.create(AmPmStyle.Gone)
                    }
                val textStyle = MaterialTheme.typography.labelLargeEmphasized

                if (ClockModernization.isEnabled) {
                    Clock(clockViewModel = clockViewModel, textColor = tint, textStyle = textStyle)
                } else {
                    ClockLegacy(textColor = tint, onClick = null, textStyle = textStyle)
                }

                VariableDayDate(
                    longerDateText = clockViewModel.longerDateText,
                    shorterDateText = clockViewModel.shorterDateText,
                    textColor = tint,
                    textStyle = textStyle,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f).clickable(onClick = viewModel::onSpacerClicked))

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(DesktopStatusBar.Dimensions.ElementSpacing, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (viewModel.isSignOutButtonVisible) {
                SignOutButton(onSignOut = viewModel::onSignOut)
            }

            val chipsVisibilityModel = viewModel.ongoingActivityChips
            if (chipsVisibilityModel.areChipsAllowed) {
                OngoingActivityChips(
                    chips = chipsVisibilityModel.chips,
                    iconViewStore = iconViewStore,
                    onChipBoundsChanged = viewModel::onChipBoundsChanged,
                    modifier = Modifier.sysUiResTagContainer(),
                )
            }

                QuickActionChipsContainer(
                    chips = viewModel.popupChips,
                    isDarkProvider = viewModel.areaDark::isDarkTheme,
                )

            // The wrapper row prevents application of ElementSpacing between the chips. Instead,
            // they will internally allocate the same padding space to their touch targets.
            // TODO(b/489444201): Remove this wrapper and `spacedBy` above once all the elements in
            //  this row have expanded touch targets.
            Row {
                NotificationsChip(viewModel = viewModel)

                QuickSettingsChip(
                    viewModel = viewModel,
                    statusBarIconController = statusBarIconController,
                    iconManagerFactory = iconManagerFactory,
                )
            }
        }
    }
}

@Composable
private fun NotificationsChip(viewModel: HomeStatusBarViewModel, modifier: Modifier = Modifier) {
    val chipHighlightModel =
        if (viewModel.isNotificationsChipHighlighted) {
            ChipHighlightModel.Strong
        } else {
            ChipHighlightModel.Transparent
        }

    WithAdaptiveTint(
        highlightModel = chipHighlightModel,
        isDarkProvider = { bounds -> viewModel.areaDark.isDarkTheme(bounds) },
    ) { tint ->
        val (hoverColor, rippleColor) =
            when (chipHighlightModel) {
                is ChipHighlightModel.Transparent ->
                    tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_HOVER) to
                        tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_RIPPLE)
                else -> chipHighlightModel.hoverBackgroundColor to chipHighlightModel.rippleColor
            }
        val contentDescription =
            LocalContext.current.getString(R.string.accessibility_notification_bell)

        ShadeHighlightChip(
            modifier =
                modifier
                    .height(DesktopStatusBar.Dimensions.ChipHeight)
                    .widthIn(min = DesktopStatusBar.Dimensions.ChipHeight)
                    .semantics { this.contentDescription = contentDescription }
                    .sysuiResTag("notificationIcons"),
            onClick = viewModel::onNotificationIconChipClicked,
            clickTargetModifier =
                Modifier.fillMaxHeight()
                    .padding(
                        // Divide by two, since the spacing between this chip and the next one is
                        // divided among the corresponding touch targets.
                        end = DesktopStatusBar.Dimensions.ElementSpacing / 2
                    ),
            backgroundColor = chipHighlightModel.backgroundColor,
            hoverBackgroundColor = hoverColor,
            rippleColor = rippleColor,
            horizontalArrangement =
                Arrangement.spacedBy(
                    DesktopStatusBar.Dimensions.ChipInternalSpacing,
                    Alignment.CenterHorizontally,
                ),
            includePadding = false,
            isClickable = viewModel.isNotificationsChipClickable,
        ) {
            if (viewModel.hasStatusBarNotifications) {
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    StatusBarIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_notification_bell_unread_base,
                                contentDescription = null,
                            ),
                        tint = tint,
                    )
                    StatusBarIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_notification_bell_unread_dot,
                                contentDescription = null,
                            ),
                        tint =
                            if (chipHighlightModel is ChipHighlightModel.Transparent)
                                Color.Unspecified
                            else tint,
                    )
                }
            } else {
                StatusBarIcon(
                    icon =
                        Icon.Resource(
                            resId = R.drawable.ic_notification_bell,
                            contentDescription = null,
                        ),
                    tint = tint,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun QuickSettingsChip(
    viewModel: HomeStatusBarViewModel,
    statusBarIconController: StatusBarIconController,
    iconManagerFactory: TintedIconManager.Factory,
    modifier: Modifier = Modifier,
) {
    val chipHighlightModel =
        if (viewModel.isQuickSettingsChipHighlighted) {
            ChipHighlightModel.Strong
        } else {
            ChipHighlightModel.Transparent
        }

    WithAdaptiveTint(
        highlightModel = chipHighlightModel,
        isDarkProvider = viewModel.areaDark::isDarkTheme,
    ) { tint ->
        val (hoverColor, rippleColor) =
            when (chipHighlightModel) {
                is ChipHighlightModel.Transparent ->
                    tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_HOVER) to
                        tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_RIPPLE)
                else -> chipHighlightModel.hoverBackgroundColor to chipHighlightModel.rippleColor
            }

        ShadeHighlightChip(
            modifier =
                modifier.height(DesktopStatusBar.Dimensions.ChipHeight).sysuiResTag("statusIcons"),
            onClick = viewModel::onQuickSettingsChipClicked,
            clickTargetModifier =
                Modifier.fillMaxHeight()
                    .padding(
                        // Divide by two, since the spacing between this chip and the previous one
                        // is divided among the corresponding touch targets.
                        start = DesktopStatusBar.Dimensions.ElementSpacing / 2,
                        end = DesktopStatusBar.Dimensions.PaddingHorizontal,
                    ),
            backgroundColor = chipHighlightModel.backgroundColor,
            hoverBackgroundColor = hoverColor,
            rippleColor = rippleColor,
            horizontalArrangement =
                Arrangement.spacedBy(
                    DesktopStatusBar.Dimensions.ChipInternalSpacing,
                    Alignment.Start,
                ),
            isClickable = viewModel.isQuickSettingsChipClickable,
        ) {
            if (SystemStatusIconsInCompose.isEnabled) {
                SystemStatusIcons(
                    viewModelFactory = viewModel.systemStatusIconsViewModelFactory,
                    tint = tint,
                    modifier = modifier,
                    systemStatusIconBlocklistInteractor =
                        viewModel.systemStatusIconBlockListInteractor,
                )
            } else {
                val localContext = LocalContext.current
                val iconContainer =
                    remember(localContext, iconManagerFactory) {
                        StatusIconContainer(
                            ContextThemeWrapper(localContext, R.style.Theme_SystemUI),
                            null,
                        )
                    }
                val iconManager =
                    remember(iconContainer) {
                        iconManagerFactory.create(iconContainer, StatusBarLocation.HOME)
                    }

                val movableContent =
                    remember(iconManager) { movableSystemStatusIconsLegacyAndroidView(iconManager) }

                SystemStatusIconsLegacy(
                    statusBarIconController = statusBarIconController,
                    iconContainer = iconContainer,
                    iconManager = iconManager,
                    useExpandedFormat = true,
                    foregroundColor = tint.toArgb(),
                    backgroundColor = ChipHighlightModel.Strong.backgroundColor.toArgb(),
                    isSingleCarrier = true,
                    isMicCameraIndicationEnabled = true,
                    isPrivacyChipEnabled = true,
                    isTransitioning = false,
                    isLocationIndicationEnabled = true,
                    content = movableContent,
                )
            }

            val batteryHeight =
                with(LocalDensity.current) {
                    BatteryViewModel.getStatusBarBatteryHeight(LocalContext.current).toDp()
                }

            val isDarkTheme = isSystemInDarkTheme()
            val batteryDarkProvider: IsAreaDark =
                when (chipHighlightModel) {
                    ChipHighlightModel.Strong -> IsAreaDark { !isDarkTheme }
                    ChipHighlightModel.Transparent -> viewModel.areaDark
                    ChipHighlightModel.Weak -> viewModel.areaDark
                }
            UnifiedBattery(
                viewModel =
                    rememberViewModel("DesktopStatusBar.BatteryViewModel") {
                        viewModel.unifiedBatteryViewModel.create()
                    },
                isDarkProvider = { batteryDarkProvider },
                modifier = Modifier.height(batteryHeight),
            )
        }
    }
}

@Composable
private fun SignOutButton(onSignOut: () -> Unit) {
    Button(
        onClick = onSignOut,
        contentPadding = PaddingValues(horizontal = DesktopStatusBar.Dimensions.ElementSpacing / 2),
        modifier = Modifier.heightIn(min = DesktopStatusBar.Dimensions.ChipHeight),
    ) {
        Icon(
            icon =
                IconModel.Resource(
                    com.android.internal.R.drawable.ic_logout,
                    contentDescription = null,
                ),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(com.android.internal.R.string.global_action_logout),
            modifier = Modifier.wrapContentHeight(unbounded = true),
        )
    }
}
