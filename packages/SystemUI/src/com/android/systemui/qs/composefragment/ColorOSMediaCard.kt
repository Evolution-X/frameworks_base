/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.MediaControlDrawables
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * keeps the media sessions shown in the ColorOS-style QS media slot.
 *
 * Fed by [MediaDataManager], which is the media pipeline in both the legacy (view) and the compose
 * media modes, so it works whichever one the device uses (some builds still use the legacy one).
 */
class ColorOSMediaSessions : MediaDataManager.Listener {
    private val entries = mutableStateMapOf<String, MediaData>()

    /** The session to show: a playing one first, otherwise the latest active one. */
    val current: MediaData?
        get() {
            val active = entries.values.filter { it.active }
            return active.lastOrNull { it.isPlaying == true } ?: active.lastOrNull()
        }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
    ) {
        if (oldKey != null && oldKey != key) entries.remove(oldKey)
        // Re-insert so the most recently updated session is last.
        entries.remove(key)
        entries[key] = data
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        entries.remove(key)
    }
}

private val ColorOSMediaArtSize = 46.dp
private val ColorOSMediaArtShape = RoundedCornerShape(10.dp)
private val ColorOSMediaButtonSize = 44.dp
private val ColorOSMediaOutputIconSize = 24.dp

/** The icon of the app in the top left while the art fills the card, as big as the output one. */
private val ColorOSMediaAppIconSize = ColorOSMediaOutputIconSize

/** Corner radius of the glass slot the card sits on, which the light along its edge follows. */
private val ColorOSMediaLightCorner = 32.dp

/** Colour of the text and icons of the card while the album art fills it. */
private val ColorOSMediaOnArt = Color.White

/** Darkening of the album art behind the text and the controls, from the top to the bottom. */
private const val ColorOSMediaScrimTop = 0.25f
private const val ColorOSMediaScrimBottom = 0.60f

/** Alpha of a control that cannot be used, like the dimmed glyphs of ColorOS when idle. */
private const val ColorOSMediaDimAlpha = 0.35f

/**
 * Media card for the ColorOS-style Quick Settings (two columns of the tile grid), laid out like
 * ColorOS: album art thumbnail at the top left, output switcher at the top right, title and artist,
 * then previous, play/pause and next in one row. The glass behind it comes from the slot, so the
 * card itself draws no background. Tapping the card opens the media app.
 */
