/*
 * Copyright (C) 2026 MistOS
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme

private val CORNER_DEFAULT = 26.dp
private val CORNER_ROUNDED = 50.dp

@Composable
fun MaterialVerticalVolumeSlider(
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val vibrator = remember {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    val hasVibrator = remember {
        vibrator?.hasVibrator() == true
    }

    fun readVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        return if (max > 0f) cur / max else 0f
    }

    fun readRingerMode(): Int = audioManager.ringerMode

    var volumeFraction by remember {
        mutableFloatStateOf(readVolume())
    }
    var ringerMode by remember {
        mutableIntStateOf(readRingerMode())
    }
    var isDragging by remember {
        mutableStateOf(false)
    }

    val targetFraction = volumeFraction.coerceAtLeast(0.05f)

    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (isDragging) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        },
        label = "VolumeFraction",
    )
    val currentFraction = if (isDragging) targetFraction else animFraction

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        ringerMode = readRingerMode()
                    }
                    "android.media.VOLUME_CHANGED_ACTION" -> {
                        if (!isDragging) volumeFraction = readVolume()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun cycleRingerMode() {
        val next = when (ringerMode) {
            AudioManager.RINGER_MODE_NORMAL  ->
                if (hasVibrator) AudioManager.RINGER_MODE_VIBRATE
                else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        scope.launch(Dispatchers.IO) {
            try { audioManager.ringerModeInternal = next } catch (_: Exception) {}
        }
        ringerMode = next
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    val fillColor by animateColorAsState(
        targetValue = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> MaterialTheme.colorScheme.tertiaryContainer
            AudioManager.RINGER_MODE_VIBRATE -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(350),
        label = "VolumeFill",
    )
    val iconTint by animateColorAsState(
        targetValue = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> MaterialTheme.colorScheme.onTertiaryContainer
            AudioManager.RINGER_MODE_VIBRATE -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(350),
        label = "VolumeIconTint",
    )

    val iconRes = when (ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_off
        AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
        else -> R.drawable.ic_volume_ringer
    }

    val tileColor = CustomColorScheme.current.qsTileColor
    val trackBg = tileColor
    val cornerRadius = if (rounded) CORNER_ROUNDED else CORNER_DEFAULT
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(trackBg)
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
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val fraction = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        volumeFraction = fraction
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = (fraction * maxVol).toInt().coerceIn(0, maxVol)
                        scope.launch(Dispatchers.IO) {
                            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                            catch (_: Exception) {}
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val fraction = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        volumeFraction = fraction
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = (fraction * maxVol).toInt().coerceIn(0, maxVol)
                        scope.launch(Dispatchers.IO) {
                            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                            catch (_: Exception) {}
                        }
                    },
                    onLongPress = { cycleRingerMode() },
                )
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
            contentDescription = "Volume",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(20.dp),
        )
    }
}
