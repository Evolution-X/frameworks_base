/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.volume.dialog.percentage.domain

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog

@VolumeDialogScope
class VolumeDialogVolumePercentageInteractor
@Inject
constructor(
    @Application private val context: Context,
    @VolumeDialog private val coroutineScope: CoroutineScope,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun isEnabled(): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SHOW_VOLUME_PERCENTAGE,
            0,
            UserHandle.USER_CURRENT,
        ) == 1

    private fun currentPercentage(): String {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) "${(current * 100 / max)}%" else "0%"
    }

    private fun observeSettings() = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(isEnabled())
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SHOW_VOLUME_PERCENTAGE),
            false,
            observer,
            UserHandle.USER_CURRENT,
        )
        trySend(isEnabled())
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val isVisible: StateFlow<Boolean> =
        observeSettings()
            .stateIn(coroutineScope, SharingStarted.Eagerly, isEnabled())

    val percentage: StateFlow<String> =
        callbackFlow {
            trySend(currentPercentage())
            awaitClose {}
        }.stateIn(coroutineScope, SharingStarted.Eagerly, currentPercentage())
}
