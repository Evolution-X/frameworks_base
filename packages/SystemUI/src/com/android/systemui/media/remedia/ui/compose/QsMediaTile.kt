/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaNavigationViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaPlayPauseActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSecondaryActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.shared.model.LocalQsMediaStyle
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsMediaArt
import com.android.systemui.qs.panels.shared.model.QsMediaButtons
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.verticalSquish
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SEGMENTS = 14

@Composable
fun QsMediaTile(
    viewModelFactory: MediaViewModel.Factory,
    size: QsMediaTileSize,
    modifier: Modifier = Modifier,
    squishGaps: () -> Int = { 0 },
    squishiness: () -> Float = { 1f },
) {
    val context = LocalContext.current
    val viewModel: MediaViewModel =
        rememberViewModel("QsMediaTile.viewModel") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = MediaCarouselVisibility.WhenNotEmpty,
            )
        }
    val cards = viewModel.cards
    val card = cards.getOrNull(viewModel.currentIndex) ?: cards.firstOrNull()
    val idle = stringResource(R.string.qs_media_tile_idle)
    CompositionLocalProvider(LocalQsStyle provides (LocalQsMediaStyle.current ?: LocalQsStyle.current)) {
        val tweaks = LocalQsTweaks.current
        val cover = tweaks.mediaArt == QsMediaArt.COVER
        val base = mediaCardLook() ?: stockLook().withTweaks(tweaks)
        val look = if (cover) base.copy(text = Color.White) else base
        val parts =
            TileParts(
                art = !cover && tweaks.mediaArt != QsMediaArt.NONE,
                skipButtons = tweaks.mediaButtons != QsMediaButtons.PLAY,
            )
        val shape = RoundedCornerShape(look.radius)
        Box(
            modifier =
                Modifier.clip(shape)
                    .verticalSquish(squishGaps, squishiness)
                    .then(modifier)
                    .semantics { contentDescription = card?.contentDescription ?: idle }
        ) {
            if (cover) {
                CoverBackground(card?.background, Modifier.matchParentSize())
            } else {
                StyledCardBackground(look, Modifier.matchParentSize())
            }
            Expandable(
                controller = rememberExpandableController(color = Color.Transparent, shape = shape),
                modifier = Modifier.fillMaxSize(),
                onClick = card?.onClick,
                useModifierBasedImplementation = true,
                defaultMinSize = false,
            ) {
                CompositionLocalProvider(LocalContentColor provides look.text) {
                    when (size) {
                        QsMediaTileSize.SQUARE -> SquareContent(card, look, idle, parts)
                        QsMediaTileSize.WIDE -> WideContent(card, look, idle, parts)
                        QsMediaTileSize.SMALL -> SmallContent(card, look)
                        QsMediaTileSize.TALL -> TallContent(card, look, idle, parts)
                        QsMediaTileSize.FULL -> FullContent(card, look, idle, parts)
                    }
                }
            }
        }
    }
}

/** Which parts of the player show, as picked for the Media tile. */
private data class TileParts(val art: Boolean, val skipButtons: Boolean)

@Composable
private fun stockLook(): MediaCardLook {
    val scheme = MaterialTheme.colorScheme
    return MediaCardLook(
        background = LocalAndroidColorScheme.current.surfaceEffect1,
        border = null,
        radius = 24.dp,
        text = scheme.onSurface,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        playButton = scheme.primary,
        playIcon = scheme.onPrimary,
        playBorder = null,
        artShape = RoundedCornerShape(14.dp),
        translucent = true,
    )
}

@Composable
private fun SquareContent(
    card: MediaCardViewModel?,
    look: MediaCardLook,
    idle: String,
    parts: TileParts,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (parts.art) ArtThumbnail(card?.background, look.artShape, 48.dp)
            Titles(card, look, idle, Modifier.weight(1f))
        }
        Progress(card, look)
        Controls(card, look, parts)
    }
}

@Composable
private fun TallContent(
    card: MediaCardViewModel?,
    look: MediaCardLook,
    idle: String,
    parts: TileParts,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (parts.art) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ArtThumbnail(card?.background, look.artShape, minOf(maxWidth, maxHeight))
            }
            Titles(card, look, idle, Modifier.fillMaxWidth())
        } else {
            Titles(card, look, idle, Modifier.weight(1f).fillMaxWidth())
        }
        Progress(card, look)
        Controls(card, look, parts)
    }
}

@Composable
private fun FullContent(
    card: MediaCardViewModel?,
    look: MediaCardLook,
    idle: String,
    parts: TileParts,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (parts.art) {
            BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(1f)) {
                ArtThumbnail(card?.background, look.artShape, maxHeight)
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Titles(card, look, idle, Modifier.fillMaxWidth())
            Progress(card, look)
            Controls(card, look, parts)
        }
    }
}

