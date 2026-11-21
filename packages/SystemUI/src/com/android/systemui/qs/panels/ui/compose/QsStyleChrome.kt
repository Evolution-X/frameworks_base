/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions
import com.android.systemui.qs.panels.shared.model.LocalQsGlass
import com.android.systemui.qs.panels.shared.model.LocalQsGlassTune
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsGlassTune
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.glassAlphas
import com.android.systemui.qs.panels.ui.compose.infinitegrid.inkOn
import com.android.systemui.qs.panels.ui.compose.infinitegrid.liquidRim
import com.android.systemui.res.R

fun SliderColors.glassy(glass: QsGlass, tune: QsGlassTune): SliderColors {
    val (on, off) = glassAlphas(glass, tune) ?: return this
    return copy(
        activeTrackColor = activeTrackColor.copy(alpha = activeTrackColor.alpha * on),
        inactiveTrackColor =
            inactiveTrackColor.copy(alpha = inactiveTrackColor.alpha * off.coerceAtLeast(.5f)),
    )
}

/** The style the brightness slider takes its look from. */
@Composable
@ReadOnlyComposable
fun qsSliderStyle(): QsStyle = LocalQsTweaks.current.sliderFrom ?: LocalQsStyle.current

/** Brightness slider colors for [style], or null for the stock ones. */
@Composable
fun qsStyleSliderColors(style: QsStyle): SliderColors? {
    val tone = LocalQsTweaks.current.accent?.let { Color(it) }
    val theme = MaterialTheme.colorScheme
    val scheme = if (tone == null) theme else theme.copy(primary = tone, onPrimary = tone.inkOn())
    val vivid = tone ?: colorResource(android.R.color.system_primary_dark)
    val vividInk = tone?.inkOn() ?: colorResource(android.R.color.system_on_primary_dark)
    return when (style) {
        QsStyle.REMIX ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = .16f),
                    activeTickColor = tone ?: colorResource(android.R.color.system_accent1_800),
                    inactiveTickColor = Color.White,
                )
        QsStyle.GLASS ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary.copy(alpha = .55f),
                    inactiveTrackColor = Color.White.copy(alpha = .05f),
                    activeTickColor = Color.White,
                    inactiveTickColor = scheme.onSurface,
                )
        QsStyle.CLASSIC ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = .18f),
                )
        QsStyle.NEON,
        QsStyle.BLUEPRINT ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = vivid,
                    activeTrackColor = vivid,
                    inactiveTrackColor =
                        Color.White.copy(alpha = if (style == QsStyle.NEON) .1f else .25f),
                )
        QsStyle.BENTO,
        QsStyle.SHAPES ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = vivid,
                    activeTrackColor = vivid,
                    inactiveTrackColor = Color.White.copy(alpha = .1f),
                    activeTickColor = vividInk,
                    inactiveTickColor = scheme.onSurface,
                )
        QsStyle.DUO ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = vivid,
                    activeTrackColor = vivid.copy(alpha = .3f),
                    inactiveTrackColor = Color.White.copy(alpha = .07f),
                    activeTickColor = scheme.onSurface,
                    inactiveTickColor = scheme.onSurface,
                )
        QsStyle.SOFT ->
            SliderDefaults.colors()
                .copy(
                    thumbColor = vivid,
                    activeTrackColor = vivid,
                    inactiveTrackColor = Color(0xFF110D0C),
                )
        else -> null
    }
}

fun qsStyleSliderDimensions(
    style: QsStyle,
    default: BrightnessSliderDimensions,
): BrightnessSliderDimensions =
    when (style) {
        QsStyle.COMPACT ->
            default.copy(
                iconSize = DpSize(20.dp, 20.dp),
                thumbHeight = 36.dp,
                trackHeight = 28.dp,
                verticalPadding = 2.dp,
            )
        QsStyle.CLASSIC ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 20.dp,
                thumbWidth = 20.dp,
                trackHeight = 4.dp,
                verticalPadding = 10.dp,
            )
        QsStyle.GLASS -> default.copy(trackHeight = 36.dp, thumbHeight = 46.dp)
        QsStyle.REMIX -> default.copy(trackHeight = 44.dp, thumbHeight = 52.dp, verticalPadding = 0.dp)
        QsStyle.NEON ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 22.dp,
                thumbWidth = 22.dp,
                trackHeight = 6.dp,
                verticalPadding = 10.dp,
            )
        QsStyle.BENTO -> default.copy(trackHeight = 52.dp, thumbHeight = 60.dp, verticalPadding = 0.dp)
        QsStyle.SHAPES -> default.copy(trackHeight = 52.dp, thumbHeight = 64.dp, verticalPadding = 0.dp)
        QsStyle.BLUEPRINT ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 26.dp,
                thumbWidth = 2.dp,
                trackHeight = 2.dp,
                verticalPadding = 10.dp,
            )
        QsStyle.DUO ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 32.dp,
                trackHeight = 52.dp,
                verticalPadding = 0.dp,
            )
        QsStyle.SOFT ->
            default.copy(
                iconSize = DpSize(0.dp, 0.dp),
                thumbHeight = 30.dp,
                thumbWidth = 30.dp,
                trackHeight = 18.dp,
                verticalPadding = 8.dp,
            )
        else -> default
    }

/** What sits around the brightness slider: Remix puts it in an accent card, Classic adds a sun. */
@Composable
fun QsStyleSliderFrame(style: QsStyle, content: @Composable () -> Unit) {
    val glass = LocalQsGlass.current
    val tune = LocalQsGlassTune.current
    val see = glassAlphas(glass, tune)?.first ?: 1f
    val rim = glass == QsGlass.LIQUID
    val tone = LocalQsTweaks.current.accent?.let { Color(it) }
    when (style) {
        QsStyle.REMIX -> {
            val top = tone ?: colorResource(android.R.color.system_accent1_600)
            val bottom =
                tone?.copy(alpha = .55f) ?: colorResource(android.R.color.system_accent1_900)
            val shape = RoundedCornerShape(28.dp)
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(top, bottom),
                                start = Offset.Zero,
                                end = Offset(900f, 600f),
                            ),
                            shape,
                            alpha = see,
                        )
                        .then(if (rim) Modifier.liquidRim(shape, tune.sheen) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                content()
            }
        }
        QsStyle.CLASSIC,
        QsStyle.NEON,
        QsStyle.BLUEPRINT,
        QsStyle.SOFT -> {
            val sun =
                when (style) {
                    QsStyle.CLASSIC -> tone ?: MaterialTheme.colorScheme.primary
                    QsStyle.NEON -> tone ?: colorResource(android.R.color.system_primary_dark)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
                }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_brightness_full),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(sun),
                    modifier = Modifier.size(22.dp),
                )
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }
        // Duo: the sun in its own accent segment, the slider beside it.
        QsStyle.DUO ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val segment = RoundedCornerShape(18.dp, 6.dp, 6.dp, 18.dp)
                val fill = tone ?: colorResource(android.R.color.system_primary_dark)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(52.dp)
                            .background(fill.copy(alpha = fill.alpha * see), segment)
                            .then(if (rim) Modifier.liquidRim(segment, tune.sheen) else Modifier),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brightness_full),
                        contentDescription = null,
                        colorFilter =
                            ColorFilter.tint(
                                tone?.inkOn()
                                    ?: colorResource(android.R.color.system_on_primary_dark)
                            ),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        else -> content()
    }
}
