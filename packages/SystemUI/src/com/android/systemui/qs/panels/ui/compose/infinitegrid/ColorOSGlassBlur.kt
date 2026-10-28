/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.app.ActivityManager
import android.os.SystemProperties
import android.view.CrossWindowBlurListeners
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min

/**
 * Blur regions for the glass of the expanded ColorOS panel. SurfaceFlinger blurs what is behind the
 * shade inside each region and draws the ColorOS glass material over it, so the wallpaper shows
 * through the pills, the tiles that are off, the sliders and the media card the way it does on
 * ColorOS.
 *
 * The regions are not part of the Compose layers. The panel provides [LocalColorOSGlassBlur] only
 * where the glass can be drawn, and everything that draws a flat fill as the glass keeps drawing it
 * while the local is null.
 */
internal class ColorOSGlassBlur(
    /**
     * The alpha of the panel. The regions do not follow the alpha of the Compose layers, so they
     * are hidden with this, and 0 hides them.
     */
    val alpha: () -> Float
)

/** The glass of the expanded ColorOS panel, null where the regions cannot be used. */
internal val LocalColorOSGlassBlur = compositionLocalOf<ColorOSGlassBlur?> { null }

/**
 * The blur radius. It is 62px on a 440dpi device, the one the glass was matched against ColorOS
 * on, and scales with the density like the tiles it blurs behind do.
 */
private val ColorOSGlassBlurRadius = 22.5.dp

/**
 * Overrides the blur radius, in pixels, to match it by eye on a device:
 * `setprop debug.evolution.lg.cos_blur 80`. Unset or 0 keeps [ColorOSGlassBlurRadius]. It is
 * followed live, the regions take it the next time they are drawn.
 */
private const val ColorOSGlassBlurRadiusProperty = "debug.evolution.lg.cos_blur"

/** How close to the edge of the window counts as touching it, in pixels. */
private const val ColorOSGlassEdgeSlop = 0.5f

/** The radius override in pixels, 0 for none. Read once and again when a system property changes. */
private object ColorOSGlassBlurTune {
    @Volatile
    var overridePx = read()
        private set

    init {
        SystemProperties.addChangeCallback { overridePx = read() }
    }

    private fun read(): Int = SystemProperties.getInt(ColorOSGlassBlurRadiusProperty, 0)
}

/** Below this alpha a region is not worth keeping in SurfaceFlinger. */
private const val ColorOSGlassMinAlpha = 0.01f

/**
 * Returns the glass of the panel while SurfaceFlinger can blur behind the shade, and null when it
 * cannot: no support, a low end device, the window blur turned off by the user or by battery
 * saver, or `persist.sysui.disableBlur`. The same conditions as the blur of the shade itself.
 */
@Composable
internal fun rememberColorOSGlassBlur(alpha: () -> Float): ColorOSGlassBlur? {
    val enabled = rememberCrossWindowBlurEnabled()
    val currentAlpha by rememberUpdatedState(alpha)
    val blur = remember { ColorOSGlassBlur { currentAlpha() } }
    return blur.takeIf { enabled }
}

@Composable
private fun rememberCrossWindowBlurEnabled(): Boolean {
    val supported = remember {
        CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED &&
            ActivityManager.isHighEndGfx() &&
            !SystemProperties.getBoolean("persist.sysui.disableBlur", false)
    }
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    DisposableEffect(supported, context) {
        if (!supported) {
            enabled = false
            return@DisposableEffect onDispose {}
        }
        val listeners = CrossWindowBlurListeners.getInstance()
        val consumer = Consumer<Boolean> { enabled = it }
        listeners.addListener(context.mainExecutor, consumer)
        onDispose { listeners.removeListener(consumer) }
    }
    return supported && enabled
}

