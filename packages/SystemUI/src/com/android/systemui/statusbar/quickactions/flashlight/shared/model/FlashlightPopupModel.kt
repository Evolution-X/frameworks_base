/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.flashlight.shared.model

/** Popup content for the flashlight page in the dynamic island. */
data class FlashlightPopupModel(
    /** Current strength step, or null when the flashlight only switches on and off. */
    val level: Int? = null,
    /** Highest strength step the hardware supports. */
    val maxLevel: Int? = null,
    val turnOff: () -> Unit,
    /** Applies [level] while it is being dragged, without saving it as the new default. */
    val setLevelTemporary: (Int) -> Unit = {},
    /** Applies [level] and keeps it as the strength for the next time. */
    val setLevel: (Int) -> Unit = {},
) {
    /** The strength as a percentage, or null when there is no adjustable strength. */
    val strengthPercent: Int?
        get() {
            val maximum = maxLevel?.takeIf { it > 1 } ?: return null
            val current = level ?: return null
            return kotlin.math.round(current.coerceIn(0, maximum) * 100f / maximum).toInt()
        }
}
