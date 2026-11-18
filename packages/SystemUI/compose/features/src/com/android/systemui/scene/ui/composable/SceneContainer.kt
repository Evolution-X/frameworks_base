/*
 * Copyright (C) 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.scene.ui.composable

import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.PassthroughSwipeDetector
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.observableTransitionState
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffectFactory
import com.android.compose.snapshot.ObserveReads
import com.android.systemui.Flags.blackScreenOnSceneContainerStartFix
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.lifecycle.rememberActivated
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.ribbon.ui.composable.BottomRightCornerRibbon
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.view.SceneJankMonitor
import com.android.systemui.scene.ui.view.SceneTransitionLatencyMonitor
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.ui.composable.CosShadeGlass
import com.android.systemui.shade.ui.composable.OverlayShade
import kotlinx.coroutines.CoroutineScope
import platform.test.motion.compose.values.isRunningMotionTest

/**
 * Renders a container of a collection of "scenes" that the user can switch between using certain
 * user actions (for instance, swiping up and down) or that can be switched automatically based on
 * application business logic in response to certain events (for example, the device unlocking).
 *
 * It's possible for the application to host several such scene containers, the configuration system
 * allows configuring each container with its own set of scenes. Scenes can be present in multiple
 * containers.
 *
 * @param viewModel The UI state holder for this container.
 * @param sceneByKey Mapping of [Scene] by [SceneKey], ordered by z-order such that the last scene
 *   is rendered on top of all other scenes. It's critical that this map contains exactly and only
 *   the scenes on this container. In other words: (a) there should be no scene in this map that is
 *   not in the configuration for this container and (b) all scenes in the configuration must have
 *   entries in this map.
 * @param overlayByKey Mapping of [Overlay] by [OverlayKey], ordered by z-order such that the last
 *   overlay is rendered on top of all other overlays. It's critical that this map contains exactly
 *   and only the overlays on this container. In other words: (a) there should be no overlay in this
 *   map that is not in the configuration for this container and (b) all overlays in the
 *   configuration must have entries in this map.
 * @param modifier A modifier.
 */
@Composable
fun SceneContainer(
    viewModel: SceneContainerViewModel,
    sceneByKey: Map<SceneKey, Scene>,
    overlayByKey: Map<OverlayKey, Overlay>,
    initialSceneKey: SceneKey,
    transitionsBuilder: SceneContainerTransitionsBuilder,
    dataSourceDelegator: SceneDataSourceDelegator,
    sceneJankMonitorFactory: SceneJankMonitor.Factory,
    sceneTransitionLatencyMonitor: SceneTransitionLatencyMonitor,
    onTransitionStart:
        (transition: TransitionState.Transition, animationScope: CoroutineScope) -> Unit,
    onSnap: (idle: TransitionState.Idle) -> Unit,
    modifier: Modifier = Modifier,
    swipeVelocityThreshold: Dp = SceneContainerDefaults.SwipeVelocityThreshold,
) {
    WithSceneContainerPreloadedResources {
        InternalSceneContainer(
            viewModel = viewModel,
            sceneByKey = sceneByKey,
            overlayByKey = overlayByKey,
            initialSceneKey = initialSceneKey,
            transitionsBuilder = transitionsBuilder,
            dataSourceDelegator = dataSourceDelegator,
            sceneJankMonitorFactory = sceneJankMonitorFactory,
            sceneTransitionLatencyMonitor = sceneTransitionLatencyMonitor,
            onTransitionStart = onTransitionStart,
            onSnap = onSnap,
            modifier = modifier,
            swipeVelocityThreshold = swipeVelocityThreshold,
        )
    }
}

