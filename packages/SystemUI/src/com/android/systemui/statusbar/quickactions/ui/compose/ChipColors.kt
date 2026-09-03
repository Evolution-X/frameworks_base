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

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.android.systemui.res.R

/**
 * Model representing how the popup chip in the status bar should be colored, accounting for whether
 * the popup is shown/hidden and whether the chip is hovered.
 */
@Immutable
sealed interface ChipColors {
    /** The color for the background of the chip. */
    @Composable fun chipBackground(isSelected: Boolean, colorScheme: ColorScheme): Color

    /** The color for the text and default (non-hovered) icon on the chip. */
    @Composable fun chipContent(isSelected: Boolean, colorScheme: ColorScheme): Color

    /** The color to use for the chip outline. */
    @Composable fun chipOutline(isSelected: Boolean, colorScheme: ColorScheme): Color

    /** The color to use for the icon */
    @Composable
    fun icon(isSelected: Boolean, isHighlighted: Boolean, colorScheme: ColorScheme): Color

    /** The background color applied to the icon area when it is hovered/highlighted */
    @Composable fun iconBackground(isSelected: Boolean, colorScheme: ColorScheme): Color

    /** The default system themed chip colors, changing based on the popup state. */
    data object SystemTheme : ChipColors {
        @Composable
        override fun chipBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            if (isSelected) colorScheme.primary else colorScheme.surfaceDim

        @Composable
        override fun chipContent(isSelected: Boolean, colorScheme: ColorScheme): Color =
            if (isSelected) colorScheme.onPrimary else colorScheme.onSurface

        @Composable
        override fun chipOutline(isSelected: Boolean, colorScheme: ColorScheme): Color =
            colorScheme.outlineVariant

        @Composable
        override fun icon(
            isSelected: Boolean,
            isHighlighted: Boolean,
            colorScheme: ColorScheme,
        ): Color =
            if (isHighlighted) {
                chipBackground(isSelected = isSelected, colorScheme = colorScheme)
            } else {
                chipContent(isSelected = isSelected, colorScheme = colorScheme)
            }

        @Composable
        override fun iconBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            if (isSelected) colorScheme.onPrimary else colorScheme.onSurface
    }

    /** The colors for the AvControls (Privacy Indicator) Chip */
    data object AvControlsTheme : ChipColors {
        @Composable private fun privacyGreen() = colorResource(R.color.av_controls_chip_background)

        @Composable private fun privacyBlack() = colorResource(R.color.av_controls_chip_icon_fill)

        @Composable
        override fun chipBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            privacyGreen()

        @Composable
        override fun chipOutline(isSelected: Boolean, colorScheme: ColorScheme): Color =
            privacyGreen()

        @Composable
        override fun chipContent(isSelected: Boolean, colorScheme: ColorScheme): Color =
            privacyBlack()

        /** The background color applied to the icon area when it is hovered/highlighted */
        @Composable
        override fun iconBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            privacyBlack()

        @Composable
        override fun icon(
            isSelected: Boolean,
            isHighlighted: Boolean,
            colorScheme: ColorScheme,
        ): Color = privacyBlack()
    }

    /** A dark, pill-shaped treatment for the centered dynamic island. */
    data object DynamicIsland : ChipColors {
        private val baseBackground = Color(0xE6000000)
        private val expandedBackground = Color(0xF2000000)

        @Composable
        override fun chipBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            if (isSelected) expandedBackground else baseBackground

        @Composable
        override fun chipContent(isSelected: Boolean, colorScheme: ColorScheme): Color =
            Color.White

        @Composable
        override fun chipOutline(isSelected: Boolean, colorScheme: ColorScheme): Color =
            Color.White.copy(alpha = if (isSelected) 0.18f else 0.10f)

        @Composable
        override fun icon(
            isSelected: Boolean,
            isHighlighted: Boolean,
            colorScheme: ColorScheme,
        ): Color = chipContent(isSelected = isSelected, colorScheme = colorScheme)

        @Composable
        override fun iconBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            Color.White.copy(alpha = 0.14f)
    }

    /** Dynamic island styling for destructive or time-critical states like recording. */
    data object DynamicIslandAlert : ChipColors {
        private val accent = Color(0xFFFF5A5F)

        @Composable
        override fun chipBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            DynamicIsland.chipBackground(isSelected = isSelected, colorScheme = colorScheme)

        @Composable
        override fun chipContent(isSelected: Boolean, colorScheme: ColorScheme): Color = accent

        @Composable
        override fun chipOutline(isSelected: Boolean, colorScheme: ColorScheme): Color =
            accent.copy(alpha = if (isSelected) 0.28f else 0.18f)

        @Composable
        override fun icon(
            isSelected: Boolean,
            isHighlighted: Boolean,
            colorScheme: ColorScheme,
        ): Color = accent

        @Composable
        override fun iconBackground(isSelected: Boolean, colorScheme: ColorScheme): Color =
            accent.copy(alpha = 0.14f)
    }
}
