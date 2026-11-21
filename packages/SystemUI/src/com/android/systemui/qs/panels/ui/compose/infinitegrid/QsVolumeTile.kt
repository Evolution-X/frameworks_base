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
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.qs.panels.shared.model.LocalQsGlass
import com.android.systemui.qs.panels.shared.model.LocalQsGlassTune
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsSliderPercent
import com.android.systemui.qs.panels.shared.model.levelLookFor
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.QsVolumeLook
import com.android.systemui.qs.panels.shared.model.QsVolumeShow
import com.android.systemui.qs.panels.shared.model.QsVolumeSound
import com.android.systemui.qs.panels.shared.model.QsVolumeTap
import com.android.systemui.qs.panels.ui.compose.glassy
import com.android.systemui.qs.panels.ui.compose.qsSliderStyle
import com.android.systemui.qs.panels.ui.compose.qsStyleSliderColors
import com.android.systemui.qs.panels.ui.compose.qsStyleSliderDimensions
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
import java.text.NumberFormat
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val StackSounds = listOf(QsVolumeSound.MEDIA, QsVolumeSound.RING, QsVolumeSound.ALARM)
private const val FILL_ALPHA = 0.3f
private val LINE_TRACK = 20.dp
private const val SHEEN_ALPHA = 0.2f
private val BLOCK = 6.dp
private val BLOCK_GAP = 3.dp
private val ICON_ZONE = 56.dp
private const val TAP_ICON = -1f
private const val TAP_NONE = -2f

private val QsVolumeSound.stream: Int
    get() =
        when (this) {
            QsVolumeSound.MEDIA -> AudioManager.STREAM_MUSIC
            QsVolumeSound.RING -> AudioManager.STREAM_RING
            QsVolumeSound.ALARM -> AudioManager.STREAM_ALARM
            QsVolumeSound.CALL -> AudioManager.STREAM_VOICE_CALL
        }

@get:DrawableRes
private val QsVolumeSound.icon: Int
    get() =
        when (this) {
            QsVolumeSound.MEDIA -> R.drawable.ic_volume_media
            QsVolumeSound.RING -> R.drawable.ic_volume_ringer
            QsVolumeSound.ALARM -> R.drawable.ic_volume_alarm
            QsVolumeSound.CALL -> R.drawable.ic_call
        }

@get:DrawableRes
private val QsVolumeSound.mutedIcon: Int
    get() =
        when (this) {
            QsVolumeSound.MEDIA -> R.drawable.ic_volume_media_mute
            QsVolumeSound.RING -> R.drawable.ic_volume_ringer_mute
            QsVolumeSound.ALARM -> R.drawable.ic_volume_alarm_mute
            QsVolumeSound.CALL -> R.drawable.ic_call
        }

@get:StringRes
private val QsVolumeSound.label: Int
    get() =
        when (this) {
            QsVolumeSound.MEDIA -> R.string.qs_volume_tile_label
            QsVolumeSound.RING -> R.string.qs_custom_volume_ring
            QsVolumeSound.ALARM -> R.string.qs_custom_volume_alarm
            QsVolumeSound.CALL -> R.string.qs_custom_volume_call
        }

@Stable
internal abstract class LevelChannel {
    abstract val min: Int
    abstract val max: Int
    var level by mutableIntStateOf(0)
        protected set

    var dragging by mutableStateOf(false)
    val fraction: Float
        get() = (level - min).coerceAtLeast(0).toFloat() / (max - min).coerceAtLeast(1)

    /** Where the finger holds the level during a drag, between whole steps. */
    var dragFraction by mutableFloatStateOf(0f)
        private set

    /** A policy blocks changes, so a drag must not move the fill. */
    protected open val blocked: Boolean
        get() = false

    open val silent: Boolean
        get() = level == 0

    @get:DrawableRes abstract val icon: Int
    @get:DrawableRes abstract val mutedIcon: Int
    @get:StringRes abstract val label: Int

    abstract fun sync()

    abstract fun set(next: Int)

    abstract fun toggleMute()

    abstract fun showPanel()

    fun setFraction(fraction: Float) {
        set(min + (fraction.coerceIn(0f, 1f) * (max - min)).roundToInt())
    }

