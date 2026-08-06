@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.model.Chronometer
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.AxBlurColors
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.animation.Expandable as SystemUiExpandable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import kotlin.math.abs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Calendar

private val ChipHeight = 36.dp
private val ChipShape = ShapeChip
private val ChipIconSize = ChipHeight - SpaceLg
private val MusicChipHeight = 52.dp
private val MusicChipMinWidth = 130.dp
private val MusicActionSize = MusicChipHeight - 16.dp
private val MusicActionIconSize = MusicChipHeight - 26.dp
private val ActionSize = SpacePanel
private val ActionIconSize = SizeBadge
private val BatteryIconSize = ChipHeight - SpaceXxl
private val CountBadgeHeight = ChipHeight / 2

private class MusicPillBlurHost(context: Context) : View(context) {
    private val blur = AxBlurBackgroundRenderer(this)
    private val overlayColor = AxBlurColors.surfaceBrightTint(context)

    private val bgDrawable: GradientDrawable = GradientDrawable().also { d ->
        d.setColor(0x00000000)
        d.cornerRadius = context.resources.displayMetrics.density * 50f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        blur.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        blur.onVisibilityAggregated(isVisible)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        blur.verifyDrawable(who) || super.verifyDrawable(who)

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            bgDrawable.setBounds(0, 0, width, height)
            if (!blur.drawBackgroundWithOverlayColor(canvas, bgDrawable, overlayColor)) {
                bgDrawable.setColor(overlayColor and 0x00FFFFFF or (0xCC shl 24))
                bgDrawable.draw(canvas)
                bgDrawable.setColor(0x00000000)
            }
        }
    }
}

