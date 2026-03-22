/*
 * Copyright (C) 2026 MistOS
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

package com.android.systemui.qs.panels.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun IosControlPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cr = context.contentResolver

    fun readEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                cr, "qs_ios_control_panel", 0, UserHandle.USER_CURRENT
            ) == 1
        } catch (_: Exception) { false }
    }

    var enabled by remember { mutableStateOf(readEnabled()) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                enabled = readEnabled()
            }
        }
        try {
            cr.registerContentObserver(
                Settings.System.getUriFor("qs_ios_control_panel"),
                false, observer, UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}
        onDispose { cr.unregisterContentObserver(observer) }
    }

    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(250)) + fadeOut(tween(250)),
        modifier = modifier,
    ) {
        IosControlPanelContent()
    }
}

@Composable
private fun IosControlPanelContent() {
    val panelHeight = 160.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.weight(2.5f).fillMaxHeight()) {
            IosMusicPlayer(modifier = Modifier.fillMaxWidth().fillMaxHeight())
        }

        IosVerticalBrightnessSlider(
            modifier = Modifier.weight(0.7f).fillMaxHeight().widthIn(max = 68.dp)
        )

        IosVerticalVolumeSlider(
            modifier = Modifier.weight(0.7f).fillMaxHeight().widthIn(max = 68.dp)
        )
    }
}
