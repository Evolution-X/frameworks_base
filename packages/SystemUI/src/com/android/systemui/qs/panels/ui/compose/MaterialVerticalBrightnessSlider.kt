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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN
import com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat
import com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme

private val CORNER_DEFAULT = 26.dp
private val CORNER_ROUNDED = 50.dp

@Composable
fun MaterialVerticalBrightnessSlider(
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cr: ContentResolver = context.contentResolver

    val displayManager = remember {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    fun brightnessInfo(): BrightnessInfo? = context.display?.brightnessInfo

    fun linearToFraction(linear: Float): Float {
        val info = brightnessInfo() ?: return linear.coerceIn(0f, 1f)
        val gamma = convertLinearToGammaFloat(linear, info.brightnessMinimum, info.brightnessMaximum)
        val min = GAMMA_SPACE_MIN.toFloat()
        val max = GAMMA_SPACE_MAX.toFloat()
        if (max <= min) return 0f
        return ((gamma - min) / (max - min)).coerceIn(0f, 1f)
    }

    fun fractionToLinear(fraction: Float): Float {
        val info = brightnessInfo() ?: return fraction.coerceIn(0f, 1f)
        val gamma = (GAMMA_SPACE_MIN + fraction.coerceIn(0f, 1f) * (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN))
            .roundToInt()
        return convertGammaToLinearFloat(gamma, info.brightnessMinimum, info.brightnessMaximum)
            .coerceIn(0f, 1f)
    }

    fun readLinearBrightness(): Float =
        brightnessInfo()?.brightness?.coerceIn(0f, 1f) ?: run {
            try {
                Settings.System.getIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT
                ).toFloat() / 255f
            } catch (_: Exception) { 0.5f }
        }

    fun readAutoMode(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    var linearBrightness by remember { mutableFloatStateOf(readLinearBrightness()) }
    var autoMode by remember { mutableStateOf(readAutoMode()) }
    var isDragging by remember { mutableStateOf(false) }

    val targetFraction = linearToFraction(linearBrightness)

    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (isDragging)
            spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh)
        else
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label = "BrightnessFraction",
    )
    val currentFraction = if (isDragging) targetFraction else animFraction

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!isDragging) {
                    linearBrightness = readLinearBrightness()
                    autoMode = readAutoMode()
                }
            }
        }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, observer, UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val fillColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "BrightnessFill",
    )
    val iconTint by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onPrimary,
        animationSpec = tween(300),
        label = "BrightnessIconTint",
    )
    val iconRes = if (autoMode) R.drawable.ic_qs_brightness_auto_on
                  else R.drawable.ic_qs_brightness_auto_off

    val cornerRadius = if (rounded) CORNER_ROUNDED else CORNER_DEFAULT
    val shape = RoundedCornerShape(cornerRadius)
    val trackBg  = CustomColorScheme.current.qsTileColor

    fun yToLinear(y: Float, heightPx: Int): Float {
        val fraction = 1f - (y / heightPx).coerceIn(0f, 1f)
        return fractionToLinear(fraction)
    }

    fun writeLinearBrightness(value: Float) {
        scope.launch(Dispatchers.IO) {
            try {
                displayManager.setBrightness(
                    context.display?.displayId ?: return@launch,
                    value,
                )
            } catch (_: Exception) {
                try {
                    val legacyInt = (value * 255f).roundToInt().coerceIn(1, 255)
                    Settings.System.putIntForUser(
                        cr, Settings.System.SCREEN_BRIGHTNESS,
                        legacyInt, UserHandle.USER_CURRENT,
                    )
                } catch (_: Exception) {}
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(trackBg)
            .pointerInput(Unit) {
                var longPressJob: Job? = null

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)

                    val downLinear = yToLinear(down.position.y, size.height)
                    linearBrightness = downLinear
                    writeLinearBrightness(downLinear)

                    longPressJob = scope.launch {
                        delay(500)
                        val newAuto = !autoMode
                        autoMode = newAuto
                        val mode = if (newAuto)
                            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        launch(Dispatchers.IO) {
                            try {
                                Settings.System.putIntForUser(
                                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    mode, UserHandle.USER_CURRENT,
                                )
                            } catch (_: Exception) {}
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }

                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ptr.pressed) { longPressJob?.cancel(); break }

                            val dragAmt = ptr.position.y - down.position.y
                            if (!dragging && abs(dragAmt) > viewConfiguration.touchSlop) {
                                dragging = true
                                isDragging = true
                                longPressJob?.cancel()
                            }
                            if (dragging) {
                                ptr.consume()
                                val v = yToLinear(ptr.position.y, size.height)
                                linearBrightness = v
                                writeLinearBrightness(v)
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        isDragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(currentFraction)
                .align(Alignment.BottomCenter)
                .background(fillColor, shape),
        )

        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Brightness",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(20.dp),
        )
    }
}
