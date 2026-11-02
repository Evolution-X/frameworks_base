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

package com.android.systemui.statusbar.quickactions.charging.shared.model

import androidx.compose.runtime.Immutable

/** One stat shown in the charging popup, e.g. `value = "2.4"`, `unit = "A"`. */
@Immutable data class ChargingDetail(val label: String, val value: String, val unit: String)

/** Contents of the island's charging popup. */
@Immutable
data class ChargingPopupModel(
    val title: String,
    /** Secondary line, e.g. the time remaining and whether battery saver is on. */
    val subtitle: String,
    /** Battery level in percent, 0..100. */
    val level: Int,
    val isWireless: Boolean,
    val isPowerSave: Boolean,
    /** What the charger reports; empty when it reports nothing. */
    val details: List<ChargingDetail>,
    val onOpenSettings: (() -> Unit)?,
)
