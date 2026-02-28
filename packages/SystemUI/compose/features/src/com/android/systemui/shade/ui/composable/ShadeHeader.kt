/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.shade.ui.composable

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateElementFloatAsState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
import com.android.internal.policy.SystemBarUtils
import com.android.settingslib.Utils
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.DualShadeEducationElement
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.composable.ShadeHeader.Values.ClockScale
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithEstimate
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModelKairosComposeWrapper
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModelKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.composeWrapper
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIcons
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIconsLegacy
import com.android.systemui.util.composable.kairos.ActivatedKairosSpec
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

object ShadeHeader {
    object Elements {
        val ExpandedContent = ElementKey("ShadeHeaderExpandedContent")
        val CollapsedContentStart = ElementKey("ShadeHeaderCollapsedContentStart")
        val CollapsedContentEnd = ElementKey("ShadeHeaderCollapsedContentEnd")
        val PrivacyChip = ElementKey("PrivacyChip", contentPicker = LowestZIndexContentPicker)
        val Clock = ElementKey("ShadeHeaderClock", contentPicker = LowestZIndexContentPicker)
        val ShadeCarrierGroup = ElementKey("ShadeCarrierGroup")
    }

    object Values {
        val ClockScale = ValueKey("ShadeHeaderClockScale")
    }

    object Dimensions {
        @Deprecated(
            "Approximation of the collapsed shade header height, used in legacy shade transitions.",
            replaceWith = ReplaceWith("StatusBarHeight"),
        )
        val CollapsedHeightForTransitions = 48.dp
        val ExpandedHeight = 120.dp
        val ChipPaddingHorizontal = 6.dp
        val ChipPaddingVertical = 4.dp

        val StatusBarHeight: Dp
            @Composable
            get() {
                return with(LocalDensity.current) {
                    SystemBarUtils.getStatusBarHeight(LocalContext.current).toDp()
                }
            }
    }

    object Colors {
        val textColor: Color
            @Composable
            @ReadOnlyComposable
            get() = if (isSystemInDarkTheme()) Color.White else Color.Black

        val inverseTextColor: Color
            @Composable
            @ReadOnlyComposable
            get() = if (isSystemInDarkTheme()) Color.Black else Color.White
    }

    object TestTags {
        const val Root = "shade_header_root"
        const val BatteryTestTag = "battery_meter_composable_view"
        const val BatteryTestTagLegacy = "battery_percentage_view"
    }
}

/** The status bar that appears above the Shade scene on small screens. */
@Composable
fun ContentScope.CollapsedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    isSplitShade: Boolean,
    modifier: Modifier = Modifier,
) {
    val cutoutLocation = LocalDisplayCutout.current().location
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    val useExpandedTextFormat by
        remember(cutoutLocation) {
            derivedStateOf {
                cutoutLocation != CutoutLocation.CENTER ||
                    shouldUseExpandedFormat(layoutState.transitionState)
            }
        }

    val textColor = ShadeHeader.Colors.textColor

    // This layout assumes it is globally positioned at (0, 0) and is the same size as the screen.
    CutoutAwareShadeHeader(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        startContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = horizontalPadding),
            ) {
                Clock(onClick = viewModel::onClockClicked, textColor = textColor)
                VariableDayDate(
                    longerDateText = viewModel.longerDateText,
                    shorterDateText = viewModel.shorterDateText,
                    textColor = textColor,
                    modifier = Modifier.element(ShadeHeader.Elements.CollapsedContentStart),
                )
            }
        },
        endContent = {
            if (viewModel.isPrivacyChipVisible) {
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
                    PrivacyChip(
                        privacyList = viewModel.privacyItems,
                        onClick = viewModel::onPrivacyChipClicked,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.element(ShadeHeader.Elements.CollapsedContentEnd)
                            .padding(horizontal = horizontalPadding),
                ) {
                    if (isSplitShade) {
                        ShadeCarrierGroup(viewModel = viewModel)
                    }
                    ShadeHighlightChip(
                        onClick = {
                            if (isSplitShade) {
                                viewModel.onSystemIconChipClicked()
                            }
                        }
                    ) {
                        val paddingEnd =
                            with(LocalDensity.current) {
                                (if (NewStatusBarIcons.isEnabled) 3.sp else 6.sp).toDp()
                            }
                        StatusIcons(
                            viewModel = viewModel,
                            useExpandedFormat = useExpandedTextFormat,
                            foregroundColor = textColor.toArgb(),
                            backgroundColor = ShadeHeader.Colors.inverseTextColor.toArgb(),
                            modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                        )
                        BatteryInfo(
                            viewModel = viewModel,
                            showIcon = true,
                            useExpandedFormat = useExpandedTextFormat,
                            modifier = Modifier.padding(vertical = 8.dp),
                            textColor = textColor,
                        )
                    }
                }
            }
        },
    )
}