    /** A drag at [exact] level units: the fill follows it, the level takes the nearest step. */
    fun drag(exact: Float) {
        if (!blocked) dragFraction = ((exact - min) / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
        set(exact.roundToInt())
    }

    open fun startDrag() {
        dragFraction = fraction
        dragging = true
    }

    open fun endDrag() {
        dragging = false
        sync()
    }
}

@Stable
internal class VolumeChannel(
    private val audio: AudioManager,
    private val view: View,
    private val ticks: Boolean,
    val sound: QsVolumeSound,
) : LevelChannel() {
    private val stream = sound.stream
    override val min = audio.getStreamMinVolume(stream)
    override val max = audio.getStreamMaxVolume(stream).coerceAtLeast(min + 1)
    override val icon = sound.icon
    override val mutedIcon = sound.mutedIcon
    override val label = sound.label

    init {
        level = audio.getStreamVolume(stream)
    }

    override fun sync() {
        if (!dragging) level = audio.getStreamVolume(stream)
    }

    override fun set(next: Int) {
        if (next == level) return
        try {
            audio.setStreamVolume(stream, next, AudioManager.FLAG_SHOW_UI_WARNINGS)
        } catch (e: SecurityException) {
            sync()
            return
        }
        val now = audio.getStreamVolume(stream)
        if (now == level) return
        level = now
        if (ticks) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    override fun toggleMute() {
        if (sound == QsVolumeSound.ALARM || sound == QsVolumeSound.CALL) {
            showPanel()
            return
        }
        try {
            if (level > 0) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            } else {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                if (audio.getStreamVolume(stream) == 0) audio.setStreamVolume(stream, 1, 0)
            }
        } catch (e: SecurityException) {
        }
        sync()
    }

    override fun showPanel() {
        audio.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }
}

@Stable
internal class BrightnessChannel(
    private val viewModel: BrightnessSliderViewModel,
    private val scope: CoroutineScope,
    private val view: View,
    private val ticks: Boolean,
    private val restricted: () -> PolicyRestriction.Restricted?,
) : LevelChannel() {
    override val min: Int
        get() = viewModel.minBrightness.value

    override val max: Int
        get() = viewModel.maxBrightness.value.coerceAtLeast(min + 1)

    override val silent: Boolean
        get() = false

    override val icon: Int
        get() =
            if (viewModel.autoMode) R.drawable.ic_qs_brightness_auto_on
            else R.drawable.ic_brightness_full

    override val mutedIcon: Int
        get() = icon

    override val label = R.string.qs_brightness_tile_label

    private var step = -1
    private var warned = false

    override val blocked: Boolean
        get() = restricted() != null

    /** Like the stock bar: while brightness is dragged the shade fades and the tile stays. */
    val mirrored: Boolean
        get() = dragging && viewModel.showMirror

    init {
        sync()
    }

    override fun sync() {
        val now = viewModel.currentBrightness.value
        if (!dragging && now >= 0) level = now
    }

    override fun set(next: Int) {
        val block = restricted()
        if (block != null) {
            if (!warned) viewModel.showPolicyRestrictionDialog(block)
            warned = dragging
            return
        }
        if (next == level) return
        level = next
        val gamma = GammaBrightness(next)
        scope.launch {
            viewModel.onDrag(if (dragging) Drag.Dragging(gamma) else Drag.Stopped(gamma))
        }
        val now = (fraction * TICKS).toInt()
        if (ticks && step >= 0 && now != step) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
        step = now
    }

    override fun startDrag() {
        super.startDrag()
        step = (fraction * TICKS).toInt()
        warned = false
        if (!blocked) viewModel.setIsDragging(true)
    }

    override fun endDrag() {
        dragging = false
        viewModel.setIsDragging(false)
        step = -1
        val gamma = GammaBrightness(level)
        scope.launch { viewModel.onDrag(Drag.Stopped(gamma)) }
    }

    override fun toggleMute() {
        viewModel.onIconClick()
    }

    override fun showPanel() {
        viewModel.onIconClick()
    }

    private companion object {
        const val TICKS = 20
    }
}

val LocalQsBrightness = staticCompositionLocalOf<BrightnessSliderViewModel?> { null }

val LocalQsCollapsed = staticCompositionLocalOf { false }

@Stable
internal class VolumeTileState(
    val channels: List<LevelChannel>,
    private val tap: QsVolumeTap,
    private val look: QsVolumeLook,
    val show: QsVolumeShow,
    val percent: QsSliderPercent,
    private val picked: Boolean,
) {
    val main = channels.first()
    var tapSpot = TAP_ICON

    /** The brightness tile while the shade fades around it. */
    val mirrored: Boolean
        get() = (main as? BrightnessChannel)?.mirrored == true

    fun lookFor(upright: Boolean): QsVolumeLook =
        if (upright && !picked && (look == QsVolumeLook.SLIDER || look == QsVolumeLook.BLOCKS)) {
            QsVolumeLook.FILL
        } else {
            look
        }

    fun onIcon(channel: LevelChannel = main) {
        if (tap == QsVolumeTap.PANEL) channel.showPanel() else channel.toggleMute()
    }

    fun onTap() {
        val spot = tapSpot
        tapSpot = TAP_ICON
        when {
            spot == TAP_ICON -> onIcon()
            spot >= 0f -> main.setFraction(spot)
        }
    }
}

@Composable
internal fun rememberVolumeTile(tweaks: QsTweaks): VolumeTileState {
    val context = LocalContext.current
    val view = LocalView.current
    val sounds =
        if (tweaks.volumeLook == QsVolumeLook.STACK) StackSounds
        else listOf(tweaks.volumeSound ?: QsVolumeSound.MEDIA)
    val ticks = tweaks.volumeTicks != false
    val tap = tweaks.volumeTap ?: QsVolumeTap.MUTE
    val look = levelLookFor(tweaks.volumeLook, qsSliderStyle(), LocalQsGlass.current == QsGlass.LIQUID)
    val show = tweaks.volumeShow ?: QsVolumeShow.ALL
    val percent = tweaks.sliderPercent ?: QsSliderPercent.ALWAYS
    val channels =
        remember(context, view, sounds, ticks) {
            val audio = context.getSystemService(AudioManager::class.java)
            sounds.map { VolumeChannel(audio, view, ticks, it) }
        }
    val picked = tweaks.volumeLook != null
    val state =
        remember(channels, tap, look, show, percent, picked) {
            VolumeTileState(channels, tap, look, show, percent, picked)
        }
    DisposableEffect(context, state) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state.channels.forEach(LevelChannel::sync)
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(AudioManager.VOLUME_CHANGED_ACTION)
                addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION)
                addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
internal fun rememberBrightnessTile(tweaks: QsTweaks): VolumeTileState? {
    val viewModel = LocalQsBrightness.current ?: return null
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val ticks = tweaks.volumeTicks != false
    val look =
        levelLookFor(
            tweaks.brightnessLook?.takeIf { it != QsVolumeLook.STACK },
            qsSliderStyle(),
            LocalQsGlass.current == QsGlass.LIQUID,
        )
    val percent = tweaks.sliderPercent ?: QsSliderPercent.ALWAYS
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val channel =
        remember(viewModel, scope, view, ticks) {
            BrightnessChannel(viewModel, scope, view, ticks) {
                restriction as? PolicyRestriction.Restricted
            }
        }
    LaunchedEffect(channel) {
        snapshotFlow { viewModel.currentBrightness.value }.collect { channel.sync() }
    }
    // A drag cut off by the panel closing must not leave the shade faded.
    DisposableEffect(channel) { onDispose { if (channel.dragging) channel.endDrag() } }
    val picked = tweaks.brightnessLook.let { it != null && it != QsVolumeLook.STACK }
    return remember(channel, look, percent, picked) {
        VolumeTileState(listOf(channel), QsVolumeTap.MUTE, look, QsVolumeShow.ALL, percent, picked)
    }
}

@Composable
private fun rememberLevelFraction(channel: LevelChannel): State<Float> {
    val level = remember(channel) { Animatable(Snapshot.withoutReadObservation { channel.fraction }) }
    LaunchedEffect(channel) {
        // Under the finger the fill tracks it exactly; changes from elsewhere glide in.
        snapshotFlow { if (channel.dragging) channel.dragFraction else channel.fraction }
            .collectLatest { if (channel.dragging) level.snapTo(it) else level.animateTo(it) }
    }
    return level.asState()
}

@Composable
internal fun volumeSliderColors(): SliderColors {
    val tone = LocalQsTweaks.current.accent?.let { Color(it) }
    val colors =
        qsStyleSliderColors(qsSliderStyle())
            ?: SliderDefaults.colors().let {
                if (tone == null) it else it.copy(thumbColor = tone, activeTrackColor = tone)
            }
    return colors.glassy(LocalQsGlass.current, LocalQsGlassTune.current)
}

@Immutable
internal class LevelPaint(val fill: Color, val ink: Color, val fade: Color? = null)

@Composable
internal fun levelPaint(look: QsVolumeLook): LevelPaint {
    val style = qsSliderStyle()
    val tone = LocalQsTweaks.current.accent?.let { Color(it) }
    val on = glassAlphas(LocalQsGlass.current, LocalQsGlassTune.current)?.first ?: 1f
    val liquid = look == QsVolumeLook.LIQUID
    if (style == QsStyle.REMIX) {
        val start = tone ?: colorResource(android.R.color.system_accent1_500)
        if (liquid) return LevelPaint(start.copy(alpha = on), start.inkOn())
        val end =
            tone?.let { lerp(it, Color.Black, .35f) }
                ?: colorResource(android.R.color.system_accent1_700)
        return LevelPaint(start.copy(alpha = on), Color.White, end.copy(alpha = on))
    }
    val accent = tone ?: (qsStyleSliderColors(style) ?: SliderDefaults.colors()).thumbColor
    val see =
        when {
            liquid -> 1f
            style == QsStyle.GLASS -> .42f
            style == QsStyle.NEON -> .34f
            style == QsStyle.BLUEPRINT -> .3f
            else -> 1f
        }
    return if (see < 1f) LevelPaint(accent.copy(alpha = see), accent)
    else LevelPaint(accent.copy(alpha = on), accent.inkOn())
}

@Composable
@ReadOnlyComposable
internal fun TileColors.withLevelTrack(style: QsStyle): TileColors =
    if (background.alpha > 0f) {
        this
    } else {
        when (style) {
            QsStyle.CLASSIC -> copy(background = Color.White.copy(alpha = .06f))
            QsStyle.DUO -> copy(background = Color.White.copy(alpha = .1f))
            QsStyle.CIRCLES,
            QsStyle.SHAPES -> copy(background = LocalAndroidColorScheme.current.surfaceEffect1)
            else -> this
        }
    }

private fun Modifier.horizontalLevelDrag(channel: LevelChannel, rtl: Boolean): Modifier =
    pointerInput(channel, rtl) {
        var exact = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                exact = channel.level.toFloat()
                channel.startDrag()
            },
            onDragEnd = { channel.endDrag() },
            onDragCancel = { channel.endDrag() },
        ) { input, dx ->
            input.consume()
            val span = maxOf(size.width.toFloat(), 180.dp.toPx())
            val steps = (if (rtl) -dx else dx) / span * (channel.max - channel.min)
            exact = (exact + steps).coerceIn(channel.min.toFloat(), channel.max.toFloat())
            channel.drag(exact)
        }
    }

