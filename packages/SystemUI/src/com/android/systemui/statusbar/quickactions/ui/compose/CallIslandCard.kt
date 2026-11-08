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
package com.android.systemui.statusbar.quickactions.ui.compose

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.shared.model.CallPopupModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel

private val CallDeclineRed = Color(0xFFFF453A)
private val CallToggleOff = Color.White.copy(alpha = 0.16f)

/**
 * The ColorOS style card of a call: who is calling over the ringing, dialling or running state,
 * answer and decline while it rings, end call once it does not, and the speaker and mute toggles
 * when the dialer offers them.
 */
@Composable
fun CallIslandCard(
    model: PopupContentModel.SystemEvent,
    call: CallPopupModel,
    modifier: Modifier = Modifier,
) {
    val timer =
        model.callStartTimeMs?.let { startMs ->
            rememberElapsedDurationText(
                SystemClock.elapsedRealtime() -
                    (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            )
        }
    val answer = stringResource(R.string.dynamic_island_call_answer)
    val decline = stringResource(R.string.dynamic_island_call_decline)
    val end = stringResource(R.string.dynamic_island_call_end)
    val ringing = call.incoming && !call.connected
    val answerAction =
        call.onAnswer?.let { PopupActionModel(answer, it, vector = Icons.Rounded.Call) }
    val declineAction =
        call.onDecline?.let { PopupActionModel(decline, it, vector = Icons.Rounded.CallEnd) }
    val endAction = call.onEnd?.let { PopupActionModel(end, it, vector = Icons.Rounded.CallEnd) }
    val showToggles = call.connected && (call.onSpeaker != null || call.onMute != null)

    IslandGlassCard(
        primary = model.title,
        secondary = timer ?: model.text.takeIf { it.isNotBlank() },
        glyph = {
            val photo = model.image
            val glyph = model.icon
            if (photo != null) {
                Icon(
                    icon = photo,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                    tint = Color.Unspecified,
                )
            } else if (glyph != null) {
                Icon(icon = glyph, modifier = Modifier.size(32.dp), tint = IslandAccents.Calls)
            }
        },
        modifier = modifier,
        // While it rings the card answers or declines; afterwards it ends the call.
        primaryAction = if (ringing) answerAction ?: endAction else endAction ?: declineAction,
        primaryActionColor = if (ringing && answerAction != null) IslandAccents.Calls else CallDeclineRed,
        secondaryAction = if (ringing && answerAction != null) declineAction else null,
        secondaryActionColor = CallDeclineRed,
        below =
            if (showToggles) {
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        call.onSpeaker?.let {
                            CallToggle(
                                label = stringResource(R.string.dynamic_island_call_speaker),
                                icon = Icons.Rounded.VolumeUp,
                                on = call.speakerOn,
                                onClick = it,
                            )
                        }
                        call.onMute?.let {
                            CallToggle(
                                label = stringResource(R.string.dynamic_island_call_mute),
                                icon = if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                on = call.muted,
                                onClick = it,
                            )
                        }
                    }
                }
            } else null,
    )
}

/** A round toggle under the call card; lit white while the speaker or mute is on. */
@Composable
private fun CallToggle(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.size(44.dp)
                .pressScale(onClick = onClick)
                .background(if (on) Color.White else CallToggleOff, CircleShape)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MaterialIcon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (on) Color.Black else Color.White,
        )
    }
}