/** The status bar that appears above the Quick Settings scene on small screens. */
@Composable
fun ContentScope.ExpandedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val useExpandedFormat by remember {
        derivedStateOf { shouldUseExpandedFormat(layoutState.transitionState) }
    }

    val textColor = ShadeHeader.Colors.textColor

    Box(modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root)) {
        if (viewModel.isPrivacyChipVisible) {
            Box(modifier = Modifier.height(ShadeHeader.Dimensions.StatusBarHeight).fillMaxWidth()) {
                PrivacyChip(
                    privacyList = viewModel.privacyItems,
                    onClick = viewModel::onPrivacyChipClicked,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 16.dp, alignment = Alignment.Bottom),
            modifier =
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = ShadeHeader.Dimensions.ExpandedHeight),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Clock(
                    onClick = viewModel::onClockClicked,
                    modifier = Modifier.align(Alignment.CenterStart),
                    scale = 2.57f,
                    textColor = textColor,
                )
                Box(
                    modifier =
                        Modifier.element(ShadeHeader.Elements.ShadeCarrierGroup).fillMaxWidth()
                ) {
                    ShadeCarrierGroup(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.element(ShadeHeader.Elements.ExpandedContent).fillMaxWidth(),
            ) {
                VariableDayDate(
                    longerDateText = viewModel.longerDateText,
                    shorterDateText = viewModel.shorterDateText,
                    textColor = textColor,
                    modifier = Modifier.widthIn(max = 90.dp),
                )
                ShadeHighlightChip {
                    val paddingEnd =
                        with(LocalDensity.current) {
                            (if (NewStatusBarIcons.isEnabled) 3.sp else 6.sp).toDp()
                        }
                    StatusIcons(
                        viewModel = viewModel,
                        useExpandedFormat = useExpandedFormat,
                        foregroundColor = textColor.toArgb(),
                        backgroundColor = ShadeHeader.Colors.inverseTextColor.toArgb(),
                        modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                    )
                    BatteryInfo(
                        viewModel = viewModel,
                        showIcon = true,
                        useExpandedFormat = useExpandedFormat,
                        textColor = textColor,
                    )
                }
            }
        }
    }
}

/**
 * The status bar that appears above both the Notifications and Quick Settings shade overlays when
 * overlay shade is enabled.
 */
@Composable
fun ContentScope.OverlayShadeHeader(
    viewModel: ShadeHeaderViewModel,
    notificationsHighlight: ChipHighlightModel,
    quickSettingsHighlight: ChipHighlightModel,
    showClock: Boolean,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    // This layout assumes it is globally positioned at (0, 0) and is the same size as the screen.
    CutoutAwareShadeHeader(
        modifier = modifier,
        startContent = {
            Box(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                ShadeHighlightChip(
                    backgroundColor = notificationsHighlight.backgroundColor,
                    onHoveredBackgroundColor = notificationsHighlight.onHoveredBackgroundColor,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    onClick = viewModel::onNotificationIconChipClicked,
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .bouncy(
                                isEnabled = viewModel.animateNotificationsChipBounce,
                                onBoundsChange = { bounds ->
                                    viewModel.onDualShadeEducationElementBoundsChange(
                                        element = DualShadeEducationElement.Notifications,
                                        bounds = bounds,
                                    )
                                },
                            ),
                ) {
                    if (showClock) {
                        Clock(textColor = notificationsHighlight.foregroundColor)
                    }
                    VariableDayDate(
                        longerDateText = viewModel.longerDateText,
                        shorterDateText = viewModel.shorterDateText,
                        textColor = notificationsHighlight.foregroundColor,
                    )
                }
            }
        },
        endContent = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            ) {
                ShadeHighlightChip(
                    backgroundColor = quickSettingsHighlight.backgroundColor,
                    onHoveredBackgroundColor = quickSettingsHighlight.onHoveredBackgroundColor,
                    onClick = viewModel::onSystemIconChipClicked,
                    modifier =
                        Modifier.bouncy(
                            isEnabled = viewModel.animateSystemIconChipBounce,
                            onBoundsChange = { bounds ->
                                viewModel.onDualShadeEducationElementBoundsChange(
                                    element = DualShadeEducationElement.QuickSettings,
                                    bounds = bounds,
                                )
                            },
                        ),
                ) {
                    val paddingEnd =
                        with(LocalDensity.current) {
                            (if (NewStatusBarIcons.isEnabled) 3.sp else 6.sp).toDp()
                        }
                    val isHighlighted = quickSettingsHighlight is ChipHighlightModel.Strong
                    StatusIcons(
                        viewModel = viewModel,
                        useExpandedFormat = false,
                        modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                        foregroundColor = quickSettingsHighlight.foregroundColor.toArgb(),
                        backgroundColor = quickSettingsHighlight.backgroundColor.toArgb(),
                    )
                    BatteryInfo(
                        viewModel = viewModel,
                        showIcon = true,
                        useExpandedFormat = false,
                        isHighlighted = isHighlighted,
                    )
                }
                if (viewModel.isPrivacyChipVisible) {
                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
                        PrivacyChip(
                            privacyList = viewModel.privacyItems,
                            onClick = viewModel::onPrivacyChipClicked,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        },
    )
}

/** The header that appears at the top of the Quick Settings shade overlay. */
@Composable
fun QuickSettingsOverlayHeader(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        ShadeCarrierGroup(viewModel = viewModel)
        BatteryInfo(viewModel = viewModel, showIcon = false, useExpandedFormat = true)
    }
}

