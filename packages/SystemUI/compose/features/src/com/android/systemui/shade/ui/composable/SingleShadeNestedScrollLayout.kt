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
package com.android.systemui.shade.ui.composable

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.effect.OffsetOverscrollEffect
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.compose.modifiers.thenIf
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import com.android.internal.jank.Cuj.CUJ_NOTIFICATION_SHADE_SCROLL_FLING
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.notifications.ui.composable.notificationScrimNestedScrollConnection
import com.android.systemui.qs.shared.style.LocalGlassClipBottom
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.session.ui.composable.sessionCoroutineScope
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrollState
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A custom layout for nested scrolling of notification content over the shade's headers.
 *
 * It measures and places the shade headers while accounting for display cutouts. It also offsets
 * and changes the height of the notification scrim during nested scrolling.
 *
 * @param modifier The modifier to be applied to this layout.
 * @param shadeSession The session for this shade instance.
 * @param viewModel The view model for this placeholder.
 * @param contentScrollState The scroll state of the scrollable notification scrim content.
 * @param contentOverScrollEffect Overscroll effect of the scrollable notification scrim content.
 * @param jankMonitor To monitor jank.
 * @param statusBarHeader The composable for the status bar header (clock, icons, etc). This header
 *   is always visible.
 * @param mediaAndQqsHeader The composable for the media and quick settings header. This header can
 *   be scrolled over.
 * @param scrollableScrim The composable for the scrollable notification scrim. The callback
 *   provides the height of the content.
 * @param cutoutInsetsProvider A provider for the display cutout insets.
 */
