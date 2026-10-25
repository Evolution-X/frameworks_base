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

import android.view.DisplayCutout
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.chipText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Single centered status bar capsule styled like a compact dynamic island. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChipBoundsChanged: (Rect) -> Unit = {},
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val chipShape = RoundedCornerShape(50)
    val colors = viewModel.colors
    val (widthScale, heightScale) = rememberDynamicIslandSizeScale()
    val chipContentColor =
        colors.chipContent(
            isSelected = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isSelected = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val view = LocalView.current
    val boundsModifier =
        Modifier.onGloballyPositioned { coordinates ->
            onChipBoundsChanged(coordinates.boundsInScreen(view))
        }
    val hapticOnTap: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onTap()
    }
    val mediaOpenApp: (() -> Unit)? =
        (viewModel.popupContent as? PopupContentModel.Media)
            ?.takeIf { it.model.isPlaying }
            ?.model
            ?.openApp
    val hapticOnLongPress: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mediaOpenApp?.invoke()
    }
    if (viewModel.popupContent.isUtilityStatusContent() && viewModel.icons.isNotEmpty()) {
        UtilityStatusIslandChip(
            viewModel = viewModel,
            onTap = hapticOnTap,
            cutoutSpec = cutoutSpec,
            widthScale = widthScale,
            heightScale = heightScale,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    val compactWidth = compactIslandWidthFor(viewModel.popupContent)?.times(widthScale)
    val hasInlineTimer = viewModel.popupContent is PopupContentModel.Stopwatch
    val trailingDecorationWidth =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    14.dp
                } else if (pageCount > 1) {
                    11.dp
                } else {
                    0.dp
                }
            is PopupContentModel.ScreenRecord -> 11.dp
            else -> {
                if (pageCount > 1) 11.dp else 0.dp
            }
        }
    val leadingDecorationWidth =
        when {
            viewModel.icons.isEmpty() -> 0.dp
            else -> 18.dp + (8.dp * (viewModel.icons.size - 1))
        }
    val maxTextWidth =
        ((CompactIslandMaxWidth * widthScale) - 24.dp - leadingDecorationWidth - trailingDecorationWidth)
            .coerceAtLeast(56.dp)

    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)
    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val startGap = if (isStartAnchored) cutoutSpec.embeddedGapWidth else 0.dp

    Row(
        modifier =
            modifier
                .then(boundsModifier)
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .widthIn(
                    min = if (isStartAnchored) startGap else (compactWidth ?: 0.dp),
                    max = (compactWidth ?: (CompactIslandMaxWidth * widthScale)) + startGap,
                )
                .graphicsLayer {
                    scaleX = collapseState.scale
                    if (isStartAnchored) transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(chipShape)
                .background(Color.Black)
                .border(width = 1.dp, color = chipOutline, shape = chipShape)
                .combinedClickable(
                    onClick = hapticOnTap,
                    onLongClick = mediaOpenApp?.let { { hapticOnLongPress() } },
                )
                .padding(
                    start = if (isStartAnchored) startGap else 10.dp * widthScale,
                    end = if (isStartAnchored) 8.dp * widthScale else 12.dp * widthScale,
                    top = 7.dp * heightScale,
                    bottom = 7.dp * heightScale,
                )
                .graphicsLayer { alpha = collapseState.contentAlpha },
        horizontalArrangement =
            if (isMediaChip && !isStartAnchored) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.spacedBy(8.dp)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.icons.forEachIndexed { index, chipIcon ->
            val isArtworkLike =
                index == 0 &&
                    (viewModel.popupContent is PopupContentModel.Media ||
                        viewModel.popupContent is PopupContentModel.LiveScore)
            Icon(
                icon = chipIcon.icon,
                modifier = Modifier
                    .size(if (isArtworkLike) 18.dp else 16.dp)
                    .then(
                        if (isArtworkLike) {
                            Modifier.clip(CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                tint = if (isArtworkLike) Color.Unspecified else chipContentColor,
            )
        }

        viewModel.chipText
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.widthIn(
                            max = if (isStartAnchored) minOf(maxTextWidth, 88.dp) else maxTextWidth
                        ),
                )
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media -> {
                val artworkDrawable =
                    remember(popupContent.model.artworkIcon) {
                        (popupContent.model.artworkIcon as? IconModel.Loaded)?.drawable
                    }
                if (popupContent.model.isPlaying) {
                    AudioReactiveBars(
                        isPlaying = true,
                        color = chipContentColor,
                        artworkDrawable = artworkDrawable,
                    )
                } else if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                }
            is PopupContentModel.Stopwatch ->
                StatusContent(
                    text =
                        popupContent.model.elapsedTimeText
                            ?: rememberElapsedDurationText(
                                popupContent.model.baseElapsedRealtimeMs
                            ),
                    color = chipContentColor,
                    showSwipeHint = pageCount > 1,
                )
            else -> {
                if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: QuickActionChipModel.PopupChip,
    onTap: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    widthScale: Float = 1f,
    heightScale: Float = 1f,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)
    val isStartAnchored = cutoutSpec.anchor == DynamicIslandAnchor.START
    val rightSegmentWidth =
        (when (viewModel.popupContent) {
            is PopupContentModel.Flashlight -> 52.dp
            is PopupContentModel.Alarm -> 72.dp
            else -> 80.dp
        }) * widthScale
    val connectedIslandWidth =
        ((CompactUtilityConnectedIslandChromeWidth * widthScale) +
                cutoutSpec.embeddedGapWidth +
                rightSegmentWidth)
            .coerceIn(
                CompactUtilityConnectedIslandMinWidth * widthScale,
                CompactUtilityConnectedIslandMaxWidth * widthScale,
            )
    val utilityText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            is PopupContentModel.Stopwatch ->
                popupContent.model.elapsedTimeText
                    ?: rememberElapsedDurationText(popupContent.model.baseElapsedRealtimeMs)
            is PopupContentModel.Alarm -> viewModel.chipText.orEmpty()
            is PopupContentModel.Flashlight -> viewModel.chipText.orEmpty()
            else -> ""
        }

    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = collapseState.scale
                    if (isStartAnchored) transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .then(
                    if (isStartAnchored) Modifier else Modifier.width(connectedIslandWidth)
                )
                .clip(RoundedCornerShape(50))
                .background(Color.Black)
                .border(width = 1.dp, color = chipOutline, shape = RoundedCornerShape(50))
                .clickable(onClick = onTap)
                .graphicsLayer { alpha = collapseState.contentAlpha },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStartAnchored) {
            Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
            Icon(
                icon = viewModel.icons.first().icon,
                modifier = Modifier.size(16.dp),
                tint = chipContentColor,
            )
            Spacer(modifier = Modifier.width(6.dp * widthScale))
            Text(
                text = utilityText,
                style = MaterialTheme.typography.labelLarge,
                color = chipContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier =
                    Modifier.widthIn(min = 36.dp * widthScale, max = rightSegmentWidth)
                        .padding(vertical = 7.dp * heightScale),
            )
            Spacer(modifier = Modifier.width(10.dp * widthScale))
        } else {
            Spacer(modifier = Modifier.width(10.dp * widthScale))
            Icon(
                icon = viewModel.icons.first().icon,
                modifier = Modifier.size(16.dp),
                tint = chipContentColor,
            )
            Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
            Box(
                modifier =
                    Modifier.width(rightSegmentWidth)
                        .padding(
                            start = 6.dp * widthScale,
                            top = 7.dp * heightScale,
                            bottom = 7.dp * heightScale,
                            end = 6.dp * widthScale,
                        ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = utilityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.width(10.dp * widthScale))
        }
    }
}

@Composable
private fun StatusContent(
    text: String,
    color: Color,
    showSwipeHint: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
        )
        if (showSwipeHint) {
            SwipeHint(color = color.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun SwipeHint(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier.size(width = 3.dp, height = 3.dp)
                    .background(color = color, shape = CircleShape)
            )
        }
    }
}

