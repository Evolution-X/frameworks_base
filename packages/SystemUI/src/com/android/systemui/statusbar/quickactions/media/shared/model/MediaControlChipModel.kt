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

package com.android.systemui.statusbar.quickactions.media.shared.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.media.controls.shared.model.MediaAction

/** Model used to display and control media from the status bar island. */
    data class MediaControlChipModel(
        val appIcon: Icon?,
        val artworkIcon: Icon?,
        val appName: String?,
        val artistName: CharSequence?,
        val songName: CharSequence?,
        val playOrPause: MediaAction?,
        val nextAction: MediaAction?,
        val previousAction: MediaAction?,
        val openApp: (() -> Unit)?,
        val seekTo: ((Long) -> Unit)?,
        val durationMs: Long,
        val positionMs: Long,
        val canBeScrubbed: Boolean,
        val isPlaying: Boolean,
        val packageName: String? = null,
        val lyrics: String? = null,
        val syncedLyrics: String? = null,
)
