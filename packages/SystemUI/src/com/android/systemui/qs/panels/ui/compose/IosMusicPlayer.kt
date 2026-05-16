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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.android.settingslib.media.MediaOutputConstants

import com.android.systemui.ActivityIntentHelper
import com.android.systemui.Dependency
import com.android.systemui.media.dialog.MediaOutputDialogReceiver
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController

@Composable
fun IosMusicPlayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyguardStateController = remember { Dependency.get(KeyguardStateController::class.java) }
    val activityIntentHelper = remember { ActivityIntentHelper(context) }
    val activityStarter = remember { Dependency.get(ActivityStarter::class.java) }
    val lockscreenUserManager = remember { Dependency.get(NotificationLockscreenUserManager::class.java) }

    val mediaState = rememberMediaState()

    IosMusicPlayerContent(
        mediaState = mediaState,
        activityStarter = activityStarter,
        keyguardStateController = keyguardStateController,
        activityIntentHelper = activityIntentHelper,
        lockscreenUserManager = lockscreenUserManager,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IosMusicPlayerContent(
    mediaState: SharedMediaState,
    activityStarter: ActivityStarter,
    keyguardStateController: KeyguardStateController,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasMedia = mediaState.controller != null
    val hasAlbumArt = mediaState.albumArt != null

    val tileColor = CustomColorScheme.current.qsTileColor
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant

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

    // Pulsing icon alpha when playing
    val infiniteTransition = rememberInfiniteTransition(label = "audio_pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "audio_pulse_alpha",
    )
    val iconAlpha = if (mediaState.isPlaying) alphaAnim else 0.5f

    // Optimistic play/pause state so the UI responds immediately.
    var localIsPlaying by remember(mediaState.controller, mediaState.isPlaying) {
        mutableStateOf(mediaState.isPlaying)
    }

    val playSrc = remember { MutableInteractionSource() }

    val titleColor by animateColorAsState(
        targetValue = if (hasMedia) Color.White else onSurface,
        animationSpec = tween(400),
        label = "titleColor",
    )
    val artistColor by animateColorAsState(
        targetValue = if (hasMedia) Color.White.copy(alpha = 0.7f) else onVariant,
        animationSpec = tween(400),
        label = "artistColor",
    )
    val controlTint = if (hasMedia) Color.White else onSurface
    val controlTintDisabled = controlTint.copy(alpha = 0.38f)

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
            .clip(RoundedCornerShape(24.dp))
            .background(tileColor),
    ) {
        // Album art background
        mediaState.albumArt?.let { bmp ->
            Image(
                painter = BitmapPainter(bmp.asImageBitmap()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp)),
                alpha = 0.35f,
            )
        }

        // Gradient overlay when media is active
        if (hasMedia) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = 0.1f),
                                onPrimaryColor.copy(alpha = 0.6f),
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ---- Top row: album art thumbnail + track info + output picker ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = hasMedia, onClick = ::launchSessionApp),
            ) {
                // Album art thumbnail or placeholder
                if (hasAlbumArt) {
                    Image(
                        painter = BitmapPainter(mediaState.albumArt!!.asImageBitmap()),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (hasMedia) Color.White.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = if (hasMedia) Color.White else onVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaState.title ?: "Not Playing",
                        style = MaterialTheme.typography.titleSmallEmphasized.copy(
                            color = titleColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!mediaState.artist.isNullOrBlank()) {
                        Text(
                            text = mediaState.artist,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = artistColor,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Audio output picker button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (hasMedia) Color.Black.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                        )
                        .clickable(enabled = hasMedia, onClick = ::launchMediaOutputDialog)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = "Audio Output",
                        tint = if (hasMedia) Color.White.copy(alpha = iconAlpha) else onVariant,
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
                IconButton(
                    onClick = { mediaState.controller?.transportControls?.skipToPrevious() },
                    enabled = hasMedia,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = if (hasMedia) controlTint else controlTintDisabled,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasMedia) Color.White.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = {
                            val ctrl = mediaState.controller ?: return@IconButton
                            if (localIsPlaying) {
                                localIsPlaying = false
                                ctrl.transportControls.pause()
                            } else {
                                localIsPlaying = true
                                ctrl.transportControls.play()
                                // Some apps need a key event to actually start audio.
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        enabled = hasMedia,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Crossfade(
                            targetState = localIsPlaying,
                            animationSpec = tween(200),
                            label = "play_pause_crossfade",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = if (hasMedia) controlTint else controlTintDisabled,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { mediaState.controller?.transportControls?.skipToNext() },
                    enabled = hasMedia,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = if (hasMedia) controlTint else controlTintDisabled,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
