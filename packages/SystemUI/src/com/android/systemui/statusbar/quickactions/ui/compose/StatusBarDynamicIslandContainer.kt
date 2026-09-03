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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun StatusBarDynamicIslandContainer(
    chips: List<QuickActionChipModel.PopupChip>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cutoutSpec = rememberDynamicIslandCutoutSpec()
    var selectedChipId by remember { mutableStateOf<QuickActionChipId?>(null) }
    var popupAnchorChip by remember { mutableStateOf<QuickActionChipModel.PopupChip?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var knownChipIds by remember { mutableStateOf<List<QuickActionChipId>>(emptyList()) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(chips) {
        val currentChipIds = chips.map { it.chipId }
        val newestChipId =
            if (knownChipIds.isEmpty()) {
                null
            } else {
                currentChipIds.lastOrNull { it !in knownChipIds }
            }
        selectedChipId =
            when {
                newestChipId != null -> newestChipId
                chips.any { it.chipId == selectedChipId } -> selectedChipId
                else -> chips.firstOrNull()?.chipId
            }
        knownChipIds = currentChipIds
    }

    val selectedIndex = chips.indexOfFirst { it.chipId == selectedChipId }.coerceAtLeast(0)
    val selectedChip = chips.getOrNull(selectedIndex)
    val shownChip = chips.firstOrNull { it.isPopupShown }

    LaunchedEffect(shownChip) {
        if (shownChip != null) {
            selectedChipId = shownChip.chipId
            popupAnchorChip = shownChip
            popupVisible = true
        } else if (popupAnchorChip != null) {
            popupVisible = false
            delay(220)
            popupAnchorChip = null
        }
    }

    LaunchedEffect(chips) {
        onMediaControlPopupVisibilityChanged(
            chips.any { it.chipId == QuickActionChipId.MediaControl && it.isPopupShown }
        )
    }

    if (selectedChip == null) {
        return
    }

    fun selectRelative(direction: Int) {
        if (chips.size <= 1) return
        val newIndex = (selectedIndex + direction).mod(chips.size)
        val newChip = chips[newIndex]
        selectedChipId = newChip.chipId
        if (popupVisible) {
            newChip.showPopup()
        }
    }

    Box(
        modifier =
            modifier
                .padding(horizontal = 8.dp)
                .offset(x = cutoutSpec.horizontalOffset),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = selectedChip.chipId,
            transitionSpec = {
                if (targetState == initialState) {
                    fadeIn(animationSpec = tween(150)) togetherWith
                        fadeOut(animationSpec = tween(150))
                } else {
                    val slideDirection =
                        if (
                            chips.indexOfFirst { it.chipId == targetState } >
                                chips.indexOfFirst { it.chipId == initialState }
                        ) {
                            1
                        } else {
                            -1
                        }
                    (slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { fullWidth -> slideDirection * fullWidth / 2 },
                    ) + fadeIn(animationSpec = tween(180))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(200),
                            targetOffsetX = { fullWidth -> -slideDirection * fullWidth / 3 },
                        ) + fadeOut(animationSpec = tween(140)))
                }
            },
            label = "dynamic_island_chip",
        ) { chipId ->
            val chip = chips.firstOrNull { it.chipId == chipId } ?: return@AnimatedContent
            var horizontalDragPx by remember(chipId, chips.size) { mutableFloatStateOf(0f) }
            val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

            StatusBarDynamicIslandChip(
                viewModel = chip,
                pageCount = chips.size,
                cutoutSpec = cutoutSpec,
                onChipBoundsChanged = { bounds -> anchorBounds = bounds },
                modifier =
                    Modifier.pointerInput(chips.size, chip.chipId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    horizontalDragPx <= -thresholdPx -> selectRelative(1)
                                    horizontalDragPx >= thresholdPx -> selectRelative(-1)
                                }
                                horizontalDragPx = 0f
                            },
                            onDragCancel = { horizontalDragPx = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                horizontalDragPx += dragAmount
                                if (chips.size > 1 && abs(horizontalDragPx) > 8f) {
                                    change.consume()
                                }
                            },
                        )
                    },
                onTap = {
                    if (chip.isPopupShown) chip.hidePopup() else chip.showPopup()
                },
            )
        }

        popupAnchorChip?.let { anchoredChip ->
            StatusBarPopup(
                viewModel = anchoredChip,
                isVisible = popupVisible,
                chipBoundsInScreen = anchorBounds,
            )
        }
    }
}