/*
 * Places startContent and endContent according to the location of the display cutout.
 * Assumes it is globally positioned at (0, 0) and the same size as the screen.
 */
@Composable
private fun CutoutAwareShadeHeader(
    modifier: Modifier = Modifier,
    startContent: @Composable () -> Unit,
    endContent: @Composable () -> Unit,
) {
    val cutoutProvider = LocalDisplayCutout.current
    val statusBarHeight = ShadeHeader.Dimensions.StatusBarHeight

    val cutoutData = remember(cutoutProvider) {
        val c = cutoutProvider()
        CutoutData(c.width, c.height, c.top, c.location)
    }

    Layout(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        contents = listOf(startContent, endContent),
    ) { measurables, constraints ->
        val cutout = cutoutProvider()

        val cutoutWidth = cutoutData.width
        val cutoutHeight = cutoutData.height
        val cutoutTop = cutoutData.top
        val cutoutLocation = cutoutData.location

        check(constraints.hasBoundedWidth)
        check(measurables.size == 2)
        check(measurables[0].size == 1)
        check(measurables[1].size == 1)

        val screenWidth = constraints.maxWidth
        val height = max(cutoutHeight + (cutoutTop * 2), statusBarHeight.roundToPx())
        val childConstraints = Constraints.fixed((screenWidth - cutoutWidth) / 2, height)

        val startMeasurable = measurables[0][0]
        val endMeasurable = measurables[1][0]

        val startPlaceable = startMeasurable.measure(childConstraints)
        val endPlaceable = endMeasurable.measure(childConstraints)

        layout(screenWidth, height) {
            when (cutoutLocation) {
                CutoutLocation.NONE,
                CutoutLocation.RIGHT -> {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width, y = 0)
                }
                CutoutLocation.CENTER -> {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidth, y = 0)
                }
                CutoutLocation.LEFT -> {
                    startPlaceable.placeRelative(x = cutoutWidth, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidth, y = 0)
                }
            }
        }
    }
}

@Composable
private fun ContentScope.Clock(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    scale: Float = 1f,
    textColor: Color? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val origin = remember(layoutDirection) { TransformOrigin(if (layoutDirection == LayoutDirection.Ltr) 0f else 1f, 0.5f) }

    ElementWithValues(key = ShadeHeader.Elements.Clock, modifier = modifier) {
        val animatedScale by animateElementFloatAsState(scale, ClockScale, canOverflow = false)

        content {
            AndroidView(
                factory = { context ->
                    Clock(
                            ContextThemeWrapper(
                                context,
                                R.style.Theme_SystemUI_QuickSettings_Header,
                            ),
                            null,
                        )
                        .apply {
                            isSingleLine = true
                            textDirection = View.TEXT_DIRECTION_LOCALE
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        }
                },
                update = { view -> textColor?.let { view.setTextColor(it.toArgb()) } },
                modifier =
                    modifier
                        .wrapContentWidth(unbounded = true)
                        // use graphicsLayer instead of Modifier.scale to anchor transform to the
                        // (start, top) corner
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                            transformOrigin = origin
                        }
                        .thenIf(onClick != null) { Modifier.clickable { onClick?.invoke() } },
            )
        }
    }
}

