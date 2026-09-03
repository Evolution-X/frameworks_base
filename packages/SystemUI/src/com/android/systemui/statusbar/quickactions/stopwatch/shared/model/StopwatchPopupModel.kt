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

package com.android.systemui.statusbar.quickactions.stopwatch.shared.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

/** Popup content for stopwatch status inside the dynamic island. */
data class StopwatchPopupModel(
    val icon: Icon?,
    val title: String,
    val subtitle: String?,
    val baseElapsedRealtimeMs: Long,
    val isRunning: Boolean,
    val elapsedTimeText: String?,
    val onOpen: (() -> Unit)?,
    val actions: List<PopupActionModel> = emptyList(),
)