@Composable
private fun Controls(card: MediaCardViewModel?, look: MediaCardLook, parts: TileParts) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (parts.skipButtons) Arrangement.SpaceBetween else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.skipButtons) Secondary(card?.navigation?.left, look, 36.dp)
        PlayPause(card?.playPauseAction, look, 44.dp)
        if (parts.skipButtons) Secondary(card?.navigation?.right, look, 36.dp)
    }
}

@Composable
private fun WideContent(
    card: MediaCardViewModel?,
    look: MediaCardLook,
    idle: String,
    parts: TileParts,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val art = (maxHeight - 16.dp).coerceIn(28.dp, 44.dp)
        val play = (maxHeight - 12.dp).coerceIn(32.dp, 40.dp)
        val skip = parts.skipButtons && maxWidth >= 240.dp
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (parts.art) ArtThumbnail(card?.background, look.artShape, art)
            Titles(card, look, idle, Modifier.weight(1f))
            if (skip) Secondary(card?.navigation?.left, look, 34.dp)
            PlayPause(card?.playPauseAction, look, play)
            if (skip) Secondary(card?.navigation?.right, look, 34.dp)
        }
    }
}

@Composable
private fun SmallContent(card: MediaCardViewModel?, look: MediaCardLook) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val art = (minOf(maxWidth, maxHeight) - 12.dp).coerceIn(24.dp, 56.dp)
        val action = card?.playPauseAction
        val onClick = action?.onClick
        Box(
            modifier =
                Modifier.size(art).let { if (onClick != null) it.clickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            ArtThumbnail(card?.background, look.artShape, art)
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                contentAlignment = Alignment.Center,
            ) {
                val icon = action?.icon
                if (icon != null) {
                    Icon(icon = icon, modifier = Modifier.size(15.dp), tint = Color.White)
                } else {
                    PlayGlyph(Color.White, Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun Titles(
    card: MediaCardViewModel?,
    look: MediaCardLook,
    idle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = card?.title ?: idle,
            style = MaterialTheme.typography.titleSmall,
            color = look.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = card?.subtitle.orEmpty()
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = look.text.copy(alpha = .72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Progress(card: MediaCardViewModel?, look: MediaCardLook) {
    val progress =
        ((card?.navigation as? MediaNavigationViewModel.Showing)?.progress ?: 0f).coerceIn(0f, 1f)
    val track = look.text.copy(alpha = .16f)
    when (look.progress) {
        MediaProgress.None -> {}
        MediaProgress.Segments ->
            Row(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val filled = (progress * SEGMENTS).roundToInt()
                repeat(SEGMENTS) { i ->
                    Box(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (i < filled) look.accent else track)
                    )
                }
            }
        MediaProgress.Wave ->
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val mid = size.height / 2
                val amp = size.height * .3f
                val width = size.width
                fun wave(from: Float, to: Float) =
                    Path().apply {
                        var x = from
                        moveTo(x, mid + amp * sin(x / width * PI.toFloat() * 14))
                        while (x < to) {
                            x = minOf(to, x + 2f)
                            lineTo(x, mid + amp * sin(x / width * PI.toFloat() * 14))
                        }
                    }
                val split = width * progress
                drawPath(wave(split, width), track, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(wave(0f, split), look.accent, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
            }
        else ->
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(2.dp))
                        .background(look.accent)
                )
            }
    }
}

@Composable
private fun PlayPause(action: MediaPlayPauseActionViewModel?, look: MediaCardLook, size: Dp) {
    val shape = RoundedCornerShape(percent = (look.playCorner * 100).roundToInt().coerceIn(0, 50))
    val border = look.playBorder
    val onClick = action?.onClick
    Box(
        modifier =
            Modifier.size(size)
                .clip(shape)
                .background(look.playButton)
                .let { if (border != null) it.border(look.borderWidth, border, shape) else it }
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        val icon = action?.icon
        if (icon != null) {
            Icon(icon = icon, modifier = Modifier.size(size * .46f), tint = look.playIcon)
        } else {
            PlayGlyph(look.playIcon, Modifier.size(size * .4f))
        }
    }
}

@Composable
private fun Secondary(action: MediaSecondaryActionViewModel?, look: MediaCardLook, size: Dp) {
    val button = action as? MediaSecondaryActionViewModel.Action
    val onClick = button?.onClick
    Box(
        modifier =
            Modifier.size(size).clip(CircleShape).let {
                if (onClick != null) it.clickable(onClick = onClick) else it
            },
        contentAlignment = Alignment.Center,
    ) {
        if (button != null) {
            Icon(icon = button.icon, modifier = Modifier.size(size * .55f), tint = look.text)
        }
    }
}

@Composable
private fun PlayGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val path =
            Path().apply {
                moveTo(size.width * .2f, size.height * .1f)
                lineTo(size.width * .9f, size.height * .5f)
                lineTo(size.width * .2f, size.height * .9f)
                close()
            }
        drawPath(path, color)
    }
}
