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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Detects whether the device is Pixel hardware with a functional
 * com.google.android.as (Android System Intelligence) install, in which
 * case Google's native ambient music indication (Now Playing) is expected
 * to already be active via the ported com.google.android.systemui.ambientmusic
 * stack. When this returns true, the fallback ambient indication in
 * com.android.systemui.nowplaying.ambient should not be started, and its
 * settings screen should present as inert.
 */
object PixelAmbientIndicationDetector {

    private const val AS_PACKAGE = "com.google.android.as"

    fun shouldUseNativeAmbientIndication(context: Context): Boolean {
        val isPixelBrand = Build.BRAND.equals("google", ignoreCase = true) &&
            Build.MANUFACTURER.equals("google", ignoreCase = true)
        if (!isPixelBrand) return false

        return try {
            context.packageManager.getApplicationInfo(AS_PACKAGE, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
