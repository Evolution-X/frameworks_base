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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.settingslib.media.MediaOutputConstants

import com.android.systemui.ActivityIntentHelper
import com.android.systemui.Dependency
import com.android.systemui.media.dialog.MediaOutputDialogReceiver
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController

@Composable
fun MaterialMusicPlayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyguardStateController = remember { Dependency.get(KeyguardStateController::class.java) }
    val activityIntentHelper = remember { ActivityIntentHelper(context) }
    val activityStarter = remember { Dependency.get(ActivityStarter::class.java) }
    val lockscreenUserManager = remember { Dependency.get(NotificationLockscreenUserManager::class.java) }

    val mediaState = rememberMediaState()

    MaterialMusicPlayerContent(
        mediaState = mediaState,
        keyguardStateController = keyguardStateController,
        activityStarter = activityStarter,
        activityIntentHelper = activityIntentHelper,
        lockscreenUserManager = lockscreenUserManager,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Equalizer bars animation
// ---------------------------------------------------------------------------

@Composable
private fun EqualizerBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "eq")
    val h1 by inf.animateFloat(
        initialValue = 0.30f, targetValue = 1.00f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b1",
    )
    val h2 by inf.animateFloat(
        initialValue = 0.80f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b2",
    )
    val h3 by inf.animateFloat(
        initialValue = 0.50f, targetValue = 0.90f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b3",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(300),
        label = "eq_alpha",
    )
    Row(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(h1, h2, h3).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Skip button
// ---------------------------------------------------------------------------

@Composable
private fun SkipButton(
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "skipScale",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialMusicPlayerContent(
    mediaState: SharedMediaState,
    keyguardStateController: KeyguardStateController,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tileColor = CustomColorScheme.current.qsTileColor

    // Audio output device detection
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var deviceType by remember { mutableStateOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }
    DisposableEffect(audioManager) {
        fun update() {
            val out = try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (_: Exception) { emptyArray() }
            val bt = out.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val wire = out.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            deviceType = when {
                bt   -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                wire -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }
        update()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) = update()
            override fun onAudioDevicesRemoved(r: Array<out AudioDeviceInfo>?) = update()
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(cb) }
    }

    val deviceIcon = when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP   -> Icons.Filled.Bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Filled.Headset
        else                                  -> Icons.Filled.Smartphone
    }

    // Optimistic play/pause state so the UI responds immediately without waiting
    // for the next MediaController callback round-trip.
    var localIsPlaying by remember(mediaState.controller, mediaState.isPlaying) {
        mutableStateOf(mediaState.isPlaying)
    }

    val playSrc = remember { MutableInteractionSource() }
    val playPressed by playSrc.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (playPressed) 0.86f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "playScale",
    )

    val accentColor by animateColorAsState(
        targetValue = if (localIsPlaying) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(400),
        label = "accent",
    )
    val onAccentColor by animateColorAsState(
        targetValue = if (localIsPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(400),
        label = "onAccent",
    )

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val hasAlbumArt = mediaState.albumArt != null
    val hasController = mediaState.controller != null

    // Helper: resolve or build a PendingIntent for the active session's app.
    fun resolveSessionPendingIntent(): PendingIntent? {
        val pkg = mediaState.packageName ?: return null
        return mediaState.controller?.sessionActivity
            ?: context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
    }

    // Helper: launch the session's app, handling lock-screen correctly.
    fun launchSessionApp() {
        val pending = resolveSessionPendingIntent() ?: return
        val showOverLockscreen = keyguardStateController.isShowing &&
            activityIntentHelper.wouldPendingShowOverLockscreen(
                pending,
                lockscreenUserManager.currentUserId,
            )
        if (showOverLockscreen) {
            activityStarter.startPendingIntentMaybeDismissingKeyguard(
                pending,
                /* dismissShade = */ true,
                /* intentSentUiThreadCallback = */ null,
                /* animationController = */ null,
                /* fillIntent = */ null,
                /* extraOptions = */ null,
                /* customMessage = */ null,
            )
        } else {
            activityStarter.postStartActivityDismissingKeyguard(pending, null)
        }
    }

    // Helper: send the broadcast to open the media output picker.
    fun launchMediaOutputDialog() {
        val pkg = mediaState.packageName ?: return
        context.sendBroadcast(
            Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG).apply {
                putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, pkg)
                component = ComponentName("com.android.systemui", MediaOutputDialogReceiver::class.java.name)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(tileColor),
    ) {
        // Album art background
        mediaState.albumArt?.let { bmp ->
            Image(
                painter = BitmapPainter(bmp.asImageBitmap()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.65f,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.10f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ---- Top row: track info + output picker ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasController, onClick = ::launchSessionApp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaState.title ?: "Not playing",
                        style = MaterialTheme.typography.titleSmallEmphasized.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasAlbumArt) Color.White else onSurface,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!mediaState.artist.isNullOrBlank()) {
                        Text(
                            text = mediaState.artist,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (hasAlbumArt) Color.White.copy(alpha = 0.7f) else onVariant,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                EqualizerBars(
                    isPlaying = localIsPlaying,
                    color = if (hasAlbumArt) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )

                // Audio output picker button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (hasAlbumArt) Color.Black.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                        )
                        .clickable(enabled = hasController, onClick = ::launchMediaOutputDialog)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        deviceIcon,
                        contentDescription = "Audio output",
                        tint = if (hasAlbumArt) Color.White.copy(alpha = 0.8f) else onVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // ---- Bottom row: playback controls ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val controlTint = if (hasAlbumArt) Color.White else onSurface
                val controlTintDisabled = controlTint.copy(alpha = 0.38f)

                SkipButton(
                    icon = {
                        Icon(
                            Icons.Filled.SkipPrevious, "Previous",
                            tint = if (hasController) controlTint else controlTintDisabled,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                    enabled = hasController,
                    onClick = { mediaState.controller?.transportControls?.skipToPrevious() },
                )

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer { scaleX = playScale; scaleY = playScale }
                        .clip(CircleShape)
                        .background(
                            if (hasAlbumArt) {
                                if (localIsPlaying) Color.White else Color.White.copy(alpha = 0.3f)
                            } else accentColor
                        )
                        .clickable(playSrc, indication = null) {
                            val ctrl = mediaState.controller ?: return@clickable
                            if (localIsPlaying) {
                                localIsPlaying = false
                                ctrl.transportControls.pause()
                            } else {
                                localIsPlaying = true
                                ctrl.transportControls.play()
                                // Some apps need a key event to actually start audio.
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = localIsPlaying,
                        animationSpec = tween(180),
                        label = "ppCf",
                    ) { playing ->
                        val playBtnTint = if (hasAlbumArt) {
                            if (playing) Color.Black else Color.White
                        } else {
                            if (hasController) onAccentColor else onAccentColor.copy(alpha = 0.38f)
                        }
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = playBtnTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                SkipButton(
                    icon = {
                        Icon(
                            Icons.Filled.SkipNext, "Next",
                            tint = if (hasController) controlTint else controlTintDisabled,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                    enabled = hasController,
                    onClick = { mediaState.controller?.transportControls?.skipToNext() },
                )
            }
        }
    }
}
