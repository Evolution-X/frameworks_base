/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSActiveFill
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridFooterHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridHeadroom
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSMinGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSSliderFill
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSTileRim
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSGlassTileMaterial
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTileSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSIconRowGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSPress
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSSpring
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSTileLabelHide
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tile specs that ColorOS pins to the fixed wide slots, in order of preference. */
private val COLOROS_WIFI_SPECS = listOf("internet", "wifi")
private val COLOROS_DATA_SPECS = listOf("cell", "mobile_data", "mobiledata")
private val COLOROS_BT_SPECS = listOf("bt", "bluetooth")

/**
 * The specs of the tiles the panel pins as cards, the Wi-Fi, Bluetooth and mobile data ones that
 * are present. The grid and the editor leave them out.
 */
internal fun colorOSCardSpecsOf(present: Set<String>): Set<String> = buildSet {
    COLOROS_WIFI_SPECS.firstOrNull { it in present }?.let(::add)
    COLOROS_BT_SPECS.firstOrNull { it in present }?.let(::add)
    COLOROS_DATA_SPECS.firstOrNull { it in present }?.let(::add)
}

/** The blue of the volume icon, as in the ColorOS panel. */
private val ColorOSSliderBlue = Color(0xFF0068FF)

/** An icon on the white fill of a slider is black at 70%, not solid black, as in ColorOS. */
private val ColorOSSliderIconOnFill = Color(0xB3000000)

/** Sun-icon colour at full brightness (the orange of the 🔆 emoji). */
private val ColorOSBrightnessFull = Color(0xFFFF9F0A)

/** Slider position treated as "full" (allows for rounding of the brightness value). */
private const val COLOROS_FULL_THRESHOLD = 0.995f

/** Diameter of the selected-mode bubble of the ringer switch, relative to the pill height. */
private const val ColorOSRingerBubbleRatio = 0.68f

/**
 * Extra space between the ringer row and the round tiles, ColorOS leaves a bit more there. The
 * pager adds [ColorOSGridHeadroom] on top of this, inside its bounds, so the gap is 6dp in all.
 */
private val ColorOSGridTopPadding = 2.dp

/** Smallest round tile the panel shrinks to before it gives up and scrolls instead. */
private val ColorOSMinTileSize = 56.dp

/**
 * How the ColorOS panel is sized: the diameter of the round tiles (which is also the height of the
 * pills and of the sliders), the rows of round tiles per page, and whether it all fits the screen.
 */
internal data class ColorOSFit(val tileSize: Dp, val rows: Int, val fits: Boolean)

/**
 * Sizes the ColorOS panel for [availableHeight], the height the panel may take inside the Quick
 * Settings scroller. The tiles keep the width of a column, [columnSize], when that fits. On a
 * short screen they shrink to the height that does fit, but not below [ColorOSMinTileSize], and
 * the grid first drops from [ColorOSGridRows] to [ColorOSMinGridRows] rows per page. If even that
 * does not fit (landscape, tiny screens), the panel keeps its full size and the Quick Settings
 * scroller takes over, which [ColorOSFit.fits] reports.
 *
 * The panel is stacked from, in tiles: the pills (1), the media card and sliders (2), the pills
 * again (1) and the rows of the grid, plus the gaps, the room for the labels and the pager footer.
 */
internal fun colorOSFit(
    columnSize: Dp,
    availableHeight: Dp,
    labelHeight: Dp,
    iconRowGap: Dp,
): ColorOSFit {
    if (availableHeight == Dp.Unspecified || availableHeight <= 0.dp) {
        return ColorOSFit(columnSize, ColorOSGridRows, fits = true)
    }
    for (rows in ColorOSGridRows downTo ColorOSMinGridRows) {
        // Gaps: between the four blocks (3) and inside the media row (1).
        val fixed =
            ColorOSGap * 4 +
                ColorOSGridTopPadding +
                ColorOSGridHeadroom +
                labelHeight * rows +
                iconRowGap * (rows - 1) +
                ColorOSGridFooterHeight
        val tileSize = (availableHeight - fixed) / (4 + rows)
        if (tileSize >= ColorOSMinTileSize) {
            return ColorOSFit(minOf(columnSize, tileSize), rows, fits = true)
        }
    }
    return ColorOSFit(columnSize, ColorOSGridRows, fits = false)
}

