/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0 
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
package com.android.systemui.nowplaying.ambient

import android.app.WallpaperColors
import android.content.Context
import android.content.theming.ThemeStyle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import com.android.systemui.monet.ColorScheme
import com.android.systemui.util.getColorWithAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adapted from Google's AmbientIndicationArtworkHelper, but working directly off a decoded
 * Bitmap (from MediaMetadata) rather than a content Uri resolved through ImageLoader, since
 * this fork's data source never has a Uri to begin with.
 */
object NowPlayingAmbientArtworkHelper {

    fun processFromBitmap(
        context: Context,
        sourceBitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        mainHandler: Handler,
        callback: (artwork: LayerDrawable?, colorScheme: ColorScheme?) -> Unit,
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            val wallpaperColors = WallpaperColors.fromBitmap(sourceBitmap)
            if (wallpaperColors == null) {
                mainHandler.post { callback(null, null) }
                return@launch
            }

            val colorScheme = ColorScheme(wallpaperColors, false, ThemeStyle.CONTENT)

            val scale =
                Math.max(targetWidth, targetHeight).toFloat() /
                    Math.max(sourceBitmap.width, sourceBitmap.height).toFloat()
            val scaledWidth = (sourceBitmap.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (sourceBitmap.height * scale).toInt().coerceAtLeast(1)

            val artworkBitmap =
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(artworkBitmap)
            val matrix =
                Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(
                        (targetWidth - scaledWidth) / 2f,
                        (targetHeight - scaledHeight) / 2f,
                    )
                }
            canvas.drawBitmap(sourceBitmap, matrix, null)

            val artworkDrawable = BitmapDrawable(context.resources, artworkBitmap)

            val scrimDrawable =
                GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0, 0), // placeholder colors, set below
                    )
                    .apply { shape = GradientDrawable.RECTANGLE }
            val secondaryTone20 = colorScheme.materialScheme.secondaryPalette.tone(20)
            scrimDrawable.colors =
                intArrayOf(
                    getColorWithAlpha(secondaryTone20, 0.65f),
                    getColorWithAlpha(secondaryTone20, 0.75f),
                )

            val layeredArtwork = LayerDrawable(arrayOf(artworkDrawable, scrimDrawable))

            mainHandler.post { callback(layeredArtwork, colorScheme) }
        }
    }
}
