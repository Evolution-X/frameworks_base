/*
 * SPDX-FileCopyrightText: The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private val ColorOSMediaAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

/** Outputs ColorOS shows as headphones: the wired and USB ones, and the Bluetooth ones for music. */
private fun isColorOSHeadphoneType(type: Int): Boolean =
    when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

/**
 * Whether the media sound is routed to headphones right now, so the ColorOS volume slider and the
 * output button of the media card show headphones and not the speaker. It is read again whenever
 * an audio device is added or removed.
 */
@Composable
internal fun rememberColorOSHeadphonesOutput(): Boolean {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var headphones by remember { mutableStateOf(false) }
    DisposableEffect(audioManager) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        fun update() {
            headphones =
                audioManager.getDevicesForAttributes(ColorOSMediaAttributes).any {
                    isColorOSHeadphoneType(it.type)
                }
        }
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    update()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    update()
                }
            }
        update()
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return headphones
}