@Composable
private fun rememberChargingParts(batteryString: String): List<String> {
    return remember(batteryString) {
        batteryString.split("\n", limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

@Composable
fun AxDynamicBarKeyguardChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val isKeyguardMusicPillEnabled by viewModel.isKeyguardMusicPillEnabled.collectAsStateWithLifecycle()
    val keyguardBatteryChipMode by viewModel.keyguardBatteryChipMode.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val batteryString by viewModel.batteryString.collectAsStateWithLifecycle()

    val motionScheme = MaterialTheme.motionScheme
    val expandableController = rememberExpandableController(color = Color.Transparent, shape = ChipShape)

    Box(modifier = modifier) {

        val expandedVisibleState = remember { MutableTransitionState(false) }
        expandedVisibleState.targetState = isKeyguardExpanded && state != null
        LaunchedEffect(expandedVisibleState.isIdle, expandedVisibleState.currentState) {
            if (expandedVisibleState.isIdle && !expandedVisibleState.currentState) {
                viewModel.keyguardExpansion.notifyCollapseSettled()
            }
        }
        AnimatedVisibility(
            visibleState = expandedVisibleState,
            enter = fadeIn(motionScheme.defaultEffectsSpec()),
            exit = fadeOut(tween(durationMillis = 250)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
        ) {
            state?.let {
                KeyguardExpandedContent(
                    event = it.event,
                    allEvents = it.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }

        AnimatedVisibility(
            visible = isOnKeyguard && isEnabled && isKeyguardEnabled && !isKeyguardExpanded,
            enter = fadeIn(tween(durationMillis = 200, delayMillis = 300)) +
                scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(durationMillis = 200, delayMillis = 300),
                ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(viewModel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startX = down.position.x
                        var dragging = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    change.consume()
                                    if (totalDx > 0) viewModel.cyclePrev()
                                    else viewModel.cycleNext()
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            if (!dragging && abs(dx) > touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                totalDx = dx
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val chipState = state
            if (chipState != null) {
                val rawEvent = chipState.notificationAlert ?: chipState.event
                val displayEvent: com.android.systemui.axdynamicbar.model.IslandEvent? =
                    when {
                        isKeyguardMusicPillEnabled -> rawEvent
                        rawEvent is com.android.systemui.axdynamicbar.model.IslandEvent.Media -> {
                            chipState.allEvents.firstOrNull { it !is com.android.systemui.axdynamicbar.model.IslandEvent.Media }
                        }
                        else -> rawEvent
                    }

                if (displayEvent == null) {
                    KeyguardBatteryChip(
                        batteryInfo,
                        keyguardBatteryChipMode,
                        batteryString,
                        modifier,
                    )
                    return@AnimatedVisibility
                }

                AnimatedContent(
                    targetState = displayEvent,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                            initialScale = 0.95f,
                            animationSpec = motionScheme.defaultSpatialSpec(),
                        )) togetherWith (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                            targetScale = 0.95f,
                            animationSpec = motionScheme.fastSpatialSpec(),
                        )) using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { it::class.simpleName },
                    label = "keyguard_chip_event",
                ) { event ->
                    val rawAccent = chipAccentColorFor(event)
                    val accent by animateColorAsState(
                        rawAccent,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_accent",
                    )
                    val contentColor by animateColorAsState(
                        chipContentColorOn(rawAccent),
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_content",
                    )
                    val rawProgress = chipProgressFor(event)
                    val progressTarget = rawProgress ?: 0f
                    val progressAnim = remember { Animatable(progressTarget) }
                    LaunchedEffect(progressTarget) {
                        if (abs(progressTarget - progressAnim.value) > 0.05f) {
                            progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                        } else {
                            progressAnim.snapTo(progressTarget)
                        }
                    }
                    val progress = if (rawProgress != null) progressAnim.value else null

                    Expandable(
                        controller = expandableController,
                        onClick = null,
                        defaultMinSize = false,
                    ) { expandable ->
                        KeyguardChipBody(
                            event = event,
                            accent = accent,
                            contentColor = contentColor,
                            progress = progress,
                            eventCount = chipState.eventCount,
                            viewModel = viewModel,
                            batteryString = batteryString,
                            aospChipExpandable = expandable,
                        )
                    }
                }
            } else {
                KeyguardBatteryChip(
                    batteryInfo,
                    keyguardBatteryChipMode,
                    batteryString,
                    modifier,
                )
            }
        }
    }
}

@Composable
private fun KeyguardChipBody(
    event: IslandEvent,
    accent: Color,
    contentColor: Color,
    progress: Float?,
    eventCount: Int,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String = "",
    aospChipExpandable: SystemUiExpandable,
) {
    val context = LocalContext.current
    val motionScheme = MaterialTheme.motionScheme
    var toggleCount by remember { mutableIntStateOf(0) }
    val isMedia = event is IslandEvent.Media

    val parts = rememberChargingParts(batteryString)
    val isMultiLineCharging = event is IslandEvent.Charging && parts.size >= 2
    val dynamicHeight = when {
        isMedia -> MusicChipHeight
        isMultiLineCharging -> 48.dp
        else -> ChipHeight
    }
    val dynamicMinWidth = if (isMedia) MusicChipMinWidth else 48.dp

    Box(contentAlignment = Alignment.Center) {
        if (isMedia) {
            Box(
                modifier = Modifier
                    .matchParentSize(),
            ) {
                AndroidView(
                    factory = { ctx -> MusicPillBlurHost(ctx) },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(ChipShape),
                )
            }
        }
        Row(
            modifier = Modifier
                .height(dynamicHeight)
                .widthIn(min = dynamicMinWidth, max = 260.dp)
                .clip(ChipShape)
                .squishAnimation(toggleCount)
                .background(if (isMedia) Color.Transparent else accent)
                .animateContentSize(motionScheme.defaultSpatialSpec())
                .then(
                    if (progress != null) {
                        val trackColor = lerp(accent, contentColor, 0.2f)
                        val fillColor = lerp(accent, contentColor, 0.6f)
                        Modifier.drawWithContent {
                            drawContent()
                            val barH = SizeStrokeWidth.toPx()
                            val y = size.height - barH
                            drawRect(trackColor, Offset(0f, y), Size(size.width, barH))
                            drawRect(fillColor, Offset(0f, y), Size(size.width * progress, barH))
                        }
                    } else Modifier
                )
                .clickable {
                    when (event) {
                        is IslandEvent.Notification ->
                            viewModel.launchNotificationFromKeyguard(event)

                        is IslandEvent.AospChip -> {
                            viewModel.handleAospChipTap(event, aospChipExpandable)
                        }

                        is IslandEvent.KeyguardIndication,
                        is IslandEvent.AppSwitch -> { }
                        else -> {
                            val wasExpanded = viewModel.keyguardExpansion.isExpanded.value
                            viewModel.keyguardExpansion.toggle()
                            if (!wasExpanded) toggleCount++
                        }
                    }
                }
                .padding(start = if (isMedia) SpaceMd else SpaceSm, end = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (event is IslandEvent.Media) {
                AnimatedContent(
                    targetState = event.albumArt,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false)
                    },
                    contentKey = { it?.hashCode() ?: 0 },
                    label = "kg_media_icon",
                ) { art ->
                    if (art != null) {
                        Image(
                            bitmap = art.toScaledBitmap(MusicActionSize),
                            contentDescription = null,
                            modifier = Modifier
                                .size(MusicActionSize)
                                .clip(ShapeSm),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        PillEventIcon(event, tint = contentColor, animated = false)
                    }
                }
                Spacer(Modifier.width(SpaceXs))
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { "${it.track}|${it.artist}" },
                    label = "kg_media_text",
                    modifier = Modifier.weight(1f, fill = false),
                ) { ev ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.widthIn(max = 110.dp),
                    ) {
                        Text(
                            ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                            style = PillPrimary,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(iterations = 1),
                        )
                        if (ev.artist.isNotBlank()) {
                            Text(
                                ev.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(SpaceXs))
                Surface(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(MusicActionSize),
                    shape = CircleShape,
                    color = lerp(accent, contentColor, AlphaSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(MusicActionSize)) {
                        Icon(
                            if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (event.isPlaying) R.string.ax_dynamic_bar_pause
                                else R.string.ax_dynamic_bar_play,
                            ),
                            tint = contentColor,
                            modifier = Modifier.size(MusicActionIconSize),
                        )
                    }
                }
            } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                SportsChipTeamBadge(event.team1Name, event.team1Icon, contentColor)
                Spacer(Modifier.width(SpaceXs))
                Text(
                    if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
                        else stringResource(R.string.ax_dynamic_bar_sports_vs),
                    style = PillPrimary,
                    color = contentColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(SpaceXs))
                SportsChipTeamBadge(event.team2Name, event.team2Icon, contentColor)
            } else {
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { iconKeyFor(it) },
                    label = "kg_chip_icon",
                ) { ev ->
                    PillEventIcon(ev, tint = contentColor, animated = false)
                }
                Spacer(Modifier.width(SpaceXs))
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { textKeyFor(it) },
                    label = "kg_chip_text",
                    modifier = Modifier.weight(1f, fill = false),
                ) { ev ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeyguardPrimaryText(ev, contentColor, Modifier.weight(1f, fill = false), batteryString)
                        val secondary = secondaryTextFor(ev)
                        if (secondary != null) {
                            Text(
                                " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                            Text(
                                secondary,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 80.dp),
                            )
                        }
                    }
                }
            }

            if (event !is IslandEvent.Media) {
                val actionsCtx = LocalContext.current
                val actions = actionsFor(event, actionsCtx)
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.width(SpaceXs))
                    actions.forEach { action ->
                        Spacer(Modifier.width(SpaceXxs))
                        ActionButton(
                            icon = action.icon,
                            color = contentColor,
                            bgColor = accent.copy(alpha = 0.5f),
                            onClick = { action.perform(viewModel, event, context) },
                            size = ActionSize,
                            iconSize = ActionIconSize,
                        )
                    }
                }
            }

            if (eventCount > 1 && event !is IslandEvent.Media) {
                Spacer(Modifier.width(SpaceXs))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(CountBadgeHeight)
                        .widthIn(min = CountBadgeHeight)
                        .background(lerp(accent, contentColor, AlphaDisabled), ShapeChip)
                        .padding(horizontal = SpaceXxs),
                ) {
                    Text(
                        "$eventCount",
                        style = TsBadge,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyguardBatteryChip(
    info: KeyguardBatteryInfo,
    keyguardBatteryChipMode: Int,
    batteryString: String,
    modifier: Modifier,
) {
    if (keyguardBatteryChipMode <= 0) return

    if (keyguardBatteryChipMode == 1 && !info.isCharging) return

    val accent = when {
        info.isCharging -> BatteryChargingColor
        info.isPowerSave -> BatteryPowerSaveColor
        else -> BatteryNeutralColor
    }
    val contentColor = chipContentColorOn(accent)

    val parts = rememberChargingParts(batteryString)
    val isMultiLine = info.isCharging && parts.size >= 2
    val dynamicHeight = if (isMultiLine) 48.dp else ChipHeight

    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = modifier
                .height(dynamicHeight)
                .clip(ChipShape)
                .background(accent)
                .widthIn(min = 48.dp, max = 260.dp)
                .padding(horizontal = SpaceMd)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            
            if (info.isCharging) {
                AnimatedChargingBoltIcon(contentColor, BatteryIconSize)
            } else {
                AnimatedBatteryFillIcon(info.level, contentColor, BatteryIconSize)
            }
            Spacer(Modifier.width(SpaceXs))
            if (info.isCharging) {
                if (isMultiLine) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            parts[0],
                            style = PillPrimary,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            parts[1],
                            style = PillPrimary.copy(fontSize = 10.sp),
                            color = contentColor.copy(alpha = AlphaSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        if (parts.isNotEmpty()) parts[0] else batteryString,
                        style = PillPrimary,
                        color = contentColor.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
                    )
                }
                return
            }
            Text(
                "${info.level}%",
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
            )

            val secondaryLabel = when {
                info.isCharging && info.isWireless -> stringResource(R.string.ax_dynamic_bar_wireless)
                info.isCharging -> stringResource(R.string.ax_dynamic_bar_charging)
                info.isPowerSave -> stringResource(R.string.ax_dynamic_bar_saver)
                else -> null
            }
            if (secondaryLabel != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    secondaryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }

            val timeRemaining = info.timeRemaining
            if (timeRemaining != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    timeRemaining,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AnimatedChargingBoltIcon(color: Color, iconSize: Dp = BatteryIconSize) {
    val transition = rememberInfiniteTransition(label = "kg_bolt")
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "kg_bolt_glow",
    )
    val boltPath = remember { Path() }
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, h * 0.05f)
        boltPath.lineTo(w * 0.25f, h * 0.50f)
        boltPath.lineTo(w * 0.45f, h * 0.50f)
        boltPath.lineTo(w * 0.40f, h * 0.95f)
        boltPath.lineTo(w * 0.75f, h * 0.42f)
        boltPath.lineTo(w * 0.55f, h * 0.42f)
        boltPath.close()
        drawPath(boltPath, color.copy(alpha = glow))
    }
}