@Composable
fun ContentScope.SingleShadeNestedScrollLayout(
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
    viewModel: NotificationsPlaceholderViewModel,
    contentScrollState: ScrollState,
    scrollingContentOverscrollEffect: OffsetOverscrollEffect,
    shortContentOverscrollEffect: OffsetOverscrollEffect,
    jankMonitor: InteractionJankMonitor,
    statusBarHeader: @Composable () -> Unit,
    mediaAndQqsHeader: @Composable () -> Unit,
    scrollableScrim:
        @Composable
        (onContentHeightChanged: (Int) -> Unit, isScrimAtRest: () -> Boolean) -> Unit,
    cutoutInsetsProvider: () -> WindowInsets?,
) {
    val coroutineScope = shadeSession.sessionCoroutineScope(key = "SingleShadeNestedScrollLayout")
    val composeViewRoot = LocalView.current
    // The offset for the notifications scrim. Its upper bound is 0, and its lower bound is
    // calculated in minScrimOffset. The scrim is the same height as the screen minus the
    // height of the Shade Header, and at rest (scrimOffset = 0) its top bound is at maxScrimStartY.
    // When fully expanded (scrimOffset = minScrimOffset), its top bound is at minScrimStartY,
    // which is equal to the height of the Shade Header. Thus, when the scrim is fully expanded, the
    // entire height of the scrim is visible on screen.
    val scrimOffset = shadeSession.rememberSession(key = "ScrimOffset") { Animatable(0f) }
    // Height of the header part that can overlap with the nested scrolling scrim (QQS + Media).
    val overlappableHeaderHeight =
        shadeSession.rememberSession(key = "OverlappableHeaderHeight") { mutableIntStateOf(0) }
    // Scrim height at rest, when the collapsibleHeader (QQS + Media) is fully visible.
    val minScrimHeight =
        shadeSession.rememberSession(key = "MinScrimHeight") { mutableIntStateOf(0) }
    // The height in px of the contents of the scrollable content.
    val contentHeight =
        shadeSession.rememberSession(key = "ScrimContentHeight") { mutableIntStateOf(0) }

    // Some scenes or overlays that use this Composable may be using alwaysCompose=true which will
    // cause them to compose everything but not be visible. Because these side effects push UI state
    // upstream to observers which are shared between callers of this composable, invisible
    // components could pollute the shared state with incorrect values. The cleanest way to prevent
    // this is to remove these side effects when the content is not visible.
    if (isAlwaysComposedContentVisible()) {
        // whether the stack is moving due to a swipe or fling
        val isScrollInProgress by remember {
            derivedStateOf {
                contentScrollState.isScrollInProgress ||
                    scrollingContentOverscrollEffect.isInProgress ||
                    shortContentOverscrollEffect.isInProgress ||
                    scrimOffset.isRunning
            }
        }

        LaunchedEffect(isScrollInProgress) {
            if (isScrollInProgress) {
                jankMonitor.begin(composeViewRoot, CUJ_NOTIFICATION_SHADE_SCROLL_FLING)
                debugLog(viewModel) { "STACK scroll begins" }
            } else {
                debugLog(viewModel) { "STACK scroll ends" }
                jankMonitor.end(CUJ_NOTIFICATION_SHADE_SCROLL_FLING)
            }
        }

        LaunchedEffect(contentScrollState, scrimOffset) {
            snapshotFlow {
                    ShadeScrollState(
                        // we are not scrolled to the top unless the scroll position is zero,
                        // and the scrim is at its maximum offset
                        isScrolledToTop = scrimOffset.value >= 0f && contentScrollState.value == 0,
                        scrollPosition = contentScrollState.value,
                        maxScrollPosition = contentScrollState.maxValue,
                    )
                }
                .collect { shadeScrollState -> viewModel.setScrollState(shadeScrollState) }
        }
    }

    val isContentOverscrolledOnTop by
        remember(contentScrollState) {
            derivedStateOf {
                // When the scrim cannot moved further up, scrolling takes over to move the content.
                contentScrollState.value > 0
            }
        }

    /** Is the content taller than the scrim at rest (when QQS, Media can be fully visible). */
    fun isContentTallerThanScrimAtRest(): Boolean {
        return minScrimHeight.intValue < contentHeight.intValue
    }
    // If contentHeight drops below minimum visible scrim height while scrim is
    // expanded and IME is not showing, reset scrim offset.
    LaunchedEffectWithLifecycle(contentHeight, minScrimHeight, scrimOffset) {
        snapshotFlow { contentHeight.intValue < minScrimHeight.intValue && scrimOffset.value < 0f }
            .collect { shouldCollapse -> if (shouldCollapse) scrimOffset.animateTo(0f) }
    }
    var scrimHeight by remember { mutableIntStateOf(0) }
    val scrimTop = remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val headerGlassClip =
        remember(scrimOffset, contentScrollState) {
            {
                if (scrimOffset.value < 0f || contentScrollState.value > 0) scrimTop.floatValue
                else Float.POSITIVE_INFINITY
            }
        }
    Layout(
        modifier = modifier,
        contents =
            listOf(
                {
                    Box( // TODO(b/460517708) remove this modifier and route these swipes to scroll
                        // the notification scrim instead, when the list is scrolled to the top.
                        Modifier.thenIf(isContentOverscrolledOnTop) {
                            // Consume all drags on the StatusBar area to prevent Scene changes from
                            // swipes, while the list is scrolled to the top.
                            Modifier.pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, _ -> change.consume() },
                                    onDragStart = {},
                                    onDragEnd = {},
                                    onDragCancel = {},
                                )
                            }
                        }
                    ) {
                        statusBarHeader()
                    }
                },
                {
                    CompositionLocalProvider(LocalGlassClipBottom provides headerGlassClip) {
                        mediaAndQqsHeader()
                    }
                },
                {
                    val flingBehavior = ScrollableDefaults.flingBehavior()
                    Box(
                        Modifier.onPlaced { scrimTop.floatValue = it.positionInWindow().y }
                            // When part of the gesture was used to scroll Notifications, don't
                            // allow Scene changes by swipes.
                            .disableSwipesWhenScrolling()
                            // The glue between scrolling the content and offsetting the scrim.
                            .nestedScroll(
                                remember(scrimOffset, flingBehavior) {
                                    scrimNestedScrollConnection(
                                        currentOffset = { scrimOffset.value },
                                        updateOffset = {
                                            coroutineScope.launch { scrimOffset.snapTo(it) }
                                        },
                                        animateOffset = {
                                            coroutineScope.launch { scrimOffset.animateTo(it) }
                                        },
                                        collapsibleHeaderHeight = {
                                            overlappableHeaderHeight.intValue.toFloat()
                                        },
                                        // Disable preScroll for nested connection while expanding
                                        // notification
                                        canOverscrollContent = {
                                            isContentTallerThanScrimAtRest() &&
                                                !viewModel.isCurrentGestureExpandingNotification
                                        },
                                        flingBehavior = flingBehavior,
                                    )
                                }
                            )
                    ) {
                        // whether the scrim is at its default position (QQS+Media fully visible)
                        val isScrimAtRest by
                            remember(scrimOffset, contentScrollState) {
                                derivedStateOf {
                                    scrimOffset.value >= 0f && contentScrollState.value == 0
                                }
                            }
                        scrollableScrim({ contentHeight.intValue = it }, { isScrimAtRest })
                    }
                },
            ),
    ) { measurables, layoutConstraints ->
        check(measurables.size == 3)
        check(measurables[0].size == 1) { "headers compose only top-level composable" }
        check(measurables[1].size == 1) { "headers should compose only top-level composable" }
        check(measurables[2].size == 1) { "content should compose only top-level composable" }

        val cutoutInsets: WindowInsets? = cutoutInsetsProvider()
        // Don't propagate min constraints to children, to allow them to be smaller if they want to.
        val constraints = layoutConstraints.copyMaxDimensions()
        val constraintsWithCutout = applyCutout(constraints, cutoutInsets)
        val insetsLeft = cutoutInsets?.getLeft(this, layoutDirection) ?: 0
        val insetsTop = cutoutInsets?.getTop(this) ?: 0
        val alwaysVisibleHeader = measurables[0][0].measure(constraintsWithCutout)
        val overlappableHeader = measurables[1][0].measure(constraintsWithCutout)
        val totalScrimOffset =
            insetsTop +
                alwaysVisibleHeader.height +
                overlappableHeader.height +
                scrimOffset.value.roundToInt()

        // Reduce the Scrim's height so it only fills the visible space, when we offset it down.
        val layoutBottom = constraints.maxHeight

        if (isLookingAhead) {
            // Calculate height only in the lookahead pass (the idle state) to prevent the scrim
            // from resizing during animations.
            scrimHeight = constraints.constrainHeight(layoutBottom - totalScrimOffset)
        }

        val scrim =
            measurables[2][0].measure(
                constraints.copy(minHeight = scrimHeight, maxHeight = scrimHeight)
            )

        // Update the last height of the header.
        overlappableHeaderHeight.intValue = overlappableHeader.height
        minScrimHeight.intValue =
            constraints.constrainHeight(
                layoutBottom - insetsTop - alwaysVisibleHeader.height - overlappableHeader.height
            )
        layout(
            width = maxOf(alwaysVisibleHeader.width, overlappableHeader.width, scrim.width),
            height = layoutBottom,
        ) {
            alwaysVisibleHeader.place(insetsLeft, insetsTop)
            overlappableHeader.place(insetsLeft, insetsTop + alwaysVisibleHeader.height)
            scrim.placeWithLayer(0, totalScrimOffset)
        }
    }
}

