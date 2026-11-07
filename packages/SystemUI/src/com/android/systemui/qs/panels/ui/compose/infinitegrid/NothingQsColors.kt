/*
 * Copyright (C) 2026 The Evolution X Project
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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.android.systemui.res.R

/** Whether the Nothing style uses the Nothing OS 5.0 palette, see [NothingQsColors]. */
internal val LocalNothingQsOsColors = compositionLocalOf { false }

@Composable
fun rememberQSNothingOsColors(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    // Both Nothing OS 5.0 styles, flat and translucent, take the palette, only 0 keeps the
    // dark grey of the style. The panel behind it is up to ShadeColors.
    fun readOsColorsEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_NOTHING_COLOR_STYLE, 1,
                UserHandle.USER_CURRENT
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var osColorsEnabled by remember { mutableStateOf(readOsColorsEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    osColorsEnabled = readOsColorsEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_NOTHING_COLOR_STYLE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return osColorsEnabled
}

/**
 * Colors of the Nothing QS panel style. With the dark grey color style they are the dark grey and
 * white of the style whatever the theme is. With the Nothing OS 5.0 color styles they follow the
 * light and dark theme and come from the nothing_os_* resources. The panel behind them is flat
 * in one of those styles and translucent in the other, see ShadeColors.
 */
object NothingQsColors {
    @Composable
    @ReadOnlyComposable
    private fun pick(@ColorRes os: Int, @ColorRes legacy: Int): Color =
        colorResource(if (LocalNothingQsOsColors.current) os else legacy)

    val surface: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_surface, R.color.nothing_qs_surface)

    val active: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_active, R.color.nothing_qs_active)

    val iconCircle: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_icon_circle, R.color.nothing_qs_icon_circle)

    val divider: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_divider, R.color.nothing_qs_divider)

    val dividerOnActive: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_divider_on_active, R.color.nothing_qs_divider_on_active)

    val sliderTrack: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_slider_track, R.color.nothing_qs_surface)

    val sliderFill: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_slider_fill, R.color.nothing_qs_slider_fill)

    val sliderIconOnFill: Color
        @Composable @ReadOnlyComposable
        get() = pick(R.color.nothing_os_slider_icon_on_fill, R.color.nothing_qs_slider_icon_on_fill)

    /** Icons and labels on a surface that is off. */
    val content: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalNothingQsOsColors.current) {
            colorResource(R.color.nothing_os_content)
        } else {
            Color.White
        }

    /** Icons and labels on a surface that is on. */
    val contentOnActive: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalNothingQsOsColors.current) {
            colorResource(R.color.nothing_os_content_on_active)
        } else {
            Color.Black
        }
}
