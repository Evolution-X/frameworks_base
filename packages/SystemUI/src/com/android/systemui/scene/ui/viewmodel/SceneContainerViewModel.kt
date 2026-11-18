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

package com.android.systemui.scene.ui.viewmodel

import android.annotation.StringRes
import android.content.res.Resources
import android.os.Build
import android.view.MotionEvent
import android.view.WindowInsetsController
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsCompat
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.DynamicSizeEdgeDetector
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SwipeSourceDetector
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.desktop.domain.interactor.DesktopInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.viewmodel.BurnInMovementState
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.qs.panels.ui.viewmodel.AnimateQsTilesViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.OnBootTransitionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.core.StatusBarForDesktop
import com.android.systemui.statusbar.domain.interactor.RemoteInputInteractor
import com.android.systemui.statusbar.notification.domain.interactor.NotificationContainerInteractor
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.wallpapers.ui.viewmodel.WallpaperViewModel
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Models UI state for the scene container. */
class SceneContainerViewModel
@AssistedInject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware private val systemBarUtilsState: SystemBarUtilsState,
    private val sceneInteractor: SceneInteractor,
    desktopInteractor: DesktopInteractor,
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val falsingInteractor: FalsingInteractor,
    private val powerInteractor: PowerInteractor,
    private val onBootTransitionInteractor: OnBootTransitionInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val notificationContainerInteractor: NotificationContainerInteractor,
    private val remoteInputInteractor: RemoteInputInteractor,
    private val logger: SceneLogger,
    hapticsViewModelFactory: SceneContainerHapticsViewModel.Factory,
    val lightRevealScrim: LightRevealScrimViewModel,
    val wallpaperViewModel: WallpaperViewModel,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    val burnInMovementFactory: BurnInMovementState.Factory,
    val dualShadeEducationalTooltipsViewModelFactory: DualShadeEducationalTooltipsViewModel.Factory,
    val animateQsTilesViewModelFactory: AnimateQsTilesViewModel.Factory,
    toBouncerTransitionViewModelFactory: ToBouncerTransitionViewModel.Factory,
    sceneTransitionBlurViewModelFactory: SceneTransitionBlurViewModel.Factory,
    private val toastDisplayer: Lazy<SceneContainerToastDisplayer>,
    @Assisted private val motionEventHandlerReceiver: (MotionEventHandler?) -> Unit,
    private val accessibilityInteractor: AccessibilityInteractor,
    shadeAnimationInteractor: ShadeAnimationInteractor,
) : HydratedActivatable() {

    /** The scene that should be rendered. */
    val currentScene: SceneKey
        get() = sceneInteractor.currentSceneAsState

    val blurViewModel: SceneTransitionBlurViewModel = sceneTransitionBlurViewModelFactory.create()
    val toBouncerTransitionViewModel = toBouncerTransitionViewModelFactory.create()

    /** Whether an activity is launching from the shade with a transition animation. */
    val isLaunchingActivity: StateFlow<Boolean> = shadeAnimationInteractor.isLaunchingActivity

    /** Whether the container is visible. */
    val isVisible: Boolean
        get() = sceneInteractor.isVisible

    val hapticsViewModel: SceneContainerHapticsViewModel = hapticsViewModelFactory.create()

    val burnInMovementState: BurnInMovementState = burnInMovementFactory.create()

    val isAodOrDozing: Boolean by
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .map { it.to == KeyguardState.DOZING || it.to == KeyguardState.AOD }
            .hydratedStateOf(false)

    /**
     * Whether to reject the transition to this ContentKey because the FalsingManager believes the
     * transition is from a false touch. If null, do not reject the current transition.
     */
    private var falsingCheckRejectsTransitionToContent: ContentKey? = null

    /**
     * Whether the last navigation bar visible request was to show navigation bars. If null, no
     * request has been sent.
     */
    private var lastNavigationBarVisibleRequest: Boolean? = null

    private val dualShadeGestureSplitRatio =
        resources.getFloat(R.dimen.config_invocationGestureSplitRatio)

    private val statusBarHeightPx: Int by
        systemBarUtilsState.statusBarHeight.hydratedStateOf(initialValue = 0)

    private val dynamicSizeEdgeDetector = DynamicSizeEdgeDetector { statusBarHeightPx }

    /**
     * The [SwipeSourceDetector] to use for defining which areas of the screen can be defined in the
     * [UserAction]s for this container.
     */
    val swipeSourceDetector: SwipeSourceDetector by
        shadeModeInteractor.shadeMode
            .map {
                if (it is ShadeMode.Dual) {
                    SceneContainerSwipeDetector(
                        dynamicSizeEdgeDetector = dynamicSizeEdgeDetector,
                        invocationGestureSplitRatio = dualShadeGestureSplitRatio,
                    )
                } else {
                    dynamicSizeEdgeDetector
                }
            }
            .hydratedStateOf(initialValue = dynamicSizeEdgeDetector)

    /** Amount of color saturation for the Flexi🥃 ribbon. */
    val ribbonColorSaturation: Float by
        keyguardInteractor.dozeAmount.map { 1 - it }.hydratedStateOf(initialValue = 1f)

    private val accessibilityEnabled: Boolean by
        accessibilityInteractor.isEnabledFiltered.hydratedStateOf(initialValue = false)

    val accessibilityTitle: Int?
        @StringRes
        get() {
            if (accessibilityEnabled) {
                val overlays = sceneInteractor.transitionState.currentOverlays
                val topmostOverlay = if (overlays.isNotEmpty()) overlays.last() else null
                return when {
                    topmostOverlay == Overlays.NotificationsShade ->
                        R.string.accessibility_desc_notification_shade
                    topmostOverlay == Overlays.QuickSettingsShade ->
                        R.string.accessibility_desc_quick_settings
                    topmostOverlay == Overlays.Bouncer -> null
                    currentScene == Scenes.Shade -> R.string.accessibility_desc_notification_shade
                    currentScene == Scenes.QuickSettings ->
                        R.string.accessibility_desc_quick_settings
                    currentScene == Scenes.Lockscreen -> R.string.accessibility_desc_lock_screen
                    else -> null
                }
            } else {
                return null
            }
        }

    private val isDesktopStatusBarEnabled by
        desktopInteractor.useDesktopStatusBar
            .map { enabled -> enabled && StatusBarForDesktop.isEnabled }
            .hydratedStateOf(initialValue = false)

    override suspend fun onActivated() {
        // Sends a MotionEventHandler to the owner of the view-model so they can report
        // MotionEvents into the view-model.
        motionEventHandlerReceiver(
            object : MotionEventHandler {
                override fun onMotionEvent(motionEvent: MotionEvent) {
                    this@SceneContainerViewModel.onMotionEvent(motionEvent)
                }

                override fun onEmptySpaceMotionEvent(motionEvent: MotionEvent) {
                    this@SceneContainerViewModel.onEmptySpaceMotionEvent(motionEvent)
                }

                override fun onMotionEventComplete() {
                    this@SceneContainerViewModel.onMotionEventComplete()
                }
            }
        )

        coroutineScope {
            launch("SceneTransitionBlurViewModel") { blurViewModel.activate() }
            launch("SceneContainerHapticsViewModel") { hapticsViewModel.activate() }
            launch("NotificationContainerInteractor") { notificationContainerInteractor.activate() }
            launch("BurnInMovementState") { burnInMovementState.activate() }
        }
    }

    override suspend fun onDeactivated() {
        // Clears the previously-sent MotionEventHandler so the owner of the view-model releases
        // their reference to it.
        motionEventHandlerReceiver(null)
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call this with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        sceneInteractor.setTransitionState(transitionState)
    }

    /**
     * Notifies that a [MotionEvent] is first seen at the top of the scene container UI. This
     * includes gestures on [SharedNotificationContainer] as well as the Composable scene container
     * hierarchy.
     *
     * Call this before the [MotionEvent] starts to propagate through the UI hierarchy.
     */
    fun onMotionEvent(event: MotionEvent) {
        powerInteractor.onUserTouch()
        falsingInteractor.onTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // If there's a current transition driven by user input, check falsing.
            val toContent = sceneInteractor.transitioningTo.value
            if (sceneInteractor.isTransitionUserInputOngoing.value && toContent != null) {
                val isAllowedByFalsing = isInteractionAllowedByFalsing(toContent)
                // Store the falsing check to be used in the near future by
                // canChangeScene or canShowOrReplaceOverlay.
                falsingCheckRejectsTransitionToContent =
                    if (isAllowedByFalsing) {
                        null
                    } else {
                        toContent
                    }
                logger.falsingCheckForContentChange(
                    from = currentScene,
                    to = toContent,
                    isAllowedByFalsing = isAllowedByFalsing,
                )
            } else {
                falsingCheckRejectsTransitionToContent = null
            }

            sceneInteractor.onUserInputFinished()
        }
    }

    /**
     * Notifies that a [MotionEvent] has propagated through the entire [SharedNotificationContainer]
     * and Composable scene container hierarchy without being handled.
     *
     * Call this after the [MotionEvent] has finished propagating through the UI hierarchy.
     */
    fun onEmptySpaceMotionEvent(event: MotionEvent) {
        // Hide dual shade overlays when there is a touch outside the shade window.
        // This is only applicable when the desktop status bar is enabled.
        if (
            shadeModeInteractor.isDualShade &&
                isDesktopStatusBarEnabled &&
                event.action == MotionEvent.ACTION_OUTSIDE &&
                sceneInteractor.transitionState.currentOverlays.isNotEmpty() &&
                sceneInteractor.transitionState.isIdle()
        ) {
            sceneInteractor.transitionState.currentOverlays.forEach {
                sceneInteractor.hideOverlay(it, "Empty space touch")
            }
        }

        // check if the touch is outside the window and if remote input is active.
        // If true, close any active remote inputs.
        if (
            event.action == MotionEvent.ACTION_OUTSIDE &&
                (remoteInputInteractor.isRemoteInputActive as StateFlow).value
        ) {
            remoteInputInteractor.closeRemoteInputs()
        }
    }

    /**
     * Notifies that a scene container user interaction has begun.
     *
     * This is a user interaction that has reached the Composable hierarchy of the scene container,
     * rather than being handled by [SharedNotificationContainer].
     */
    fun onSceneContainerUserInputStarted() {
        sceneInteractor.onSceneContainerUserInputStarted()
    }

    /**
     * Notifies that a [MotionEvent] that was previously sent to [onMotionEvent] has passed through
     * the scene container UI.
     *
     * Call this after the [MotionEvent] propagates completely through the UI hierarchy.
     */
    fun onMotionEventComplete() {
        falsingInteractor.onMotionEventComplete()
    }

    /**
     * Returns `true` if a change to [toScene] is currently allowed; `false` otherwise.
     *
     * This is invoked only for user-initiated transitions. The goal is to check with the falsing
     * system whether the change from the current scene to the given scene should be rejected due to
     * it being a false touch.
     */
    fun canChangeScene(toScene: SceneKey): Boolean {
        if (
            toScene == Scenes.Gone && !deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked
        ) {
            logger.logContentChangeRejection(
                from = currentScene,
                to = toScene,
                originalChangeReason = null,
                rejectionReason = "Device not unlocked",
            )
            return false
        }

        val canChangeScene = isFalsingAllowingContentChange(from = currentScene, to = toScene)
        if (canChangeScene) {
            // A scene change is guaranteed; log it.
            logger.logSceneChanged(
                from = currentScene,
                to = toScene,
                keyguardState = null,
                reason = "user interaction",
                isInstant = false,
            )
        }
        return canChangeScene
    }

    /**
     * Returns `true` if showing the [newlyShown] overlay is currently allowed; `false` otherwise.
     *
     * This is invoked only for user-initiated transitions. The goal is to check with the falsing
     * system whether the overlay change should be rejected due to it being a false touch.
     */
    fun canShowOrReplaceOverlay(
        newlyShown: OverlayKey,
        beingReplaced: OverlayKey? = null,
    ): Boolean {
        val canShowOrReplaceOverlay = isFalsingAllowingContentChange(beingReplaced, newlyShown)
        if (canShowOrReplaceOverlay) {
            // An overlay change is guaranteed; log it.
            logger.logOverlayChangeRequested(
                from = beingReplaced,
                to = newlyShown,
                reason = "user interaction",
            )
        }
        return canShowOrReplaceOverlay
    }

    /**
     * Immediately resolves any scene families present in [actionResultMap] to their current
     * resolution target.
     */
    fun resolveSceneFamilies(
        actionResultMap: Map<UserAction, UserActionResult>
    ): Map<UserAction, UserActionResult> {
        return actionResultMap.mapValues { (_, actionResult) ->
            when (actionResult) {
                is UserActionResult.ChangeScene -> {
                    sceneInteractor.resolveSceneFamilyOrNull(actionResult.toScene)?.value?.let {
                        toScene ->
                        UserActionResult(
                            toScene = toScene,
                            transitionKey = actionResult.transitionKey,
                            requiresFullDistanceSwipe = actionResult.requiresFullDistanceSwipe,
                        )
                    }
                }
                // Overlay transitions don't use scene families, nothing to resolve.
                is UserActionResult.ShowOverlay,
                is UserActionResult.HideOverlay,
                is UserActionResult.ReplaceByOverlay -> null
            } ?: actionResult
        }
    }

    /**
     * Returns the [ContentKey] whose user actions should be active.
     *
     * @param overlayByKey Mapping of [Overlay] by [OverlayKey], ordered by z-order such that the
     *   last overlay is rendered on top of all other overlays.
     */
    fun getActionableContentKey(
        currentScene: SceneKey,
        currentOverlays: Set<OverlayKey>,
        overlayByKey: Map<OverlayKey, Overlay>,
    ): ContentKey {
        // Overlay actions take precedence over scene actions.
        return when (currentOverlays.size) {
            // No overlays, the scene is actionable.
            0 -> currentScene
            // Small optimization for the most common case.
            1 -> currentOverlays.first()
            // Find the top-most overlay by z-index.
            else ->
                checkNotNull(overlayByKey.asSequence().findLast { it.key in currentOverlays }?.key)
        }
    }

    /**
     * Returns a filtered version of [unfiltered], without action-result entries that would navigate
     * to disabled scenes.
     */
    fun filteredUserActions(
        unfiltered: Flow<Map<UserAction, UserActionResult>>
    ): Flow<Map<UserAction, UserActionResult>> {
        return sceneInteractor.filteredUserActions(unfiltered)
    }

    suspend fun updateNavigationBarVisibility(
        windowInsetsController: WindowInsetsController?,
        hasBackAction: Boolean,
        sceneKey: SceneKey,
        aodOrDozing: Boolean,
        hasAnyEnabledBackHandler: Boolean,
    ) {
        if (windowInsetsController == null) {
            return
        }
        coroutineScope {
            // We launch a new coroutine that will be processed on the main thread message queue
            // after the frame to avoid blocking calls during frame rendering.
            // Note: We cannot update windowInsetsController from a background thread because
            // showing and hiding navigation bars must be called from the main thread.
            launch {
                val isNavigationBarVisible =
                    hasBackAction ||
                        hasAnyEnabledBackHandler ||
                        sceneKey == Scenes.Gone ||
                        sceneKey == Scenes.Communal ||
                        (sceneKey == Scenes.Lockscreen && !aodOrDozing)
                if (lastNavigationBarVisibleRequest != isNavigationBarVisible) {
                    lastNavigationBarVisibleRequest = isNavigationBarVisible
                    if (isNavigationBarVisible) {
                        windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
                    } else {
                        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
                    }
                }
            }
        }
    }

    /** Immediately changes the initial scene if necessary. */
    suspend fun onInitialComposition() {
        onBootTransitionInteractor.maybeChangeInitialScene()
    }

    /** Called when SceneContainer has currently composed [scene] with TransitionState.Idle. */
    fun onIdleSceneEnteredComposition(scene: SceneKey) {
        sceneInteractor.onIdleSceneEnteredComposition(scene)
    }

    /**
     * Called when SceneContainer has re-composed, and previously it was showing [scene] with
     * TransitionState.Idle - this means that [scene] (at least in its idle state) has left
     * composition.
     */
    fun onIdleSceneExitedComposition(scene: SceneKey) {
        sceneInteractor.onIdleSceneExitedComposition(scene)
    }

    fun startTransitionImmediately(transition: TransitionState.Transition) {
        sceneInteractor.startTransitionImmediately(transition)
    }

    private fun isFalsingAllowingContentChange(from: ContentKey?, to: ContentKey): Boolean {
        // Only false if the falsing check was for this transition
        if (falsingCheckRejectsTransitionToContent == to) {
            showDebuggingToast("${to.debugName} rejected: false touch")
            logger.logContentChangeRejection(
                from = from,
                to = to,
                originalChangeReason = null,
                rejectionReason = "Falsing: false touch detected",
            )
            falsingCheckRejectsTransitionToContent = null
            return false
        }
        falsingCheckRejectsTransitionToContent = null
        return true
    }

    /**
     * Returns `true` if transitioning to [content] is permissible by the falsing system; `false`
     * otherwise.
     */
    private fun isInteractionAllowedByFalsing(content: ContentKey): Boolean {
        val interactionTypeOrNull =
            when (content) {
                Overlays.Bouncer -> Classifier.BOUNCER_SWIPE
                Scenes.Communal -> Classifier.GLANCEABLE_HUB_SWIPE
                Scenes.Gone -> Classifier.UNLOCK
                Scenes.Shade -> Classifier.SHADE_DRAG
                Overlays.NotificationsShade -> Classifier.NOTIFICATION_DRAG_DOWN
                Scenes.QuickSettings,
                Overlays.QuickSettingsShade,
                Overlays.QuickActions -> Classifier.QUICK_SETTINGS
                else -> null
            }

        return interactionTypeOrNull?.let { interactionType ->
            // It's important that the falsing system is always queried, even if no enforcement
            // will occur. This helps build up the right signal in the system.
            val isFalseTouch = falsingInteractor.isFalseTouch(interactionType)

            // Only enforce falsing if moving from the lockscreen scene to new content.
            val fromLockscreenScene = currentScene == Scenes.Lockscreen

            !fromLockscreenScene || !isFalseTouch
        } ?: true
    }

    private fun showDebuggingToast(text: String) {
        if (!Build.IS_ENG && !Build.IS_USERDEBUG) {
            return
        }

        toastDisplayer.get().displayToast(text)
    }

    /** Defines interface for classes that can handle externally-reported [MotionEvent]s. */
    interface MotionEventHandler {
        /** Notifies that a [MotionEvent] has occurred. */
        fun onMotionEvent(motionEvent: MotionEvent)

        /** Notifies that a [MotionEvent] has occurred outside the root window. */
        fun onEmptySpaceMotionEvent(motionEvent: MotionEvent)

        /**
         * Notifies that the previous [MotionEvent] reported by [onMotionEvent] has finished
         * processing.
         */
        fun onMotionEventComplete()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            motionEventHandlerReceiver: (MotionEventHandler?) -> Unit
        ): SceneContainerViewModel
    }
}
