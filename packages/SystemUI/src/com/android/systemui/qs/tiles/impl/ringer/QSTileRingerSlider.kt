/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.qs.tiles.impl.ringer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ringer.RingerSliderWidget
import com.android.systemui.common.ringer.RingerModeInteractorImpl
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileShapeMode

@Composable
fun QSTileRingerSlider(
    border: Modifier = Modifier
) {
    val context = LocalContext.current

    val interactor = remember {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        RingerModeInteractorImpl(context, audioManager, notificationManager)
    }

    val shapeMode = rememberTileShapeMode()

    // 0: Default, 1: Circle, 2: Rounded Square, 3: Square
    val containerCornerRadius = when (shapeMode) {
        1 -> CommonTileDefaults.InactiveCornerRadius
        2 -> CommonTileDefaults.ActiveTileCornerRadius
        3 -> 0.dp
        else -> CommonTileDefaults.ActiveTileCornerRadius
    }

    val thumbCornerRadius = when (shapeMode) {
        1 -> CommonTileDefaults.InactiveCornerRadius
        3 -> 0.dp
        else -> 16.dp
    }

    val animatedContainerRadius by animateDpAsState(targetValue = containerCornerRadius, label = "RingerContainerRadius")
    val animatedThumbRadius by animateDpAsState(targetValue = thumbCornerRadius, label = "RingerThumbRadius")
    
    RingerSliderWidget(
        interactor = interactor,
        theme = QSTileRingerTheme(),
        dimens = QSTileRingerDimens(TileHeight),
        modifier = Modifier.fillMaxWidth(),
        isDozing = false,
        border = border,
        containerShape = RoundedCornerShape(animatedContainerRadius),
        thumbShape = RoundedCornerShape(animatedThumbRadius),
        onLongClick = {
            interactor.toggleDnd()
        }
    )
}
