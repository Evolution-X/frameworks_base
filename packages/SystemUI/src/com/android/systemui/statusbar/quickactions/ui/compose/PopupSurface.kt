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

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.shared.style.LiquidGlassTune
import com.android.systemui.qs.shared.style.liquidGlassEnabled
import com.android.systemui.qs.shared.style.liquidGlassRim
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_SOLID_BLACK
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandPopupColorMode
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandPopupColorMode
import kotlin.math.roundToInt

/**
 * How much of the window blur behind island glass shows. The blur is drawn by the window, not
 * the layer, so a parent that fades or tucks a card away passes its fade on through this.
 */
internal val LocalIslandGlassAlpha = compositionLocalOf<() -> Float> { { 1f } }

@Composable
private fun Modifier.plainBlurBehind(shape: Shape, tint: Int, radius: Dp): Modifier {
    val view = LocalView.current
    val context = LocalContext.current
    val holder = remember(view) { arrayOfNulls<BackgroundBlurDrawable>(1) }
    val glassAlpha = LocalIslandGlassAlpha.current
    val blurEnabled =
        remember(context) {
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
        }
    DisposableEffect(view) { onDispose { holder[0]?.setVisible(false, false) } }
    return drawBehind {
        val root = view.viewRootImpl
        if (!blurEnabled || root == null) {
            // Without blur a light glass tint would leave white text on white.
            drawOutline(shape.createOutline(size, layoutDirection, this), GlassNoBlurFill)
            return@drawBehind
        }
        val blur =
            holder[0]
                ?: root.createBackgroundBlurDrawable().also {
                    it.setXfermode(null)
                    holder[0] = it
                }
        val corner =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                else -> 0f
            }
        blur.setVisible(true, false)
        blur.setMaterial(PLAIN_BLUR)
        blur.setColor(tint)
        blur.alpha = (glassAlpha().coerceIn(0f, 1f) * 255).roundToInt()
        blur.setBlurRadius(radius.toPx().toInt())
        blur.setCornerRadius(corner)
        blur.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { blur.draw(it.nativeCanvas) }
    }
}

@Composable
fun rememberPopupColorMode(): Int {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandPopupColorMode() }
    val flow = remember(context) { observeDynamicIslandPopupColorMode(context) }
    val mode by flow.collectAsState(initial = initial)
    return mode
}

@Composable
fun PopupSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 12.dp,
    colorMode: Int = rememberPopupColorMode(),
    content: @Composable () -> Unit,
) {
    val cameraClearance = LocalPopupCameraClearance.current
    val body: @Composable () -> Unit = {
        Box(Modifier.padding(top = cameraClearance)) { content() }
    }
    // Liquid glass: frosted blur of what is behind, a dark tint and the glass rim. Content is
    // white in light and dark themes, so the tint must keep it readable over a light app too.
    if (liquidGlassEnabled() && colorMode != POPUP_COLOR_MODE_SOLID_BLACK) {
        val heavy = colorMode == POPUP_COLOR_MODE_BLUR
        Box(
            modifier =
                modifier
                    .plainBlurBehind(
                        shape,
                        // Dark enough for white text over a white app (about 5.7:1); tune with
                        // debug.lg.island_blur (dp) and debug.lg.island_surface.
                        tint =
                            if (heavy) LiquidGlassTune.color("island_blur_surface", HeavyGlassTint)
                            else LiquidGlassTune.color("island_surface", GlassTint),
                        radius =
                            if (heavy) PopupBlurRadius
                            else LiquidGlassTune.f("island_blur", 14f).dp,
                    )
                    .liquidGlassRim(shape)
                    .clip(shape)
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { body() }
        }
        return
    }
    // Every background runs all the way up around the camera; the content is only pushed down by
    // the clearance so it stays off the lens.
    when (colorMode) {
        POPUP_COLOR_MODE_BLUR -> {
            AxBlurSurface(
                modifier = modifier,
                shape = shape,
                cornerRadius = 32.dp,
                highlight = AxBlurSurfaceDefaults.highlightBrush(),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    body()
                }
            }
        }
        POPUP_COLOR_MODE_SOLID_BLACK -> {
            Surface(
                color = Color.Black,
                contentColor = Color.White,
                shape = shape,
                shadowElevation = shadowElevation,
                modifier = modifier,
            ) {
                body()
            }
        }
        else -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = shape,
                // The island window clips the shadow into ghost edges at the card corners.
                shadowElevation = 0.dp,
                modifier = modifier,
            ) {
                body()
            }
        }
    }
}

/** Height of camera cutout the popup content has to stay clear of at its top edge. */
val LocalPopupCameraClearance = compositionLocalOf { 0.dp }

private const val PLAIN_BLUR = -1
private const val GlassTint = 0x99000000.toInt()
private const val HeavyGlassTint = 0xB3000000.toInt()
private val PopupBlurRadius = 34.dp
private val GlassNoBlurFill = Color(0xE62C2C2E)