private fun Modifier.verticalLevelDrag(channel: LevelChannel, view: View): Modifier =
    pointerInput(channel) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        .pointerInput(channel) {
            var exact = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    exact = channel.level.toFloat()
                    channel.startDrag()
                },
                onDragEnd = { channel.endDrag() },
                onDragCancel = { channel.endDrag() },
            ) { input, dy ->
                input.consume()
                val steps = -dy / size.height * (channel.max - channel.min)
                exact = (exact + steps).coerceIn(channel.min.toFloat(), channel.max.toFloat())
                channel.drag(exact)
            }
        }

private fun Modifier.tapSpot(
    state: VolumeTileState,
    vertical: Boolean,
    slides: Boolean,
    rtl: Boolean,
): Modifier =
    pointerInput(state, vertical, slides, rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val x = if (rtl) size.width - down.position.x else down.position.x
            state.tapSpot =
                when {
                    vertical -> if (down.position.y > size.height * .7f) TAP_ICON else TAP_NONE
                    slides && x > ICON_ZONE.toPx() -> x / size.width
                    else -> TAP_ICON
                }
        }
    }

@Composable
internal fun volumeLevelModifier(
    state: VolumeTileState,
    tweaks: QsTweaks,
    iconOnly: Boolean,
    rows: Int,
): Modifier {
    val vertical = iconOnly && rows >= 2
    val look = state.lookFor(vertical)
    if (look == QsVolumeLook.STACK && rows >= 2) return Modifier
    val channel = state.main
    val view = LocalView.current
    val collapsed = LocalQsCollapsed.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val filled = look == QsVolumeLook.FILL || look == QsVolumeLook.LIQUID
    val paints = filled || (iconOnly && !vertical)
    val fraction by rememberLevelFraction(channel)
    val paint = levelPaint(look)
    val liquid = look == QsVolumeLook.LIQUID
    val drawn =
        if (!paints) {
            Modifier
        } else {
            Modifier.drawWithCache {
                val tint =
                    if (filled) paint.fill
                    else paint.fill.copy(alpha = minOf(paint.fill.alpha, FILL_ALPHA))
                val body =
                    when {
                        liquid -> {
                            val deep = lerp(tint, Color.Black, .22f)
                            val lit = lerp(tint, Color.White, .24f)
                            if (vertical) Brush.verticalGradient(listOf(lit, tint, deep))
                            else
                                Brush.horizontalGradient(
                                    if (rtl) listOf(lit, tint, deep) else listOf(deep, tint, lit)
                                )
                        }
                        filled && paint.fade != null ->
                            Brush.linearGradient(
                                listOf(tint, paint.fade),
                                Offset.Zero,
                                Offset(size.width, size.height),
                            )
                        else -> SolidColor(tint)
                    }
                val sheen = listOf(Color.Transparent, Color.White.copy(alpha = SHEEN_ALPHA), Color.Transparent)
                val shine =
                    if (vertical) Brush.horizontalGradient(sheen, size.width * .06f, size.width * .44f)
                    else Brush.verticalGradient(sheen, size.height * .06f, size.height * .40f)
                onDrawBehind {
                    val level = fraction.coerceIn(0f, 1f)
                    if (level <= 0f) return@onDrawBehind
                    if (vertical) {
                        val height = size.height * level
                        val top = size.height - height
                        if (liquid) {
                            withTransform({
                                translate(0f, top)
                                scale(1f, level, Offset.Zero)
                            }) {
                                drawRect(body)
                            }
                            drawRect(shine, Offset(size.width * .06f, top), Size(size.width * .38f, height))
                        } else {
                            drawRect(body, Offset(0f, top), Size(size.width, height))
                        }
                    } else {
                        val width = size.width * level
                        val left = if (rtl) size.width - width else 0f
                        if (liquid) {
                            withTransform({
                                translate(left, 0f)
                                scale(level, 1f, Offset.Zero)
                            }) {
                                drawRect(body)
                            }
                            drawRect(shine, Offset(left, size.height * .06f), Size(width, size.height * .34f))
                        } else {
                            drawRect(body, Offset(left, 0f), Size(width, size.height))
                        }
                    }
                }
            }
        }
    return Modifier.semantics {
            progressBarRangeInfo =
                ProgressBarRangeInfo(
                    channel.level.toFloat(),
                    channel.min.toFloat()..channel.max.toFloat(),
                    (channel.max - channel.min - 1).coerceIn(0, 19),
                )
            setProgress { value ->
                channel.set(value.roundToInt().coerceIn(channel.min, channel.max))
                true
            }
        }
        .then(drawn)
        .tapSpot(state, vertical, filled && !iconOnly, rtl)
        .then(
            when {
                !vertical -> Modifier.horizontalLevelDrag(channel, rtl)
                collapsed -> Modifier
                else -> Modifier.verticalLevelDrag(channel, view)
            }
        )
}

