/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.qs.shared.style.LiquidGlass
import com.android.systemui.qs.shared.style.LocalGlassBlurAlpha
import com.android.systemui.qs.shared.style.glassBlurRegion
import com.android.systemui.qs.shared.style.liquidGlassOnShade
import com.android.systemui.scene.shared.model.Overlays
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val Shades = setOf<OverlayKey>(Overlays.NotificationsShade, Overlays.QuickSettingsShade)

@Composable
fun CosShadeGlass(state: SceneTransitionLayoutState, launchingActivity: StateFlow<Boolean>) {
    val context = LocalContext.current
    val cosStyle = remember(context) { LiquidGlass.cosStyle(context) }.value
    val colorOsPanel = remember(context) { LiquidGlass.colorOsPanel(context) }.value
    if (!liquidGlassOnShade() || !(cosStyle || colorOsPanel)) return
    val dark = isSystemInDarkTheme()
    // An app launched from a shade draws above the shade until its animation ends, then drops
    // below it for a frame before the shade goes away. Fade the glass out while the app opens and
    // keep it out a little past the end, or that frame shows the app blurred and dimmed.
    val launching by launchingActivity.collectAsStateWithLifecycle()
    var suppressed by remember { mutableStateOf(false) }
    LaunchedEffect(launching) {
        if (launching) {
            suppressed = true
        } else if (suppressed) {
            delay(LAUNCH_END_HOLD_MS)
            suppressed = false
        }
    }
    val launchAlpha by
        animateFloatAsState(
            if (suppressed) 0f else 1f,
            animationSpec = tween(durationMillis = LAUNCH_FADE_MS),
            label = "COS shade glass launch fade",
        )
    val visibility = remember(state) { { shadeVisibility(state) * launchAlpha } }
    CompositionLocalProvider(LocalGlassBlurAlpha provides visibility) {
        Spacer(
            Modifier.fillMaxSize()
                .glassBlurRegion(RectangleShape, material = if (dark) 5 else 6)
        )
    }
}

private fun shadeVisibility(state: SceneTransitionLayoutState): Float {
    val transition = state.currentTransition
    if (
        transition is TransitionState.Transition.ReplaceOverlay &&
            transition.fromContent in Shades &&
            transition.toContent in Shades
    ) {
        return 1f
    }
    val shown = state.transitionState.currentOverlays.any { it in Shades }
    return when {
        transition?.toContent in Shades -> leadingBlur(transition!!.progress)
        transition?.fromContent in Shades -> leadingBlur(1f - transition!!.progress)
        shown -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)
}

/**
 * The glass is mostly there early in the pull, so the panel unfolds over blur rather than over
 * the sharp screen; closing, it stays until the panel has nearly gone.
 */
private fun leadingBlur(shown: Float): Float =
    LinearOutSlowInEasing.transform((shown / BLUR_LEAD).coerceIn(0f, 1f))

private const val BLUR_LEAD = 0.35f

private const val LAUNCH_FADE_MS = 250

// The shade is removed on the frame after the launch ends; wait well past that before the glass
// may come back, which only shows if the launch was cancelled and the shade stayed open.
private const val LAUNCH_END_HOLD_MS = 300L
