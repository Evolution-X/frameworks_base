/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

data class NowPlayingSettings(
    val isEnabled: Boolean,
    val useAccentColor: Boolean,
    val iconStyle: Int,
    val iconSize: Int,
    val useCompactStyle: Boolean,
    val verticalPosition: Float,
    val trackTextSize: Float,
    val artistTextSize: Float,
    val showOnAod: Boolean,
    val showOnLockscreen: Boolean
)

class NowPlayingSettingsRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    private val DEFAULT_VERTICAL_POSITION = 98
    private val DEFAULT_TRACK_SIZE = 14
    private val DEFAULT_ARTIST_SIZE = 12
    private val DEFAULT_ICON_STYLE = 0
    private val DEFAULT_ICON_SIZE = 24

    val settingsFlow: Flow<NowPlayingSettings> = combine(
        observeSettingInt(SETTING_ENABLED, 0),
        observeSettingInt(SETTING_USE_ACCENT_COLOR, 0),
        observeSettingInt(SETTING_ICON_STYLE, DEFAULT_ICON_STYLE),
        observeSettingInt(SETTING_ICON_SIZE, DEFAULT_ICON_SIZE),
        observeSettingInt(SETTING_USE_COMPACT_STYLE, 0),
        observeSettingInt(SETTING_VERTICAL_POSITION, DEFAULT_VERTICAL_POSITION),
        observeSettingInt(SETTING_TRACK_TEXT_SIZE, DEFAULT_TRACK_SIZE),
        observeSettingInt(SETTING_ARTIST_TEXT_SIZE, DEFAULT_ARTIST_SIZE),
        observeSettingInt(SETTING_SHOW_ON_AOD, 1),
        observeSettingInt(SETTING_SHOW_ON_LOCKSCREEN, 1)
    ) { flows: Array<Any?> ->
        val enabled = flows[0] as Int
        val useAccent = flows[1] as Int
        val iconStyle = flows[2] as Int
        val iconSize = flows[3] as Int
        val compactStyle = flows[4] as Int
        val positionInt = flows[5] as Int
        val trackSize = flows[6] as Int
        val artistSize = flows[7] as Int
        val showAod = flows[8] as Int
        val showLock = flows[9] as Int
        val position = (positionInt / 100f).coerceIn(0.1f, 1.0f)
        val iconStyleClamped = iconStyle.coerceIn(0, 2)
        val iconSizeClamped = iconSize.coerceIn(5, 40)
        val trackSizeClamped = trackSize.toFloat().coerceIn(8f, 24f)
        val artistSizeClamped = artistSize.toFloat().coerceIn(5f, 20f)
        
        NowPlayingSettings(
            isEnabled = enabled == 1,
            useAccentColor = useAccent == 1,
            iconStyle = iconStyleClamped,
            iconSize = iconSizeClamped,
            useCompactStyle = compactStyle == 1,
            verticalPosition = position,
            trackTextSize = trackSizeClamped,
            artistTextSize = artistSizeClamped,
            showOnAod = showAod == 1,
            showOnLockscreen = showLock == 1
        )
    }.distinctUntilChanged()

    fun currentSettings(): NowPlayingSettings {
        val positionInt = Settings.System.getIntForUser(
            resolver, 
            SETTING_VERTICAL_POSITION, 
            DEFAULT_VERTICAL_POSITION, 
            UserHandle.USER_CURRENT
        )
        val trackSizeInt = Settings.System.getIntForUser(
            resolver, 
            SETTING_TRACK_TEXT_SIZE, 
            DEFAULT_TRACK_SIZE, 
            UserHandle.USER_CURRENT
        )
        val artistSizeInt = Settings.System.getIntForUser(
            resolver, 
            SETTING_ARTIST_TEXT_SIZE, 
            DEFAULT_ARTIST_SIZE, 
            UserHandle.USER_CURRENT
        )
        val iconStyle = Settings.System.getIntForUser(
            resolver,
            SETTING_ICON_STYLE,
            DEFAULT_ICON_STYLE,
            UserHandle.USER_CURRENT
        )
        val iconSize = Settings.System.getIntForUser(
            resolver,
            SETTING_ICON_SIZE,
            DEFAULT_ICON_SIZE,
            UserHandle.USER_CURRENT
        )
        
        return NowPlayingSettings(
            isEnabled = Settings.System.getIntForUser(
                resolver, SETTING_ENABLED, 0, UserHandle.USER_CURRENT
            ) == 1,
            useAccentColor = Settings.System.getIntForUser(
                resolver, SETTING_USE_ACCENT_COLOR, 0, UserHandle.USER_CURRENT
            ) == 1,
            iconStyle = iconStyle.coerceIn(0, 2),
            iconSize = iconSize.coerceIn(5, 40),
            useCompactStyle = Settings.System.getIntForUser(
                resolver, SETTING_USE_COMPACT_STYLE, 0, UserHandle.USER_CURRENT
            ) == 1,
            verticalPosition = (positionInt / 100f).coerceIn(0.1f, 1.0f),
            trackTextSize = trackSizeInt.toFloat().coerceIn(8f, 24f),
            artistTextSize = artistSizeInt.toFloat().coerceIn(5f, 20f),
            showOnAod = Settings.System.getIntForUser(
                resolver, SETTING_SHOW_ON_AOD, 1, UserHandle.USER_CURRENT
            ) == 1,
            showOnLockscreen = Settings.System.getIntForUser(
                resolver, SETTING_SHOW_ON_LOCKSCREEN, 1, UserHandle.USER_CURRENT
            ) == 1
        )
    }

    private fun observeSettingInt(key: String, default: Int): Flow<Int> = callbackFlow {
        val uri = Settings.System.getUriFor(key)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val value = Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)
                trySend(value)
            }
        }
        
        try {
            resolver.registerContentObserver(uri, false, observer)
            val initialValue = Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)
            trySend(initialValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error observing setting: $key", e)
            trySend(default)
        }
        
        awaitClose { 
            try {
                resolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering observer: $key", e)
            }
        }
    }.distinctUntilChanged()

    companion object {
        private const val TAG = "NowPlayingSettings"
        private const val SETTING_ENABLED = "nowplaying_enabled"
        private const val SETTING_USE_ACCENT_COLOR = "nowplaying_use_accent_color"
        private const val SETTING_ICON_STYLE = "nowplaying_icon_style"
        private const val SETTING_ICON_SIZE = "nowplaying_icon_size"
        private const val SETTING_USE_COMPACT_STYLE = "nowplaying_use_compact_style"
        private const val SETTING_VERTICAL_POSITION = "nowplaying_vertical_position"
        private const val SETTING_TRACK_TEXT_SIZE = "nowplaying_track_text_size"
        private const val SETTING_ARTIST_TEXT_SIZE = "nowplaying_artist_text_size"
        private const val SETTING_SHOW_ON_AOD = "nowplaying_show_on_aod"
        private const val SETTING_SHOW_ON_LOCKSCREEN = "nowplaying_show_on_lockscreen"
    }
}