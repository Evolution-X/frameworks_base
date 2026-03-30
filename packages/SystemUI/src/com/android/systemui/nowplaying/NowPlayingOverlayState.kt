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
package com.android.systemui.nowplaying

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlayingMediaState(
    val track: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val progress: Float = 0f,
    val playbackSpeed: Float = 1f,
    val positionUpdateTime: Long = 0L,
    val packageName: String = "",
    val outputDeviceName: String = "",
    val albumArt: Bitmap? = null,
    val useWaveformSeekBar: Boolean = false,
)

object NowPlayingOverlayState {
    private val _state = MutableStateFlow(NowPlayingMediaState())
    val state: StateFlow<NowPlayingMediaState> = _state.asStateFlow()

    private val _isOverlayOpen = MutableStateFlow(false)
    val isOverlayOpen: StateFlow<Boolean> = _isOverlayOpen.asStateFlow()

    fun update(block: NowPlayingMediaState.() -> NowPlayingMediaState) {
        _state.value = _state.value.block()
    }

    fun setOverlayOpen(open: Boolean) {
        _isOverlayOpen.value = open
    }
}

