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

package com.android.systemui.statusbar.quickactions.livescore.shared.model

import com.android.systemui.common.shared.model.Icon

/** Popup content for live scores surfaced in the dynamic island. */
data class LiveScoreChipModel(
    val key: String,
    val icon: Icon?,
    val appName: String,
    val title: String?,
    val score: String,
    val subtitle: String?,
    val onOpen: (() -> Unit)?,
)
