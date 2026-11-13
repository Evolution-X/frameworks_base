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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal object CosIsland {
    val MinHeight = 30.dp
    val MaxWidth = 202.dp
    val MinWidth = 100.dp
    val Padding = 5.dp
    val IconSize = 20.dp
    val TextSize = 12.sp
    val TextColor = Color(0xE6FFFFFF)

    val ExtraWidth = 16.dp

    val SwitchWidth = 80.dp

    val StrokeWidth = 1.3.dp
    val StrokeColor = Color(0x40959595)
    val MoonWidth = 4.dp
    val MoonGap = 2.dp
    val MoonColor = Color(0xE64A4A4A)
    val ContentBlur = 9.dp

    val Move = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
    val SwitchGrow = CubicBezierEasing(0.17f, 0.23f, 0.83f, 0.95f)
    val SwitchSettle = CubicBezierEasing(0.17f, 0.56f, 0.1f, 1f)
    private val PressDown = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val PressUp = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Island height around a camera [hole], scaled by the user's height [scale] but never
     *  shorter than the hole itself. */
    fun height(hole: Dp, scale: Float = 1f): Dp =
        maxOf(hole, maxOf(MinHeight, hole + 4.dp) * scale)

    internal suspend fun press(scale: Animatable<Float, *>, down: Boolean) {
        if (down) scale.animateTo(0.98f, tween(200, easing = PressDown))
        else scale.animateTo(1f, tween(340, easing = PressUp))
    }
}

