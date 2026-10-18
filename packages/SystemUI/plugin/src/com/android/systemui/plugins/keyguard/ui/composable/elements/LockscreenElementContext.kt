/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.keyguard.ui.composable.elements

import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.Key
import com.android.systemui.plugins.keyguard.VRectF

/** Combined context for lockscreen elements. Contains relevant rendering parameters. */
data class LockscreenElementContext(
    /** Modifier for non-clock elemnents that require burn-in mitigation */
    val burnInAware: Modifier = Modifier,

    /** Modifier for clock elements that require burn-in mitigation */
    val burnInAwareClock: Modifier = Modifier,

    /** Callback executed when an element is positioned by compose. */
    val onElementPositioned: (Key, VRectF) -> Unit = { _, _ -> },

    /** Callback executed when a previously positioned element leaves the composition. */
    val onElementUnpositioned: (Key) -> Unit = {},

    /** Modifier to apply to elements that should be hidden when only showing authUI when dozing */
    val nonAuthUI: Modifier = Modifier,
)
