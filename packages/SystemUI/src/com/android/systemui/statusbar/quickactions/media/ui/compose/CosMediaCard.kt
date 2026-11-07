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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieConstants
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.common.ui.compose.Icon as UiIconView
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface
import com.android.systemui.statusbar.quickactions.ui.compose.rememberAlbumArtAccent
import com.android.systemui.statusbar.quickactions.ui.compose.rememberCosArtTint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object CosMediaCard {
    val Corner = 26.dp
    val ArtSize = 44.dp
    val ArtCorner = 10.dp
    val Margin = 16.dp
    val TitleHeight = 24.dp
    val ContentHeight = 20.dp
    val Secondary = Color(0x8AFFFFFF)
    val ButtonSize = 36.dp
    val ButtonArea = 42.dp
    val ButtonSpacing = 24.dp
    val Track = Color(0x26FFFFFF)

    val Move = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
    val PressDown = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val PressUp = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    private val compositions = ConcurrentHashMap<Int, Result<LottieComposition>>()

    /** Parses the Lottie file in `res/raw` once and keeps it, since every card shows the same. */
    suspend fun lottie(context: Context, rawRes: Int): LottieComposition? =
        compositions
            .getOrPut(rawRes) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        LottieCompositionFactory.fromRawResSync(
                                context,
                                rawRes,
                                "cos_media_$rawRes",
                            )
                            .value!!
                    }
                }
            }
            .getOrNull()
}

@Composable
private fun rememberCosLottie(rawRes: Int): LottieComposition? {
    val context = LocalContext.current
    var composition by remember(rawRes) { mutableStateOf<LottieComposition?>(null) }
    LaunchedEffect(rawRes) { composition = CosMediaCard.lottie(context, rawRes) }
    return composition
}

/**
 * The ColorOS style media card of the island, on the frosted island glass lightly tinted by the
 * artwork. Only shown with the ColorOS island style on.
 */
@Composable
fun CosMediaCard(model: MediaControlChipModel, modifier: Modifier = Modifier) {
    val artwork = remember(model.artworkIcon) { (model.artworkIcon as? UiIcon.Loaded)?.drawable }
    val tint = rememberCosArtTint(artwork)
    PopupSurface(
        shape = RoundedCornerShape(CosMediaCard.Corner),
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp),
    ) {
        CosMediaCardContent(model, artwork, Modifier.background(tint))
    }
}

@Composable
private fun CosMediaCardContent(
    model: MediaControlChipModel,
    artwork: android.graphics.drawable.Drawable?,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(bottom = CosMediaCard.Margin)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = CosMediaCard.Margin,
                        end = CosMediaCard.Margin,
                        top = CosMediaCard.Margin,
                    )
                    .pointerInput(model.openApp) { detectTapGestures { model.openApp?.invoke() } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosCardArtwork(model)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                CosSwitchText(
                    text = model.songName?.toString().orEmpty(),
                    color = Color.White,
                    size = 16,
                    bold = true,
                    height = CosMediaCard.TitleHeight,
                )
                Spacer(Modifier.height(2.dp))
                CosSwitchText(
                    text = model.artistName?.toString().orEmpty(),
                    color = CosMediaCard.Secondary,
                    size = 14,
                    bold = false,
                    height = CosMediaCard.ContentHeight,
                )
            }
            CosCardVoicePrint(model.isPlaying)
        }

        if (model.durationMs > 0L) {
            CosSeekBar(
                model = model,
                glow = rememberAlbumArtAccent(artwork, fallback = Color.White).toArgb(),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = CosMediaCard.Margin)
                        .padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement =
                Arrangement.spacedBy(CosMediaCard.ButtonSpacing, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosLottieButton(model.previousAction, R.raw.cos_media_previous)
            CosPlayPause(model)
            CosLottieButton(model.nextAction, R.raw.cos_media_next)
        }
    }
}

@Composable
private fun CosCardArtwork(model: MediaControlChipModel) {
    val angle = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(model.artworkIcon) }
    // A new cover spins in: a spring released from a fast turn, as ColorOS does.
    LaunchedEffect(model.artworkIcon) {
        if (shown == model.artworkIcon) return@LaunchedEffect
        shown = model.artworkIcon
        angle.snapTo(0f)
        angle.animateTo(
            0f,
            spring(dampingRatio = 0.5f, stiffness = 93.4f),
            initialVelocity = -3140f,
        )
    }
    val artwork = model.artworkIcon ?: model.appIcon
    Box(
        Modifier.size(CosMediaCard.ArtSize)
            .graphicsLayer { rotationZ = angle.value }
            .clip(RoundedCornerShape(CosMediaCard.ArtCorner))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        if (artwork != null) {
            UiIconView(
                icon = artwork,
                modifier = Modifier.size(CosMediaCard.ArtSize),
                tint = Color.Unspecified,
            )
        }
    }
}

@Composable
private fun CosSwitchText(text: String, color: Color, size: Int, bold: Boolean, height: Dp) {
    Crossfade(
        targetState = text,
        animationSpec = tween(300, easing = CosMediaCard.Move),
        label = "cos_media_text",
    ) { words ->
        Text(
            text = words,
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(height),
        )
    }
}

@Composable
private fun CosCardVoicePrint(playing: Boolean) {
    val composition = rememberCosLottie(R.raw.cos_media_voiceprint_dark)
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (playing && composition != null) {
            LottieAnimation(
                composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier.size(24.dp),
            )
        } else {
            // Paused: the six dots the voiceprint rests on.
            Canvas(Modifier.size(24.dp)) {
                val unit = this.size.width / 26f
                for (i in 0 until 6) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.8f),
                        topLeft = Offset((0.5f + 4.5f * i) * unit, 11.75f * unit),
                        size = Size(2.5f * unit, 2.5f * unit),
                        cornerRadius = CornerRadius(1.25f * unit),
                    )
                }
            }
        }
    }
}

