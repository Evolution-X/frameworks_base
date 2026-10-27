/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSTileRim
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/** Tile specs that ColorOS pins to the fixed wide slots, in order of preference. */
private val COLOROS_WIFI_SPECS = listOf("internet", "wifi")
private val COLOROS_DATA_SPECS = listOf("cell", "mobile_data", "mobiledata")
private val COLOROS_BT_SPECS = listOf("bt", "bluetooth")

/** Sun-icon colour at full brightness (the orange of the 🔆 emoji). */
private val ColorOSBrightnessFull = Color(0xFFFF9F0A)

/** Slider position treated as "full" (allows for rounding of the brightness value). */
private const val COLOROS_FULL_THRESHOLD = 0.995f

/** Matches the media card's own corner radius (Media.kt clips cards to 32dp). */
private val ColorOSMediaShape = RoundedCornerShape(32.dp)

/**
 * ColorOS-style expanded Quick Settings.
 *
 * ```
 * [ Wi-Fi         ][ Mobile data   ]
 * [ Media        ][ ☀ ][ 🔊 ]          tall brightness + media volume sliders
 * [ Bluetooth     ][ 🔔  📳  🔕     ]   ringer switch
 * ( ) ( ) ( ) ( )                       every other tile: round, label below
 * ```
 *
 * The fixed tiles are the real tile view models, so they behave exactly like their stock
 * versions. Anything missing on a device (e.g. no mobile data tile) simply leaves its slot out.
 */
@Composable
internal fun ContentScope.ColorOSQuickSettingsLayout(
    tiles: List<TileViewModel>,
    gridLayout: GridLayout,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    mediaHost: @Composable (Modifier) -> Unit = {},
    listening: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    fun pick(specs: List<String>): TileViewModel? =
        specs.firstNotNullOfOrNull { spec -> tiles.firstOrNull { it.spec.spec == spec } }

    val wifi = pick(COLOROS_WIFI_SPECS)
    val data = pick(COLOROS_DATA_SPECS)
    val bt = pick(COLOROS_BT_SPECS)
    val fixed = listOfNotNull(wifi, data, bt)
    val rest = remember(tiles, fixed) { tiles.filterNot { it in fixed } }

    // The fixed tiles are not part of a grid, so make them listen ourselves.
    TileListener(fixed, listening)

    // Follows the ColorOS tile height (66dp) set in CommonTileDefaults for this style.
    val tileHeight = CommonTileDefaults.TileHeight
    val glass = colorOSGlass()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(ColorOSGap)) {
        // Row 1: Wi-Fi + mobile data
        if (wifi != null || data != null) {
            Row(horizontalArrangement = spacedBy(ColorOSGap)) {
                listOfNotNull(wifi, data).forEach { tile ->
                    with(infiniteGridLayout) {
                        ColorOSWideTile(tile, listening, Modifier.weight(1f))
                    }
                }
                if (wifi == null || data == null) Box(Modifier.weight(1f))
            }
        }

        // Row 2: media + tall brightness + tall volume
        Row(
            horizontalArrangement = spacedBy(ColorOSGap),
            modifier = Modifier.height(tileHeight * 2 + ColorOSGap),
        ) {
            Box(
                modifier =
                    Modifier.weight(2f)
                        .fillMaxHeight()
                        // While media plays, the player card is the whole slot: no glass behind
                        // it, same corner radius as the card (32dp) so there is a single outline.
                        .then(
                            if (showMedia) Modifier.clip(ColorOSMediaShape)
                            else Modifier.glassSurface(glass, ColorOSMediaShape)
                        ),
                contentAlignment = Alignment.Center,
            ) {
                mediaHost(Modifier.matchParentSize())
                if (showMedia) {
                    media(Modifier.fillMaxSize())
                } else {
                    Text(
                        text = stringResource(R.string.qs_coloros_no_media),
                        color = glass.secondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            ColorOSBrightnessSlider(
                viewModel = brightnessViewModel,
                glass = glass,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ColorOSVolumeSlider(
                audioVolumeInteractor = audioVolumeInteractor,
                glass = glass,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // Row 3: Bluetooth + ringer switch
        Row(horizontalArrangement = spacedBy(ColorOSGap)) {
            if (bt != null) {
                with(infiniteGridLayout) { ColorOSWideTile(bt, listening, Modifier.weight(1f)) }
            } else {
                Box(Modifier.weight(1f))
            }
            ColorOSRingerSwitch(
                audioVolumeInteractor = audioVolumeInteractor,
                glass = glass,
                height = tileHeight,
                modifier = Modifier.weight(1f),
            )
        }

        // Everything else: round icon tiles with labels (the grid handles paging + listening)
        if (rest.isNotEmpty()) {
            with(gridLayout) {
                TileGrid(
                    tiles = rest,
                    modifier = Modifier.fillMaxWidth(),
                    listening = listening,
                    enableRevealEffect = false,
                )
            }
        }
    }
}

/** Colours shared by the ColorOS glass cards, matching the tile glass in Tile.kt. */
private data class ColorOSGlass(
    val fill: Color,
    val iconOff: Color,
    val accent: Color,
    val secondaryText: Color,
    val dark: Boolean,
)

@Composable
private fun colorOSGlass(): ColorOSGlass {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = Color(0xFF1677FF)
    return ColorOSGlass(
        fill = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f),
        iconOff = onSurface,
        accent = accent,
        secondaryText = onSurface.copy(alpha = 0.70f),
        dark = dark,
    )
}

private fun Modifier.glassSurface(glass: ColorOSGlass, shape: Shape): Modifier =
    this.clip(shape).background(glass.fill, shape).border(1.dp, ColorOSTileRim, shape)

/**
 * A tall pill slider: the white fill rises from the bottom. Dragging is relative to where the
 * finger lands, like ColorOS. While dragging, the shade is asked not to steal the gesture.
 */
@Composable
private fun ColorOSVerticalSlider(
    fraction: Float,
    @DrawableRes iconRes: Int,
    iconTint: Color,
    label: String,
    glass: ColorOSGlass,
    onChange: (Float) -> Unit,
    onStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fullIconTint: Color? = null,
) {
    val view = LocalView.current
    var local by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(fraction, dragging) { if (!dragging) local = fraction }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnStop by rememberUpdatedState(onStop)
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier =
            modifier
                .glassSurface(glass, shape)
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        dragging = true
                        down.consume()
                        val start = local
                        val height = size.height.coerceAtLeast(1)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = (down.position.y - change.position.y) / height
                            local = (start + delta).coerceIn(0f, 1f)
                            currentOnChange(local)
                            change.consume()
                        }
                        currentOnStop(local)
                        dragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
    ) {
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(local.coerceIn(0.001f, 1f))
                    .background(Color.White)
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint =
                when {
                    fullIconTint != null && local >= COLOROS_FULL_THRESHOLD -> fullIconTint
                    local > 0.18f -> iconTint
                    else -> glass.iconOff
                },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(26.dp),
        )
    }
}

@Composable
private fun ColorOSBrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    glass: ColorOSGlass,
    modifier: Modifier = Modifier,
) {
    val gamma = viewModel.currentBrightness.value
    val min = viewModel.minBrightness.value
    val max = viewModel.maxBrightness.value
    val scope = rememberCoroutineScope()
    val span = (max - min).coerceAtLeast(1)
    val fraction =
        if (gamma < 0) 0.5f else ((gamma - min).toFloat() / span).coerceIn(0f, 1f)
    fun toGamma(f: Float) = GammaBrightness((min + f * span).toInt().coerceIn(min, max))

    ColorOSVerticalSlider(
        fraction = fraction,
        iconRes = BrightnessSliderViewModel.getIconForPercentage(fraction * 100f),
        iconTint = Color(0xFF1A1A1A),
        // At full brightness only the sun icon turns orange, like the 🔆 emoji.
        fullIconTint = ColorOSBrightnessFull,
        label = stringResource(R.string.quick_settings_brightness_dialog_title),
        glass = glass,
        onChange = { f -> scope.launch { viewModel.onDrag(Drag.Dragging(toGamma(f))) } },
        onStop = { f -> scope.launch { viewModel.onDrag(Drag.Stopped(toGamma(f))) } },
        modifier = modifier,
    )
}