@Composable
internal fun ColorOSMediaCard(
    data: MediaData,
    onOpen: (PendingIntent) -> Unit,
    onOutputSwitcher: (MediaData) -> Unit,
    modifier: Modifier = Modifier,
    artFill: Boolean = false,
) {
    val context = LocalContext.current
    val artwork by
        produceState<ImageBitmap?>(initialValue = null, data.artwork) {
            value =
                data.artwork?.let { icon ->
                    withContext(Dispatchers.IO) {
                        runCatching { icon.loadDrawable(context)?.toBitmap()?.asImageBitmap() }
                            .getOrNull()
                    }
                }
        }
    val buttons = data.semanticActions
    val playing = data.isPlaying == true
    val outputEnabled = data.device?.enabled != false && !data.resumption
    val deviceIcon = rememberDrawableBitmap(data.device?.icon, ColorOSMediaOutputIconSize)
    // With the art filling the card the text and icons sit on the art and not on the glass, so they
    // are light whatever the theme is. Without art there is nothing to fill, the thumbnail layout
    // stays.
    val art = artwork
    val fillArt = artFill && art != null
    // The thumbnail is gone while the art fills the card, the icon of the app takes its place.
    val appIcon by
        produceState<Drawable?>(initialValue = null, data.appIcon, fillArt) {
            value =
                data.appIcon?.takeIf { fillArt }?.let { icon ->
                    withContext(Dispatchers.IO) {
                        runCatching { icon.loadDrawable(context) }.getOrNull()
                    }
                }
        }
    val appIconBitmap = rememberDrawableBitmap(appIcon, ColorOSMediaAppIconSize)
    val scheme = MaterialTheme.colorScheme
    val contentScheme =
        if (fillArt) {
            scheme.copy(onSurface = ColorOSMediaOnArt, onSurfaceVariant = ColorOSMediaOnArt)
        } else {
            scheme
        }
    val onSurface = contentScheme.onSurface

    val previousGlyph = painterResource(R.drawable.ic_coloros_media_previous)
    val nextGlyph = painterResource(R.drawable.ic_coloros_media_next)
    val playPauseGlyph =
        painterResource(
            if (playing) R.drawable.ic_coloros_media_pause else R.drawable.ic_coloros_media_play
        )

    // Only the standard previous and next actions get the ColorOS glyphs. The slots can also hold
    // a custom action of the app (like, shuffle, ...), which keeps the icon the app provides.
    val prev = buttons?.prevOrCustom
    val next = buttons?.nextOrCustom
    val prevIsStandard = prev != null && prev.icon === MediaControlDrawables.getPrevIcon(context)
    val nextIsStandard = next != null && next.icon === MediaControlDrawables.getNextIcon(context)

    // The light in the color of the art starts from the thumbnail. With the art over the whole
    // card the art is the light, so it has none.
    val density = LocalDensity.current
    val thumbnailCenter =
        with(density) {
            Offset(
                (16.dp + ColorOSMediaArtSize / 2).toPx(),
                (14.dp + ColorOSMediaArtSize / 2).toPx(),
            )
        }
    Box(
        modifier.then(
            if (fillArt) {
                Modifier
            } else {
                Modifier.colorOSMediaLight(art, ColorOSMediaLightCorner, thumbnailCenter)
            }
        )
    ) {
        if (fillArt && art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Keeps the text and the controls readable over bright art.
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = ColorOSMediaScrimTop),
                                Color.Black.copy(alpha = ColorOSMediaScrimBottom),
                            )
                        )
                    )
            )
        }
        MaterialTheme(colorScheme = contentScheme) {
            ColorOSMediaLayout(
                modifier = Modifier.fillMaxSize(),
                showThumbnail = !fillArt,
                appIcon = appIconBitmap.takeIf { fillArt },
                description = listOfNotNull(data.song, data.artist, data.app).joinToString(", "),
                onClick = data.clickIntent?.let { intent -> { onOpen(intent) } },
                thumbnail = {
                    val bitmap = artwork
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ColorOSMediaNoteTile()
                    }
                },
                output = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.size(ColorOSMediaButtonSize)
                                .clip(CircleShape)
                                .clickable(enabled = outputEnabled) { onOutputSwitcher(data) }
                                .semantics {
                                    contentDescription = data.device?.name?.toString().orEmpty()
                                },
                    ) {
                        val tint =
                            onSurface.copy(alpha = if (outputEnabled) 1f else ColorOSMediaDimAlpha)
                        if (deviceIcon != null) {
                            Icon(
                                bitmap = deviceIcon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(ColorOSMediaOutputIconSize),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_media_home_devices),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(ColorOSMediaOutputIconSize),
                            )
                        }
                    }
                },
                title = data.song?.toString().orEmpty(),
                subtitle = data.artist?.toString().orEmpty(),
                controls = {
                    ColorOSMediaButton(
                        action = prev,
                        glyph = if (prevIsStandard) previousGlyph else null,
                        iconSize = 22.dp,
                    )
                    ColorOSMediaButton(
                        action = buttons?.playOrPause,
                        glyph = playPauseGlyph,
                        iconSize = 26.dp,
                    )
                    ColorOSMediaButton(
                        action = next,
                        glyph = if (nextIsStandard) nextGlyph else null,
                        iconSize = 22.dp,
                    )
                },
            )
        }
    }
}

/**
 * The media card of the ColorOS-style Quick Settings while nothing is playing: the same layout as
 * the playing card, with a placeholder tile, the cast icon and dimmed controls, like ColorOS.
 */
@Composable
internal fun ColorOSIdleMediaCard(modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ColorOSMediaLayout(
        modifier = modifier,
        description = stringResource(R.string.qs_coloros_no_media),
        onClick = null,
        thumbnail = { ColorOSMediaNoteTile() },
        output = {
            Box(Modifier.size(ColorOSMediaButtonSize), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_coloros_media_output),
                    contentDescription = null,
                    tint = onSurface,
                    modifier = Modifier.size(ColorOSMediaOutputIconSize),
                )
            }
        },
        title = null,
        subtitle = stringResource(R.string.qs_coloros_no_media),
        controls = {
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_previous),
                iconSize = 22.dp,
            )
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_play),
                iconSize = 26.dp,
            )
            ColorOSMediaButton(
                action = null,
                glyph = painterResource(R.drawable.ic_coloros_media_next),
                iconSize = 22.dp,
            )
        },
    )
}

