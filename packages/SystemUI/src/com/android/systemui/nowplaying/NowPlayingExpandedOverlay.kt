/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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
package com.android.systemui.nowplaying

import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.SeekBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.res.R
import kotlinx.coroutines.delay

private val SpaceXs = 4.dp
private val SpaceSm = 6.dp
private val SpaceMd = 8.dp
private val SpaceLg = 12.dp
private val SpaceXxl = 16.dp

private val ShapeCard = RoundedCornerShape(28.dp)
private val ShapeLg = RoundedCornerShape(24.dp)
private val ShapeIcon = RoundedCornerShape(14.dp)

private val SizeSeekHeight = 26.dp
private val SizeAlbumArt = 80.dp

private const val AlphaFaint = 0.10f
private const val AlphaSubtle = 0.15f

private val CardBg: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

private val CardBorderBrush: Brush
    @Composable get() = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
        )
    )

private val OnCardPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

private val OnCardSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val AccentColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary

private val AccentContainer: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer

private val OnAccentColor: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary

private val TonalSurface: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer

private val OnTonalSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer

private fun formatElapsedTime(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(secs / 60, secs % 60)
}

private const val ANIM_DURATION = 280

class NowPlayingExpandedOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mediaSessionManager: MediaSessionManager,
) {
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        if (overlayView != null) return
        NowPlayingOverlayState.setOverlayOpen(true)
        ensureMainThread { showInternal() }
    }

    fun hide() {
        NowPlayingOverlayState.setOverlayOpen(false)
        ensureMainThread {
            mainHandler.postDelayed({ hideInternal() }, 220L)
        }
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private fun showInternal() {
        val lco = OverlayLifecycleOwner().also { lifecycleOwner = it }
        lco.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lco.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lco.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val view = ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    NowPlayingExpandedPanel(
                        onDismiss = { hide() },
                        onTogglePlayPause = { togglePlayPause() },
                        onSkipNext = { skipNext() },
                        onSkipPrev = { skipPrev() },
                        onSeekTo = { pos -> seekTo(pos) },
                        onOpenApp = { openMediaApp() },
                    )
                }
            }
        }
        view.setViewTreeLifecycleOwner(lco)
        view.setViewTreeSavedStateRegistryOwner(lco)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            title = "NowPlayingExpanded"
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun hideInternal() {
        overlayView?.let { view ->
            lifecycleOwner?.apply {
                handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            windowManager.removeViewImmediate(view)
        }
        overlayView = null
        lifecycleOwner = null
    }

    private fun getController(): MediaController? =
        try { mediaSessionManager.getActiveSessions(null).firstOrNull() }
        catch (_: Exception) { null }

    private fun togglePlayPause() {
        val c = getController() ?: return
        when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> c.transportControls.pause()
            else -> c.transportControls.play()
        }
    }

    private fun skipNext() { getController()?.transportControls?.skipToNext() }
    private fun skipPrev() { getController()?.transportControls?.skipToPrevious() }
    private fun seekTo(pos: Long) { getController()?.transportControls?.seekTo(pos) }

    private fun openMediaApp() {
        val pkg = getController()?.packageName ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        try { context.startActivity(intent) } catch (_: Exception) {}
        hide()
    }
}