@Composable
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
    val fillFraction by animateFloatAsState(
        targetValue = (level / 100f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "kg_battery_fill",
    )
    Canvas(modifier = Modifier.size(iconSize * 1.6f, iconSize)) {
        val w = size.width
        val h = size.height
        val cornerRadius = h * 0.18f

        val bodyW = w * 0.88f
        val bodyH = h * 0.62f
        val bodyX = 0f
        val bodyY = (h - bodyH) / 2f

        val nubW = w * 0.07f
        val nubH = bodyH * 0.38f
        val nubX = bodyW
        val nubY = bodyY + (bodyH - nubH) / 2f
        val nubRadius = nubW * 0.45f

        val inset = h * 0.05f
        val innerRadius = (cornerRadius - inset).coerceAtLeast(2f)

        drawRoundRect(
            color = color.copy(alpha = 0.75f),
            topLeft = Offset(nubX, nubY),
            size = Size(nubW, nubH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(nubRadius, nubRadius),
        )

        drawRoundRect(
            color = color.copy(alpha = 0.35f),
            topLeft = Offset(bodyX, bodyY),
            size = Size(bodyW, bodyH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.09f),
        )

        drawRoundRect(
            color = color.copy(alpha = 0.12f),
            topLeft = Offset(bodyX + inset, bodyY + inset),
            size = Size(bodyW - inset * 2, bodyH - inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(innerRadius, innerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.03f),
        )

        if (fillFraction > 0f) {
            val fillPad = h * 0.10f
            val fillAreaX = bodyX + fillPad
            val fillAreaY = bodyY + fillPad
            val fillAreaW = bodyW - fillPad * 2
            val fillAreaH = bodyH - fillPad * 2
            val fillRadius = (cornerRadius - fillPad).coerceAtLeast(2f)

            val clipPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = fillAreaX,
                        top = fillAreaY,
                        right = fillAreaX + fillAreaW,
                        bottom = fillAreaY + fillAreaH,
                        radiusX = fillRadius,
                        radiusY = fillRadius,
                    )
                )
            }

            withTransform({ clipPath(clipPath) }) {
                drawRect(
                    color = color,
                    topLeft = Offset(fillAreaX, fillAreaY),
                    size = Size(fillAreaW * fillFraction, fillAreaH),
                )
            }
        }
    }
}

