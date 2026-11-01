/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberAlbumArtAccent(artworkDrawable: Drawable?, fallback: Color): Color {
    var extracted by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkDrawable) {
        extracted = null
        if (artworkDrawable == null) return@LaunchedEffect
        val rgb =
            withContext(Dispatchers.Default) {
                runCatching {
                    val bitmap = artworkDrawable.toBitmap(width = 64, height = 64)
                    val palette = Palette.from(bitmap).generate()
                    palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                }
                    .getOrNull()
            }
        extracted = rgb?.let { Color(it) }
    }

    return animateColorAsState(
            targetValue = extracted ?: fallback,
            animationSpec = tween(durationMillis = 450),
            label = "album_art_accent",
        )
        .value
}
