/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.qs.panels.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MaterialControlPanel(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 8.dp,
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    fun readEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.QS_WIDGET_PANEL, 0, UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    fun readIosMusicStyle(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.QS_WIDGET_IOS_MUSIC, 0, UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    fun readSliderRounded(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.QS_WIDGET_SLIDER_CORNER, 0, UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    var enabled by remember {
        mutableStateOf(readEnabled())
    }
    var iosMusicStyle by remember {
        mutableStateOf(readIosMusicStyle())
    }
    var sliderRounded by remember {
        mutableStateOf(readSliderRounded())
    }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val nowEnabled = readEnabled()
                enabled = nowEnabled
                iosMusicStyle = readIosMusicStyle()
                sliderRounded = readSliderRounded()

                try {
                    Settings.Secure.putIntForUser(
                        cr,
                        Settings.Secure.QS_SHOW_MEDIA_PLAYER,
                        if (nowEnabled) 0 else 1,
                        UserHandle.USER_CURRENT,
                    )
                } catch (_: Exception) {}
            }
        }
        try {
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_WIDGET_PANEL),
                false, observer, UserHandle.USER_ALL,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_WIDGET_IOS_MUSIC),
                false, observer, UserHandle.USER_ALL,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_WIDGET_SLIDER_CORNER),
                false, observer, UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}

        try {
            Settings.Secure.putIntForUser(
                cr,
                Settings.Secure.QS_SHOW_MEDIA_PLAYER,
                if (enabled) 0 else 1,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Exception) {}

        onDispose { cr.unregisterContentObserver(observer) }
    }

    val fraction by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "MaterialControlPanelFraction",
    )

    if (fraction == 0f) return

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val animatedHeight = (placeable.height * fraction).roundToInt()
                layout(placeable.width, animatedHeight) {
                    placeable.place(0, 0)
                }
            }
            .clipToBounds()
            .graphicsLayer { alpha = fraction },
        contentAlignment = Alignment.TopCenter,
    ) {
        MaterialControlPanelContent(
            verticalPadding = verticalPadding,
            iosMusicStyle = iosMusicStyle,
            sliderRounded = sliderRounded,
        )
    }
}

@Composable
private fun MaterialControlPanelContent(
    verticalPadding: Dp,
    iosMusicStyle: Boolean,
    sliderRounded: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .height(180.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1.85f)
                .fillMaxHeight(),
        ) {
            if (iosMusicStyle) {
                IosMusicPlayer(modifier = Modifier.fillMaxWidth().fillMaxHeight())
            } else {
                MaterialMusicPlayer(modifier = Modifier.fillMaxWidth().fillMaxHeight())
            }
        }

        MaterialVerticalBrightnessSlider(
            modifier = Modifier
                .weight(0.73f)
                .fillMaxHeight()
                .widthIn(max = 64.dp),
            rounded = sliderRounded,
        )

        MaterialVerticalVolumeSlider(
            modifier = Modifier
                .weight(0.73f)
                .fillMaxHeight()
                .widthIn(max = 64.dp),
            rounded = sliderRounded,
        )
    }
}
