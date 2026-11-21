/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.qs.panels.shared.model.LocalQsGlass
import com.android.systemui.qs.panels.shared.model.LocalQsGlassTune
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsMediaArt
import com.android.systemui.qs.panels.shared.model.QsMediaBar
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.ui.compose.infinitegrid.BlobShape
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SoftSurface
import com.android.systemui.qs.panels.ui.compose.infinitegrid.glassAlphas
import com.android.systemui.qs.panels.ui.compose.infinitegrid.inkOn
import com.android.systemui.qs.panels.ui.compose.infinitegrid.liquidRim

/** Media card look for the QS style: a plain card with the artwork as a thumbnail. */
internal data class MediaCardLook(
    val background: Color,
    val border: Color?,
    val radius: Dp,
    val text: Color,
    val accent: Color,
    val onAccent: Color,
    val playButton: Color,
    val playIcon: Color,
    val playBorder: Color?,
    val artShape: Shape,
    val translucent: Boolean = false,
    /** Play button corner radius as a fraction of its size; one half is round. */
    val playCorner: Float = .5f,
    val progress: MediaProgress = MediaProgress.Line,
    val borderWidth: Dp = 1.5.dp,
    /** Soft: a light rim at the top left and a dark one at the bottom right. */
    val bevel: Boolean = false,
)

/** How the styled card shows playback progress. */
internal enum class MediaProgress {
    Line,
    /** Neon: the line glows. */
    Glow,
    /** Blueprint: a row of blocks. */
    Segments,
    /** Shapes: the played part waves. */
    Wave,
    /** Soft: pressed into the card. */
    Channel,
    None,
}

/** True for the lockscreen card, where see-through styles get a dark base over the wallpaper. */
val LocalMediaOnLockscreen = staticCompositionLocalOf { false }

private val LockscreenBase = Color(0x9E0E0F11)
private val ShapesArt = BlobShape(9, .06f)

/** QS card layout from the canvas. The lockscreen keeps the stock rows. */
internal enum class QsMediaShape(val height: Dp) {
    /** Artwork, title and play button over a thin progress line. */
    Card(108.dp),
    /** One short row with round artwork. */
    Pill(60.dp),
    /** Artwork on top, title and play button below. Sits next to the Circles sliders. */
    Tall(172.dp),
    /** Bento: big artwork beside the title, progress and controls. */
    Big(156.dp),
    /** Duo: the artwork and the rest in two segments. */
    Split(108.dp),
}

/** True for the collapsed QS card, which stays short. */
internal val LocalMediaInQqs = staticCompositionLocalOf { false }

/** The QS layout for the current style, or null for the stock rows. */
@Composable
@ReadOnlyComposable
internal fun qsMediaShape(): QsMediaShape? {
    if (LocalMediaOnLockscreen.current) return null
    return when (LocalQsStyle.current) {
        QsStyle.STOCK -> null
        QsStyle.COMPACT -> QsMediaShape.Pill
        QsStyle.CIRCLES -> if (LocalMediaInQqs.current) QsMediaShape.Card else QsMediaShape.Tall
        QsStyle.BENTO -> if (LocalMediaInQqs.current) QsMediaShape.Card else QsMediaShape.Big
        QsStyle.DUO -> QsMediaShape.Split
        else -> QsMediaShape.Card
    }
}

/** True for the Circles QQS card, which grows into the tall QS card as the shade expands. */
@Composable
@ReadOnlyComposable
internal fun qsMediaCardGrowsToTall(): Boolean =
    !LocalMediaOnLockscreen.current &&
        LocalQsStyle.current == QsStyle.CIRCLES &&
        LocalMediaInQqs.current