@Composable
private fun KeyguardPrimaryText(event: IslandEvent, color: Color, modifier: Modifier, batteryString: String = "") {
    when (event) {
        is IslandEvent.AudioRecording -> when (event.state) {
            RecordingState.RECORDING -> ElapsedTimeText(
                event.startTimeMs, color, modifier, event.pausedDurationMs,
            )
            RecordingState.PAUSED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
            RecordingState.SAVED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_saved), color, modifier)
        }
        is IslandEvent.Media -> MarqueeText(event.track, color, modifier)
        is IslandEvent.Timer -> {
            if (event.endTimeMs > 0L) CountdownText(event, color, modifier)
            else MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }, color, modifier)
        }
        is IslandEvent.Stopwatch -> StopwatchTimeText(event, color, modifier)
        is IslandEvent.Notification -> MarqueeText(event.title ?: event.appName, color, modifier)
        is IslandEvent.Charging -> {
            val parts = rememberChargingParts(batteryString)
            if (parts.size >= 2) {
                Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        parts[0],
                        style = PillPrimary,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        parts[1],
                        style = PillPrimary.copy(fontSize = 10.sp),
                        color = color.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                MarqueeText(if (parts.isNotEmpty()) parts[0] else "${event.level}%", color, modifier)
            }
        }
        is IslandEvent.Bluetooth -> MarqueeText(event.deviceName.take(12), color, modifier)
        is IslandEvent.Hotspot -> {
            val hotspotLabel = stringResource(R.string.ax_dynamic_bar_hotspot)
            MarqueeText(
                if (event.numDevices > 0) "$hotspotLabel · ${event.numDevices}" else hotspotLabel,
                color, modifier,
            )
        }
        is IslandEvent.Alarm -> MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }, color, modifier)
        is IslandEvent.Call -> {
                if (event.callStartTimeMs > 0) CallTimerText(event, modifier, color)
                else MarqueeText(event.callType ?: stringResource(R.string.ax_dynamic_bar_call), color, modifier)
        }
        is IslandEvent.Torch -> MarqueeText(
            if (event.supportsLevel) "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%"
            else stringResource(R.string.ax_dynamic_bar_flashlight),
            color, modifier,
        )
        is IslandEvent.RingerMode -> MarqueeText(event.label, color, modifier)
        is IslandEvent.Vpn -> MarqueeText(stringResource(R.string.ax_dynamic_bar_vpn_active), color, modifier)
        is IslandEvent.Clipboard -> MarqueeText(
            event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }, color, modifier,
        )
        is IslandEvent.BiometricUnlock -> MarqueeText(stringResource(R.string.ax_dynamic_bar_unlocked), color, modifier)
        is IslandEvent.AppSwitch -> MarqueeText(stringResource(R.string.ax_dynamic_bar_recents), color, modifier)
        is IslandEvent.PromotedOngoing -> MarqueeText(
            event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }, color, modifier,
        )
        is IslandEvent.Sports -> MarqueeText(
            "${event.score1}-${event.score2}", color, modifier,
        )
        is IslandEvent.NowPlaying -> MarqueeText(
            "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' '), color, modifier,
        )
        is IslandEvent.KeyguardIndication -> MarqueeText(event.text, color, modifier)
        is IslandEvent.AospChip -> AospKeyguardChipText(event, color, modifier)
    }
}