/**
 * The scroll connection used to offset the scrim depending on the current offset and the scroll
 * state of the scrim content.
 *
 * @param currentOffset Lambda that returns the current scrim offset.
 * @param updateOffset Lambda to snap the scrim offset to a new value.
 * @param animateOffset Lambda to animate the scrim offset to a new value.
 * @param collapsibleHeaderHeight Lambda that returns the height of the collapsible header.
 * @param canOverscrollContent Lambda that returns whether the content can be overscrolled.
 * @param flingBehavior The fling behavior for the scrollable content.
 * @return The nested scroll connection for the scrim.
 */
private fun scrimNestedScrollConnection(
    currentOffset: () -> Float,
    updateOffset: (Float) -> Unit,
    animateOffset: (Float) -> Unit,
    collapsibleHeaderHeight: () -> Float,
    canOverscrollContent: () -> Boolean,
    flingBehavior: FlingBehavior,
): PriorityNestedScrollConnection {
    return notificationScrimNestedScrollConnection(
        scrimOffset = currentOffset,
        snapScrimOffset = updateOffset,
        animateScrimOffset = animateOffset,
        minScrimOffset = { -collapsibleHeaderHeight() },
        maxScrimOffset = 0f,
        canOverscrollContent = { canOverscrollContent() },
        flingBehavior = flingBehavior,
    )
}

/**
 * Applies the cutout insets to the constraints.
 *
 * @param constraints The constraints to be applied.
 * @param cutoutInsets The cutout insets to be applied.
 * @return The constraints with the cutout insets applied.
 */
private fun MeasureScope.applyCutout(
    constraints: Constraints,
    cutoutInsets: WindowInsets?,
): Constraints {
    return if (cutoutInsets == null) {
        constraints
    } else {
        val left = cutoutInsets.getLeft(this, layoutDirection)
        val top = cutoutInsets.getTop(this)
        val right = cutoutInsets.getRight(this, layoutDirection)
        val bottom = cutoutInsets.getBottom(this)
        constraints.offset(horizontal = -(left + right), vertical = -(top + bottom))
    }
}

private const val TAG = "FlexiNotifs"

private inline fun debugLog(viewModel: NotificationsPlaceholderViewModel, msg: () -> Any) {
    if (viewModel.isDebugLoggingEnabled) {
        Log.d(TAG, msg().toString())
    }
}