/**
 * Least height to width ratio of a ColorOS slider. The sliders are one column wide, which is right
 * on a phone, but on a wide panel the column grows and the module does not, so they would turn
 * fat. They stop at this ratio and sit centered in their column instead.
 */
private const val ColorOSSliderAspect = 2.1f

/** Corner radius of the glass slot behind the media card, playing or idle. */
private val ColorOSMediaShape = RoundedCornerShape(32.dp)

/**
 * ColorOS-style expanded Quick Settings.
 *
 * ```
 * [ Wi-Fi         ][ Bluetooth     ]
 * [ Media        ][ ☀ ][ 🔊 ]          tall brightness + media volume sliders
 * [ 🔔  📳  🔕     ][ Mobile data   ]   ringer switch, mobile data card
 * ( ) ( ) ( ) ( )                       every other tile: round, label below
 *
 * The media card spans the first two columns of the round tile grid and each slider is one
 * column wide, so they line up with the tiles below.
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
    availableHeight: Dp = Dp.Unspecified,
    onFitChanged: (Boolean) -> Unit = {},
    glassAlpha: () -> Float = { 1f },
    expansion: () -> Float = { 1f },
) {
    fun pick(specs: List<String>): TileViewModel? =
        specs.firstNotNullOfOrNull { spec -> tiles.firstOrNull { it.spec.spec == spec } }

    val wifi = pick(COLOROS_WIFI_SPECS)
    val bt = pick(COLOROS_BT_SPECS)
    val data = pick(COLOROS_DATA_SPECS)
    val fixed = listOfNotNull(wifi, bt, data)
    val rest = remember(tiles, fixed) { tiles.filterNot { it in fixed } }

    // The fixed tiles are not part of a grid, so make them listen ourselves.
    TileListener(fixed, listening)

    // Null where SurfaceFlinger cannot blur behind the shade, the flat fills stay then.
    // The modules are revealed when the shade opens and hidden when it closes, and the glass goes
    // out quickly with it.
    val leaving = rememberColorOSLeaving(expansion)
    val glassFade = rememberColorOSGlassFade(leaving)
    val glassBlur = rememberColorOSGlassBlur { glassAlpha() * glassFade.value }
    val glass = colorOSGlass(blurred = glassBlur != null)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // ColorOS is built on four equal columns with a 17dp gap. The round tiles, the pills, the
        // media card and the sliders all take their size from the column width, so the circles
        // fill their column whatever the screen width is. When the screen is too short for that,
        // the tiles shrink and the grid loses a row, see colorOSFit.
        val columnSize = (maxWidth - ColorOSGap * 3) / 4
        // Without the labels the round tile rows are shorter and further apart.
        val labelsHidden = rememberQSTileLabelHide()
        val labelHeight = if (labelsHidden) 0.dp else CommonTileDefaults.ClassicLabelHeight
        val iconRowGap = colorOSIconRowGap(labelsHidden)
        val fit =
            remember(columnSize, availableHeight, labelHeight, iconRowGap) {
                colorOSFit(columnSize, availableHeight, labelHeight, iconRowGap)
            }
        SideEffect { onFitChanged(fit.fits) }
        val tileSize = fit.tileSize
        CompositionLocalProvider(
            LocalColorOSTileSize provides tileSize,
            LocalColorOSGridRows provides fit.rows,
            LocalColorOSGlassBlur provides glassBlur,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = spacedBy(ColorOSGap)) {
                // Row 1: Wi-Fi + Bluetooth
                if (wifi != null || bt != null) {
                    Row(
                        horizontalArrangement = spacedBy(ColorOSGap),
                        modifier = Modifier.colorOSModuleReveal(1, leaving),
                    ) {
                        listOfNotNull(wifi, bt).forEach { tile ->
                            with(infiniteGridLayout) {
                                ColorOSWideTile(tile, listening, Modifier.weight(1f))
                            }
                        }
                        if (wifi == null || bt == null) Box(Modifier.weight(1f))
                    }
                }

                // Row 2: media + tall brightness + tall volume, on the same four columns as the round
            // tiles below: the media card covers columns 1-2 and each slider one of columns 3-4.
            val moduleHeight = tileSize * 2 + ColorOSGap
            val sliderWidth = minOf(columnSize, moduleHeight / ColorOSSliderAspect)
            Row(
                horizontalArrangement = spacedBy(ColorOSGap),
                modifier =
                    Modifier.colorOSModuleReveal(2, leaving)
                        .fillMaxWidth()
                        .height(moduleHeight),
            ) {
                Box(
                    modifier =
                        Modifier.width(columnSize * 2 + ColorOSGap)
                            .fillMaxHeight()
                            // Playing and idle share the same glass slot, like the other ColorOS
                            // cards, the media card only lays out its content on it.
                            .glassSurface(glass, ColorOSMediaShape),
                    contentAlignment = Alignment.Center,
                ) {
                    mediaHost(Modifier.matchParentSize())
                    if (showMedia) {
                        media(Modifier.fillMaxSize())
                    } else {
                        ColorOSIdleMediaCard(Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.width(columnSize).fillMaxHeight(), Alignment.Center) {
                    ColorOSBrightnessSlider(
                        viewModel = brightnessViewModel,
                        glass = glass,
                        modifier = Modifier.width(sliderWidth).fillMaxHeight(),
                    )
                }
                Box(Modifier.width(columnSize).fillMaxHeight(), Alignment.Center) {
                    ColorOSVolumeSlider(
                        audioVolumeInteractor = audioVolumeInteractor,
                        glass = glass,
                        modifier = Modifier.width(sliderWidth).fillMaxHeight(),
                    )
                }
            }

            // Row 3: ringer switch + mobile data. ColorOS keeps the ringer switch on the left next
                // to the mobile data card, and on the right when there is no card to put beside it.
                Row(
                    horizontalArrangement = spacedBy(ColorOSGap),
                    modifier = Modifier.colorOSModuleReveal(3, leaving),
                ) {
                    if (data == null) Box(Modifier.weight(1f))
                    ColorOSRingerSwitch(
                        audioVolumeInteractor = audioVolumeInteractor,
                        glass = glass,
                        height = tileSize,
                        modifier = Modifier.weight(1f),
                    )
                    if (data != null) {
                        with(infiniteGridLayout) { ColorOSWideTile(data, listening, Modifier.weight(1f)) }
                    }
                }

                // Everything else: round icon tiles with labels (the grid handles paging + listening)
                if (rest.isNotEmpty()) {
                    with(gridLayout) {
                        TileGrid(
                            tiles = rest,
                            modifier =
                                Modifier.colorOSModuleReveal(4, leaving)
                                    .fillMaxWidth()
                                    .padding(top = ColorOSGridTopPadding),
                            listening = listening,
                            enableRevealEffect = false,
                        )
                    }
                }
            }
        }
    }
}

/** Colours shared by the ColorOS glass cards, matching the tile glass in Tile.kt. */
private data class ColorOSGlass(
    val fill: Color,
    val iconOff: Color,
    val accent: Color,
    val dark: Boolean,
    /** Whether the glass is drawn by SurfaceFlinger, and not with [fill] and the rim. */
    val blurred: Boolean,
    val material: Int,
)