/** The look for the current QS style, or null for the stock album art card. */
@Composable
@ReadOnlyComposable
internal fun mediaCardLook(): MediaCardLook? {
    val tweaks = LocalQsTweaks.current
    val tone = tweaks.accent?.let { Color(it) }
    val theme = MaterialTheme.colorScheme
    val scheme = if (tone == null) theme else theme.copy(primary = tone, onPrimary = tone.inkOn())
    val surface = LocalAndroidColorScheme.current.surfaceEffect1
    val art = RoundedCornerShape(14.dp)
    val lock = LocalMediaOnLockscreen.current
    val vivid = tone ?: colorResource(android.R.color.system_primary_dark)
    val vividInk = tone?.inkOn() ?: colorResource(android.R.color.system_on_primary_dark)
    val look: MediaCardLook? = when (LocalQsStyle.current) {
        QsStyle.STOCK -> null
        QsStyle.REMIX ->
            MediaCardLook(
                background = Color(0xFF1E2024),
                border = null,
                radius = if (lock) 28.dp else 22.dp,
                text = Color(0xFFEDEEF0),
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = tone ?: colorResource(android.R.color.system_accent1_600),
                playIcon = tone?.inkOn() ?: Color.White,
                playBorder = null,
                artShape = art,
            )
        QsStyle.COMPACT ->
            MediaCardLook(
                background = Color.White.copy(alpha = .09f),
                border = null,
                radius = if (lock) 32.dp else 30.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary,
                playIcon = scheme.onPrimary,
                playBorder = null,
                artShape = CircleShape,
                translucent = true,
            )
        QsStyle.GLASS ->
            MediaCardLook(
                background = Color.White.copy(alpha = .035f),
                border = Color.White.copy(alpha = .13f),
                radius = if (lock) 24.dp else 22.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary.copy(alpha = .2f),
                playIcon = scheme.primary,
                playBorder = scheme.primary,
                artShape = art,
                translucent = true,
            )
        QsStyle.CLASSIC ->
            MediaCardLook(
                background = Color.White.copy(alpha = .06f),
                border = null,
                radius = 16.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = Color.Transparent,
                playIcon = scheme.primary,
                playBorder = scheme.primary,
                artShape = RoundedCornerShape(10.dp),
                translucent = true,
            )
        QsStyle.NEON ->
            MediaCardLook(
                background = Color(0xFF0B080C),
                border = vivid.copy(alpha = .9f),
                radius = 24.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = vivid.copy(alpha = .12f),
                playIcon = vivid,
                playBorder = vivid,
                artShape = art,
                progress = MediaProgress.Glow,
            )
        QsStyle.BENTO ->
            MediaCardLook(
                background = surface,
                border = null,
                radius = 26.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = vivid,
                playIcon = vividInk,
                playBorder = null,
                artShape = RoundedCornerShape(18.dp),
                translucent = true,
            )
        QsStyle.SHAPES ->
            MediaCardLook(
                background = surface,
                border = null,
                radius = 32.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = vivid,
                playIcon = vividInk,
                playBorder = null,
                artShape = ShapesArt,
                translucent = true,
                playCorner = .32f,
                progress = MediaProgress.Wave,
            )
        QsStyle.BLUEPRINT ->
            MediaCardLook(
                background = Color.Transparent,
                border = Color.White.copy(alpha = .18f),
                radius = 10.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = vivid.copy(alpha = .08f),
                playIcon = vivid,
                playBorder = vivid,
                artShape = RoundedCornerShape(6.dp),
                translucent = true,
                playCorner = .18f,
                progress = MediaProgress.Segments,
                borderWidth = 1.dp,
            )
        QsStyle.DUO ->
            MediaCardLook(
                background = Color.White.copy(alpha = .07f),
                border = null,
                radius = 22.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = vivid,
                playIcon = vividInk,
                playBorder = null,
                artShape = RoundedCornerShape(22.dp, 6.dp, 6.dp, 22.dp),
                translucent = true,
                playCorner = .3f,
            )
        QsStyle.SOFT ->
            MediaCardLook(
                background = SoftSurface,
                border = null,
                radius = 26.dp,
                text = scheme.onSurface,
                accent = vivid,
                onAccent = vividInk,
                playButton = SoftSurface,
                playIcon = vivid,
                playBorder = Color.White.copy(alpha = .08f),
                artShape = RoundedCornerShape(16.dp),
                progress = MediaProgress.Channel,
                bevel = true,
            )
        // Circles and Cards.
        else ->
            MediaCardLook(
                background = surface,
                border = null,
                radius = 26.dp,
                text = scheme.onSurface,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                playButton = scheme.primary,
                playIcon = scheme.onPrimary,
                playBorder = null,
                artShape = art,
                translucent = true,
            )
    }
    return look?.withTweaks(tweaks)
}

/** The artwork shape and progress picked in the customizer. */
internal fun MediaCardLook.withTweaks(tweaks: QsTweaks): MediaCardLook =
    copy(
        artShape =
            when (tweaks.mediaArt) {
                QsMediaArt.ROUNDED -> RoundedCornerShape(14.dp)
                QsMediaArt.CIRCLE -> CircleShape
                QsMediaArt.BLOB -> ShapesArt
                else -> artShape
            },
        progress =
            when (tweaks.mediaBar) {
                QsMediaBar.LINE -> MediaProgress.Line
                QsMediaBar.GLOW -> MediaProgress.Glow
                QsMediaBar.BLOCKS -> MediaProgress.Segments
                QsMediaBar.WAVE -> MediaProgress.Wave
                QsMediaBar.NONE -> MediaProgress.None
                null -> progress
            },
    )

/** The card fill that replaces the album art background. */
@Composable
internal fun StyledCardBackground(look: MediaCardLook, modifier: Modifier = Modifier) {
    val shape = remember(look.radius) { RoundedCornerShape(look.radius) }
    val lockscreen = LocalMediaOnLockscreen.current
    val base = if (look.translucent && lockscreen) LockscreenBase else Color.Transparent
    val glass = if (lockscreen) QsGlass.SOLID else LocalQsGlass.current
    val tune = LocalQsGlassTune.current
    val see = glassAlphas(glass, tune)?.first ?: 1f
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(base)
                .background(look.background.copy(alpha = look.background.alpha * see))
                .let { if (look.border != null) it.border(look.borderWidth, look.border, shape) else it }
                .let { if (look.bevel) it.bevel(shape) else it }
                .let {
                    if (glass == QsGlass.LIQUID) it.liquidRim(shape, tune.sheen) else it
                }
    )
}

/** Soft: light catches the top left edge, the bottom right falls into shadow. */
internal fun Modifier.bevel(shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val rim =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = .1f),
            .5f to Color.Transparent,
            1f to Color.Black.copy(alpha = .5f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    // The card clips to its shape, so half the stroke shows.
    val stroke = Stroke(width = 3.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawOutline(outline, rim, style = stroke)
    }
}

/** The artwork, shown small next to the title. */
@Composable
internal fun ArtThumbnail(image: Icon?, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
    if (LocalQsTweaks.current.mediaArt == QsMediaArt.NONE) return
    val bitmap = remember(image) { (image as? Icon.Loaded)?.asImageBitmap() }
    Box(modifier = modifier.size(size).clip(shape).background(Color.White.copy(alpha = .1f))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The artwork filling the card, darkened so the text stays readable. */
@Composable
internal fun CoverBackground(image: Icon?, modifier: Modifier = Modifier) {
    val bitmap = remember(image) { (image as? Icon.Loaded)?.asImageBitmap() }
    Box(modifier = modifier.background(Color.White.copy(alpha = .1f))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .7f))
                        )
                    )
        )
    }
}