@Composable
private fun ColorOSVolumeSlider(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: ColorOSGlass,
    modifier: Modifier = Modifier,
) {
    val stream = remember { AudioStream(AudioManager.STREAM_MUSIC) }
    val model by
        remember(audioVolumeInteractor) { audioVolumeInteractor.getAudioStream(stream) }
            .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val current = model
    val min = current?.minVolume ?: 0
    val max = current?.maxVolume ?: 1
    val span = (max - min).coerceAtLeast(1)
    val fraction = current?.let { ((it.volume - min).toFloat() / span).coerceIn(0f, 1f) } ?: 0f
    fun toVolume(f: Float) = (min + Math.round(f * span)).coerceIn(min, max)

    ColorOSVerticalSlider(
        fraction = fraction,
        iconRes =
            if (current?.isMuted == true || fraction == 0f) R.drawable.ic_volume_media_mute
            else R.drawable.ic_volume_media,
        iconTint = glass.accent,
        label = stringResource(R.string.stream_music),
        glass = glass,
        onChange = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        onStop = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        modifier = modifier,
    )
}

/**
 * ColorOS-style colour for each sound mode: ring = blue, vibrate = orange, silent = red.
 * Light panel: coloured bubble with a white icon. Dark panel: white bubble with a coloured icon,
 * the same pairing the active QS tiles use.
 */
private fun colorOSRingerColor(mode: Int): Color =
    when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> Color(0xFF1677FF)
        AudioManager.RINGER_MODE_VIBRATE -> Color(0xFFFF8A00)
        else -> Color(0xFFF2453D)
    }

@Composable
private fun ColorOSRingerSwitch(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: ColorOSGlass,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val ringerMode by audioVolumeInteractor.ringerMode.collectAsStateWithLifecycle()
    val modes =
        listOf(
            Triple(
                AudioManager.RINGER_MODE_NORMAL,
                R.drawable.ic_volume_ringer,
                stringResource(R.string.volume_ringer_status_normal),
            ),
            Triple(
                AudioManager.RINGER_MODE_VIBRATE,
                R.drawable.ic_volume_ringer_vibrate,
                stringResource(R.string.volume_ringer_status_vibrate),
            ),
            Triple(
                AudioManager.RINGER_MODE_SILENT,
                R.drawable.ic_volume_ringer_mute,
                stringResource(R.string.volume_ringer_status_silent),
            ),
        )
    val shape = RoundedCornerShape(percent = 50)
    val bubble = height - 18.dp

    Row(
        modifier =
            modifier.height(height).glassSurface(glass, shape).padding(horizontal = 9.dp),
        horizontalArrangement = spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (mode, icon, label) ->
            val selected = ringerMode.value == mode
            val modeColor = colorOSRingerColor(mode)
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.size(bubble)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !selected -> Color.Transparent
                                    glass.dark -> Color.White
                                    else -> modeColor
                                }
                            )
                            .semantics { contentDescription = label }
                            .pointerInput(mode) {
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        audioManager?.setRingerModeInternal(mode)
                                    }
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint =
                            when {
                                !selected -> glass.iconOff
                                glass.dark -> modeColor
                                else -> Color.White
                            },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