private val CompactIslandMaxWidth = 192.dp
private val CompactMediaIslandWidth = 108.dp
private val CompactTimerIslandWidth = 116.dp
private val CompactRecordingIslandWidth = 88.dp
private val CompactAlarmIslandWidth = 92.dp
private val CompactUtilityIslandWidth = 74.dp
private val CompactUtilityConnectedIslandChromeWidth = 42.dp
private val CompactUtilityConnectedIslandMinWidth = 132.dp
private val CompactUtilityConnectedIslandMaxWidth = 188.dp
private val DynamicIslandEmbeddedGapFallbackWidth = 38.dp
private val DynamicIslandEmbeddedGapMinWidth = 34.dp
private val DynamicIslandEmbeddedGapMaxWidth = 88.dp
private val DynamicIslandEmbeddedGapSidePadding = 10.dp

enum class DynamicIslandAnchor {
    CENTER,
    START,
}

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
    val anchor: DynamicIslandAnchor = DynamicIslandAnchor.CENTER,
)

private val DynamicIslandHoleSidePadding = 4.dp
private val DynamicIslandScreenEdgeMargin = 4.dp
private const val DynamicIslandStartAnchorMaxFraction = 0.35f
private const val DynamicIslandCenterMinFraction = 0.35f
private const val DynamicIslandCenterMaxFraction = 0.65f