@Composable
private fun NowPlayingExpandedPanel(
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenApp: () -> Unit,
) {
    val isOpen by NowPlayingOverlayState.isOverlayOpen.collectAsState()

    val visible = remember { MutableTransitionState(false) }

    LaunchedEffect(isOpen) {
        delay(16)
        visible.targetState = isOpen
    }

    val origin = TransformOrigin(0.5f, 1.0f)

    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(ANIM_DURATION)) + scaleIn(
            animationSpec = tween(ANIM_DURATION + 70),
            initialScale = 0.72f,
            transformOrigin = origin,
        ),
        exit = fadeOut(tween(180)) + scaleOut(
            animationSpec = tween(200),
            targetScale = 0.72f,
            transformOrigin = origin,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                NowPlayingMediaCard(
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrev = onSkipPrev,
                    onSeekTo = onSeekTo,
                    onOpenApp = onOpenApp,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingMediaCard(
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by NowPlayingOverlayState.state.collectAsState()
    val accent = AccentColor
    val onAccent = OnAccentColor
    val tonalBg = TonalSurface
    val onTonal = OnTonalSurface
    val cardBg = CardBg
    val border = CardBorderBrush
    val onCard = OnCardPrimary
    val onCardSub = OnCardSecondary

    var showVolumeSlider by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, ShapeCard),
        shape = ShapeCard,
        color = cardBg,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenApp() }
                    .padding(SpaceXxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceXxl),
            ) {
                Box(
                    modifier = Modifier
                        .size(SizeAlbumArt)
                        .clip(ShapeIcon),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = state.albumArt
                    if (art != null) {
                        Image(
                            bitmap = remember(art) { art.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(ShapeIcon)
                                .then(
                                    Modifier.run {
                                        val bg = AccentContainer
                                        background(bg)
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote, null,
                                tint = accent,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpaceXs),
                ) {
                    Text(
                        text = state.track.ifEmpty { "Now playing" },
                        color = onCard,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    if (state.artist.isNotEmpty()) {
                        Text(
                            text = state.artist,
                            color = onCardSub,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tonalBg.copy(alpha = 0.5f))
                    .padding(horizontal = SpaceXxl, vertical = SpaceLg),
                verticalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                if (state.duration > 0L) {
                    NowPlayingSeekBar(
                        state = state,
                        accent = accent,
                        onTonal = onTonal,
                        onSeekTo = onSeekTo,
                    )
                }

                AnimatedVisibility(
                    visible = showVolumeSlider,
                    enter = expandVertically(
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing
                        ),
                        expandFrom = Alignment.Top
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = 200,
                            delayMillis = 50,
                            easing = LinearOutSlowInEasing
                        )
                    ),
                    exit = shrinkVertically(
                        animationSpec = tween(
                            durationMillis = 200,
                            easing = FastOutSlowInEasing
                        ),
                        shrinkTowards = Alignment.Top
                    ) + fadeOut(
                        animationSpec = tween(
                            durationMillis = 150,
                            easing = LinearOutSlowInEasing
                        )
                    ),
                ) {
                    VolumeSliderRow(
                        accent = accent,
                        onTonal = onTonal,
                        tonalBg = tonalBg,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { showVolumeSlider = !showVolumeSlider },
                        shape = CircleShape,
                        color = if (showVolumeSlider) accent else tonalBg,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = null,
                                tint = if (showVolumeSlider) onAccent else onTonal,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onSkipPrev,
                        shape = CircleShape,
                        color = tonalBg,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = null,
                                tint = onTonal,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onTogglePlayPause,
                        shape = CircleShape,
                        color = accent,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause
                                              else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = onAccent,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }

                    Surface(
                        onClick = onSkipNext,
                        shape = CircleShape,
                        color = tonalBg,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = null,
                                tint = onTonal,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = tonalBg,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = onTonal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeSliderRow(
    accent: Color,
    onTonal: Color,
    tonalBg: Color,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (newVolume != currentVolume) {
                    currentVolume = newVolume
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(runnable)
        onDispose {
            handler.removeCallbacks(runnable)
        }
    }

    val volumeIcon: ImageVector = when {
        currentVolume == 0 -> Icons.Filled.VolumeOff
        currentVolume < maxVolume / 3 -> Icons.Filled.VolumeMute
        currentVolume < maxVolume * 2 / 3 -> Icons.Filled.VolumeDown
        else -> Icons.Filled.VolumeUp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tonalBg.copy(alpha = 0.6f))
            .padding(horizontal = SpaceLg, vertical = SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Icon(
            imageVector = volumeIcon,
            contentDescription = null,
            tint = onTonal,
            modifier = Modifier.size(20.dp),
        )
        
        Slider(
            value = currentVolume.toFloat(),
            onValueChange = { newValue ->
                val newVolume = newValue.toInt()
                currentVolume = newVolume
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    newVolume,
                    0
                )
            },
            valueRange = 0f..maxVolume.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = onTonal.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun NowPlayingSeekBar(
    state: NowPlayingMediaState,
    accent: Color,
    onTonal: Color,
    onSeekTo: (Long) -> Unit,
) {
    val durationMs = state.duration

    val serverFraction = if (durationMs > 0L)
        (state.position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    var displayFraction by remember { mutableFloatStateOf(serverFraction) }
    var isScrubbing by remember { mutableStateOf(false) }
    val onSeekRef = rememberUpdatedState(onSeekTo)

    LaunchedEffect(
        state.position, durationMs, state.isPlaying, state.positionUpdateTime
    ) {
        if (isScrubbing) return@LaunchedEffect
        displayFraction = serverFraction
        if (!state.isPlaying || durationMs <= 0L) return@LaunchedEffect
        val wallStart = System.currentTimeMillis()
        val posStart = state.position
        while (true) {
            delay(16L)
            if (isScrubbing) break
            val elapsed = System.currentTimeMillis() - wallStart
            val interp = ((posStart + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
            displayFraction = interp
            if (interp >= 1f) break
        }
    }

    val progressMs = (displayFraction * durationMs).toLong()
    val timeStyle = MaterialTheme.typography.labelSmall

    Column(verticalArrangement = Arrangement.spacedBy(SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatElapsedTime(progressMs), color = onTonal, style = timeStyle)
            if (durationMs > 0L) {
                Text(
                    "-${formatElapsedTime(durationMs - progressMs)}",
                    color = onTonal,
                    style = timeStyle,
                )
            }
        }

        if (state.useWaveformSeekBar) {
            NowPlayingWaveformBar(
                displayFraction = displayFraction,
                durationMs = durationMs,
                isPlaying = state.isPlaying,
                isScrubbing = isScrubbing,
                onScrubStart = { isScrubbing = true },
                onScrubStop = { isScrubbing = false },
                onSeek = { f -> onSeekRef.value((f * durationMs).toLong()) },
            )
        } else {
            NowPlayingSquigglyBar(
                displayFraction = displayFraction,
                durationMs = durationMs,
                isPlaying = state.isPlaying,
                isScrubbing = isScrubbing,
                onScrubStart = { isScrubbing = true },
                onScrubStop = { isScrubbing = false },
                onSeek = { f -> onSeekRef.value((f * durationMs).toLong()) },
            )
        }
    }
}

@Composable
private fun NowPlayingSquigglyBar(
    displayFraction: Float,
    durationMs: Long,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubStop: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val onSeekRef = rememberUpdatedState(onSeek)

    AndroidView(
        factory = { ctx ->
            SeekBar(ctx).apply {
                max = 10_000
                splitTrack = false

                val layer = progressDrawable?.mutate() as? LayerDrawable
                if (layer != null) {
                    layer.findDrawableByLayerId(android.R.id.background)
                        ?.mutate()
                        ?.setTint(
                            com.android.internal.graphics.ColorUtils
                                .setAlphaComponent(android.graphics.Color.WHITE, 70)
                        )
                    val squiggle = SquigglyProgress().apply {
                        waveLength = ctx.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_wavelength
                        ).toFloat()
                        lineAmplitude = ctx.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_amplitude
                        ).toFloat()
                        phaseSpeed = ctx.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_phase
                        ).toFloat()
                        strokeWidth = ctx.resources.getDimensionPixelSize(
                            R.dimen.qs_media_seekbar_progress_stroke_width
                        ).toFloat()
                        setTint(android.graphics.Color.WHITE)
                        drawRemainingLine = false
                        transitionEnabled = false
                        animate = false
                    }
                    layer.setDrawableByLayerId(android.R.id.progress, squiggle)
                    progressDrawable = layer
                }

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (fromUser) onSeekRef.value(v / 10_000f)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { onScrubStart() }
                    override fun onStopTrackingTouch(sb: SeekBar?)  { onScrubStop() }
                })
            }
        },
        update = { bar ->
            val target = (displayFraction * 10_000f).toInt().coerceIn(0, 10_000)
            if (!isScrubbing && bar.progress != target) bar.progress = target

            val alpha = if (isPlaying) 255 else (255 * 0.55f).toInt()
            bar.thumb?.alpha = alpha

            val squiggle = (bar.progressDrawable as? LayerDrawable)
                ?.findDrawableByLayerId(android.R.id.progress) as? SquigglyProgress
            squiggle?.apply {
                setTint(android.graphics.Color.WHITE)
                setAlpha(alpha)
                animate = isPlaying && !isScrubbing
            }
            (bar.progressDrawable as? LayerDrawable)?.alpha = alpha
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(SizeSeekHeight),
    )
}

@Composable
private fun NowPlayingWaveformBar(
    displayFraction: Float,
    durationMs: Long,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubStop: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val onSeekRef = rememberUpdatedState(onSeek)

    AndroidView(
        factory = { ctx ->
            WaveformSeekBar(ctx).apply {
                max = 10_000
                setMediaColor(android.graphics.Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (fromUser) onSeekRef.value(v / 10_000f)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { onScrubStart() }
                    override fun onStopTrackingTouch(sb: SeekBar?) { onScrubStop() }
                })
            }
        },
        update = { bar ->
            if (!isScrubbing) {
                val target = (displayFraction * 10_000f).toInt().coerceIn(0, 10_000)
                if (bar.progress != target) bar.progress = target
            }
            when {
                isPlaying && !bar.isPlaying -> bar.startWaveAnimation()
                !isPlaying && bar.isPlaying -> bar.stopWaveAnimation()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(SizeSeekHeight),
    )
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry  get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        registry.handleLifecycleEvent(event)
    }
}
