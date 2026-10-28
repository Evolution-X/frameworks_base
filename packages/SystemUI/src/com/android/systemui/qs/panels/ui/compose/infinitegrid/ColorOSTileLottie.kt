/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import androidx.annotation.RawRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.systemui.res.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The animation of a tile: how it turns on, and how it turns off when that differs. [scale] sizes
 * the clip against the icon box, for a glyph that ColorOS draws larger than the others.
 */
private class ColorOSTileClip(
    @RawRes val on: Int,
    @RawRes val off: Int? = null,
    val scale: Float = 1f,
)

private val ColorOSTileClips =
    mapOf(
        "wifi" to ColorOSTileClip(R.raw.coloros_tile_wifi),
        "internet" to ColorOSTileClip(R.raw.coloros_tile_wifi),
        // The rune spans 92% of the clip, 25.6dp in the 28dp box, where ColorOS draws it 30dp tall.
        "bt" to ColorOSTileClip(R.raw.coloros_tile_bluetooth, scale = 1.17f),
        "cell" to ColorOSTileClip(R.raw.coloros_tile_cellular),
        "airplane" to ColorOSTileClip(R.raw.coloros_tile_airplane),
        "dnd" to ColorOSTileClip(R.raw.coloros_tile_dnd),
        "location" to ColorOSTileClip(R.raw.coloros_tile_location),
        "rotation" to ColorOSTileClip(R.raw.coloros_tile_rotation, R.raw.coloros_tile_rotation_inactive),
        "flashlight" to ColorOSTileClip(R.raw.coloros_tile_flashlight),
        "hotspot" to ColorOSTileClip(R.raw.coloros_tile_hotspot),
        "battery" to ColorOSTileClip(R.raw.coloros_tile_battery),
        "nfc" to ColorOSTileClip(R.raw.coloros_tile_nfc, R.raw.coloros_tile_nfc_inactive),
        "night" to ColorOSTileClip(R.raw.coloros_tile_night, R.raw.coloros_tile_night_inactive),
    )

private object ColorOSTileLottie {
    private val compositions = ConcurrentHashMap<Int, Result<LottieComposition>>()

    suspend fun load(context: Context, @RawRes res: Int): LottieComposition? =
        compositions
            .getOrPut(res) {
                withContext(Dispatchers.IO) {
                    runCatching { LottieCompositionFactory.fromRawResSync(context, res).value!! }
                }
            }
            .getOrNull()
}

/** Remembers the color a tile has when it is on, as it is not told that while it is off. */
private class OnColor(var color: Color)

/**
 * Draws the ColorOS animation of the tile [spec] in a box of [size], and returns whether it did, so
 * the caller draws the regular icon when it did not. The animation plays when [active] changes and
 * rests on its first frame while the tile is off and on its last while it is on. [offColor] is the
 * color of the icon of a tile that is off and [onColor] the one of a tile that is on.
 */
@Composable
internal fun colorOSTileLottie(
    spec: String?,
    active: Boolean,
    offColor: Color,
    onColor: Color,
    size: Dp,
    modifier: Modifier = Modifier,
): Boolean {
    val clip = spec?.let { ColorOSTileClips[it] } ?: return false
    val context = LocalContext.current
    var shown by remember(spec) { mutableStateOf<LottieComposition?>(null) }
    var from by remember(spec) { mutableStateOf(active) }
    var playing by remember(spec) { mutableStateOf(false) }
    // The clip could not be loaded, so the regular icon is the only one there is.
    var failed by remember(spec) { mutableStateOf(false) }
    val progress = remember(spec) { Animatable(1f) }
    val lastOn = remember(spec) { OnColor(onColor) }
    if (active) lastOn.color = onColor
    LaunchedEffect(spec, active) {
        val forward = ColorOSTileLottie.load(context, clip.on) ?: run {
            shown = null
            failed = true
            return@LaunchedEffect
        }
        val changed = from != active
        from = active
        val turn = if (active) forward else clip.off?.let { ColorOSTileLottie.load(context, it) } ?: forward
        if (changed) {
            shown = turn
            playing = true
            progress.snapTo(0f)
            progress.animateTo(1f, tween(turn.duration.toInt(), easing = LinearEasing))
            playing = false
        }
        shown = forward
        progress.snapTo(if (active) 1f else 0f)
    }
    // While the clip loads nothing is drawn, so the regular icon does not flash before it.
    val composition = shown ?: return !failed
    val on = if (active) onColor else lastOn.color
    val start = if (playing && !active) on else offColor
    val end = if (playing && !active) offColor else on
    val properties =
        rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(LottieProperty.COLOR, start.toArgb(), "**", "startFill", "**"),
            rememberLottieDynamicProperty(LottieProperty.COLOR, end.toArgb(), "**", "endFill", "**"),
            rememberLottieDynamicProperty(
                LottieProperty.STROKE_COLOR,
                start.toArgb(),
                "**",
                "startStroke",
                "**",
            ),
            rememberLottieDynamicProperty(
                LottieProperty.STROKE_COLOR,
                end.toArgb(),
                "**",
                "endStroke",
                "**",
            ),
        )
    LottieAnimation(
        composition = composition,
        progress = { progress.value },
        dynamicProperties = properties,
        modifier = modifier.size(size * clip.scale),
    )
    return true
}