@Composable
internal fun BoxScope.VolumeTileContent(
    state: VolumeTileState,
    colors: TileColors,
    look: TileLook,
    paint: QsStyle,
    iconOnly: Boolean,
    rows: Int,
    onLongClick: (() -> Unit)?,
) {
    val tweaks = LocalQsTweaks.current
    val vertical = iconOnly && rows >= 2
    val volumeLook = state.lookFor(vertical)
    val show = state.show
    val sliderColors = volumeSliderColors()
    val longClick by rememberUpdatedState(onLongClick)
    val parts =
        VolumeParts(colors, look, sliderColors, paint, show, state.percent, { longClick?.invoke() })
    val stack = volumeLook == QsVolumeLook.STACK && rows >= 2
    val recolor =
        !stack && (volumeLook == QsVolumeLook.FILL || volumeLook == QsVolumeLook.LIQUID)
    val ink = levelPaint(volumeLook).ink
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fraction by rememberLevelFraction(state.main)
    Box(
        Modifier.fillMaxSize()
            .then(
                if (recolor) Modifier.inkOverFill(ink, { fraction }, vertical, rtl)
                else Modifier
            )
    ) {
        when {
            stack -> StackContent(state, parts)
            vertical -> TallContent(state.main, parts, volumeLook)
            iconOnly ->
                ChannelIcon(state.main, parts, chip = false, Modifier.align(Alignment.Center))
            rows >= 2 -> SquareContent(state.main, parts, volumeLook)
            else -> RowContent(state.main, parts, volumeLook)
        }
    }
}

