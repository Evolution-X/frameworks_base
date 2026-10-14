/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

const val EDGE_LIGHT_DEFAULT_SPREAD = 0.00f
const val EDGE_LIGHT_DEFAULT_INTENSITY = 0.00f
const val EDGE_LIGHT_MODE_LOCKSCREEN = 0
const val EDGE_LIGHT_MODE_UNLOCKED = 1
const val EDGE_LIGHT_MODE_BOTH = 2

data class EdgeLightSettings(
    val isEnabled: Boolean,
    val colorMode: String,
    val customColor: Int,
    val pulseCount: Int,
    val strokeWidth: Int,
    val edgeStyle: String,
    val animationEffect: String,
    val spread: Float = EDGE_LIGHT_DEFAULT_SPREAD,
    val intensity: Float = EDGE_LIGHT_DEFAULT_INTENSITY,
    val displayMode: Int = EDGE_LIGHT_MODE_LOCKSCREEN,
    val triggerUnlockOnly: Boolean = false,
) {
    val showOnLockscreen: Boolean
        get() = isEnabled &&
            (displayMode == EDGE_LIGHT_MODE_LOCKSCREEN || displayMode == EDGE_LIGHT_MODE_BOTH)

    val showWhenUnlocked: Boolean
        get() = isEnabled &&
            (displayMode == EDGE_LIGHT_MODE_UNLOCKED || displayMode == EDGE_LIGHT_MODE_BOTH)
}

class EdgeLightSettingsRepository @Inject constructor(
    context: Context,
    private val userTracker: UserTracker,
    @Main private val handler: Handler,
    @Main private val executor: Executor,
) {
    private val resolver = context.contentResolver

    val settingsFlow: Flow<EdgeLightSettings> = callbackFlow {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(currentSettings())
            }
        }
        val userCallback = object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                trySend(currentSettings())
            }
        }
        SETTING_KEYS.forEach {
            resolver.registerContentObserver(
                Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL
            )
        }
        userTracker.addCallback(userCallback, executor)
        trySend(currentSettings())
        awaitClose {
            resolver.unregisterContentObserver(observer)
            userTracker.removeCallback(userCallback)
        }
    }.conflate().distinctUntilChanged()

    fun currentSettings(): EdgeLightSettings {
        val userId = userTracker.userId
        fun int(key: String, default: Int) =
            Settings.System.getIntForUser(resolver, key, default, userId)
        fun string(key: String, default: String) =
            Settings.System.getStringForUser(resolver, key, userId) ?: default

        return EdgeLightSettings(
            isEnabled = int(Settings.System.EDGE_LIGHT_ENABLED, 0) == 1,
            colorMode = string(Settings.System.EDGE_LIGHT_COLOR_MODE, "accent"),
            customColor = int(Settings.System.EDGE_LIGHT_CUSTOM_COLOR, Color.WHITE),
            pulseCount = int(Settings.System.EDGE_LIGHT_PULSE_COUNT, 3).coerceIn(1, 5),
            strokeWidth = int(Settings.System.EDGE_LIGHT_STROKE_WIDTH, 8).coerceIn(2, 32),
            edgeStyle = string(Settings.System.EDGE_LIGHT_STYLE, "default"),
            animationEffect = string(Settings.System.EDGE_LIGHT_ANIMATION_EFFECT, "none"),
            spread = (int(Settings.System.EDGE_LIGHT_SPREAD, 0) / 100f).coerceIn(0.05f, 1f),
            intensity = (int(Settings.System.EDGE_LIGHT_INTENSITY, 0) / 100f).coerceIn(0f, 1f),
            displayMode = int(Settings.System.EDGE_LIGHT_DISPLAY_MODE, EDGE_LIGHT_MODE_LOCKSCREEN)
                .takeIf { it in EDGE_LIGHT_MODE_LOCKSCREEN..EDGE_LIGHT_MODE_BOTH }
                ?: EDGE_LIGHT_MODE_LOCKSCREEN,
            triggerUnlockOnly = int(Settings.System.EDGE_LIGHT_TRIGGER_UNLOCK_ONLY, 0) == 1,
        )
    }

    companion object {
        private val SETTING_KEYS = arrayOf(
            Settings.System.EDGE_LIGHT_ENABLED,
            Settings.System.EDGE_LIGHT_DISPLAY_MODE,
            Settings.System.EDGE_LIGHT_COLOR_MODE,
            Settings.System.EDGE_LIGHT_CUSTOM_COLOR,
            Settings.System.EDGE_LIGHT_PULSE_COUNT,
            Settings.System.EDGE_LIGHT_STROKE_WIDTH,
            Settings.System.EDGE_LIGHT_STYLE,
            Settings.System.EDGE_LIGHT_ANIMATION_EFFECT,
            Settings.System.EDGE_LIGHT_SPREAD,
            Settings.System.EDGE_LIGHT_INTENSITY,
            Settings.System.EDGE_LIGHT_TRIGGER_UNLOCK_ONLY,
        )
    }
}
