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

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * Motion parameters recovered from IslandView.stepCards.
 *
 * Each spring is described the way the original is, by a response time in seconds and a damping
 * ratio, rather than by a stiffness constant. The specs animate unitless scale and pixel offsets,
 * so they use the default visibility threshold instead of the 0.4 px one a size animation would.
 */
internal object DynamicIslandMotion {
    fun springFor(
        response: Float,
        damping: Float,
        threshold: Float = Spring.DefaultDisplacementThreshold,
    ): SpringSpec<Float> =
        spring(
            dampingRatio = damping,
            stiffness = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat(),
            visibilityThreshold = threshold,
        )

    val OpenWidth = springFor(0.40f, 0.76f)
    val OpenHeight = springFor(0.48f, 0.78f)
    val OpenPosition = springFor(0.46f, 0.74f)
    val CloseWidth = springFor(0.30f, 0.92f)
    val CloseHeight = springFor(0.34f, 0.90f)
    val ClosePosition = springFor(0.40f, 0.90f)
    val PillShrink = springFor(0.34f, 0.86f, threshold = 0.0005f)
}
