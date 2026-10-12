/*
 * Copyright (C) 2026 Evolution X
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

package com.android.systemui.statusbar.pipeline.mobile.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * User preference for displaying the cellular radio access technology badge (LTE, 5G, etc.)
 * in the home status bar. The signal-strength bars are intentionally not affected.
 */
object NetworkTypeIconVisibility {
    const val SETTING = "status_bar_show_network_type"

    fun enabled(context: Context): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver,
            SETTING,
            1,
            UserHandle.USER_CURRENT,
        ) != 0

    /**
     * Emits a value immediately, then on every user-visible setting change.
     * Registering only while collected avoids permanent content-observer leaks.
     */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(enabled(context))
                }
            }
        resolver.registerContentObserver(
            Settings.System.getUriFor(SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        trySend(enabled(context))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()
}
