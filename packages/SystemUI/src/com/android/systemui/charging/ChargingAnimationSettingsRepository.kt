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
package com.android.systemui.charging

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

data class ChargingAnimationSettings(
    val isEnabled: Boolean,
    val animationStyle: Int,
    val colorMode: Int,
    val rippleOpacity: Float,
    val glowIntensity: Float,
    val arcCount: Int,
    val showOnAod: Boolean,
    val showOnLockscreen: Boolean
)

class ChargingAnimationSettingsRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    private val DEFAULT_ANIMATION_STYLE = 0
    private val DEFAULT_COLOR_MODE = 0
    private val DEFAULT_RIPPLE_OPACITY = 60
    private val DEFAULT_GLOW_INTENSITY = 80
    private val DEFAULT_ARC_COUNT = 3

    val settingsFlow: Flow<ChargingAnimationSettings> = combine(
        observeSettingInt(SETTING_ENABLED, 0),
        observeSettingInt(SETTING_ANIMATION_STYLE, DEFAULT_ANIMATION_STYLE),
        observeSettingInt(SETTING_COLOR_MODE, DEFAULT_COLOR_MODE),
        observeSettingInt(SETTING_RIPPLE_OPACITY, DEFAULT_RIPPLE_OPACITY),
        observeSettingInt(SETTING_GLOW_INTENSITY, DEFAULT_GLOW_INTENSITY),
        observeSettingInt(SETTING_ARC_COUNT, DEFAULT_ARC_COUNT),
        observeSettingInt(SETTING_SHOW_ON_AOD, 1),
        observeSettingInt(SETTING_SHOW_ON_LOCKSCREEN, 1)
    ) { flows: Array<Any?> ->
        val enabled = flows[0] as Int
        val style = flows[1] as Int
        val colorMode = flows[2] as Int
        val rippleOpacity = flows[3] as Int
        val glowIntensity = flows[4] as Int
        val arcCount = flows[5] as Int
        val showAod = flows[6] as Int
        val showLock = flows[7] as Int
        
        val styleClamped = style.coerceIn(0, 7)
        val colorModeClamped = colorMode.coerceIn(0, 2)
        val opacityClamped = (rippleOpacity / 100f).coerceIn(0f, 1f)
        val intensityClamped = (glowIntensity / 100f).coerceIn(0f, 1f)
        val arcCountClamped = arcCount.coerceIn(1, 6)
        
        ChargingAnimationSettings(
            isEnabled = enabled == 1,
            animationStyle = styleClamped,
            colorMode = colorModeClamped,
            rippleOpacity = opacityClamped,
            glowIntensity = intensityClamped,
            arcCount = arcCountClamped,
            showOnAod = showAod == 1,
            showOnLockscreen = showLock == 1
        )
    }.distinctUntilChanged()

    fun currentSettings(): ChargingAnimationSettings {
        val style = Settings.System.getIntForUser(
            resolver, 
            SETTING_ANIMATION_STYLE, 
            DEFAULT_ANIMATION_STYLE, 
            UserHandle.USER_CURRENT
        )
        val colorMode = Settings.System.getIntForUser(
            resolver,
            SETTING_COLOR_MODE,
            DEFAULT_COLOR_MODE,
            UserHandle.USER_CURRENT
        )
        val rippleOpacity = Settings.System.getIntForUser(
            resolver, 
            SETTING_RIPPLE_OPACITY, 
            DEFAULT_RIPPLE_OPACITY, 
            UserHandle.USER_CURRENT
        )
        val glowIntensity = Settings.System.getIntForUser(
            resolver, 
            SETTING_GLOW_INTENSITY, 
            DEFAULT_GLOW_INTENSITY, 
            UserHandle.USER_CURRENT
        )
        val arcCount = Settings.System.getIntForUser(
            resolver, 
            SETTING_ARC_COUNT, 
            DEFAULT_ARC_COUNT, 
            UserHandle.USER_CURRENT
        )
        
        return ChargingAnimationSettings(
            isEnabled = Settings.System.getIntForUser(
                resolver, SETTING_ENABLED, 0, UserHandle.USER_CURRENT
            ) == 1,
            animationStyle = style.coerceIn(0, 7),
            colorMode = colorMode.coerceIn(0, 2),
            rippleOpacity = (rippleOpacity / 100f).coerceIn(0f, 1f),
            glowIntensity = (glowIntensity / 100f).coerceIn(0f, 1f),
            arcCount = arcCount.coerceIn(1, 6),
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
        private const val TAG = "ChargingAnimSettings"
        private const val SETTING_ENABLED = "charging_animation_enabled"
        private const val SETTING_ANIMATION_STYLE = "charging_animation_style"
        private const val SETTING_COLOR_MODE = "charging_color_mode"
        private const val SETTING_RIPPLE_OPACITY = "charging_ripple_opacity"
        private const val SETTING_GLOW_INTENSITY = "charging_glow_intensity"
        private const val SETTING_ARC_COUNT = "charging_arc_count"
        private const val SETTING_SHOW_ON_AOD = "charging_show_on_aod"
        private const val SETTING_SHOW_ON_LOCKSCREEN = "charging_show_on_lockscreen"
    }
}
