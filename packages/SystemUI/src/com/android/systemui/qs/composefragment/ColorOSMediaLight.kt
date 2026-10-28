/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.ux.material.libmonet.hct.Hct
import com.google.ux.material.libmonet.scheme.SchemeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long the light stays on after the art is gone, so a track change does not flash it off. */
private const val ART_GONE_DELAY_MS = 600L

/** Side of the copy of the album art the color is taken from. */
private const val ART_SAMPLE_SIZE = 96

/** The color the light takes from the album art, and a fingerprint to tell one cover from another. */
private data class ColorOSMediaArt(val color: Int, val signature: Int)

/**
 * Lights the card from behind with the color of [artwork]: a glow along its edge and a ripple from
 * [artworkCenter], relative to the top left of the card, when the cover changes. Nothing is drawn
 * while there is no artwork. [corner] is the corner radius of the card.
 */
@Composable
internal fun Modifier.colorOSMediaLight(
    artwork: ImageBitmap?,
    corner: Dp,
    artworkCenter: Offset,
): Modifier {
    val light = remember { runCatching { ColorOSCardLight() }.getOrNull() } ?: return this
    val night = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val density = LocalDensity.current
    var frame by remember { mutableIntStateOf(0) }

    val callback = remember {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
    }
    DisposableEffect(light) {
        light.callback = callback
        onDispose { light.callback = null }
    }

    val art = rememberColorOSMediaArt(artwork, night)

    var measured by remember { mutableIntStateOf(0) }
    LaunchedEffect(art.color, night, measured) {
        if (art.color == 0) {
            light.clear()
        } else {
            light.setColor(art.color, night)
        }
    }

    val ready = measured != 0 && art.color != 0
    LaunchedEffect(art.signature, ready) {
        if (!ready) return@LaunchedEffect
        // The ripple is placed from the center of the card.
        light.playRipple(
            artworkCenter.x - light.bounds.width() / 2f,
            artworkCenter.y - light.bounds.height() / 2f,
        )
    }

    return onSizeChanged {
            light.setBounds(0, 0, it.width, it.height)
            with(density) { light.setCornerRadius(corner.toPx()) }
            measured = it.width * 31 + it.height
        }
        .drawWithContent {
            // Read so a frame of the light redraws the card.
            frame
            drawIntoCanvas { light.draw(it.nativeCanvas) }
            drawContent()
        }
}

@Composable
private fun rememberColorOSMediaArt(artwork: ImageBitmap?, night: Boolean): ColorOSMediaArt {
    val art by
        produceState(ColorOSMediaArt(0, 0), artwork, night) {
            val next =
                if (artwork == null) {
                    delay(ART_GONE_DELAY_MS)
                    ColorOSMediaArt(0, 0)
                } else {
                    withContext(Dispatchers.Default) { colorOSMediaArt(artwork, night) }
                }
            if (next != value) value = next
        }
    return art
}

private fun colorOSMediaArt(artwork: ImageBitmap, night: Boolean): ColorOSMediaArt =
    runCatching {
            val source = artwork.asAndroidBitmap()
            // A hardware bitmap cannot be read pixel by pixel.
            val readable =
                if (source.config == Bitmap.Config.HARDWARE) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    source
                }
            val bitmap =
                Bitmap.createScaledBitmap(readable, ART_SAMPLE_SIZE, ART_SAMPLE_SIZE, true)
            val seed = WallpaperColors.fromBitmap(bitmap).primaryColor.toArgb()
            val color = SchemeContent(Hct.fromInt(seed), night, 0.0).primaryPalette.tone(80)
            ColorOSMediaArt(color, signatureOf(bitmap))
        }
        .getOrDefault(ColorOSMediaArt(0, 0))

private fun signatureOf(bitmap: Bitmap): Int {
    var hash = 17
    val step = bitmap.width / 8
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val pixel = bitmap.getPixel(x * step + step / 2, y * step + step / 2)
            hash = hash * 31 + ((pixel ushr 4) and 0x0F0F0F)
        }
    }
    return hash
}