@Composable
private fun AospKeyguardChipText(
    event: IslandEvent.AospChip,
    color: Color,
    modifier: Modifier,
) {
    when (val content = event.active.content) {
        is OngoingActivityChipModel.Content.Text -> {
            if (content.text.isNotBlank()) MarqueeText(content.text, color, modifier)
        }
        is OngoingActivityChipModel.Content.Timer -> AospKeyguardTimerText(content, color, modifier)
        is OngoingActivityChipModel.Content.ShortTimeDelta -> AospKeyguardDeltaText(content, color, modifier)
        is OngoingActivityChipModel.Content.Countdown -> Text(
            formatCountdownLong(content.secondsUntilStarted * 1000L),
            color = color,
            style = PillMono,
            modifier = modifier,
        )
        is OngoingActivityChipModel.Content.IconOnly -> Unit
        is OngoingActivityChipModel.Content.TextVariants -> {
            content.textVariants.firstOrNull()?.let { text ->
                if (text.isNotBlank()) MarqueeText(text, color, modifier)
            }
        }
    }
}

@Composable
private fun AospKeyguardTimerText(
    content: OngoingActivityChipModel.Content.Timer,
    color: Color,
    modifier: Modifier,
) {
    var elapsedMs by remember(content.value, content.timeSource) {
        mutableLongStateOf(aospTimerElapsedMs(content))
    }
    LaunchedEffect(content.value, content.timeSource) {
        while (true) {
            elapsedMs = aospTimerElapsedMs(content)
            when (val chronometer = content.value) {
                is Chronometer.Paused -> break
                is Chronometer.Running -> {
                    val zeroMs = chronometer.eventTime.asElapsedRealtime(content.timeSource)
                    val nowMs = content.timeSource.elapsedRealtime()
                    delay(1000L - abs(nowMs - zeroMs) % 1000L)
                }
            }
        }
    }
    Text(formatCountdownLong(elapsedMs), color = color, style = PillMono, modifier = modifier)
}