private fun Modifier.inkOverFill(
    ink: Color,
    fraction: () -> Float,
    vertical: Boolean,
    rtl: Boolean,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val level = fraction().coerceIn(0f, 1f)
            if (vertical) {
                val height = size.height * level
                drawRect(
                    ink,
                    Offset(0f, size.height - height),
                    Size(size.width, height),
                    blendMode = BlendMode.SrcIn,
                )
            } else {
                val width = size.width * level
                drawRect(
                    ink,
                    Offset(if (rtl) size.width - width else 0f, 0f),
                    Size(width, size.height),
                    blendMode = BlendMode.SrcIn,
                )
            }
        }

private class VolumeParts(
    val colors: TileColors,
    val look: TileLook,
    val slider: SliderColors,
    val paint: QsStyle,
    val show: QsVolumeShow,
    val percent: QsSliderPercent,
    val onLongClick: () -> Unit,
) {
    fun showsPercent(channel: LevelChannel): Boolean =
        when (percent) {
            QsSliderPercent.ALWAYS -> true
            QsSliderPercent.DRAG -> channel.dragging
            QsSliderPercent.OFF -> false
        }

    val iconSize: Dp
        get() = look.largeIconSize.takeIf { it > 0.dp } ?: look.smallIconSize

    val labelStyle: TextStyle?
        get() = look.labelStyle

    val secondaryStyle: TextStyle?
        get() = look.secondaryLabelStyle

    val clearChip: Boolean
        get() =
            paint == QsStyle.CLASSIC ||
                paint == QsStyle.NEON ||
                paint == QsStyle.BLUEPRINT ||
                paint == QsStyle.GLASS ||
                paint == QsStyle.SOFT
}