@Composable
internal fun CosIslandShell(
    key: Any,
    visible: Boolean,
    hole: Dp,
    moons: Boolean,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val holePx = with(density) { hole.toPx() }
    val (_, heightScale) = rememberDynamicIslandSizeScale()
    val heightPx = with(density) { CosIsland.height(hole, heightScale).toPx() }
    val currentHeightPx by rememberUpdatedState(heightPx)
    val extraPx = with(density) { CosIsland.ExtraWidth.toPx() }
    val switchPx = with(density) { CosIsland.SwitchWidth.toPx() }
    val maxWidthPx = with(density) { CosIsland.MaxWidth.roundToPx() }
    val blurPx = with(density) { CosIsland.ContentBlur.toPx() }

    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val blur = remember { Animatable(blurPx) }
    val press = remember { Animatable(1f) }
    val moonAlpha = remember { Animatable(0f) }

    var natural by remember { mutableStateOf(IntSize.Zero) }
    var shownKey by remember { mutableStateOf(key) }
    var busy by remember { mutableStateOf(true) }
    var shownContent by remember { mutableStateOf(content) }
    if (key == shownKey) {
        SideEffect { shownContent = content }
    }
    val body = if (key == shownKey) content else shownContent
    val currentKey by rememberUpdatedState(key)
    val currentVisible by rememberUpdatedState(visible)
    val currentOnGone by rememberUpdatedState(onGone)

    LaunchedEffect(moons) {
        moonAlpha.animateTo(if (moons) 1f else 0f, tween(300, easing = CosIsland.Move))
    }

    suspend fun nextNatural(): IntSize {
        withFrameNanos {}
        withFrameNanos {}
        return snapshotFlow { natural }.first { it.width > 0 }
    }

    suspend fun appear() = coroutineScope {
        val target = nextNatural().width.toFloat()
        launch { width.animateTo(holePx, tween(50, easing = CosIsland.Move)) }
        height.animateTo(holePx, tween(50, easing = CosIsland.Move))
        launch { width.animateTo(target + extraPx, tween(300, easing = CosIsland.Move)) }
        launch { height.animateTo(currentHeightPx, tween(300, easing = CosIsland.Move)) }
        launch { alpha.animateTo(1f, tween(250, delayMillis = 50, easing = CosIsland.Move)) }
        launch { blur.animateTo(0f, tween(250, delayMillis = 50, easing = CosIsland.Move)) }
        scale.animateTo(1.1f, tween(300, easing = CosIsland.Move))
        launch { width.animateTo(target, tween(500, easing = CosIsland.Move)) }
        scale.animateTo(1f, tween(500, easing = CosIsland.Move))
    }

    suspend fun disappear() = coroutineScope {
        val linear300 = tween<Float>(300, easing = LinearEasing)
        val linear200 = tween<Float>(200, easing = LinearEasing)
        launch { width.animateTo(holePx, linear300) }
        launch { height.animateTo(holePx, linear300) }
        launch { alpha.animateTo(0f, linear200) }
        launch { blur.animateTo(blurPx, linear200) }
        scale.animateTo(0.1f, linear300)
        launch { width.animateTo(0f, tween(50, easing = LinearEasing)) }
        height.animateTo(0f, tween(50, easing = LinearEasing))
    }

    suspend fun switchTo(newKey: Any) = coroutineScope {
        val old = natural.width.toFloat().coerceAtLeast(1f)
        val close = tween<Float>(200, easing = LinearEasing)
        launch { width.animateTo(switchPx, close) }
        launch { height.animateTo(heightPx, close) }
        launch { blur.animateTo(blurPx, close) }
        launch { scale.animateTo((switchPx / old).coerceAtMost(1f), close) }
        alpha.animateTo(0f, close)
        shownKey = newKey
        val target = nextNatural().width.toFloat()
        scale.snapTo((switchPx / target).coerceAtMost(1f))
        launch { alpha.animateTo(1f, tween(200, easing = CosIsland.SwitchGrow)) }
        launch { blur.animateTo(0f, tween(200, easing = CosIsland.SwitchGrow)) }
        launch { scale.animateTo(1.1f, tween(200, easing = CosIsland.SwitchGrow)) }
        width.animateTo(target + extraPx, tween(200, easing = CosIsland.SwitchGrow))
        launch { scale.animateTo(1f, tween(500, easing = CosIsland.SwitchSettle)) }
        width.animateTo(target, tween(500, easing = CosIsland.SwitchSettle))
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentVisible to currentKey }
            .collectLatest { (shown, newKey) ->
                busy = true
                when {
                    !shown -> {
                        disappear()
                        currentOnGone()
                    }
                    newKey != shownKey && alpha.value > 0.5f -> switchTo(newKey)
                    else -> {
                        shownKey = newKey
                        if (alpha.value < 1f || width.value < 1f) appear()
                    }
                }
                busy = false
            }
    }

    // Follow the height scale live while the island is up.
    LaunchedEffect(Unit) {
        snapshotFlow { currentHeightPx to busy }
            .collectLatest { (h, isBusy) ->
                if (!isBusy && currentVisible && height.value > 0f) {
                    height.animateTo(h, tween(300, easing = CosIsland.Move))
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { natural.width to busy }
            .collectLatest { (w, isBusy) ->
                if (!isBusy && w > 0 && currentVisible) {
                    width.animateTo(w.toFloat(), tween(300, easing = CosIsland.Move))
                }
            }
    }

    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(50)
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = press.value
                    scaleY = press.value
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        scope.launch { CosIsland.press(press, true) }
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        scope.launch { CosIsland.press(press, false) }
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (moonAlpha.value > 0f && size.width > 0f) {
                        drawMoons(this, moonAlpha.value)
                    }
                }
    ) {
        Layout(
            content = {
                Box(
                    Modifier.wrapContentSize()
                        // The content lambda already shows the new chip while the old one
                        // closes, so its size has to be taken whatever the shown key is.
                        .onSizeChanged { natural = it }
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            this.alpha = alpha.value
                            val radius = blur.value
                            renderEffect =
                                if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal)
                                else null
                        }
                ) {
                    body()
                }
            },
            modifier =
                Modifier.graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
                    .drawWithContent {
                        drawRoundRect(
                            Color.Black,
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                        drawContent()
                        val stroke = CosIsland.StrokeWidth.toPx()
                        if (size.minDimension > stroke * 2) {
                            drawRoundRect(
                                CosIsland.StrokeColor,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius((size.height - stroke) / 2f),
                                style = Stroke(stroke),
                            )
                        }
                    },
        ) { measurables, _ ->
            val placeable =
                measurables.first().measure(Constraints(maxWidth = maxWidthPx))
            val w = width.value.roundToInt().coerceAtLeast(0)
            val h = height.value.roundToInt().coerceAtLeast(0)
            layout(w, h) {
                placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
            }
        }
    }
}

private fun drawMoons(scope: androidx.compose.ui.graphics.drawscope.DrawScope, alpha: Float) =
    with(scope) {
        val r = size.height / 2f
        val gap = CosIsland.MoonGap.toPx()
        val thick = CosIsland.MoonWidth.toPx()
        for (side in listOf(-1f, 1f)) {
            val edge = if (side < 0) r else size.width - r
            val inner = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(edge + side * gap, r), r))
            }
            val outer = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(edge + side * (gap + thick), r), r))
            }
            val moon = Path().apply { op(outer, inner, PathOperation.Difference) }
            drawPath(moon, CosIsland.MoonColor, alpha = alpha)
        }
    }
