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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandColorOSStyle
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun StatusBarDynamicIslandContainer(
    chips: List<QuickActionChipModel.PopupChip>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    onIslandBoundsChanged: (android.graphics.Rect) -> Unit = {},
    onIslandActiveChanged: (Boolean) -> Unit = {},
    onIslandExpandedChanged: (Boolean) -> Unit = {},
    appHandleBounds: List<android.graphics.Rect> = emptyList(),
    modifier: Modifier = Modifier,
    sideEdge: DynamicIslandCutoutEdge? = null,
) {
    val cutoutSpec =
        if (sideEdge != null) {
            rememberDynamicIslandSideCutoutSpec(sideEdge)
        } else {
            rememberDynamicIslandCutoutSpec()
        }
    // The ColorOS island is drawn on a top camera only; a side-docked pill keeps our own look.
    val cos = sideEdge == null && rememberDynamicIslandColorOSStyle()
    var selectedChipId by remember { mutableStateOf<QuickActionChipId?>(null) }
    var popupAnchorChip by remember { mutableStateOf<QuickActionChipModel.PopupChip?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var knownChipIds by remember { mutableStateOf<List<QuickActionChipId>>(emptyList()) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    // Where the island was placed before it is shifted onto the camera; NaN until first placed.
    var naturalCenterX by remember { mutableFloatStateOf(Float.NaN) }
    val cameraCenterX = if (sideEdge == null) cutoutSpec.cutoutCenterX else null
    // Tablets show the desktop-windowing app handle at the top centre, where the island sits.
    // Step the island aside so the handle stays visible and tappable. Phones keep their layout.
    val avoidAppHandles =
        sideEdge == null && cameraCenterX == null && dynamicIslandAvoidsAppHandles()
    var islandBounds by remember { mutableStateOf(Rect.Zero) }
    val appHandleGapPx = with(LocalDensity.current) { AppHandleGap.toPx() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val appHandleShift by
        animateFloatAsState(
            targetValue =
                if (avoidAppHandles) {
                    appHandleClearance(islandBounds, appHandleBounds, appHandleGapPx, isRtl)
                } else {
                    0f
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "islandAppHandleShift",
        )

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

    val stackChips = remember(chips) { islandCardStack(chips) }
    // Chosen when the popup opens and kept until it closes, so dismissing cards never swaps the
    // stack for the single card while it is open.
    var stacked by remember { mutableStateOf(false) }

    LaunchedEffect(shownChip) {
        if (shownChip != null) {
            if (popupAnchorChip == null) {
                stacked =
                    cos &&
                        stackChips.size >= ISLAND_STACK_MIN_CARDS &&
                        stackChips.any { it.chipId == shownChip.chipId }
            }
            selectedChipId = shownChip.chipId
            popupAnchorChip = shownChip
            popupVisible = true
        } else if (popupAnchorChip != null) {
            popupVisible = false
            delay(if (cos) CosCardCloseMillis + 20L else 220L)
            popupAnchorChip = null
            stacked = false
        }
    }

    LaunchedEffect(chips) {
        onMediaControlPopupVisibilityChanged(
            chips.any { it.chipId == QuickActionChipId.MediaControl && it.isPopupShown }
        )
    }

    val hasChip = selectedChip != null
    LaunchedEffect(hasChip) { onIslandActiveChanged(hasChip) }

    val isExpanded = shownChip != null
    LaunchedEffect(isExpanded) { onIslandExpandedChanged(isExpanded) }

    LaunchedEffect(selectedChip) {
        if (selectedChip == null) {
            onIslandBoundsChanged(android.graphics.Rect())
        }
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
                .padding(
                    start =
                        if (sideEdge != null || cutoutSpec.anchor == DynamicIslandAnchor.START) {
                            0.dp
                        } else {
                            8.dp
                        },
                    end = if (sideEdge != null) 0.dp else 8.dp,
                )
                .then(
                    if (cameraCenterX != null) {
                        Modifier.onPlaced { naturalCenterX = it.boundsInWindow().center.x }
                            .graphicsLayer {
                                val natural = naturalCenterX
                                translationX =
                                    if (natural.isNaN()) 0f else cameraCenterX - natural
                            }
                    } else {
                        Modifier.offset(x = cutoutSpec.horizontalOffset)
                    }
                )
                .then(
                    if (avoidAppHandles) {
                        Modifier.onPlaced { islandBounds = it.boundsInWindow() }
                            .graphicsLayer { translationX = appHandleShift }
                    } else {
                        Modifier
                    }
                )
                .onGloballyPositioned { coordinates ->
                    if (selectedChip != null) {
                        val b = coordinates.boundsInWindow()
                        onIslandBoundsChanged(
                            android.graphics.Rect(
                                b.left.toInt(),
                                b.top.toInt(),
                                b.right.toInt(),
                                b.bottom.toInt(),
                            )
                        )
                    } else {
                        onIslandBoundsChanged(android.graphics.Rect())
                    }
                }
                // Docked to a side camera: turn the whole pill with its edge. The reported bounds
                // above are of the turned pill.
                .rotatedLayout(sideEdge?.quarterTurns ?: 0),
        contentAlignment = Alignment.Center,
    ) {
        if (cos) {
            // The shell outlives the selected chip so it can shrink back into the camera hole.
            val leaving = remember { arrayOfNulls<QuickActionChipModel.PopupChip>(1) }
            var present by remember { mutableStateOf(false) }
            if (selectedChip != null) {
                leaving[0] = selectedChip
                SideEffect { present = true }
            }
            val chip = selectedChip ?: leaving[0]
            if (chip != null && (selectedChip != null || present)) {
                val slopPx = with(LocalDensity.current) { 30.dp.toPx() }
                var dragged by remember { mutableFloatStateOf(0f) }
                val currentChip by rememberUpdatedState(chip)
                val currentSelectRelative by
                    rememberUpdatedState { direction: Int -> selectRelative(direction) }
                CosIslandShell(
                    key = chip.chipId,
                    visible = selectedChip != null && !popupVisible,
                    hole = cutoutSpec.holeSize,
                    moons = chips.size > 1,
                    tall = chip.isCallChip(),
                    onGone = {
                        present = false
                        leaving[0] = null
                    },
                    modifier =
                        Modifier.swipeUpToHide(chip.chipId, slopPx) { currentChip.hide() }
                            .pointerInput(chips.size, chip.chipId) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragEnd = {
                                        when {
                                            chips.size <= 1 && abs(dragged) >= slopPx ->
                                                currentChip.hide()
                                            dragged <= -slopPx -> currentSelectRelative(1)
                                            dragged >= slopPx -> currentSelectRelative(-1)
                                        }
                                        dragged = 0f
                                    },
                                    onDragCancel = { dragged = 0f },
                                    onHorizontalDrag = { change, amount ->
                                        dragged += amount
                                        if (chips.size > 1) change.consume()
                                    },
                                )
                            },
                ) {
                    StatusBarDynamicIslandChip(
                        viewModel = chip,
                        pageCount = 1,
                        cutoutSpec = cutoutSpec,
                        onChipBoundsChanged = { bounds -> anchorBounds = bounds },
                        onTap = { if (chip.isPopupShown) chip.hidePopup() else chip.showPopup() },
                        cos = true,
                    )
                }
            }
        } else if (selectedChip != null) AnimatedContent(
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
            // The gesture handlers below keep running across recompositions, so they read the
            // latest chip and pager state instead of what they were created with. Otherwise a
            // chip that updates (the battery level, say) is hidden through a stale model, and a
            // swipe pages through a list that has since changed.
            val currentChip by rememberUpdatedState(chip)
            val currentSelectRelative by rememberUpdatedState { direction: Int -> selectRelative(direction) }
            var horizontalDragPx by remember(chipId, chips.size) { mutableFloatStateOf(0f) }
            val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

            ProvideDynamicIslandPillTurns(sideEdge?.quarterTurns ?: 0) {
                StatusBarDynamicIslandChip(
                    viewModel = chip,
                    pageCount = chips.size,
                    pageIndex = chips.indexOfFirst { it.chipId == chipId }.coerceAtLeast(0),
                    onNextPage = { selectRelative(1) },
                    cutoutSpec = cutoutSpec,
                    onChipBoundsChanged = { bounds -> anchorBounds = bounds },
                    modifier =
                        Modifier.swipeUpToHide(chip.chipId, thresholdPx) { currentChip.hide() }
                            .pointerInput(chips.size, chip.chipId) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        chips.size <= 1 && abs(horizontalDragPx) >= thresholdPx ->
                                            currentChip.hide()
                                        horizontalDragPx <= -thresholdPx -> currentSelectRelative(1)
                                        horizontalDragPx >= thresholdPx -> currentSelectRelative(-1)
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
        }

        popupAnchorChip?.takeIf { cos || selectedChip != null }?.let { anchoredChip ->
            StatusBarPopup(
                viewModel = anchoredChip,
                isVisible = popupVisible,
                chipBoundsInScreen = anchorBounds,
                anchorCenterX = cameraCenterX,
                sideEdge = sideEdge,
                cos = cos,
                stackedChips = if (stacked) stackChips else null,
            )
        }
    }
}

/** Whether the island uses the ColorOS style; follows its setting live. */
@Composable
internal fun rememberDynamicIslandColorOSStyle(): Boolean {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandColorOSStyle() }
    val flow = remember(context) { observeDynamicIslandColorOSStyle(context) }
    val enabled by flow.collectAsState(initial = initial)
    return enabled
}

private const val TABLET_MIN_WIDTH_DP = 600
private val AppHandleGap = 8.dp

/** Whether the island steps aside for app handles: tablets only, phones keep their layout. */
@Composable
internal fun dynamicIslandAvoidsAppHandles(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP

/**
 * How far to move an island at [island] so it clears every app handle it overlaps, stepping
 * towards the end side and leaving [gapPx] between them. Zero when nothing overlaps.
 */
private fun appHandleClearance(
    island: Rect,
    appHandles: List<android.graphics.Rect>,
    gapPx: Float,
    isRtl: Boolean,
): Float {
    if (island.width <= 0f) return 0f
    var left = island.left
    repeat(appHandles.size) {
        val hit =
            appHandles.firstOrNull {
                !it.isEmpty &&
                    it.left < left + island.width &&
                    it.right > left &&
                    it.top < island.bottom &&
                    it.bottom > island.top
            } ?: return left - island.left
        left = if (isRtl) hit.left - gapPx - island.width else hit.right + gapPx
    }
    return left - island.left
}

/** Hides the island on a swipe up; a pull down is left to the shade. */
private fun Modifier.swipeUpToHide(key: Any, slopPx: Float, onHide: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var change = down
            while (change.pressed) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (-delta.y >= slopPx && -delta.y > abs(delta.x)) {
                    change.consume()
                    onHide()
                    break
                }
            }
        }
    }

internal fun QuickActionChipModel.PopupChip.isCallChip(): Boolean =
    (popupContent as? PopupContentModel.SystemEvent)?.kind == SystemEventKind.Call
