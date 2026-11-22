/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val SliderWidth = 58.dp
private val MusicStream = AudioStream(AudioManager.STREAM_MUSIC)

/** Circles style: the media card next to tall brightness and media volume sliders. */
@Composable
fun QsCirclesMediaRow(
    media: @Composable () -> Unit,
    brightness: BrightnessSliderViewModel?,
    volume: AudioVolumeInteractor?,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().height(172.dp),
    ) {
        Box(Modifier.weight(1f)) { media() }
        if (brightness != null) {
            VerticalBrightnessSlider(brightness)
        }
        if (volume != null) {
            VerticalVolumeSlider(volume)
        }
    }
}

@Composable
private fun VerticalBrightnessSlider(viewModel: BrightnessSliderViewModel) {
    val gamma = viewModel.currentBrightness.value
    val icon = painterResource(R.drawable.ic_brightness_full)
    // Hold the space until the first real value, so the media card keeps its width.
    if (gamma == BrightnessSliderViewModel.initialValue.value) {
        EmptySlider(icon)
        return
    }
    val min = viewModel.minBrightness.value
    val max = viewModel.maxBrightness.value
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: ((gamma - min).toFloat() / (max - min).coerceAtLeast(1))
    fun gammaAt(f: Float) = GammaBrightness((min + f * (max - min)).roundToInt())

    VerticalSlider(
        fraction = fraction,
        icon = icon,
        label = stringResource(R.string.quick_settings_brightness_dialog_title),
        onChange = { f ->
            dragging = f
            scope.launch { viewModel.onDrag(Drag.Dragging(gammaAt(f))) }
        },
        onStop = { f ->
            dragging = null
            scope.launch { viewModel.onDrag(Drag.Stopped(gammaAt(f))) }
        },
        onBlocked =
            (restriction as? PolicyRestriction.Restricted)?.let {
                { viewModel.showPolicyRestrictionDialog(it) }
            },
    )
}

@Composable
private fun VerticalVolumeSlider(interactor: AudioVolumeInteractor) {
    val model by
        remember(interactor) { interactor.getAudioStream(MusicStream) }
            .collectAsStateWithLifecycle(initialValue = null)
    val icon = painterResource(R.drawable.ic_speaker_on)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sentLevel by remember { mutableStateOf(-1) }
    val stream = model
    if (stream == null) {
        EmptySlider(icon)
        return
    }
    val range = (stream.maxVolume - stream.minVolume).coerceAtLeast(1)
    val fraction = dragging ?: ((stream.volume - stream.minVolume).toFloat() / range)

    VerticalSlider(
        fraction = fraction,
        icon = icon,
        label = stringResource(R.string.stream_music),
        onChange = { f ->
            dragging = f
            val level = stream.minVolume + (f * range).roundToInt()
            if (level != sentLevel) {
                sentLevel = level
                scope.launch { interactor.setVolume(MusicStream, level) }
            }
        },
        onStop = {
            dragging = null
            sentLevel = -1
        },
    )
}

/** The slider's track and icon, shown until its value loads. */
@Composable
private fun EmptySlider(icon: Painter) {
    Box(
        Modifier.width(SliderWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(SliderWidth / 2))
            .background(Color.White.copy(alpha = .1f))
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 19.dp).size(20.dp),
        )
    }
}

/**
 * A tall pill that fills from the bottom. The icon sits in the bottom cap, which stays filled so
 * the icon is always readable. A drag or tap anywhere sets the level.
 */
@Composable
private fun VerticalSlider(
    fraction: Float,
    icon: Painter,
    label: String,
    onChange: (Float) -> Unit,
    onStop: (Float) -> Unit,
    onBlocked: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(SliderWidth / 2)
    BoxWithConstraints(
        modifier =
            Modifier.width(SliderWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(Color.White.copy(alpha = .1f))
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { f ->
                        onChange(f)
                        onStop(f)
                        true
                    }
                }
                .pointerInput(onBlocked) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Keep the shade from taking the vertical drag.
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        down.consume()
                        if (onBlocked != null) {
                            onBlocked()
                            return@awaitEachGesture
                        }
                        val cap = size.width.toFloat()
                        fun at(y: Float) =
                            ((size.height - y - cap) / (size.height - cap)).coerceIn(0f, 1f)
                        var last = at(down.position.y)
                        onChange(last)
                        drag(down.id) { change ->
                            last = at(change.position.y)
                            onChange(last)
                            change.consume()
                        }
                        onStop(last)
                    }
                }
    ) {
        val fill = SliderWidth + (maxHeight - SliderWidth) * fraction.coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fill)
                .clip(shape)
                .background(scheme.primary)
        )
        Icon(
            painter = icon,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 19.dp).size(20.dp),
        )
    }
}