/**
 * The position the bar shows. The model's position is a snapshot, so while playing it is advanced
 * four times a second from the wall clock, as the standard media popup does. Frozen while [frozen].
 */
@Composable
private fun rememberTickingPositionMs(model: MediaControlChipModel, frozen: Boolean): Long {
    var shownMs by remember { mutableLongStateOf(model.positionMs.coerceIn(0L, model.durationMs)) }
    LaunchedEffect(model.positionMs, model.durationMs, model.isPlaying, frozen) {
        if (frozen) return@LaunchedEffect
        shownMs = model.positionMs.coerceIn(0L, model.durationMs)
        if (!model.isPlaying || model.durationMs <= 0L) return@LaunchedEffect
        val startWallClockMs = System.currentTimeMillis()
        val startPositionMs = shownMs
        while (true) {
            delay(250L)
            val elapsedMs = System.currentTimeMillis() - startWallClockMs
            shownMs = (startPositionMs + elapsedMs).coerceAtMost(model.durationMs)
            if (shownMs >= model.durationMs) break
        }
    }
    return shownMs
}

@Composable
private fun CosSeekBar(model: MediaControlChipModel, glow: Int, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = model.durationMs.coerceAtLeast(1L)
    val tickingMs = rememberTickingPositionMs(model, frozen = dragging)
    val shownFraction =
        if (dragging) dragFraction else (tickingMs.toFloat() / duration).coerceIn(0f, 1f)
    val thickness by
        animateDpAsState(if (dragging) 6.dp else 4.dp, tween(150, easing = CosMediaCard.Move))
    // The row takes the times' own height, so they are not cut off at larger font sizes.
    Row(modifier.heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CosTime((shownFraction * duration).toLong())
        Canvas(
            Modifier.weight(1f)
                .height(14.dp)
                .padding(horizontal = 4.dp)
                .cosSeekGlow(shownFraction, model.isPlaying, dragging, glow)
                .then(
                    if (model.canBeScrubbed && model.seekTo != null) {
                        Modifier.pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    model.seekTo?.invoke((dragFraction * duration).toLong())
                                    dragging = false
                                },
                                onDragCancel = { dragging = false },
                                onHorizontalDrag = { change, _ ->
                                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val h = thickness.toPx()
            val top = (size.height - h) / 2f
            val radius = CornerRadius(h / 2f)
            drawRoundRect(CosMediaCard.Track, Offset(0f, top), Size(size.width, h), radius)
            drawRoundRect(Color.White, Offset(0f, top), Size(size.width * shownFraction, h), radius)
        }
        CosTime(model.durationMs)
    }
}

@Composable
private fun CosTime(ms: Long) {
    val seconds = (ms / 1000).coerceAtLeast(0)
    Text(
        text = "%d:%02d".format(seconds / 60, seconds % 60),
        color = CosMediaCard.Secondary,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        maxLines = 1,
    )
}

@Composable
private fun Modifier.cosPress(enabled: Boolean, onClick: () -> Unit): Modifier {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    return this.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    scope.launch {
                        scale.animateTo(0.92f, tween(200, easing = CosMediaCard.PressDown))
                    }
                    tryAwaitRelease()
                    scope.launch { scale.animateTo(1f, tween(340, easing = CosMediaCard.PressUp)) }
                },
                onTap = { onClick() },
            )
        }
}

@Composable
private fun CosLottieButton(action: MediaAction?, rawRes: Int) {
    val composition = rememberCosLottie(rawRes)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val enabled = action?.action != null
    Box(
        Modifier.size(CosMediaCard.ButtonArea)
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .cosPress(enabled) {
                action?.action?.run()
                val playing = composition ?: return@cosPress
                scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(playing.duration.toInt(), easing = LinearEasing))
                    progress.snapTo(0f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (composition != null) {
            LottieAnimation(
                composition,
                progress = { progress.value },
                modifier = Modifier.size(CosMediaCard.ButtonSize),
            )
        }
    }
}

@Composable
private fun CosPlayPause(model: MediaControlChipModel) {
    val toPlay = rememberCosLottie(R.raw.cos_media_play_transition_dark)
    val toPause = rememberCosLottie(R.raw.cos_media_pause_transition_dark)
    val progress = remember { Animatable(1f) }
    var shownPlaying by remember { mutableStateOf(model.isPlaying) }
    LaunchedEffect(model.isPlaying) {
        if (shownPlaying == model.isPlaying) return@LaunchedEffect
        shownPlaying = model.isPlaying
        val turn = if (model.isPlaying) toPause else toPlay
        progress.snapTo(0f)
        progress.animateTo(1f, tween(turn?.duration?.toInt() ?: 300, easing = LinearEasing))
    }
    val composition = if (shownPlaying) toPause else toPlay
    // The action decides between pause and play when it runs, so it is safe to call as is.
    val action = model.playOrPause
    Box(
        Modifier.size(CosMediaCard.ButtonArea).cosPress(action?.action != null) {
            action?.action?.run()
        },
        contentAlignment = Alignment.Center,
    ) {
        if (composition != null) {
            LottieAnimation(
                composition,
                progress = { progress.value },
                modifier = Modifier.size(CosMediaCard.ButtonSize),
            )
        }
    }
}