@Composable
private fun colorOSGlass(blurred: Boolean): ColorOSGlass {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = ColorOSSliderBlue
    return ColorOSGlass(
        fill = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f),
        iconOff = onSurface,
        accent = accent,
        dark = dark,
        blurred = blurred,
        material = colorOSGlassTileMaterial(),
    )
}

/**
 * The glass of a pill, a slider, the ringer switch or the media card, which all use the material
 * of the tiles that are off. SurfaceFlinger draws it, with the tint, the lit rim and the glow of the
 * ColorOS material, when [glass] is [ColorOSGlass.blurred]. Otherwise it is a translucent fill
 * with a rim.
 */
@Composable
private fun Modifier.glassSurface(glass: ColorOSGlass, shape: Shape): Modifier =
    if (glass.blurred) {
        this.clip(shape).colorOSGlassBlur(shape, glass.material)
    } else {
        this.clip(shape).background(glass.fill, shape).border(1.dp, ColorOSTileRim, shape)
    }

/** How long a released slider waits for the value it set to be reported back. */
private const val ColorOSSliderSettleMillis = 400L

/** How far the reported value may be from the released one to count as caught up. */
private const val ColorOSSliderSettleTolerance = 0.005f

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
    snap: (Float) -> Float = { it },
    onLongPress: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    // After a release, [fraction] reports the values on the way to the one that was set, the one
    // from before the drag among them, so the fill stays where it was released until the reported
    // value gets there instead of jumping back and forward again.
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(fraction, dragging, settling) {
        when {
            dragging -> Unit
            !settling -> local = fraction
            abs(fraction - local) < ColorOSSliderSettleTolerance -> {
                settling = false
                local = fraction
            }
            else -> {
                delay(ColorOSSliderSettleMillis)
                settling = false
                local = fraction
            }
        }
    }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentSnap by rememberUpdatedState(snap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier =
            modifier
                .colorOSStretch()
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
                        // A finger that stays down without moving does not set a level, it fires
                        // the long press. Once that fired the rest of the gesture is ignored.
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        var longPressed = false
                        val longPressJob =
                            if (currentOnLongPress != null) {
                                scope.launch {
                                    delay(longPressTimeout)
                                    longPressed = true
                                    currentOnLongPress?.invoke()
                                }
                            } else {
                                null
                            }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (longPressed) {
                                change.consume()
                                continue
                            }
                            if ((change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                longPressJob?.cancel()
                            }
                            val delta = (down.position.y - change.position.y) / height
                            local = (start + delta).coerceIn(0f, 1f)
                            currentOnChange(local)
                            change.consume()
                        }
                        longPressJob?.cancel()
                        if (!longPressed) {
                            local = currentSnap(local)
                            settling = true
                            currentOnStop(local)
                        }
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
                    .background(ColorOSSliderFill)
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
        iconRes = R.drawable.ic_coloros_brightness,
        iconTint = ColorOSSliderIconOnFill,
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
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
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
        // The volume is in steps, so the fill rests on the step the volume gets set to.
        snap = { f -> ((toVolume(f) - min).toFloat() / span).coerceIn(0f, 1f) },
        onLongPress = {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE,
                0,
            )
        },
    )
}