@Composable
@ReadOnlyComposable
private fun colorOSDarkPanel(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** The glass of the pills, of the round tiles that are off and of the sliders. */
@Composable
@ReadOnlyComposable
internal fun colorOSGlassTileMaterial(): Int =
    if (colorOSDarkPanel()) BackgroundBlurDrawable.MATERIAL_TILE_DARK
    else BackgroundBlurDrawable.MATERIAL_TILE_LIGHT

/**
 * The glass under the bubble of a round tile that is on. The bubble is a touch short of opaque, so
 * the lit rim and the glow of this material still show around it.
 */
@Composable
@ReadOnlyComposable
internal fun colorOSGlassActiveTileMaterial(): Int =
    if (colorOSDarkPanel()) BackgroundBlurDrawable.MATERIAL_TILE_ACTIVE_DARK
    else BackgroundBlurDrawable.MATERIAL_TILE_ACTIVE_LIGHT

/** Keeps the blur drawable of one [colorOSGlassBlur], which is created when it is first drawn. */
private class ColorOSGlassRegion(private val view: View) {
    var drawable: BackgroundBlurDrawable? = null

    fun hide() {
        val hidden = drawable?.setVisible(false, false) ?: return
        // Hiding a region does not draw anything, and SurfaceFlinger only hears of it with the
        // next frame of the window. Without one, which is what happens when the panel is closing
        // for an app, the glass stays on screen over the app.
        if (hidden) view.postInvalidateOnAnimation()
    }
}

/**
 * Draws the element on a blur region of [material], one of the `MATERIAL_*` values of
 * [BackgroundBlurDrawable], in the shape of [shape]. Does nothing where [LocalColorOSGlassBlur] is
 * null.
 *
 * The region is the part of the element that is visible in the window, so what a scroller or any
 * other parent clips does not leave glass behind. Where that part reaches the edge of the window,
 * the region goes on to the real edge of the element instead: what is past the window is not
 * seen, and the rim of the glass does not run along the edge of the window like it was a cut.
 * It is hidden while the panel is, and when the element leaves the composition.
 */
@Composable
internal fun Modifier.colorOSGlassBlur(shape: Shape, material: Int): Modifier {
    val blur = LocalColorOSGlassBlur.current ?: return this
    val view = LocalView.current
    val region = remember(view) { ColorOSGlassRegion(view) }
    val defaultRadius = with(LocalDensity.current) { ColorOSGlassBlurRadius.roundToPx() }
    // The part of the element that is in the window, in its own coordinates.
    var visible by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(region) { onDispose { region.hide() } }
    // The panel can be hidden without drawing the element again, and the region would stay.
    LaunchedEffect(blur, region) {
        snapshotFlow { blur.alpha().coerceIn(0f, 1f) }
            .collect { alpha ->
                val drawable = region.drawable ?: return@collect
                if (alpha < ColorOSGlassMinAlpha) {
                    region.hide()
                } else {
                    drawable.alpha = (alpha * 255).toInt()
                }
            }
    }

    return this.onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            val clipped = coordinates.boundsInWindow()
            if (clipped.width <= 0f || clipped.height <= 0f) {
                // Nothing of it is in the window.
                visible = Rect.Zero
                return@onGloballyPositioned
            }
            val origin = coordinates.positionInWindow()
            val windowWidth = view.rootView.width.toFloat()
            val windowHeight = view.rootView.height.toFloat()
            val left =
                if (clipped.left <= ColorOSGlassEdgeSlop) min(clipped.left, origin.x)
                else clipped.left
            val top =
                if (clipped.top <= ColorOSGlassEdgeSlop) min(clipped.top, origin.y)
                else clipped.top
            val right =
                if (clipped.right >= windowWidth - ColorOSGlassEdgeSlop) {
                    max(clipped.right, origin.x + coordinates.size.width)
                } else {
                    clipped.right
                }
            val bottom =
                if (clipped.bottom >= windowHeight - ColorOSGlassEdgeSlop) {
                    max(clipped.bottom, origin.y + coordinates.size.height)
                } else {
                    clipped.bottom
                }
            visible =
                Rect(
                        coordinates.windowToLocal(Offset(left, top)),
                        coordinates.windowToLocal(Offset(right, bottom)),
                    )
                    .intersect(coordinates.size.toSize().toRect())
        }
        .drawBehind {
            val alpha = blur.alpha().coerceIn(0f, 1f)
            val shown = visible ?: size.toRect()
            if (alpha < ColorOSGlassMinAlpha || shown.width < 1f || shown.height < 1f) {
                region.hide()
                return@drawBehind
            }
            val root = view.viewRootImpl ?: return@drawBehind
            val drawable =
                region.drawable
                    ?: root.createBackgroundBlurDrawable().also {
                        it.setXfermode(null)
                        region.drawable = it
                    }
            val corner =
                when (val outline = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                    // A path shape, like the icon shapes of the tiles, has no corner radius of its
                    // own. The glass can only be a rounded rect, so give it the fullest one that
                    // fits in the bounds of the path, which is exact for the circle of the tiles.
                    is Outline.Generic ->
                        outline.path.getBounds().let { min(it.width, it.height) / 2f }
                    is Outline.Rectangle -> 0f
                }
            drawable.setVisible(true, false)
            drawable.setMaterial(material)
            drawable.setColor(0)
            drawable.setBlurRadius(
                ColorOSGlassBlurTune.overridePx.takeIf { it > 0 } ?: defaultRadius
            )
            drawable.setCornerRadius(min(corner, shown.height / 2f))
            drawable.alpha = (alpha * 255).toInt()
            drawable.setBounds(
                shown.left.toInt(),
                shown.top.toInt(),
                shown.right.toInt(),
                shown.bottom.toInt(),
            )
            // Drawing it is what reports its position to SurfaceFlinger.
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
}