private fun aospTimerElapsedMs(content: OngoingActivityChipModel.Content.Timer): Long {
    return when (val chronometer = content.value) {
        is Chronometer.Paused -> chronometer.atDuration.toMillis().coerceAtLeast(0L)
        is Chronometer.Running -> {
            val zeroMs = chronometer.eventTime.asElapsedRealtime(content.timeSource)
            val nowMs = content.timeSource.elapsedRealtime()
            if (chronometer.isCountdown) {
                (zeroMs - nowMs).coerceAtLeast(0L)
            } else {
                (nowMs - zeroMs).coerceAtLeast(0L)
            }
        }
    }
}

@Composable
private fun AospKeyguardDeltaText(
    content: OngoingActivityChipModel.Content.ShortTimeDelta,
    color: Color,
    modifier: Modifier,
) {
    var deltaMs by remember(content.time) {
        mutableLongStateOf(System.currentTimeMillis() - content.time)
    }
    LaunchedEffect(content.time) {
        while (true) {
            deltaMs = System.currentTimeMillis() - content.time
            delay(30_000)
        }
    }
    MarqueeText(aospShortDeltaText(deltaMs), color, modifier)
}

@Composable
private fun aospShortDeltaText(deltaMs: Long): String {
    val mins = abs(deltaMs) / 60_000L
    return when {
        mins < 1L -> stringResource(R.string.ax_dynamic_bar_just_now)
        mins < 60L -> stringResource(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        mins < 1440L -> stringResource(R.string.ax_dynamic_bar_hours_ago, (mins / 60L).toInt())
        else -> stringResource(R.string.ax_dynamic_bar_days_ago, (mins / 1440L).toInt())
    }
}

@Composable
private fun secondaryTextFor(event: IslandEvent): String? = when (event) {
    is IslandEvent.Media -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.AudioRecording -> event.appName.takeIf { it.isNotBlank() }
    is IslandEvent.Timer,
    is IslandEvent.Stopwatch,
    is IslandEvent.Hotspot,
    is IslandEvent.Vpn,
    is IslandEvent.AppSwitch -> null
    is IslandEvent.Charging -> event.timeRemaining
    is IslandEvent.Bluetooth -> if (event.batteryLevel >= 0) "${event.batteryLevel}%" else null
    is IslandEvent.Alarm -> {
        if (event.triggerTimeMs > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = event.triggerTimeMs }
            val triggerTime = "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            triggerTime.takeIfDistinctFrom(event.label)
        } else null
    }
    is IslandEvent.Call -> event.callerName
    is IslandEvent.BiometricUnlock -> event.sourceName
    is IslandEvent.Notification ->
        event.text
            ?.takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.title, event.appName)
            ?.take(30)
    is IslandEvent.PromotedOngoing ->
        event.text
            .takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.shortText, event.title, event.appName)
            ?.take(20)
    is IslandEvent.Sports -> "${event.team1Name} ${stringResource(R.string.ax_dynamic_bar_sports_vs)} ${event.team2Name}"
    is IslandEvent.KeyguardIndication -> when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> stringResource(R.string.ax_dynamic_bar_biometric)
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> stringResource(R.string.ax_dynamic_bar_trust)
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> stringResource(R.string.ax_dynamic_bar_alignment)
        else -> null
    }
    else -> null
}

