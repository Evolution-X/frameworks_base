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

import androidx.compose.ui.graphics.Color

object IslandAccents {
    /** Music / media. */
    val Music = Color(0xFFFF6F91)

    /** Flashlight / torch. */
    val Flashlight = Color(0xFFF5B84B)

    /** Countdown timer. */
    val Timer = Color(0xFFF5B84B)

    /** Next alarm. */
    val Alarm = Color(0xFFF5B84B)

    /** Screen recording (kept as featurepods' existing coral-red). */
    val Recording = Color(0xFFFF5A5F)

    /** Stopwatch. */
    val Stopwatch = Color(0xFF22C7D6)

    /** Live score. */
    val Score = Color(0xFF34D399)

    /** Added system events share the same fixed, feature-specific palette. */
    val Battery = Color(0xFF34D399)
    val Connectivity = Color(0xFF60A5FA)
    val Calls = Color(0xFF34D399)
    val Notifications = Color(0xFFA78BFA)
    val System = Color(0xFF22C7D6)

    /** Translucent fill used behind a feature glyph inside [IslandGlyphBadge]. */
    fun badgeFill(accent: Color): Color = accent.copy(alpha = 0.20f)
}