@Composable
private fun BatteryInfo(
    viewModel: ShadeHeaderViewModel,
    showIcon: Boolean,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (NewStatusBarIcons.isEnabled) {
        BatteryWithEstimate(
            viewModelFactory = viewModel.batteryViewModelFactory,
            isDarkProvider = { viewModel.isShadeAreaDark },
            showIcon = showIcon,
            showEstimate = useExpandedFormat,
            textColor = textColor,
            modifier = modifier.sysuiResTag(ShadeHeader.TestTags.BatteryTestTag),
        )
    } else {
        BatteryIconLegacy(
            createBatteryMeterViewController = viewModel.createBatteryMeterViewController,
            useExpandedFormat = useExpandedFormat,
            modifier = modifier.sysuiResTag(ShadeHeader.TestTags.BatteryTestTagLegacy),
            isHighlighted = isHighlighted,
        )
    }
}

@Composable
private fun BatteryIconLegacy(
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
) {
    NewStatusBarIcons.assertInLegacyMode()

    val localContext = LocalContext.current
    val themedContext = remember(localContext) { ContextThemeWrapper(localContext, R.style.Theme_SystemUI_QuickSettings_Header) }
    val primaryColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimary)
    val inverseColor =
        Utils.getColorAttrDefaultColor(themedContext, android.R.attr.textColorPrimaryInverse)

    val cutout = LocalDisplayCutout.current

    AndroidView(
        factory = { context ->
            val batteryIcon = BatteryMeterView(context, null)
            batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ON)

            // [BatteryMeterView.updateColors] is an old method that was built to distinguish
            // between dual-tone colors and single-tone. The current icon is only single-tone, so
            // the final [fg] is the only one we actually need
            batteryIcon.updateColors(primaryColor, inverseColor, primaryColor)

            val batteryMaterViewController =
                createBatteryMeterViewController(batteryIcon, StatusBarLocation.QS)
            batteryMaterViewController.init()
            batteryMaterViewController.ignoreTunerUpdates()

            batteryIcon
        },
        update = { batteryIcon ->
            val cutoutLocation = cutout().location
            batteryIcon.setPercentShowMode(
                if (useExpandedFormat || cutoutLocation != CutoutLocation.CENTER) {
                    BatteryMeterView.MODE_ESTIMATE
                } else {
                    BatteryMeterView.MODE_ON
                }
            )
            // TODO(b/397223606): Get the actual spec for this.
            batteryIcon.updateColors(
                primaryColor,
                inverseColor,
                if (isHighlighted) inverseColor else primaryColor,
            )
        },
        modifier = modifier,
    )
}

private data class CutoutData(val width: Int, val height: Int, val top: Int, val location: CutoutLocation)

@OptIn(ExperimentalKairosApi::class)
@Composable
private fun ShadeCarrierGroup(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    if (StatusBarMobileIconKairos.isEnabled) {
        ShadeCarrierGroupKairos(viewModel, modifier)
        return
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (subId in viewModel.mobileSubIds) {
            AndroidView(
                factory = { context ->
                    ModernShadeCarrierGroupMobileView.constructAndBind(
                            context = context,
                            logger = viewModel.mobileIconsViewModel.logger,
                            slot = "mobile_carrier_shade_group",
                            viewModel =
                                (viewModel.mobileIconsViewModel.viewModelForSub(
                                    subId,
                                    StatusBarLocation.SHADE_CARRIER_GROUP,
                                ) as ShadeCarrierGroupMobileIconViewModel),
                        )
                        .also { it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() } }
                }
            )
        }
    }
}

@ExperimentalKairosApi
@Composable
private fun ShadeCarrierGroupKairos(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        ActivatedKairosSpec(
            buildSpec = viewModel.mobileIconsViewModelKairos.get().composeWrapper(),
            kairosNetwork = viewModel.kairosNetwork,
            name = nameTag("ShadeCarrierGroupKairos"),
        ) { iconsViewModel: MobileIconsViewModelKairosComposeWrapper ->
            for ((subId, icon) in iconsViewModel.icons) {
                Spacer(modifier = Modifier.width(5.dp))
                val scope = rememberCoroutineScope()
                AndroidView(
                    factory = { context ->
                        ModernShadeCarrierGroupMobileView.constructAndBindKairos(
                                context = context,
                                logger = iconsViewModel.logger,
                                slot = "mobile_carrier_shade_group",
                                viewModel =
                                    ShadeCarrierGroupMobileIconViewModelKairos(
                                        icon,
                                        icon.iconInteractor,
                                    ),
                                scope = scope,
                                subscriptionId = subId,
                                location = StatusBarLocation.SHADE_CARRIER_GROUP,
                                kairosNetwork = viewModel.kairosNetwork,
                            )
                            .first
                            .also {
                                it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() }
                            }
                    }
                )
            }
        }
    }
}