/**
 * ColorOS colours the selected sound mode red whichever mode it is. Light panel: red bubble with a
 * white icon. Dark panel: white bubble with a red icon, the same pairing the active QS tiles use.
 */
private val ColorOSRingerRed = Color(0xFFF2453D)

/** How far the end buttons are pulled towards the middle, in segments, by the selected bubble. */
private const val ColorOSRingerNudge = 0.1f

private class ColorOSRingerMode(
    val mode: Int,
    @DrawableRes val icon: Int,
    @RawRes val animation: Int,
    val label: String,
)

/** Which way the button at [index] leans: the ends of the switch lean in, towards the middle. */
private fun colorOSRingerNudge(index: Int, selected: Int, last: Int): Float =
    when (selected) {
        0 -> 1f
        last -> -1f
        else ->
            when (index) {
                0 -> 1f
                last -> -1f
                else -> 0f
            }
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
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_NORMAL,
                R.drawable.ic_volume_ringer,
                R.raw.coloros_ringer_normal,
                stringResource(R.string.volume_ringer_status_normal),
            ),
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_VIBRATE,
                R.drawable.ic_volume_ringer_vibrate,
                R.raw.coloros_ringer_vibrate,
                stringResource(R.string.volume_ringer_status_vibrate),
            ),
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_SILENT,
                R.drawable.ic_volume_ringer_mute,
                R.raw.coloros_ringer_silent,
                stringResource(R.string.volume_ringer_status_silent),
            ),
        )
    val shape = RoundedCornerShape(percent = 50)
    val bubble = height * ColorOSRingerBubbleRatio
    val selected = modes.indexOfFirst { it.mode == ringerMode.value }.coerceAtLeast(0)
    val last = modes.lastIndex

    BoxWithConstraints(
        modifier = modifier.height(height).glassSurface(glass, shape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val segmentPx = with(density) { maxWidth.toPx() } / modes.size
        val bubblePx = with(density) { bubble.toPx() }
        val nudge = { index: Int ->
            colorOSRingerNudge(index, selected, last) * ColorOSRingerNudge * segmentPx
        }
        // One bubble slides to the selected mode, it leans a little towards the middle when it
        // sits at an end. The buttons lean with it.
        val indicatorX by
            animateFloatAsState(
                selected * segmentPx + (segmentPx - bubblePx) / 2f + nudge(selected),
                colorOSSpring(0.25f, 0.3f),
                label = "colorOSRingerIndicator",
            )
        // requiredSize: the slot can be a little narrower than the bubble on small screens, and
        // size() would squash the circle into an oval.
        Box(
            Modifier.graphicsLayer { translationX = indicatorX }
                .requiredSize(bubble)
                .background(if (glass.dark) ColorOSActiveFill else ColorOSRingerRed, CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEachIndexed { index, mode ->
                val shift by
                    animateFloatAsState(
                        nudge(index),
                        colorOSSpring(0f, 0.35f),
                        label = "colorOSRingerNudge",
                    )
                ColorOSRingerButton(
                    mode = mode,
                    selected = index == selected,
                    glass = glass,
                    audioManager = audioManager,
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().graphicsLayer { translationX = shift },
                )
            }
        }
    }
}

/**
 * One mode of the ringer switch. Its icon is red on the white bubble of a dark panel and white on
 * the red bubble of a light one, it grows a little and plays its animation when it gets selected.
 */
@Composable
private fun ColorOSRingerButton(
    mode: ColorOSRingerMode,
    selected: Boolean,
    glass: ColorOSGlass,
    audioManager: AudioManager?,
    modifier: Modifier = Modifier,
) {
    val pressSource = remember { MutableInteractionSource() }
    val scale by
        animateFloatAsState(
            if (selected) 1.2f else 1f,
            colorOSSpring(0f, 0.25f),
            label = "colorOSRingerScale",
        )
    val color by
        animateColorAsState(
            when {
                !selected -> glass.iconOff
                glass.dark -> ColorOSRingerRed
                else -> Color.White
            },
            colorOSSpring(0f, 0.3f),
            label = "colorOSRingerColor",
        )
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(mode.animation))
    val progress = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, composition) {
        val clip = composition ?: return@LaunchedEffect
        if (selected == wasSelected) return@LaunchedEffect
        wasSelected = selected
        if (!selected) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(clip.duration.toInt(), easing = LinearEasing))
    }

    Box(
        modifier =
            modifier
                .colorOSPress(
                    pressSource,
                    press = colorOSSpring(0.18f, 0.13f),
                    release = colorOSSpring(0.62f, 0.35f),
                )
                .semantics { contentDescription = mode.label }
                .pointerInput(mode.mode) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val press = PressInteraction.Press(down.position)
                        pressSource.tryEmit(press)
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            pressSource.tryEmit(PressInteraction.Release(press))
                            audioManager?.setRingerModeInternal(mode.mode)
                        } else {
                            pressSource.tryEmit(PressInteraction.Cancel(press))
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier =
            Modifier.size(22.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        val clip = composition
        if (clip != null) {
            // One colour for the whole animation, so it follows the icon colour above.
            val properties =
                rememberLottieDynamicProperties(
                    rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"),
                    rememberLottieDynamicProperty(
                        LottieProperty.STROKE_COLOR,
                        color.toArgb(),
                        "**",
                    ),
                )
            LottieAnimation(
                composition = clip,
                progress = { progress.value },
                dynamicProperties = properties,
                modifier = iconModifier,
            )
        } else {
            Icon(
                painter = painterResource(mode.icon),
                contentDescription = null,
                tint = color,
                modifier = iconModifier,
            )
        }
    }
}