/**
 * Layout shared by the playing and the idle card. [title] is skipped when null, so the idle text
 * sits on the artist line. The top left corner holds the thumbnail, or with [showThumbnail] off the
 * [appIcon], if there is one.
 */
@Composable
private fun ColorOSMediaLayout(
    modifier: Modifier,
    showThumbnail: Boolean = true,
    appIcon: ImageBitmap? = null,
    description: String,
    onClick: (() -> Unit)?,
    thumbnail: @Composable () -> Unit,
    output: @Composable () -> Unit,
    title: String?,
    subtitle: String,
    controls: @Composable () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .clickable(enabled = onClick != null) { onClick?.invoke() }
                    .semantics { contentDescription = description }
                    // Side insets are applied per item: the controls sit closer to the edges
                    // than the text, and the row ends 3dp above the bottom like ColorOS.
                    .padding(top = 14.dp, bottom = 3.dp)
        ) {
            val textInset = Modifier.padding(start = 16.dp, end = 12.dp)
            if (showThumbnail) {
                Box(
                    Modifier.padding(start = 16.dp)
                        .size(ColorOSMediaArtSize)
                        .clip(ColorOSMediaArtShape)
                ) {
                    thumbnail()
                }
            } else if (appIcon != null) {
                Icon(
                    bitmap = appIcon,
                    contentDescription = null,
                    tint = onSurface,
                    modifier = Modifier.padding(start = 16.dp).size(ColorOSMediaAppIconSize),
                )
            }
            Spacer(Modifier.weight(1f))
            if (title != null) {
                Text(
                    text = title,
                    modifier = textInset,
                    color = onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle,
                modifier = textInset,
                color =
                    if (title != null) onSurface.copy(alpha = 0.75f)
                    else onSurface.copy(alpha = 0.5f),
                style =
                    if (title != null) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                // 44dp targets with 5dp inset: the prev/next icons are centred 27dp from the edges.
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 2.dp),
            ) {
                controls()
            }
        }
        // The output button is centred 26dp below the top and 27dp from the right edge.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp)) { output() }
    }
}

/** Placeholder for the album art: a faint tile with a music note. */
@Composable
private fun ColorOSMediaNoteTile() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * One transport button. Uses [glyph] when given, otherwise the icon of [action] (a custom action
 * of the app). Without a usable action the button is dimmed, and a null [action] with no [glyph]
 * leaves an empty slot, so the other buttons keep their place.
 */
@Composable
private fun ColorOSMediaButton(action: MediaAction?, glyph: Painter?, iconSize: Dp) {
    val enabled = action?.action != null
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else ColorOSMediaDimAlpha)
    val actionIcon = rememberDrawableBitmap(if (glyph == null) action?.icon else null, iconSize)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(ColorOSMediaButtonSize)
                .clip(CircleShape)
                .clickable(enabled = enabled) { action?.action?.run() }
                .semantics { contentDescription = action?.contentDescription?.toString().orEmpty() },
    ) {
        if (glyph != null) {
            Icon(
                painter = glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        } else if (actionIcon != null) {
            Icon(
                bitmap = actionIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Returns a static snapshot of a drawable of the media pipeline (an action icon or the output
 * device icon).
 *
 * These [Drawable]s are shared with the stock media player (the QQS/QS player binds the very same
 * instance to its ImageButton), so they must never be handed to a Compose painter.
 * `rememberDrawablePainter` takes over the drawable: it replaces its callback, starts it when it is
 * an [android.graphics.drawable.Animatable] and overwrites its colour filter. For the play/pause
 * AnimatedVectorDrawable that runs the pause to play morph, which left the stock QQS player showing
 * a play glyph while media was playing, and the white filter beat the player's own tint, giving a
 * white icon on its light button.
 *
 * So a private copy is drawn once into a bitmap instead, which shows the icon in its initial,
 * non-animated frame and leaves the original untouched.
 */
@Composable
private fun rememberDrawableBitmap(icon: Drawable?, size: Dp): ImageBitmap? {
    val resources = LocalContext.current.resources
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    return remember(icon, sizePx) {
        icon?.let { source ->
            // Fall back to the source if it has no constant state; toBitmap() restores its bounds
            // and only reads from it.
            val copy = source.constantState?.newDrawable(resources)?.mutate() ?: source
            runCatching { copy.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
        }
    }
}
