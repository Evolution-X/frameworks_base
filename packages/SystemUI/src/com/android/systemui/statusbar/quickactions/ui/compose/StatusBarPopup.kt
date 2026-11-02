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

import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.charging.ui.compose.ChargingPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Displays a popup in the status bar area. When [chipBoundsInScreen] is provided, the popup opens
 * from the chip's own top edge, over the status bar and around the camera, and grows out of the
 * chip's on-screen position and aspect ratio with an independent-axis squish animation. Without
 * it the popup is drawn below the status bar.
 *
 * With a [sideEdge] camera the island is docked to the left or right edge, so the popup opens
 * beside the pill instead: next to it on the inner side and centered on the camera, growing out
 * of the pill from that edge.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    sideEdge: DynamicIslandCutoutEdge? = null,
) {
    val density = Density(LocalContext.current)
    val yOffsetPx =
        with(density) {
            LocalContext.current.resources
                .getDimensionPixelSize(R.dimen.status_bar_height)
                .plus(4.dp.roundToPx())
        }
    val sideGapPx = with(density) { 4.dp.roundToPx() }
    val sideMarginPx = with(density) { 4.dp.roundToPx() }
    val edgePaddingPx = with(density) { PopupEdgePadding.roundToPx() }
    val cameraBottomPx =
        LocalView.current.rootWindowInsets?.displayCutout?.let { cutout ->
            cutout.boundingRectTop.takeUnless { it.isEmpty }?.bottom
        }
    // Only a popup under a top camera opens from the island's edge; side-docked ones sit beside it.
    val popupTopPx = if (sideEdge == null) chipBoundsInScreen?.top?.toInt() else null
    // How far the popup's content has to be pushed down so it starts below the camera.
    val cameraClearance =
        if (popupTopPx != null && cameraBottomPx != null) {
            with(density) { (cameraBottomPx - popupTopPx).coerceAtLeast(0).toDp() }
        } else {
            0.dp
        }
    val positionProvider =
        remember(
            yOffsetPx,
            sideEdge?.edge,
            chipBoundsInScreen,
            sideGapPx,
            sideMarginPx,
            popupTopPx,
            edgePaddingPx,
        ) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val chip = chipBoundsInScreen
                    if (sideEdge != null && chip != null) {
                        // The anchor is the turned pill, whose parent-reported bounds are not
                        // reliable, so place against the pill's own on-screen bounds. The popup
                        // window is parented to the full-screen island window, so these are
                        // its coordinates.
                        val x =
                            when (sideEdge.edge) {
                                DynamicIslandEdge.RIGHT ->
                                    chip.left.roundToInt() - sideGapPx - popupContentSize.width
                                else -> chip.right.roundToInt() + sideGapPx
                            }.coerceIn(0, maxX)
                        val maxY =
                            (windowSize.height - popupContentSize.height - sideMarginPx)
                                .coerceAtLeast(sideMarginPx)
                        val y =
                            (chip.center.y.roundToInt() - popupContentSize.height / 2)
                                .coerceIn(sideMarginPx, maxY)
                        return IntOffset(x, y)
                    }
                    val x =
                        (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
                    val y =
                        if (popupTopPx != null) {
                            popupTopPx - edgePaddingPx
                        } else {
                            anchorBounds.top + yOffsetPx
                        }
                    return IntOffset(x, y)
                }
            }
        }
    // Direction the popup slides in from. Top camera: down from the status bar. Side camera:
    // out from the edge the pill sits on.
    val slideInPx = with(density) { 24.dp.toPx() }
    val slideOutPx = with(density) { 16.dp.toPx() }
    val enterOffset =
        when (sideEdge?.edge) {
            null,
            DynamicIslandEdge.TOP -> Offset(0f, -24f)
            DynamicIslandEdge.LEFT -> Offset(-slideInPx, 0f)
            DynamicIslandEdge.RIGHT -> Offset(slideInPx, 0f)
        }
    val exitOffset =
        when (sideEdge?.edge) {
            null,
            DynamicIslandEdge.TOP -> Offset(0f, -16f)
            DynamicIslandEdge.LEFT -> Offset(-slideOutPx, 0f)
            DynamicIslandEdge.RIGHT -> Offset(slideOutPx, 0f)
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { viewModel.hidePopup() },
        properties =
            PopupProperties(
                // Keep Back dismissal without taking IME focus from the app below the popup.
                flags =
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        val popupView = LocalView.current
        // Let the popup window reach into the camera cutout so it can open from the island.
        DisposableEffect(popupView) {
            val root = popupView.rootView
            val lp = root.layoutParams as? WindowManager.LayoutParams
            if (lp != null && lp.layoutInDisplayCutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                root.context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, lp)
            }
            onDispose {}
        }
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
                } else {
                    if (sideEdge != null && sideEdge.edge != DynamicIslandEdge.TOP) {
                        // Grow out of the pill on the edge: pivot on the popup side facing
                        // it, level with the camera.
                        val pivotX =
                            ((chip.center.x - popup.left) / popup.width).coerceIn(0f, 1f)
                        val pivotY =
                            if (popup.height > 0f) {
                                ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 1f)
                            } else {
                                0.5f
                            }
                        TransformOrigin(pivotX, pivotY)
                    } else {
                    val pivotX =
                        ((chip.center.x - popup.left) / popup.width).coerceIn(0.05f, 0.95f)
                    val pivotY =
                        if (popup.height > 0f) {
                            ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 0.3f)
                        } else {
                            0f
                        }
                    TransformOrigin(pivotX, pivotY)
                    }
                }
            }
        }

        val initialScaleFromChip by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f || popup.height <= 0f) {
                    Offset(0.4f, 0.4f)
                } else {
                    Offset(
                        x = (chip.width / popup.width).coerceIn(0.2f, 1f),
                        y = (chip.height / popup.height).coerceIn(0.15f, 1f),
                    )
                }
            }
        }

        val scaleX = remember { Animatable(initialScaleFromChip.x) }
        val scaleY = remember { Animatable(initialScaleFromChip.y) }
        val alpha = remember { Animatable(0f) }
        val translationX = remember { Animatable(enterOffset.x) }
        val translationY = remember { Animatable(enterOffset.y) }
        val colorMode = rememberPopupColorMode()
        val skipMotionOnDismiss = colorMode == POPUP_COLOR_MODE_BLUR

        LaunchedEffect(isVisible) {
            if (isVisible) {
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                translationX.snapTo(enterOffset.x)
                translationY.snapTo(enterOffset.y)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = DynamicIslandMotion.OpenWidth,
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec = DynamicIslandMotion.OpenHeight,
                        )
                    }
                    launch {
                        translationX.animateTo(
                            targetValue = 0f,
                            animationSpec = DynamicIslandMotion.OpenPosition,
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = 0f,
                            animationSpec = DynamicIslandMotion.OpenPosition,
                        )
                    }
                    launch { alpha.animateTo(1f, animationSpec = tween(180)) }
                }
            } else {
                if (skipMotionOnDismiss) {
                    coroutineScope {
                        launch {
                            scaleX.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch {
                            scaleY.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch { alpha.animateTo(0f, animationSpec = tween(120)) }
                    }
                } else {
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = initialScaleFromChip.x,
                            animationSpec = DynamicIslandMotion.CloseWidth,
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = initialScaleFromChip.y,
                            animationSpec = DynamicIslandMotion.CloseHeight,
                        )
                    }
                    launch {
                        translationX.animateTo(
                            targetValue = exitOffset.x,
                            animationSpec = DynamicIslandMotion.ClosePosition,
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = exitOffset.y,
                            animationSpec = DynamicIslandMotion.ClosePosition,
                        )
                    }
                    launch { alpha.animateTo(0f, animationSpec = tween(160)) }
                    }
                }
            }
        }

        DisposableEffect(popupView) {
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                    info.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME
                    )
                }
            popupView.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
            onDispose {
                popupView.viewTreeObserver.removeOnComputeInternalInsetsListener(listener)
            }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(60)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            CompositionLocalProvider(LocalPopupCameraClearance provides cameraClearance) {
                Box(
                    modifier =
                        Modifier.padding(PopupEdgePadding)
                            .wrapContentSize()
                            .onGloballyPositioned { coordinates ->
                                popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                            }
                            .graphicsLayer {
                                this.scaleX = scaleX.value
                                this.scaleY = scaleY.value
                                this.alpha = alpha.value
                                this.translationX = translationX.value
                                this.translationY = translationY.value
                                this.transformOrigin = transformOrigin
                            }
                ) {
                    when (val popupContent = viewModel.popupContent) {
                        is PopupContentModel.Media -> {
                            val model = popupContent.model
                            val useWaveform = popupContent.useWaveform
                            val hasLyrics =
                                !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
                            if (hasLyrics) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MediaControlPopup(model = model, useWaveform = useWaveform)
                                    CompositionLocalProvider(LocalPopupCameraClearance provides 0.dp) {
                                        LyricsCard(model = model)
                                    }
                                }
                            } else {
                                MediaControlPopup(model = model, useWaveform = useWaveform)
                            }
                        }
                        is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
                        is PopupContentModel.LiveScore -> LiveScorePopup(model = popupContent.model)
                        is PopupContentModel.Flashlight ->
                            FlashlightPopup(
                                model = popupContent.model,
                                hapticsViewModelFactory = popupContent.hapticsViewModelFactory,
                            )
                        is PopupContentModel.Stopwatch -> StopwatchPopup(model = popupContent.model)
                        is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
                        is PopupContentModel.Charging -> ChargingPopup(model = popupContent.model)
                        PopupContentModel.None -> Unit
                    }
                }
            }
        }
    }
}

private val PopupEdgePadding = 8.dp

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