@Composable
private fun ContentScope.StatusIcons(
    viewModel: ShadeHeaderViewModel,
    useExpandedFormat: Boolean,
    @ColorInt foregroundColor: Int,
    @ColorInt backgroundColor: Int,
    modifier: Modifier = Modifier,
) {
    val statusIconContext = LocalStatusIconContext.current
    val iconContainer = statusIconContext.iconContainer(contentKey)
    val iconManager = statusIconContext.iconManager(contentKey)
    val movableContent =
        remember(statusIconContext, iconManager) { statusIconContext.movableContent(iconManager) }

    // TODO(408001821): Add support for background color like [TintedIconManager.setTint].
    if (SystemStatusIconsInCompose.isEnabled) {
        SystemStatusIcons(
            viewModelFactory = viewModel.systemStatusIconsViewModelFactory,
            tint = Color(foregroundColor),
        )
    } else {
        val isTransitioning = layoutState.isTransitioningBetween(Scenes.Shade, Scenes.QuickSettings)

        SystemStatusIconsLegacy(
            iconContainer = iconContainer,
            iconManager = iconManager,
            statusBarIconController = viewModel.statusBarIconController,
            useExpandedFormat = useExpandedFormat,
            isTransitioning = isTransitioning,
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            isSingleCarrier = viewModel.isSingleCarrier,
            isMicCameraIndicationEnabled = viewModel.isMicCameraIndicationEnabled,
            isPrivacyChipEnabled = viewModel.isPrivacyChipVisible,
            isLocationIndicationEnabled = viewModel.isLocationIndicationEnabled,
            modifier = modifier,
            content = movableContent,
        )
    }
}

@Composable
private fun ContentScope.PrivacyChip(
    privacyList: List<PrivacyItem>,
    onClick: (OngoingPrivacyChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val view =
                OngoingPrivacyChip(context, null).also { privacyChip ->
                    privacyChip.privacyList = privacyList
                    privacyChip.setOnClickListener { onClick(privacyChip) }
                }
            view
        },
        update = { it.privacyList = privacyList },
        modifier = modifier.element(ShadeHeader.Elements.PrivacyChip),
    )
}

/** Modifies the given [Modifier] such that it shows a looping vertical bounce animation. */
@Composable
private fun Modifier.bouncy(
    isEnabled: Boolean,
    onBoundsChange: (bounds: IntRect) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(isEnabled) {
        if (isEnabled) {
            while (true) {
                // Lifts the element up to the first peak.
                animatable.animateTo(
                    targetValue = with(density) { -(10.dp).toPx() },
                    animationSpec =
                        tween(
                            durationMillis = 200,
                            easing = CubicBezierEasing(0.15f, 0f, 0.23f, 1f),
                        ),
                )
                // Drops the element back to the ground from the first peak.
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        tween(
                            durationMillis = 167,
                            easing = CubicBezierEasing(0.74f, 0f, 0.22f, 1f),
                        ),
                )
                // Lifts the element up again, this time to the second, smaller peak.
                animatable.animateTo(
                    targetValue = with(density) { -(5.dp).toPx() },
                    animationSpec =
                        tween(
                            durationMillis = 150,
                            easing = CubicBezierEasing(0.62f, 0f, 0.35f, 1f),
                        ),
                )
                // Drops the element back to the ground from the second peak.
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        tween(
                            durationMillis = 117,
                            easing = CubicBezierEasing(0.67f, 0f, 0.51f, 1f),
                        ),
                )
                // Wait for a moment before repeating it.
                delay(1000)
            }
        } else {
            animatable.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 500))
        }
    }

    return this.thenIf(isEnabled) {
        Modifier.offset { IntOffset(x = 0, y = animatable.value.roundToInt()) }
            .onGloballyPositioned { coordinates ->
                val offset = coordinates.positionInWindow()
                onBoundsChange(
                    IntRect(
                        offset = IntOffset(x = offset.x.roundToInt(), y = offset.y.roundToInt()),
                        size = coordinates.size,
                    )
                )
            }
    }
}

private fun shouldUseExpandedFormat(state: TransitionState): Boolean {
    return when (state) {
        is TransitionState.Idle -> state.currentScene == Scenes.QuickSettings
        is TransitionState.Transition -> {
            (state.isTransitioning(to = Scenes.QuickSettings) && state.progress >= 0.5) ||
                (state.isTransitioning(from = Scenes.QuickSettings) && state.progress <= 0.5)
        }
    }
}