@Composable
private fun InternalSceneContainer(
    viewModel: SceneContainerViewModel,
    sceneByKey: Map<SceneKey, Scene>,
    overlayByKey: Map<OverlayKey, Overlay>,
    initialSceneKey: SceneKey,
    transitionsBuilder: SceneContainerTransitionsBuilder,
    dataSourceDelegator: SceneDataSourceDelegator,
    sceneJankMonitorFactory: SceneJankMonitor.Factory,
    sceneTransitionLatencyMonitor: SceneTransitionLatencyMonitor,
    onTransitionStart:
        (transition: TransitionState.Transition, animationScope: CoroutineScope) -> Unit,
    onSnap: (idle: TransitionState.Idle) -> Unit,
    modifier: Modifier = Modifier,
    swipeVelocityThreshold: Dp = SceneContainerDefaults.SwipeVelocityThreshold,
) {
    val coroutineScope = rememberCoroutineScope()

    val view = LocalView.current
    val sceneJankMonitor =
        rememberActivated(traceName = "sceneJankMonitor") { sceneJankMonitorFactory.create() }

    val hapticFeedback = LocalHapticFeedback.current
    val isFullWidthShade = LocalSceneContainerPreloadedResources.current.isFullWidthShade
    val shadeExpansionMotion = OverlayShade.rememberShadeExpansionMotion(isFullWidthShade)
    val animateQsTilesViewModel =
        rememberViewModel(traceName = "SceneContainer.animateQsTilesViewModel") {
            viewModel.animateQsTilesViewModelFactory.create()
        }

    val resources = LocalResources.current
    val sceneTransitions =
        remember(
            hapticFeedback,
            shadeExpansionMotion,
            animateQsTilesViewModel,
            resources,
            viewModel.toBouncerTransitionViewModel,
        ) {
            transitionsBuilder.build(
                shadeExpansionMotion,
                viewModel.hapticsViewModel.getRevealHaptics(hapticFeedback),
                animateQsTilesViewModel,
                viewModel.toBouncerTransitionViewModel,
                resources,
            )
        }

    val state =
        rememberMutableSceneTransitionLayoutState(
            initialScene = initialSceneKey,
            canChangeScene = { toScene -> viewModel.canChangeScene(toScene) },
            canShowOverlay = { overlay -> viewModel.canShowOrReplaceOverlay(overlay) },
            canReplaceOverlay = { beingReplaced, newlyShown ->
                viewModel.canShowOrReplaceOverlay(
                    newlyShown = newlyShown,
                    beingReplaced = beingReplaced,
                )
            },
            transitions = sceneTransitions,
            onTransitionStart = { transition ->
                onTransitionStart(transition, coroutineScope)

                sceneJankMonitor.onTransitionStart(
                    view = view,
                    from = transition.fromContent,
                    to = transition.toContent,
                    cuj = transition.cuj,
                    cujTag = transition.cujTag,
                )
                sceneTransitionLatencyMonitor.onTransitionStart(transition)
            },
            onTransitionEnd = { transition ->
                sceneJankMonitor.onTransitionEnd(
                    from = transition.fromContent,
                    to = transition.toContent,
                    cuj = transition.cuj,
                )
            },
            onSnap = onSnap,
            deferTransitionProgress = true,
        )

    LaunchedEffect(Unit) { viewModel.onInitialComposition() }

    DisposableEffect(state) {
        val dataSource = SceneTransitionLayoutDataSource(state, coroutineScope)
        dataSourceDelegator.setDelegate(dataSource)
        onDispose { dataSourceDelegator.setDelegate(null) }
    }

    DisposableEffect(viewModel, state) {
        viewModel.setTransitionState(state.observableTransitionState())
        onDispose { viewModel.setTransitionState(null) }
    }

    if (blackScreenOnSceneContainerStartFix() && state.transitionState.isIdle()) {
        DisposableEffect(state.transitionState.currentScene) {
            viewModel.onIdleSceneEnteredComposition(state.transitionState.currentScene)
            onDispose { viewModel.onIdleSceneExitedComposition(state.transitionState.currentScene) }
        }
    }

    ObserveReads {
        val transitionState = state.transitionState
        viewModel.blurViewModel.requestWindowBackgroundBlur(
            transitionState,
            (transitionState as? TransitionState.Transition)?.progress ?: 1f,
        )
    }

    val hasAnyEnabledBackHandler =
        LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher?.hasEnabledCallbacks() ==
            true

    val actionableContentKey =
        viewModel.getActionableContentKey(state.currentScene, state.currentOverlays, overlayByKey)
    val userActionsByContentKey: MutableMap<ContentKey, Map<UserAction, UserActionResult>> =
        remember {
            mutableStateMapOf()
        }
    val aodOrDozing = viewModel.isAodOrDozing
    LaunchedEffect(
        actionableContentKey,
        aodOrDozing,
        state.currentScene,
        hasAnyEnabledBackHandler,
    ) {
        try {
            val actionableContent: ActionableContent =
                checkNotNull(
                    overlayByKey[actionableContentKey] ?: sceneByKey[actionableContentKey]
                ) {
                    "invalid ContentKey: $actionableContentKey"
                }
            viewModel.filteredUserActions(actionableContent.userActions).collect { userActions ->
                userActionsByContentKey[actionableContentKey] =
                    viewModel.resolveSceneFamilies(userActions)
                viewModel.updateNavigationBarVisibility(
                    windowInsetsController = view.windowInsetsController,
                    hasBackAction = userActions.containsKey(Back),
                    sceneKey = state.currentScene,
                    aodOrDozing = aodOrDozing,
                    hasAnyEnabledBackHandler = hasAnyEnabledBackHandler,
                )
            }
        } finally {
            userActionsByContentKey[actionableContentKey] = emptyMap()
        }
    }

    // Overlays use the offset overscroll effect when shown on large screens, otherwise they
    // stretch. All scenes use the OffsetOverscrollEffect.
    val offsetOverscrollEffectFactory = rememberOffsetOverscrollEffectFactory()
    val stretchOverscrollEffectFactory = checkNotNull(LocalOverscrollFactory.current)
    val overlayEffectFactory =
        if (isFullWidthShade) {
            stretchOverscrollEffectFactory
        } else {
            offsetOverscrollEffectFactory
        }

    Box(
        modifier =
            modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(false)
                    viewModel.onSceneContainerUserInputStarted()
                }
            }
    ) {
        SceneRevealScrim(
            viewModel = viewModel.lightRevealScrim,
            wallpaperViewModel = viewModel.wallpaperViewModel,
            modifier = Modifier.fillMaxSize(),
        )

        CosShadeGlass(state, viewModel.isLaunchingActivity)

        SceneTransitionLayout(
            state = state,
            modifier = Modifier.fillMaxSize(),
            swipeSourceDetector = viewModel.swipeSourceDetector,
            swipeDetector =
                remember { PassthroughSwipeDetector(velocityThreshold = swipeVelocityThreshold) },
            implicitTestTags = isRunningMotionTest,
            debugName = "SceneContainer",
        ) {
            sceneByKey.forEach { (sceneKey, scene) ->
                scene(
                    key = sceneKey,
                    userActions = userActionsByContentKey.getOrDefault(sceneKey, emptyMap()),
                    effectFactory = offsetOverscrollEffectFactory,
                    alwaysCompose = scene.alwaysCompose,
                ) {
                    // Activate the scene.
                    LaunchedEffect(scene) { scene.activate() }

                    // Render the scene.
                    with(scene) {
                        this@scene.Content(
                            modifier = Modifier.element(sceneKey.rootElementKey).fillMaxSize()
                        )
                    }
                }
            }
            overlayByKey.forEach { (overlayKey, overlay) ->
                overlay(
                    key = overlayKey,
                    userActions = userActionsByContentKey.getOrDefault(overlayKey, emptyMap()),
                    effectFactory = overlayEffectFactory,
                    alwaysCompose = overlay.alwaysCompose,
                    // The bouncer overlay is special and not rendered here, so avoid adding
                    // the fullscreen clickable which modals typically introduce. This avoids
                    // issues with accessibility touch exploration while on the bouncer.
                    isModal = overlayKey != Overlays.Bouncer,
                ) {
                    // Activate the overlay.
                    LaunchedEffect(overlay) { overlay.activate() }

                    if (overlayKey == Overlays.Bouncer) {
                        // The bouncer overlay is special because it needs to be rendered above the
                        // notifications which, themselves, are rendered above the scene container.
                        //
                        // There is a separate, external, bouncer scene container whose only job is
                        // to render the bouncer overlay. We still want to have the overlay here in
                        // this scene container because we still need it to manage transitions in
                        // and out of that overlay - but we delegate the actual showing and
                        // transition animations out to that dedicated bouncer scene container.
                        return@overlay
                    }

                    // Render the overlay.
                    with(overlay) { this@overlay.Content(Modifier) }
                }
            }
        }

        if (Build.IS_ENG) {
            BottomRightCornerRibbon(
                content = { Text(text = "flexi\uD83E\uDD43", color = Color.White) },
                colorSaturation = { viewModel.ribbonColorSaturation },
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .burnInAware(movement = viewModel.burnInMovementState, isClock = false),
            )
        }
    }
}

object SceneContainerDefaults {
    val SwipeVelocityThreshold = 250.dp
}
