/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.shared.model.QsVolumeLook
import com.android.systemui.res.R
import kotlin.math.roundToInt

internal class SoundMode(val mode: Int, val icon: Int, val label: Int)

private val SWITCH_INSET = 3.dp

private val Ring =
    SoundMode(AudioManager.RINGER_MODE_NORMAL, R.drawable.ic_volume_ringer, R.string.qs_sound_tile_ring)
private val Vibrate =
    SoundMode(
        AudioManager.RINGER_MODE_VIBRATE,
        R.drawable.ic_volume_ringer_vibrate,
        R.string.qs_sound_tile_vibrate,
    )
private val Silent =
    SoundMode(
        AudioManager.RINGER_MODE_SILENT,
        R.drawable.ic_volume_ringer_mute,
        R.string.qs_sound_tile_silent,
    )

@Stable
internal class SoundModeState(
    private val audio: AudioManager,
    vibrates: Boolean,
    private val view: View,
) {
    val modes = if (vibrates) listOf(Ring, Vibrate, Silent) else listOf(Ring, Silent)

    var mode by mutableIntStateOf(audio.getRingerModeInternal())
        private set

    fun sync() {
        mode = audio.getRingerModeInternal()
    }

    fun pick(next: Int) {
        if (next == mode) return
        audio.setRingerModeInternal(next)
        mode = next
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
internal fun rememberSoundMode(): SoundModeState {
    val context = LocalContext.current
    val view = LocalView.current
    val state =
        remember(context, view) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            SoundModeState(
                context.getSystemService(AudioManager::class.java),
                vibrator?.hasVibrator() == true,
                view,
            )
        }
    DisposableEffect(context, state) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state.sync()
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
internal fun BoxScope.SoundModeContent(state: SoundModeState, colors: TileColors, look: TileLook) {
    val paint = levelPaint(QsVolumeLook.FILL)
    val selected = state.modes.indexOfFirst { it.mode == state.mode }.coerceAtLeast(0)
    val last = (state.modes.size - 1).toFloat()
    val at by
        animateFloatAsState(
            selected.toFloat(),
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "QSTileSoundMode",
        )
    BoxWithConstraints(Modifier.fillMaxSize().padding(SWITCH_INSET)) {
        val segment = maxWidth / state.modes.size
        val step = constraints.maxWidth.toFloat() / state.modes.size
        val shape = RoundedCornerShape((look.inactiveRadius - SWITCH_INSET).coerceAtLeast(4.dp))
        Box(
            Modifier.offset { IntOffset((step * at.coerceIn(0f, last)).roundToInt(), 0) }
                .width(segment)
                .fillMaxHeight()
                .then(
                    paint.fade?.let { Modifier.background(Brush.linearGradient(listOf(paint.fill, it)), shape) }
                        ?: Modifier.background(paint.fill, shape)
                )
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            state.modes.forEachIndexed { index, item ->
                val on = index == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .selectable(
                                selected = on,
                                role = Role.RadioButton,
                                onClick = { state.pick(item.mode) },
                            ),
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = stringResource(item.label),
                        tint = if (on) paint.ink else colors.icon,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