@Composable
private fun ChannelIcon(
    channel: LevelChannel,
    parts: VolumeParts,
    chip: Boolean,
    modifier: Modifier = Modifier,
) {
    val live = !channel.silent
    val thumb = parts.slider.thumbColor
    val backed = chip && !parts.clearChip
    val tint =
        when {
            backed -> if (live) thumb.inkOn() else parts.colors.icon
            chip -> if (live) thumb else parts.colors.icon
            else -> parts.colors.icon
        }
    val size = min(parts.look.height.value - 14f, 40f).dp
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            if (chip) {
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        when {
                            !backed -> Color.Transparent
                            live -> thumb
                            else -> parts.colors.iconBackground
                        }
                    )
            } else {
                modifier
            },
    ) {
        Icon(
            painter =
                painterResource(if (live) channel.icon else channel.mutedIcon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(parts.iconSize),
        )
    }
}

@Composable
private fun channelPercent(channel: LevelChannel, caps: Boolean): String {
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val text =
        if (channel.silent) stringResource(R.string.qs_volume_tile_muted)
        else percent.format(channel.fraction.toDouble())
    return if (caps) text.uppercase() else text
}

@Composable
private fun ChannelTexts(channel: LevelChannel, parts: VolumeParts, showName: Boolean) {
    val caps = parts.look.caps
    Column {
        if (showName) {
            val name = stringResource(channel.label)
            Text(
                text = if (caps) name.uppercase() else name,
                style = parts.labelStyle ?: MaterialTheme.typography.labelLarge,
                color = parts.colors.label,
                maxLines = 1,
            )
        }
        if (parts.showsPercent(channel)) {
            Text(
                text = channelPercent(channel, caps),
                style = parts.secondaryStyle ?: MaterialTheme.typography.labelMedium,
                color = parts.colors.secondaryLabel,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EndPercent(channel: LevelChannel, parts: VolumeParts) {
    Text(
        text = channelPercent(channel, parts.look.caps),
        style = parts.secondaryStyle ?: MaterialTheme.typography.labelMedium,
        color = parts.colors.secondaryLabel,
        maxLines = 1,
        softWrap = false,
    )
}

private fun Modifier.trackTap(
    channel: LevelChannel,
    rtl: Boolean,
    onLongClick: () -> Unit,
): Modifier =
    pointerInput(channel, rtl) {
        detectTapGestures(
            onLongPress = { onLongClick() },
            onTap = { offset ->
                val x = if (rtl) size.width - offset.x else offset.x
                channel.setFraction(x / size.width)
            },
        )
    }

@Composable
private fun VolumeTrack(
    channel: LevelChannel,
    colors: SliderColors,
    dimensions: BrightnessSliderDimensions,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fraction by rememberLevelFraction(channel)
    Canvas(modifier) {
        val trackHeight = dimensions.trackHeight.toPx()
        val thumbWidth = dimensions.thumbWidth.toPx()
        val thumbHeight = dimensions.thumbHeight.toPx()
        val radius = CornerRadius(min(14.dp.toPx(), trackHeight / 2))
        val gap = if (dimensions.thumbWidth <= 6.dp) 3.dp.toPx() else 0f
        val center = thumbWidth / 2 + (size.width - thumbWidth) * fraction
        val activeEnd = if (gap > 0f) center - thumbWidth / 2 - gap else center
        val inactiveStart = if (gap > 0f) center + thumbWidth / 2 + gap else center
        val top = (size.height - trackHeight) / 2
        fun span(start: Float, end: Float, color: Color) {
            if (end <= start) return
            val left = if (rtl) size.width - end else start
            drawRoundRect(color, Offset(left, top), Size(end - start, trackHeight), radius)
        }
        span(inactiveStart, size.width, colors.inactiveTrackColor)
        span(0f, activeEnd, colors.activeTrackColor)
        val thumbLeft = if (rtl) size.width - center - thumbWidth / 2 else center - thumbWidth / 2
        drawRoundRect(
            colors.thumbColor,
            Offset(thumbLeft, (size.height - thumbHeight) / 2),
            Size(thumbWidth, thumbHeight),
            CornerRadius(min(thumbWidth, thumbHeight) / 2),
        )
    }
}

@Composable
private fun VolumeBlocks(
    channel: LevelChannel,
    colors: SliderColors,
    radius: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val gap = BLOCK_GAP.toPx()
        val count = blockCount(channel, size.width)
        val lit = (channel.fraction * count).roundToInt()
        val blockHeight = height.toPx()
        val width = (size.width - gap * (count - 1)) / count
        val top = (size.height - blockHeight) / 2
        repeat(count) { index ->
            val slot = if (rtl) count - 1 - index else index
            drawRoundRect(
                if (index < lit) colors.activeTrackColor else colors.inactiveTrackColor,
                Offset(slot * (width + gap), top),
                Size(width, blockHeight),
                CornerRadius(radius.toPx()),
            )
        }
    }
}

@Composable
private fun RowContent(channel: LevelChannel, parts: VolumeParts, look: QsVolumeLook) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sliderStyle = qsSliderStyle()
    val base = qsStyleSliderDimensions(sliderStyle, BrightnessSliderDimensions.Default)
    val dimensions =
        base.copy(
            trackHeight = minOf(base.trackHeight, parts.look.height - 18.dp),
            thumbHeight = minOf(base.thumbHeight, parts.look.height - 8.dp),
        )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val roomy = maxWidth >= 240.dp
        val fill = look == QsVolumeLook.FILL || look == QsVolumeLook.LIQUID
        val showName = parts.show == QsVolumeShow.ALL && (fill || roomy)
        val showTexts = showName || (parts.show == QsVolumeShow.PERCENT && fill)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp),
        ) {
            ChannelIcon(channel, parts, chip = !fill)
            if (showTexts) ChannelTexts(channel, parts, showName)
            when (look) {
                QsVolumeLook.BLOCKS ->
                    VolumeBlocks(
                        channel,
                        parts.slider,
                        blockRadius(parts),
                        20.dp,
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .trackTap(channel, rtl, parts.onLongClick),
                    )
                QsVolumeLook.FILL,
                QsVolumeLook.LIQUID -> Box(Modifier.weight(1f))
                else ->
                    VolumeTrack(
                        channel,
                        parts.slider,
                        dimensions,
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .trackTap(channel, rtl, parts.onLongClick),
                    )
            }
            if (
                !fill &&
                    !showTexts &&
                    parts.show != QsVolumeShow.ICON &&
                    parts.showsPercent(channel)
            ) {
                EndPercent(channel, parts)
            }
        }
    }
}

