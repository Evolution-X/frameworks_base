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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.android.systemui.statusbar.quickactions.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.quickactions.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.compose.StopwatchPopup

/**
 * Displays a popup in the status bar area. The offset is calculated to draw the popup below the
 * status bar.
 */
@Composable
fun StatusBarPopup(
    viewModel: QuickActionChipModel.PopupChip,
    isVisible: Boolean,
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
        val window = LocalView.current
        DisposableEffect(window) {
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                    info.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME
                    )
                }
            window.viewTreeObserver.addOnComputeInternalInsetsListener(listener)
            onDispose {
                window.viewTreeObserver.removeOnComputeInternalInsetsListener(listener)
            }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter =
                fadeIn(animationSpec = tween(140)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(220),
                    ) +
                    slideInVertically(
                        initialOffsetY = { -it / 6 },
                        animationSpec = tween(220),
                    ),
            exit =
                fadeOut(animationSpec = tween(120)) +
                    scaleOut(
                        targetScale = 0.94f,
                        animationSpec = tween(160),
                    ) +
                    slideOutVertically(
                        targetOffsetY = { -it / 8 },
                        animationSpec = tween(160),
                    ),
        ) {
            Box(modifier = Modifier.padding(8.dp).wrapContentSize()) {
                when (val popupContent = viewModel.popupContent) {
                    is PopupContentModel.Media -> MediaControlPopup(model = popupContent.model)
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
