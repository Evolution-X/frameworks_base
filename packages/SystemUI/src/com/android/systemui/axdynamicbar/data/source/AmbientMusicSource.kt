/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.data.source

import android.app.PendingIntent
import kotlinx.coroutines.flow.Flow

/**
 * A song recognized from ambient audio (Now Playing). SystemUI cannot depend on the vendor module
 * that does the recognition, so that module implements [AmbientMusicSource] and binds it. Nothing
 * is bound on builds without it, and the Now Playing island then comes from the notification.
 */
data class AmbientMusic(
    /** Raw indication text, e.g. "Song by Artist". Used when [songTitle] is not provided. */
    val text: String? = null,
    val songTitle: String? = null,
    val artistName: String? = null,
    /** True while a song is still being searched for; there is nothing to show yet. */
    val isSearching: Boolean = false,
    /** Opens the source app (or a search for the song) when the island is tapped. */
    val openIntent: PendingIntent? = null,
)

interface AmbientMusicSource {
    /** The current recognized song, or `null` when none. */
    val ambientMusic: Flow<AmbientMusic?>
}
