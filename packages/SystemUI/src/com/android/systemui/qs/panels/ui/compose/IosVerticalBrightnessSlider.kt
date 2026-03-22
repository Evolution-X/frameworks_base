/*
 * Copyright (C) 2026 MistOS
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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IosVerticalBrightnessSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cr: ContentResolver = context.contentResolver

    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val brightnessRange = remember {
        val min = try {
            Settings.System.getIntForUser(cr, "screen_brightness_for_vr_10bit", 10, UserHandle.USER_CURRENT)
        } catch (_: Exception) { 10 }
        0f..255f
    }

    fun readBrightness(): Float {
        return try {
            Settings.System.getIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT).toFloat()
        } catch (_: Exception) { 128f }
    }

    fun readAutoMode(): Boolean {
        return try {
            Settings.System.getIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }
    }

    var brightness by remember { mutableFloatStateOf(readBrightness()) }
    var autoMode by remember { mutableStateOf(readAutoMode()) }
    var isDragging by remember { mutableStateOf(false) }

    val targetFraction = (brightness - brightnessRange.start) / (brightnessRange.endInclusive - brightnessRange.start)
    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(150),
        label = "BrightnessFraction"
    )
    val currentFraction = if (isDragging) targetFraction else animFraction

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!isDragging) {
                    brightness = readBrightness()
                    autoMode = readAutoMode()
                }
            }
        }
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer, UserHandle.USER_ALL)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer, UserHandle.USER_ALL)
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val trackBgColor = Color.White.copy(alpha = 0.18f)
    val fillColor by animateColorAsState(
        if (autoMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
        label = "BrightnessFill"
    )
    val iconTint by animateColorAsState(
        if (autoMode) MaterialTheme.colorScheme.onPrimary else Color(0xFF2C2C2E),
        label = "BrightnessIconTint"
    )
    val iconRes = if (autoMode) R.drawable.ic_qs_brightness_auto_on else R.drawable.ic_qs_brightness_auto_off

    var dragStartFraction by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(28.dp))
            .background(trackBgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    waitForUpOrCancellation()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val startY = offset.y
                        dragStartFraction = 1f - (startY / size.height).coerceIn(0f, 1f)
                        val newBrightness = (brightnessRange.start + dragStartFraction * (brightnessRange.endInclusive - brightnessRange.start))
                                .coerceIn(brightnessRange.start, brightnessRange.endInclusive)
                        brightness = newBrightness
                        scope.launch(Dispatchers.IO) {
                            try {
                                Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, newBrightness.toInt(), UserHandle.USER_CURRENT)
                            } catch (_: Exception) {}
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val fraction = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        val newBrightness = (brightnessRange.start + fraction * (brightnessRange.endInclusive - brightnessRange.start))
                            .coerceIn(brightnessRange.start, brightnessRange.endInclusive)
                        brightness = newBrightness
                        scope.launch(Dispatchers.IO) {
                            try {
                                Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, newBrightness.toInt(), UserHandle.USER_CURRENT)
                            } catch (_: Exception) {}
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val fraction = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        val newBrightness = (brightnessRange.start + fraction * (brightnessRange.endInclusive - brightnessRange.start))
                            .coerceIn(brightnessRange.start, brightnessRange.endInclusive)
                        brightness = newBrightness
                        scope.launch(Dispatchers.IO) {
                            try {
                                Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, newBrightness.toInt(), UserHandle.USER_CURRENT)
                            } catch (_: Exception) {}
                        }
                    },
                    onLongPress = {
                        val newAutoMode = !autoMode
                        autoMode = newAutoMode
                        val mode = if (newAutoMode) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        scope.launch(Dispatchers.IO) {
                            try {
                                Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode, UserHandle.USER_CURRENT)
                            } catch (_: Exception) {}
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(currentFraction)
                .align(Alignment.BottomCenter)
                .background(fillColor, RoundedCornerShape(28.dp))
        )

        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Brightness",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(22.dp)
        )
    }
}