fun resolveDynamicIslandAnchor(
    cutoutCenterX: Float?,
    windowWidthPx: Int,
    isRtl: Boolean,
): DynamicIslandAnchor {
    if (cutoutCenterX == null || windowWidthPx <= 0 || isRtl) return DynamicIslandAnchor.CENTER
    return if (cutoutCenterX / windowWidthPx < DynamicIslandStartAnchorMaxFraction) {
        DynamicIslandAnchor.START
    } else {
        DynamicIslandAnchor.CENTER
    }
}

private fun embeddedGapFor(cutoutWidthPx: Int, density: Float): Dp =
    ((cutoutWidthPx / density).dp + (DynamicIslandEmbeddedGapSidePadding * 2))
        .coerceIn(DynamicIslandEmbeddedGapMinWidth, DynamicIslandEmbeddedGapMaxWidth)

fun dynamicIslandStartEdgePx(cutout: android.graphics.Rect, density: Float): Int =
    (cutout.left - DynamicIslandHoleSidePadding.value * density)
        .roundToInt()
        .coerceAtLeast((DynamicIslandScreenEdgeMargin.value * density).roundToInt())

private fun startAnchoredGapFor(cutout: android.graphics.Rect, density: Float): Dp {
    val edgePx = dynamicIslandStartEdgePx(cutout, density)
    val gapPx = cutout.right + DynamicIslandHoleSidePadding.value * density - edgePx
    return (gapPx / density).dp
}

private data class DynamicIslandCutoutSnapshot(
    val topCutout: android.graphics.Rect?,
    val windowWidthPx: Int,
    val isRtl: Boolean,
)

private fun readDynamicIslandCutoutSnapshot(view: View): DynamicIslandCutoutSnapshot {
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val topCutout = displayCutout?.dynamicIslandHoleRect()
    val windowWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }
    return DynamicIslandCutoutSnapshot(
        topCutout = topCutout,
        windowWidthPx = windowWidthPx,
        isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
    )
}

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    var snapshot by remember { mutableStateOf(readDynamicIslandCutoutSnapshot(view)) }
    DisposableEffect(view) {
        val listener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val updated = readDynamicIslandCutoutSnapshot(view)
                if (updated != snapshot) snapshot = updated
            }
        view.rootView.addOnLayoutChangeListener(listener)
        snapshot = readDynamicIslandCutoutSnapshot(view)
        onDispose { view.rootView.removeOnLayoutChangeListener(listener) }
    }

    val topCutout = snapshot.topCutout
    val rootWidthPx = snapshot.windowWidthPx

    return with(density) {
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
            )
        } else {
            val anchor =
                resolveDynamicIslandAnchor(topCutout.exactCenterX(), rootWidthPx, snapshot.isRtl)
            val fraction = topCutout.exactCenterX() / rootWidthPx
            val horizontalOffsetDp =
                if (
                    anchor == DynamicIslandAnchor.CENTER &&
                        fraction in DynamicIslandCenterMinFraction..DynamicIslandCenterMaxFraction
                ) {
                    (topCutout.exactCenterX() - (rootWidthPx / 2f)).toDp()
                } else {
                    0.dp
                }
            DynamicIslandCutoutSpec(
                embeddedGapWidth =
                    if (anchor == DynamicIslandAnchor.START) {
                        startAnchoredGapFor(topCutout, this.density)
                    } else {
                        embeddedGapFor(topCutout.width(), this.density)
                    },
                horizontalOffset = horizontalOffsetDp,
                anchor = anchor,
            )
        }
    }
}

