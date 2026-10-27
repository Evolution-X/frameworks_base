/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon as AndroidIcon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
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

/**
 * square media card for the ColorOS-style Quick Settings (half the panel width), laid out
 * like ColorOS: app icon at the top, title and artist, then previous, play/pause and next in one
 * row, over the album art. Tapping the card opens the media app.
 */
@Composable
internal fun ColorOSMediaCard(
    data: MediaData,
    onOpen: (PendingIntent) -> Unit,
    modifier: Modifier = Modifier,
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
    val appIcon by
        produceState<Drawable?>(initialValue = null, data.appIcon) {
            value = data.appIcon?.let { loadIcon(it, context) }
        }
    val buttons = data.semanticActions
    val shape = RoundedCornerShape(32.dp)

    Box(modifier.clip(shape).background(Color(0xFF2C2C2E))) {
        artwork?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Darken the art so white text and icons stay readable, stronger at the bottom.
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.60f))
                    )
                )
        )
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .clickable(enabled = data.clickIntent != null) {
                        data.clickIntent?.let(onOpen)
                    }
                    .semantics {
                        contentDescription =
                            listOfNotNull(data.song, data.artist, data.app).joinToString(", ")
                    }
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)
        ) {
            appIcon?.let { icon ->
                Icon(
                    painter = rememberDrawablePainter(icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = data.song?.toString().orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = data.artist?.toString().orEmpty(),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                ColorOSMediaButton(buttons?.prevOrCustom, iconSize = 22.dp)
                ColorOSMediaButton(buttons?.playOrPause, iconSize = 30.dp)
                ColorOSMediaButton(buttons?.nextOrCustom, iconSize = 22.dp)
            }
        }
    }
}

@Composable
private fun ColorOSMediaButton(action: MediaAction?, iconSize: Dp) {
    val icon = action?.icon
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = action?.action != null) { action?.action?.run() }
                .semantics { contentDescription = action?.contentDescription?.toString().orEmpty() },
    ) {
        if (icon != null) {
            Icon(
                painter = rememberDrawablePainter(icon),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

private suspend fun loadIcon(icon: AndroidIcon, context: Context): Drawable? =
    withContext(Dispatchers.IO) { runCatching { icon.loadDrawable(context) }.getOrNull() }