private fun String.takeIfDistinctFrom(vararg others: String?): String? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.takeIf { candidate ->
        others.none { other -> candidate.equals(other?.trim(), ignoreCase = true) }
    }
}

@Composable
private fun CallTimerText(event: IslandEvent.Call, modifier: Modifier, overrideColor: Color? = null) {
    val isActive = event.callType == "Phone:active"
    if (isActive) {
        var elapsedMs by remember(event.callStartTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.callStartTimeMs) {
            while (true) {
                delay(1000)
                elapsedMs = (System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L)
            }
        }
        val color = overrideColor ?: GreenAccent
        Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
    } else {
        MarqueeText(stringResource(R.string.ax_dynamic_bar_incoming_call), overrideColor ?: BlueAccent, modifier)
    }
}

@Composable
private fun SportsChipTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(ChipIconSize),
            contentDescription = name,
            modifier = Modifier.size(ChipIconSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(ChipIconSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

private enum class ActionIcon { PLAY, PAUSE, STOP, SKIP_PREV, SKIP_NEXT }

private data class ChipAction(
    val icon: ActionIcon,
    val perform: (AxDynamicBarChipViewModel, IslandEvent, Context) -> Unit,
)

private fun actionsFor(event: IslandEvent, context: Context): List<ChipAction> = when (event) {
    is IslandEvent.Media -> listOf(
        ChipAction(ActionIcon.SKIP_PREV) { vm, _, _ -> vm.skipPrev() },
        ChipAction(if (event.isPlaying) ActionIcon.PAUSE else ActionIcon.PLAY) { vm, _, _ ->
            vm.togglePlayPause()
        },
        ChipAction(ActionIcon.SKIP_NEXT) { vm, _, _ -> vm.skipNext() },
    )
    is IslandEvent.AudioRecording -> {
        val classified = event.actions.map { it to it.action.classify(context, it.action.actionIntent?.creatorPackage ?: context.packageName) }
        val pauseResume = classified.firstOrNull { (_, k) -> k == NotificationActionType.PAUSE || k == NotificationActionType.RESUME }?.first
        val stop = classified.firstOrNull { (_, k) -> k == NotificationActionType.STOP || k == NotificationActionType.DELETE }?.first
        listOfNotNull(
            pauseResume?.let { action ->
                ChipAction(
                    if (event.state == RecordingState.RECORDING) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
            stop?.let { action ->
                ChipAction(ActionIcon.STOP) { _, _, ctx ->
                    action.action.actionIntent?.sendWithBal(ctx)
                }
            },
        )
    }
    is IslandEvent.Timer -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isPaused) ActionIcon.PLAY else ActionIcon.PAUSE,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Stopwatch -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isRunning) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Torch -> listOf(
        ChipAction(ActionIcon.STOP) { vm, event, _ -> vm.dismissEvent(event) },
    )
    else -> emptyList()
}

@Composable
private fun ActionButton(
    icon: ActionIcon,
    color: Color,
    bgColor: Color,
    onClick: () -> Unit,
    size: Dp = ActionSize,
    iconSize: Dp = ActionIconSize,
) {
    val imageVector = when (icon) {
        ActionIcon.PLAY -> Icons.Filled.PlayArrow
        ActionIcon.PAUSE -> Icons.Filled.Pause
        ActionIcon.STOP -> Icons.Filled.Stop
        ActionIcon.SKIP_PREV -> Icons.Filled.SkipPrevious
        ActionIcon.SKIP_NEXT -> Icons.Filled.SkipNext
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(
                imageVector,
                contentDescription = icon.name,
                tint = color,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun MarqueeText(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        style = PillPrimary,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.widthIn(max = 120.dp).basicMarquee(iterations = 1),
    )
}

@Composable
private fun ElapsedTimeText(
    startTimeMs: Long,
    color: Color,
    modifier: Modifier,
    pausedDurationMs: Long = 0L,
) {
    var elapsedMs by remember(startTimeMs, pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - startTimeMs - pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(startTimeMs, pausedDurationMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs - pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }
    Text(formatCountdownLong(elapsedMs), color = color, style = PillMono, modifier = modifier)
}

@Composable
private fun CountdownText(event: IslandEvent.Timer, color: Color, modifier: Modifier) {
    if (event.isPaused) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var remainingMs by remember(event.endTimeMs) {
            mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
        Text(formatCountdownLong(remainingMs), color = color, style = PillMono, modifier = modifier)
    }
}

@Composable
private fun StopwatchTimeText(event: IslandEvent.Stopwatch, color: Color, modifier: Modifier) {
    if (!event.isRunning) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var elapsedMs by remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        Text(formatStopwatch(elapsedMs), color = color, style = PillMono, modifier = modifier)
    }
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest {
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 400
                                1.06f at 120 using FastOutSlowInEasing
                                0.97f at 260
                                1f at 400
                            },
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 400
                                0.95f at 120 using FastOutSlowInEasing
                                1.03f at 260
                                1f at 400
                            },
                        )
                    }
                }
            }
    }

    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}
