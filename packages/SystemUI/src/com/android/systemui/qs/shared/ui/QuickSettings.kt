/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.shared.ui

import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ValueKey
import com.android.systemui.qs.pipeline.shared.TileSpec

object QuickSettings {
    /** Element keys to be used by the compose implementation of QS for animations. */
    object Elements {
        val QuickSettingsContent = ElementKey("QuickSettingsContent")
        val GridAnchor = ElementKey("QuickSettingsGridAnchor")
        val FooterActions = ElementKey("QuickSettingsFooterActions")
        val BrightnessSlider = ElementKey("BrightnessSlider")
        val MaterialControlPanel = ElementKey("MaterialControlPanel")

        fun TileSpec.toElementKey() = ElementKey(this.spec, TileIdentity(this))

        val TileElementMatcher = ElementKey.withIdentity { it is TileIdentity }

        val QuickQuickSettingsAndMedia = ElementKey("QuickQuickSettingsAndMedia")
        val SplitShadeQuickSettings = ElementKey("SplitShadeQuickSettings")
    }

    object SharedValues {
        val TilesSquishiness = ValueKey("QuickSettingsTileSquishiness")

        object SquishinessValues {
            val Default = 1f
            val LockscreenSceneStarting = 0f
            val GoneSceneStarting = 0.3f
            val OccludedSceneStarting = 0.3f
        }
    }
}

private data class TileIdentity(val spec: TileSpec)
