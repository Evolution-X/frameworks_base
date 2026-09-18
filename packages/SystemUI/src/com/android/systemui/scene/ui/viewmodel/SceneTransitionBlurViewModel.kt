/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.scene.ui.viewmodel

import android.os.Build
import android.util.Log
import android.util.MathUtils.max
import androidx.compose.runtime.Stable
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.Flags.spatialModelBouncerPushback
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import com.android.systemui.window.logging.BlurLogger
import com.android.systemui.window.shared.model.BlurEffect
import com.android.systemui.window.ui.BlurChoreographer
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Named
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map

/**
 * View model that maps [TransitionState] to blur radius that needs to applied to the window's
 * background (e.g. the wallpaper, other activities visible behind NotificationShadeWindow).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Stable
class SceneTransitionBlurViewModel
@AssistedInject
constructor(
    @Named("ShadeWindowBlurChoreographer") val blurChoreographer: BlurChoreographer,
    wallpaperInteractor: WallpaperInteractor,
    communalSettingsInteractor: CommunalSettingsInteractor,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val blurConfig: BlurConfig,
    private val shadeInteractor: ShadeInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val logger: BlurLogger,
    private val displayTracker: DisplayTracker,
) : HydratedActivatable(false) {

    private val ignoredSceneChanges: Set<SceneKey> = setOf(Scenes.Shade, Scenes.QuickSettings)
    private val ignoredOverlayChanges: Set<OverlayKey> =
        setOf(Overlays.QuickSettingsShade, Overlays.NotificationsShade, Overlays.QuickActions)

    private val isCommunalBackgroundBlurred: Boolean by
        communalSettingsInteractor.communalBackground
            .map { it == CommunalBackgroundType.BLUR }
            .hydratedStateOf(false)

    private val ambientModeSupported: Boolean by
        wallpaperInteractor.wallpaperSupportsAmbientMode.hydratedStateOf(false)

    override suspend fun onActivated() {
        blurChoreographer.registerOnBlurAppliedListener { blurEffect ->
            windowRootViewBlurInteractor.onBlurApplied(blurEffect.radius.toInt())
        }

        windowRootViewBlurInteractor.registerShadeBlurChangedListener {
            v("Applying blur requested by shade", it.radius, it.scale)
            applyBlur(it)
        }
    }

    override suspend fun onDeactivated() {
        blurChoreographer.clearOnBlurAppliedListener()
        windowRootViewBlurInteractor.clearShadeBlurChangedListener()
    }

    fun requestWindowBackgroundBlur(transitionState: TransitionState, transitionProgress: Float) {
        val blurRadius =
            when (transitionState) {
                is TransitionState.Idle -> transitionState.toBlurRadius()
                is TransitionState.Transition.ChangeScene ->
                    transitionState.toBlurRadius(transitionProgress)

                is TransitionState.Transition.ReplaceOverlay ->
                    transitionState.toBlurRadius(transitionProgress)

                is TransitionState.Transition.ShowOrHideOverlay ->
                    transitionState.toBlurRadius(transitionProgress)
            }
        if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
            val isTransitioningToShadeOrQuickSettings =
                transitionState.isTransitioning(to = Scenes.Shade) ||
                    transitionState.isTransitioning(to = Scenes.QuickSettings)
            if (
                !deviceEntryInteractor.isDeviceEntered.value ||
                    isTransitioningToShadeOrQuickSettings
            ) {
                blurChoreographer.setPersistentEarlyWakeup(true)
            } else if (
                transitionState is TransitionState.Idle &&
                    transitionState.currentScene == Scenes.Gone &&
                    transitionState.currentOverlays.isEmpty()
            ) {
                blurChoreographer.setPersistentEarlyWakeup(false)
            }
        }
        blurRadius?.let {
            val scale = computeBackgroundBlurScale(transitionState)
            val blurEffect = BlurEffect(it, scale)
            if (transitionState is TransitionState.Idle) {
                logger.logRequestWindowBackgroundBlur(
                    transitionState.currentScene,
                    transitionState.currentOverlays,
                    blurEffect,
                    windowRootViewBlurInteractor.isBlurCurrentlySupported.value,
                )
            }
            v("Applying blur for TransitionState change", blurRadius, scale)
            applyBlur(blurEffect)
        }
    }

    private fun ContentKey.blurRadius(): Float {
        val maxBlurRadiusPx =
            blurConfig.maxBlurRadiusPxForRefreshRate(
                displayTracker.getDisplay(displayTracker.defaultDisplayId).refreshRate
            )
        return when (this) {
            is SceneKey -> {
                when (this) {
                    Scenes.Communal ->
                        if (isCommunalBackgroundBlurred) {
                            maxBlurRadiusPx
                        } else {
                            blurConfig.minBlurRadiusPx
                        }
                    Scenes.Lockscreen ->
                        if (
                            ambientModeSupported &&
                                keyguardTransitionInteractor.currentKeyguardState.value == AOD
                        ) {
                            maxBlurRadiusPx / 2
                        } else {
                            blurConfig.minBlurRadiusPx
                        }
                    Scenes.QuickSettings -> maxBlurRadiusPx
                    Scenes.Shade -> maxBlurRadiusPx
                    Scenes.Dream -> blurConfig.minBlurRadiusPx
                    Scenes.Gone -> blurConfig.minBlurRadiusPx
                    Scenes.Occluded -> blurConfig.minBlurRadiusPx
                    else -> blurConfig.minBlurRadiusPx
                }
            }

            is OverlayKey -> {
                maxBlurRadiusPx
            }
        }
    }

    private fun transitionProgressToBlurRadius(
        startBlurRadius: Float,
        endBlurRadius: Float,
        transitionProgress: Float,
    ): Float = lerp(startBlurRadius, endBlurRadius, transitionProgress)

    private fun computeBackgroundBlurScale(state: TransitionState): Float {
        return 1f -
            when {
                state is TransitionState.Transition.ChangeScene ->
                    when {
                        state.fromScene == Scenes.Lockscreen && state.toScene == Scenes.Communal ->
                            state.progress * BLUR_SCALE_COMMUNAL
                        state.fromScene == Scenes.Communal ->
                            (1f - state.progress) * BLUR_SCALE_COMMUNAL
                        else -> 0f
                    }
                state is TransitionState.Transition.ShowOrHideOverlay &&
                    spatialModelBouncerPushback() ->
                    when {
                        state.fromContent == Scenes.Lockscreen &&
                            state.toContent == Overlays.Bouncer ->
                            state.progress * BLUR_SCALE_BOUNCER
                        state.fromContent == Overlays.Bouncer &&
                            (state.toContent == Scenes.Lockscreen ||
                                state.toContent == Scenes.Gone) ->
                            (1f - state.progress) * BLUR_SCALE_BOUNCER
                        else -> 0f
                    }
                state is TransitionState.Idle ->
                    when {
                        state.currentScene == Scenes.Communal -> BLUR_SCALE_COMMUNAL
                        spatialModelBouncerPushback() &&
                            state.currentScene == Scenes.Lockscreen &&
                            state.currentOverlays.contains(Overlays.Bouncer) -> BLUR_SCALE_BOUNCER
                        else -> 0f
                    }
                else -> 0f
            }
    }

    private fun applyBlur(blurEffect: BlurEffect) {
        blurChoreographer.applyBlur(
            if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                blurEffect
            } else {
                BlurEffect(0f, 1f)
            }
        )
    }

    private fun TransitionState.Transition.ShowOrHideOverlay.toBlurRadius(
        transitionProgress: Float
    ): Float? {
        val transitionState = this
        return if (
            ignoredOverlayChanges.contains(transitionState.overlay) ||
                ignoredOverlayChanges.contains(transitionState.toContent) ||
                ignoredOverlayChanges.contains(transitionState.fromContent)
        ) {
            null
        } else {
            val startBlurRadius =
                max(
                    transitionState.currentScene.blurRadius(),
                    transitionState.fromContent.blurRadius(),
                )
            val endBlurRadius =
                max(
                    transitionState.currentScene.blurRadius(),
                    transitionState.toContent.blurRadius(),
                )

            transitionProgressToBlurRadius(startBlurRadius, endBlurRadius, transitionProgress)
        }
    }

    private fun TransitionState.Transition.ReplaceOverlay.toBlurRadius(
        transitionProgress: Float
    ): Float? {
        val transitionState = this
        return if (
            ignoredOverlayChanges.contains(transitionState.fromOverlay) &&
                ignoredOverlayChanges.contains(transitionState.toOverlay)
        ) {
            null
        } else {
            transitionProgressToBlurRadius(
                transitionState.fromOverlay.blurRadius(),
                transitionState.toOverlay.blurRadius(),
                transitionProgress,
            )
        }
    }

    private fun TransitionState.Transition.ChangeScene.toBlurRadius(
        transitionProgress: Float
    ): Float? {
        val transitionState = this
        return if (
            !ignoredSceneChanges.contains(transitionState.toScene) &&
                !ignoredSceneChanges.contains(transitionState.fromScene)
        ) {
            transitionProgressToBlurRadius(
                transitionState.fromScene.blurRadius(),
                transitionState.toScene.blurRadius(),
                transitionProgress,
            )
        } else null
    }

    private fun TransitionState.Idle.toBlurRadius(): Float? {
        val transitionState = this
        return if (
            transitionState.currentOverlays.any { ignoredOverlayChanges.contains(it) } ||
                ignoredSceneChanges.contains(transitionState.currentScene)
        ) {
            null
        } else
            max(
                transitionState.currentScene.blurRadius(),
                transitionState.currentOverlays.maxOfOrNull { it.blurRadius() } ?: 0f,
            )
    }

    private fun v(message: String, blurRadius: Float, blurScale: Float) {
        if (isLoggable) {
            Log.v(TAG, "$message blurRadius=$blurRadius blurScale=$blurScale")
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): SceneTransitionBlurViewModel
    }

    companion object {
        private const val BLUR_SCALE_COMMUNAL = 0.05f
        private const val BLUR_SCALE_BOUNCER = 0.05f
        private const val TAG = "SceneTransitionBlur"
        private val isLoggable
            get() = Log.isLoggable(TAG, Log.VERBOSE) || Build.IS_ENG
    }
}
