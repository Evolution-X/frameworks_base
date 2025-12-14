/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.ActivityManager
import android.content.Context
import android.os.SystemProperties
import android.provider.Settings
import android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class CustomColorScheme(private val context: Context) {

    private fun supportsBlursOnWindows(): Boolean {
        val blurEnabledByDefault = SystemProperties.getBoolean("ro.custom.blur.enable", false)
        val defaultSetting = if (blurEnabledByDefault) 0 else 1
        return CROSS_WINDOW_BLUR_SUPPORTED &&
            ActivityManager.isHighEndGfx() &&
            !SystemProperties.getBoolean("persist.sysui.disableBlur", false) &&
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DISABLE_WINDOW_BLURS,
                defaultSetting
            ) != 1
    }

    val qsTileColor: Color
        get() {
            val blurEnabled = supportsBlursOnWindows()

            val useAlternateColor = Settings.System.getInt(
                context.contentResolver,
                Settings.System.QS_TILE_ALTERNATE_COLOR,
                0
            ) == 1

            val colorRes = if (blurEnabled) {
                if (useAlternateColor)
                    com.android.internal.R.color.surface_effect_2
                else
                    com.android.internal.R.color.surface_effect_1
            } else {
                com.android.internal.R.color.materialColorSurfaceBright
            }

            return Color(context.resources.getColor(colorRes, context.theme))
        }

    companion object {
        val current: CustomColorScheme
            @Composable
            @ReadOnlyComposable
            get() = CustomColorScheme(LocalContext.current)
    }
}
