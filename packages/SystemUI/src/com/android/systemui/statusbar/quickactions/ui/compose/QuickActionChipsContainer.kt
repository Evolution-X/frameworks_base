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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.shade.ui.composable.ChipHighlightModel
import com.android.systemui.shade.ui.composable.ShadeHighlightChip
import com.android.systemui.statusbar.chips.ui.viewmodel.rememberChronometerState
import com.android.systemui.statusbar.pipeline.shared.ui.composable.DesktopStatusBar
import com.android.systemui.statusbar.pipeline.shared.ui.composable.WithAdaptiveTint
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.shared.ui.compose.StatusBarIcon

/** Container view that holds all right hand side chips in the status bar. */
@Composable
fun QuickActionChipsContainer(
    chips: List<QuickActionChipModel>,
    isDarkProvider: (Rect) -> Boolean,
    modifier: Modifier = Modifier,
) {
    //    TODO(b/385353140): Add padding and spacing for this container according to UX specs.
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopStatusBar.Dimensions.ElementSpacing),
        ) {
            chips.forEach { chip ->
                when (chip) {
                    is QuickActionChipModel.LaunchChip -> {
                        Launch(chip = chip, isDarkProvider = isDarkProvider)
                    }
                    is QuickActionChipModel.PopupChip -> {
                        Popup(chip = chip)
                    }
                    is QuickActionChipModel.Hidden -> {}
                }
            }
        }
    }
}

@Composable
private fun Launch(chip: QuickActionChipModel.LaunchChip, isDarkProvider: (Rect) -> Boolean) {
    val context = LocalContext.current
    val chipHighlightModel =
        if (chip.isSelected) {
            ChipHighlightModel.Strong
        } else {
            ChipHighlightModel.Transparent
        }

    WithAdaptiveTint(highlightModel = chipHighlightModel, isDarkProvider = isDarkProvider) { tint ->
        val (hoverColor, rippleColor) =
            when (chipHighlightModel) {
                is ChipHighlightModel.Transparent -> {
                    tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_HOVER) to
                        tint.copy(alpha = ChipHighlightModel.Companion.Alpha.TRANSPARENT_RIPPLE)
                }
                else -> {
                    chipHighlightModel.hoverBackgroundColor to chipHighlightModel.rippleColor
                }
            }

        ShadeHighlightChip(
            modifier =
                Modifier.height(DesktopStatusBar.Dimensions.ChipHeight)
                    .widthIn(min = DesktopStatusBar.Dimensions.ChipHeight)
                    .contentDescription(chip.contentDescription)
                    .sysuiResTag(chip.chipId.value),
            onClick = { chip.onClick(context) },
            clickTargetModifier = Modifier.fillMaxHeight(),
            backgroundColor = chipHighlightModel.backgroundColor,
            hoverBackgroundColor = hoverColor,
            rippleColor = rippleColor,
            horizontalArrangement =
                Arrangement.spacedBy(
                    DesktopStatusBar.Dimensions.ChipInternalSpacing,
                    Alignment.CenterHorizontally,
                ),
            includePadding = false,
        ) {
            when (val content = chip.chipContent) {
                is ChipContent.IconOnly ->
                    StatusBarIcon(
                        icon = content.icon,
                        tint = tint,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                is ChipContent.Text ->
                    Text(text = content.text, color = tint, style = chipTextStyle)
                is ChipContent.Timer -> {
                    val timerState =
                        rememberChronometerState(
                            chronometer = content.chronometer,
                            timeSource = content.timeSource,
                        )
                    timerState.currentTimeText?.let {
                        Text(text = it, color = tint, style = chipTextStyle)
                    }
                }
            }
        }
    }
}

private val chipTextStyle
    @Composable
    get() =
        LocalTextStyle.current.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle =
                LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
        )

@Composable
private fun Popup(chip: QuickActionChipModel.PopupChip, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val boundsCache = remember { RectF() }

    val positionedModifier =
        modifier.onGloballyPositioned { coordinates ->
            val boundsInWindow = coordinates.boundsInWindow()
            boundsCache.set(
                boundsInWindow.left,
                boundsInWindow.top,
                boundsInWindow.right,
                boundsInWindow.bottom,
            )
        }

    val isMediaChip = chip.chipId == QuickActionChipId.MediaControl
    if (chip.chipId == QuickActionChipId.ShareScreenPrivacyIndicator) {
        QuickActionChip(
            modifier = positionedModifier,
            isSelected = chip.isPopupShown,
            chipContent = chip.chipContent,
            icons = chip.icons,
            colors = chip.colors,
            contentDescription = chip.contentDescription,
            onClick = { chip.togglePopup(context, RectF(boundsCache)) },
            cornerRadius = 16.dp,
            horizontalPadding = PaddingValues(horizontal = 8.dp),
            isMediaChip = isMediaChip,
        )
    } else {
        QuickActionChip(
            modifier = positionedModifier,
            isSelected = chip.isPopupShown,
            chipContent = chip.chipContent,
            icons = chip.icons,
            colors = chip.colors,
            contentDescription = chip.contentDescription,
            onClick = { chip.togglePopup(context, RectF(boundsCache)) },
            isMediaChip = isMediaChip,
        )
    }
}

@Composable
private fun Modifier.contentDescription(description: ContentDescription?): Modifier {
    val resolvedDescription = description?.load() ?: return this
    return this.semantics { contentDescription = resolvedDescription }
}