@Composable
private fun SquareContent(channel: LevelChannel, parts: VolumeParts, look: QsVolumeLook) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sliderStyle = qsSliderStyle()
    val dimensions = qsStyleSliderDimensions(sliderStyle, BrightnessSliderDimensions.Default)
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelIcon(
                channel,
                parts,
                chip = look != QsVolumeLook.FILL && look != QsVolumeLook.LIQUID,
            )
            if (parts.show != QsVolumeShow.ICON) {
                ChannelTexts(channel, parts, parts.show == QsVolumeShow.ALL)
            }
        }
        when (look) {
            QsVolumeLook.BLOCKS ->
                VolumeBlocks(
                    channel,
                    parts.slider,
                    blockRadius(parts),
                    26.dp,
                    Modifier.fillMaxWidth()
                        .height(32.dp)
                        .trackTap(channel, rtl, parts.onLongClick),
                )
            QsVolumeLook.FILL,
            QsVolumeLook.LIQUID -> Box(Modifier)
            else ->
                VolumeTrack(
                    channel,
                    parts.slider,
                    dimensions,
                    Modifier.fillMaxWidth()
                        .height(maxOf(dimensions.thumbHeight, dimensions.trackHeight))
                        .trackTap(channel, rtl, parts.onLongClick),
                )
        }
    }
}