private fun DisplayCutout.topBoundingRectOrNull() =
    getBoundingRectTop().takeUnless { it.isEmpty }

fun DisplayCutout.dynamicIslandHoleRect(): android.graphics.Rect? {
    val boundingRect = topBoundingRectOrNull() ?: return null
    val path = cutoutPath ?: return android.graphics.Rect(boundingRect)
    val pathBounds = android.graphics.RectF()
    path.computeBounds(pathBounds, true)
    if (pathBounds.isEmpty) return android.graphics.Rect(boundingRect)
    val hole = android.graphics.Rect()
    pathBounds.roundOut(hole)
    return if (android.graphics.Rect.intersects(hole, boundingRect)) {
        hole
    } else {
        android.graphics.Rect(boundingRect)
    }
}

private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Flashlight
}

private fun compactIslandWidthFor(content: PopupContentModel): Dp? {
    return when (content) {
        is PopupContentModel.Media -> CompactMediaIslandWidth
        is PopupContentModel.ScreenRecord ->
            when (content.model) {
                is ScreenRecordPopupModel.Starting -> CompactTimerIslandWidth
                is ScreenRecordPopupModel.Recording -> CompactRecordingIslandWidth
            }
        is PopupContentModel.Stopwatch -> CompactTimerIslandWidth
        is PopupContentModel.Alarm -> CompactAlarmIslandWidth
        is PopupContentModel.Flashlight -> CompactUtilityIslandWidth
        else -> null
    }
}

@Composable
private fun rememberDynamicIslandSizeScale(): Pair<Float, Float> {
    val context = LocalContext.current
    val widthScale by
        remember {
            DynamicIslandFeatureSettings.observeDynamicIslandScale(
                context,
                DynamicIslandFeatureSettings.WIDTH_SCALE,
            )
        }.collectAsState(initial = 1f)
    val heightScale by
        remember {
            DynamicIslandFeatureSettings.observeDynamicIslandScale(
                context,
                DynamicIslandFeatureSettings.HEIGHT_SCALE,
            )
        }.collectAsState(initial = 1f)
    return widthScale to heightScale
}

private data class DynamicIslandCollapseState(val scale: Float, val contentAlpha: Float)

@Composable
private fun rememberDynamicIslandCollapseState(isOpen: Boolean): DynamicIslandCollapseState {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.0005f) }
    val currentIsOpen by rememberUpdatedState(isOpen)

    LaunchedEffect(Unit) {
        snapshotFlow { currentIsOpen }
            .drop(1)
            .collectLatest { open ->
                if (open) {
                    scaleX.animateTo(
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = 0.9f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    )
                } else {
                    scaleX.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            keyframes {
                               durationMillis = 380
                                0f at 0
                                1.08f at 240 using FastOutSlowInEasing
                                0.96f at 320
                                1f at 380
                            },
                    )
                }
            }
    }
    val fadeThreshold = 0.7f
    val alpha = (scaleX.value / fadeThreshold).coerceIn(0f, 1f)
    return DynamicIslandCollapseState(scale = scaleX.value, contentAlpha = alpha)
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
