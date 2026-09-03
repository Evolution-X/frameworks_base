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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.quickactions.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.quickactions.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Displays a popup in the status bar area. The offset is calculated to draw the popup below the
 * status bar. When [chipBoundsInScreen] is provided, the popup grows out of the chip's on-screen
 * position and aspect ratio with an independent-axis squish animation, rather than a generic
 * uniform scale-in.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
) {
    val density = Density(LocalContext.current)
    Popup(
        alignment = Alignment.TopCenter,
        offset =
            IntOffset(
                x = 0,
                y =
                    with(density) {
                        LocalContext.current.resources
                            .getDimensionPixelSize(R.dimen.status_bar_height)
                            .plus(4.dp.roundToPx())
                    },
            ),
        onDismissRequest = { viewModel.hidePopup() },
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        val popupView = LocalView.current
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
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
        val translationY = remember { Animatable(-24f) }
        val colorMode = rememberPopupColorMode()
        val skipMotionOnDismiss = colorMode == POPUP_COLOR_MODE_BLUR

        LaunchedEffect(isVisible, popupBoundsInScreen != null) {
            if (isVisible && popupBoundsInScreen != null) {
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                translationY.snapTo(-24f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.6f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.65f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.7f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch { alpha.animateTo(1f, animationSpec = tween(180)) }
                }
            } else if (!isVisible) {
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
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = initialScaleFromChip.y,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = -16f,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
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
            Box(
                modifier =
                    Modifier.padding(8.dp)
                        .wrapContentSize()
                        .onGloballyPositioned { coordinates ->
                            popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                        }
                        .graphicsLayer {
                            this.scaleX = scaleX.value
                            this.scaleY = scaleY.value
                            this.alpha = alpha.value
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
                                LyricsCard(model = model)
                            }
                        } else {
                            MediaControlPopup(model = model, useWaveform = useWaveform)
                        }
                    }
                    is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
                    is PopupContentModel.LiveScore -> LiveScorePopup(model = popupContent.model)
                    is PopupContentModel.Flashlight -> FlashlightPopup(model = popupContent.model)
                    is PopupContentModel.Stopwatch -> StopwatchPopup(model = popupContent.model)
                    is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
                    PopupContentModel.None -> Unit
                }
            }
        }
    }
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
