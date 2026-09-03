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

package com.android.systemui.statusbar.quickactions.media.ui.compose

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.widget.SeekBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.common.ui.compose.Icon as UiIconView
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import kotlinx.coroutines.delay

private val PopupShape = RoundedCornerShape(34.dp)
private val ArtworkShape = RoundedCornerShape(24.dp)

/** Expanded media controls for the centered dynamic island. */
@Composable
fun MediaControlPopup(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PopupShape,
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable(enabled = model.openApp != null) {
                        model.openApp?.invoke()
                    },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaArtwork(model = model)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    model.appName?.takeIf { it.isNotBlank() }?.let { appName ->
                        Text(
                            text = appName.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accent.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = model.songName?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.artistName?.takeIf { it.isNotBlank() }?.let { artist ->
                        Text(
                            text = artist.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (model.durationMs > 0L) {
                MediaProgressSection(
                    positionMs = model.positionMs,
                    durationMs = model.durationMs,
                    isPlaying = model.isPlaying,
                    canBeScrubbed = model.canBeScrubbed,
                    onSeekTo = model.seekTo,
                    accent = accent,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaActionButton(
                    action = model.previousAction,
                    containerColor = Color.White.copy(alpha = 0.11f),
                    iconTint = Color.White,
                )
                MediaActionButton(
                    action = model.playOrPause,
                    containerColor = accent,
                    iconTint = MaterialTheme.colorScheme.onPrimary,
                    buttonSize = 72.dp,
                    iconSize = 28.dp,
                    emphasized = true,
                )
                MediaActionButton(
                    action = model.nextAction,
                    containerColor = Color.White.copy(alpha = 0.11f),
                    iconTint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun MediaArtwork(model: MediaControlChipModel) {
    val contentDescription = model.appName?.let { ContentDescription.Loaded(it) }
    val artwork =
        model.artworkIcon
            ?: model.appIcon
            ?: UiIcon.Resource(
                resId = com.android.internal.R.drawable.ic_audio_media,
                contentDescription = contentDescription,
            )
    Box(
        modifier =
            Modifier.size(76.dp)
                .clip(ArtworkShape)
                .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        UiIconView(
            icon = artwork,
            modifier = Modifier.size(76.dp),
            tint = Color.Unspecified,
        )
    }
}

@Composable
private fun MediaProgressSection(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    canBeScrubbed: Boolean,
    onSeekTo: ((Long) -> Unit)?,
    accent: Color,
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var displayedPositionMs by remember { mutableStateOf(positionMs.coerceIn(0L, durationMs)) }
    val currentSeekAction by rememberUpdatedState(onSeekTo)

    LaunchedEffect(positionMs, durationMs, isPlaying, isScrubbing) {
        if (isScrubbing) {
            return@LaunchedEffect
        }

        displayedPositionMs = positionMs.coerceIn(0L, durationMs)

        if (!isPlaying || durationMs <= 0L) {
            return@LaunchedEffect
        }

        val startWallClockMs = System.currentTimeMillis()
        val startPositionMs = displayedPositionMs
        while (!isScrubbing) {
            delay(16L)
            val elapsedMs = System.currentTimeMillis() - startWallClockMs
            displayedPositionMs = (startPositionMs + elapsedMs).coerceAtMost(durationMs)
            if (displayedPositionMs >= durationMs) {
                break
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatElapsedTime(displayedPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.60f),
            )
            Text(
                text = formatElapsedTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.60f),
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            factory = { context ->
                SeekBar(context).apply {
                    max = durationMs.toClampedInt()
                    splitTrack = false
                    progressDrawable = context.getDrawable(R.drawable.media_squiggly_progress)?.mutate()
                    thumb = createSeekBarThumb(context, accent.toArgb())
                    setPadding(0, 0, 0, 0)
                }
            },
            update = { seekBar ->
                seekBar.max = durationMs.toClampedInt().coerceAtLeast(1)
                configureProgressDrawable(
                    seekBar = seekBar,
                    accent = accent,
                    isPlaying = isPlaying && canBeScrubbed && !isScrubbing,
                    enabled = canBeScrubbed,
                )
                seekBar.isEnabled = canBeScrubbed
                seekBar.thumb.alpha = if (canBeScrubbed) 255 else 120
                if (!isScrubbing) {
                    seekBar.progress = displayedPositionMs.coerceIn(0L, durationMs).toClampedInt()
                }
                seekBar.setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                displayedPositionMs = progress.toLong()
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) {
                            isScrubbing = true
                        }

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            val newPositionMs = seekBar?.progress?.toLong() ?: displayedPositionMs
                            displayedPositionMs = newPositionMs
                            currentSeekAction?.invoke(newPositionMs)
                            isScrubbing = false
                        }
                    }
                )
            },
        )
    }
}

@Composable
private fun MediaActionButton(
    action: MediaAction?,
    containerColor: Color,
    iconTint: Color,
    buttonSize: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    emphasized: Boolean = false,
) {
    if (action == null || action.icon == null) {
        Spacer(modifier = Modifier.size(buttonSize))
        return
    }

    val contentDescription =
        action.contentDescription?.toString()?.let { ContentDescription.Loaded(it) }
    Box(
        modifier =
            Modifier.size(buttonSize)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(enabled = action.action != null) { action.action?.run() },
        contentAlignment = Alignment.Center,
    ) {
        UiIconView(
            icon = UiIcon.Loaded(action.icon, contentDescription),
            modifier = Modifier.size(iconSize).padding(start = if (emphasized) 1.dp else 0.dp),
            tint = iconTint,
        )
    }
}

private fun configureProgressDrawable(
    seekBar: SeekBar,
    accent: Color,
    isPlaying: Boolean,
    enabled: Boolean,
) {
    val context = seekBar.context
    val progressDrawable = seekBar.progressDrawable as? SquigglyProgress ?: return
    progressDrawable.waveLength =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_wavelength).toFloat()
    progressDrawable.lineAmplitude =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_amplitude).toFloat()
    progressDrawable.phaseSpeed =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_phase).toFloat()
    progressDrawable.strokeWidth =
        context.resources.getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_stroke_width).toFloat()
    progressDrawable.transitionEnabled = true
    progressDrawable.animate = isPlaying
    progressDrawable.setTint(accent.toArgb())
    progressDrawable.alpha = if (enabled) 255 else 180
}

private fun createSeekBarThumb(context: Context, tintColor: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999f
        setColor(tintColor)
        setSize(
            (context.resources.displayMetrics.density * 4f).toInt().coerceAtLeast(1),
            (context.resources.displayMetrics.density * 18f).toInt().coerceAtLeast(1),
        )
    }
}

private fun Long.toClampedInt(): Int {
    return coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun formatElapsedTime(milliseconds: Long): String {
    return DateUtils.formatElapsedTime(milliseconds / DateUtils.SECOND_IN_MILLIS)
}
