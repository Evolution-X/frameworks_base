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

package com.android.systemui.statusbar.quickactions.popups.shared

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object DynamicIslandFeatureSettings {
    const val MEDIA_CONTROLS = "status_bar_dynamic_island_media_controls"
    const val SCREEN_RECORDING = "status_bar_dynamic_island_screen_recording"
    const val ALARMS = "status_bar_dynamic_island_alarms"
    const val FLASHLIGHT = "status_bar_dynamic_island_flashlight"
    const val STOPWATCH = "status_bar_dynamic_island_stopwatch"
    const val LIVE_SCORES = "status_bar_dynamic_island_live_scores"
    const val SHOW_LYRICS = "status_bar_dynamic_island_lyrics"
    const val WIDTH_SCALE = "status_bar_dynamic_island_width_scale"
    const val HEIGHT_SCALE = "status_bar_dynamic_island_height_scale"

    const val SCALE_MIN = 0.7f
    const val SCALE_MAX = 1.4f
    private const val SCALE_DEFAULT = 1.0f
    private const val SCALE_PERCENT_DEFAULT = 100

    fun ContentResolver.readDynamicIslandFeatureEnabled(
        key: String,
        defaultValue: Boolean = true,
    ): Boolean {
        return Settings.System.getIntForUser(
            this,
            key,
            if (defaultValue) 1 else 0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    fun ContentResolver.readDynamicIslandScale(key: String): Float {
        val percent =
            Settings.System.getIntForUser(
                this,
                key,
                SCALE_PERCENT_DEFAULT,
                UserHandle.USER_CURRENT,
            )
        return (percent / 100f).coerceIn(SCALE_MIN, SCALE_MAX)
    }

    fun observeDynamicIslandFeatureEnabled(
        context: Context,
        key: String,
        defaultValue: Boolean = true,
    ): Flow<Boolean> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(
                            context.contentResolver.readDynamicIslandFeatureEnabled(
                                key,
                                defaultValue,
                            )
                        )
                    }
                }

            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandFeatureEnabled(key, defaultValue))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    fun observeDynamicIslandScale(
        context: Context,
        key: String,
    ): Flow<Float> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(context.contentResolver.readDynamicIslandScale(key))
                    }
                }

            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandScale(key))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
}