@Composable
private fun BoxScope.TallContent(
    channel: LevelChannel,
    parts: VolumeParts,
    look: QsVolumeLook,
) {
    if (parts.show != QsVolumeShow.ICON && parts.showsPercent(channel)) {
        Text(
            text = channelPercent(channel, parts.look.caps),
            style = parts.secondaryStyle ?: MaterialTheme.typography.labelMedium,
            color = parts.colors.secondaryLabel,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
    }
    Icon(
        painter =
            painterResource(if (channel.silent) channel.mutedIcon else channel.icon),
        contentDescription = null,
        tint = parts.colors.icon,
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(parts.iconSize),
    )
    val lane =
        Modifier.align(Alignment.Center)
            .fillMaxWidth()
            .padding(top = 34.dp, bottom = 44.dp)
            .fillMaxHeight()
    when (look) {
        QsVolumeLook.SLIDER -> {
            val dimensions = qsStyleSliderDimensions(qsSliderStyle(), BrightnessSliderDimensions.Default)
            UprightTrack(channel, parts.slider, dimensions, lane)
        }
        QsVolumeLook.BLOCKS -> UprightBlocks(channel, parts.slider, blockRadius(parts), lane)
        else -> {}
    }
}

@Composable
private fun UprightTrack(
    channel: LevelChannel,
    colors: SliderColors,
    dimensions: BrightnessSliderDimensions,
    modifier: Modifier = Modifier,
) {
    val fraction by rememberLevelFraction(channel)
    Canvas(modifier) {
        val line = dimensions.trackHeight < LINE_TRACK
        val thickness =
            if (line) maxOf(dimensions.trackHeight.toPx(), size.width * .42f)
            else min(dimensions.trackHeight.toPx(), size.width - 16.dp.toPx())
        val across =
            if (line) min(thickness + 14.dp.toPx(), size.width - 8.dp.toPx())
            else min(dimensions.thumbHeight.toPx(), size.width - 8.dp.toPx())
        val along = if (line) 5.dp.toPx() else dimensions.thumbWidth.toPx()
        val radius = CornerRadius(min(14.dp.toPx(), thickness / 2))
        val gap = if (line || dimensions.thumbWidth <= 6.dp) 3.dp.toPx() else 0f
        val reach = size.height - along
        val center = along / 2 + reach * (1f - fraction)
        val left = (size.width - thickness) / 2
        val half = along / 2
        val activeTop = if (gap > 0f) center + half + gap else center
        val inactiveEnd = if (gap > 0f) center - half - gap else center
        if (inactiveEnd > 0f) {
            drawRoundRect(colors.inactiveTrackColor, Offset(left, 0f), Size(thickness, inactiveEnd), radius)
        }
        if (activeTop < size.height) {
            drawRoundRect(
                colors.activeTrackColor,
                Offset(left, activeTop),
                Size(thickness, size.height - activeTop),
                radius,
            )
        }
        drawRoundRect(
            colors.thumbColor,
            Offset((size.width - across) / 2, center - along / 2),
            Size(across, along),
            CornerRadius(along / 2),
        )
    }
}

@Composable
private fun UprightBlocks(
    channel: LevelChannel,
    colors: SliderColors,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val gap = BLOCK_GAP.toPx()
        val count = blockCount(channel, size.height)
        val lit = (channel.fraction * count).roundToInt()
        val width = min(size.width - 24.dp.toPx(), 40.dp.toPx())
        val height = (size.height - gap * (count - 1)) / count
        val left = (size.width - width) / 2
        repeat(count) { index ->
            drawRoundRect(
                if (index < lit) colors.activeTrackColor else colors.inactiveTrackColor,
                Offset(left, size.height - (index + 1) * height - index * gap),
                Size(width, height),
                CornerRadius(min(radius.toPx(), height / 2)),
            )
        }
    }
}

@Composable
private fun BoxScope.StackContent(state: VolumeTileState, parts: VolumeParts) {
    val sliderStyle = qsSliderStyle()
    val base = qsStyleSliderDimensions(sliderStyle, BrightnessSliderDimensions.Default)
    val bar = base.thumbWidth <= 6.dp
    val dimensions =
        base.copy(
            trackHeight = minOf(base.trackHeight, 10.dp),
            thumbHeight = minOf(base.thumbHeight, if (bar) 22.dp else 20.dp),
            thumbWidth = minOf(base.thumbWidth, 20.dp),
        )
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        state.channels.forEach { channel -> StackRow(state, channel, parts, dimensions) }
    }
}

@Composable
private fun ColumnScope.StackRow(
    state: VolumeTileState,
    channel: LevelChannel,
    parts: VolumeParts,
    dimensions: BrightnessSliderDimensions,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { state.onIcon(channel) },
        ) {
            Icon(
                painter =
                    painterResource(
                        if (channel.silent) channel.mutedIcon else channel.icon
                    ),
                contentDescription = stringResource(channel.label),
                tint = if (channel.silent) parts.colors.icon else parts.slider.thumbColor,
                modifier = Modifier.size(18.dp),
            )
        }
        VolumeTrack(
            channel,
            parts.slider,
            dimensions,
            Modifier.weight(1f)
                .fillMaxHeight()
                .horizontalLevelDrag(channel, rtl)
                .trackTap(channel, rtl, parts.onLongClick),
        )
        if (parts.show != QsVolumeShow.ICON) {
            Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.width(40.dp)) {
                EndPercent(channel, parts)
            }
        }
    }
}

private fun DrawScope.blockCount(channel: LevelChannel, length: Float): Int {
    val gap = BLOCK_GAP.toPx()
    val room = ((length + gap) / (BLOCK.toPx() + gap)).toInt()
    return room.coerceIn(1, (channel.max - channel.min).coerceIn(8, 16))
}

private fun blockRadius(parts: VolumeParts): Dp =
    if (parts.look.activeRadius <= 10.dp) 1.dp else 3.dp
