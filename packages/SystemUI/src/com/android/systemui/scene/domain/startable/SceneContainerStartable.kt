/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.domain.startable

import android.app.StatusBarManager
import android.os.PowerManager
import android.view.SurfaceControl
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.snapshotFlow
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.CoreStartable
import com.android.systemui.Flags
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorActual
import com.android.systemui.common.domain.interactor.SysUIStateDisplaysInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.WindowManagerLockscreenVisibilityManager
import com.android.systemui.keyguard.data.model.ShowWhenLockedActivityInfoModel
import com.android.systemui.keyguard.domain.interactor.KeyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardShowWhileAwakeInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardSurfaceBehindInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardWakeDirectlyToGoneInteractor
import com.android.systemui.keyguard.domain.interactor.ShowWhileAwakeReason
import com.android.systemui.keyguard.domain.interactor.TrustInteractor
import com.android.systemui.keyguard.domain.model.OcclusionStateModel
import com.android.systemui.keyguard.shared.DriveDreamStateFromOcclusion
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.model.SceneContainerPlugin
import com.android.systemui.model.SceneContainerPluginImpl
import com.android.systemui.model.StateChange
import com.android.systemui.model.SysUiState
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.FalsingBeliefListener
import com.android.systemui.power.data.model.PowerButtonLaunchEvent
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.data.model.peek
import com.android.systemui.scene.domain.SceneFrameworkTableLog
import com.android.systemui.scene.domain.interactor.DisabledContentInteractor
import com.android.systemui.scene.domain.interactor.OnBootTransitionInteractor
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor.HideOverlayCommand
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToAlwaysOnDisplay
import com.android.systemui.shade.domain.interactor.ShadeDisplaysInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.util.asIndenting
import com.android.systemui.util.kotlin.Quad
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import dagger.Lazy
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Hooks up business logic that manipulates the state of the [SceneInteractor] for the system UI
 * scene container based on state from other systems.
 */
@SysUISingleton
class SceneContainerStartable
@Inject
constructor(
    // go/keep-sorted start by_regex=(?:@\S+)?\s*(?:private|internal|public)?\s*(?:val|var)?\s*(.*)
    private val activityTransitionAnimator: ActivityTransitionAnimator,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    @Application private val applicationScope: CoroutineScope,
    private val authenticationInteractor: Lazy<AuthenticationInteractor>,
    private val bootInteractor: OnBootTransitionInteractor,
    private val bouncerInteractor: BouncerInteractor,
    private val centralSurfacesOptLazy: Lazy<Optional<CentralSurfaces>>,
    private val deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val deviceProvisioningInteractor: DeviceProvisioningInteractor,
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val disabledContentInteractor: DisabledContentInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    private val faceUnlockInteractor: DeviceEntryFaceAuthInteractor,
    @FalsingCollectorActual private val falsingCollector: FalsingCollector,
    private val falsingManager: FalsingManager,
    private val headsUpInteractor: HeadsUpNotificationInteractor,
    private val keyguardEnabledInteractor: KeyguardEnabledInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardShowWhileAwakeInteractor: KeyguardShowWhileAwakeInteractor,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val msdlPlayer: MSDLPlayer,
    private val occlusionInteractor: KeyguardOcclusionInteractor,
    private val powerInteractor: PowerInteractor,
    private val sceneBackInteractor: SceneBackInteractor,
    private val sceneInteractor: SceneInteractor,
    private val sceneLogger: SceneLogger,
    shadeDisplaysInteractor: Lazy<ShadeDisplaysInteractor>,
    private val shadeInteractor: ShadeInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val shadeSessionStorage: SessionStorage,
    private val simBouncerInteractor: Lazy<SimBouncerInteractor>,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val surfaceBehindInteractor: KeyguardSurfaceBehindInteractor,
    private val sysuiStateInteractor: SysUIStateDisplaysInteractor,
    @SceneFrameworkTableLog private val tableLogBuffer: TableLogBuffer,
    private val trustInteractor: TrustInteractor,
    private val vibratorHelper: VibratorHelper,
    private val wakeDirectlyToGoneInteractor: KeyguardWakeDirectlyToGoneInteractor,
    private val windowController: NotificationShadeWindowController,
    private val windowManagerLockscreenVisibilityManager: WindowManagerLockscreenVisibilityManager,
    // go/keep-sorted end
) : CoreStartable {
    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    private val authInteractionProperties = AuthInteractionProperties()

    private val shadePendingDisplayId: Flow<Int> = shadeDisplaysInteractor.get().pendingDisplayId

    override fun start() {
        if (SceneContainerFlag.isEnabled) {
            applicationScope.launch { sceneLogger.activate() }
            sceneLogger.logFrameworkEnabled(isEnabled = true)
            applicationScope.launch { hydrateTableLogBuffer() }
            maybeShowLockscreenOnStart()
            hydrateVisibility()
            automaticallySwitchScenes()
            hydrateSystemUiState()
            collectFalsingSignals()
            respondToFalsingDetections()
            hydrateInteractionState()
            handleBouncerOverscroll()
            if (DriveDreamStateFromOcclusion.isEnabled) {
                handleOcclusionAndDreaming()
            } else {
                handleOcclusion()
            }
            handleDeviceEntryHapticsWhileDeviceNotGone()
            hydrateWindowController()
            hydrateBackStack()
            resetShadeSessions()
            handleKeyguardEnabledness()
            notifyKeyguardDismissCancelledCallbacks()
            hydrateActivityTransitionAnimationState()
            lockWhenDeviceBecomesUntrusted()
            lockWhenKeyguardShowWhenAwake()
            showDismissibleKeyguardWhenFolded()
            wakeFromDozingOnContentChange()
            hydrateLockScreenUserManager()
        } else {
            sceneLogger.logFrameworkEnabled(isEnabled = false)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        with(pw.asIndenting()) {
            printSection("SceneContainerFlag") {
                printSection("Framework availability") {
                    println("isEnabled", SceneContainerFlag.isEnabled)
                    println("isEnabledOnVariant", SceneContainerFlag.isEnabledOnVariant)
                }

                if (!SceneContainerFlag.isEnabled) {
                    return
                }

                printSection("Framework state") {
                    sceneInteractor.dump(this)
                    println("backStack", sceneBackInteractor.backStack.value)
                    println("shadeMode", shadeModeInteractor.shadeMode.value)
                }

                printSection("Authentication state") {
                    println("isKeyguardEnabled", keyguardEnabledInteractor.isKeyguardEnabled.value)
                    println(
                        "isUnlocked",
                        deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked,
                    )
                    println("isDeviceEntered", deviceEntryInteractor.isDeviceEntered.value)
                    println(
                        "isFaceAuthEnabledAndEnrolled",
                        faceUnlockInteractor.isFaceAuthEnabledAndEnrolled(),
                    )
                    println("canSwipeToEnter", deviceEntryInteractor.canSwipeToEnter.value)
                }

                printSection("Power state") {
                    println(
                        "detailedWakefulness",
                        powerInteractor.detailedWakefulness.value.internalWakefulnessState,
                    )
                    println("isDozing", keyguardInteractor.isDozing.value)
                    println("isAodAvailable", keyguardInteractor.isAodAvailable.value)
                    println("isInteractive", powerInteractor.isInteractive.value)
                }

                printSection("Other") {
                    println(
                        "isDeviceProvisioned",
                        deviceProvisioningInteractor.isDeviceProvisioned(),
                    )
                }
            }
        }
    }

    private suspend fun hydrateTableLogBuffer() {
        coroutineScope {
            launch { sceneInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { keyguardEnabledInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { faceUnlockInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { powerInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { keyguardInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { deviceProvisioningInteractor.hydrateTableLogBuffer(tableLogBuffer) }
            launch { occlusionInteractor.hydrateTableLogBuffer(tableLogBuffer) }
        }
    }

    private fun resetShadeSessions() {
        applicationScope.launch {
            combine(
                    sceneBackInteractor.backStack
                        // We are in a session if either Shade or QuickSettings is on the back stack
                        .map { backStack ->
                            backStack.asIterable().any {
                                // TODO(b/356596436): Include overlays in the back stack as well.
                                it == Scenes.Shade || it == Scenes.QuickSettings
                            }
                        }
                        .distinctUntilChanged(),
                    // We are also in a session if either Notifications Shade or QuickSettings Shade
                    // is currently shown (whether idle or animating).
                    shadeInteractor.isAnyExpanded,
                ) { inBackStack, isShadeShown ->
                    inBackStack || isShadeShown
                }
                // Once a session has ended, clear the session storage.
                .filter { inSession -> !inSession }
                .collect { shadeSessionStorage.clear() }
        }
    }

    private fun <T> CoroutineScope.reportEvents(
        from: Flow<T>,
        eventBuilder: (T) -> SceneInteractor.Event,
    ) {
        launch { from.collect { sceneInteractor.handleEvent(eventBuilder(it)) } }
    }

    /**
     * Updates states in [SceneInteractor] that it needs to calculate the visibility of the scene
     * container.
     */
    private fun hydrateVisibility() {
        applicationScope.launch {
            coroutineScope {
                reportEvents(deviceProvisioningInteractor.isDeviceProvisioned) {
                    SceneInteractor.Event.DeviceProvisioningChange(it)
                }

                reportEvents(deviceUnlockedInteractor.deviceUnlockStatus) {
                    SceneInteractor.Event.DeviceUnlockChange(it.isUnlocked)
                }

                reportEvents(headsUpInteractor.isHeadsUpOrAnimatingAway) {
                    SceneInteractor.Event.HeadsUpNotificationVisibilityChange(it)
                }

                reportEvents(alternateBouncerInteractor.isVisible) {
                    SceneInteractor.Event.AlternateBouncerVisibilityChange(it)
                }

                reportEvents(surfaceBehindInteractor.isAnimatingSurface) {
                    SceneInteractor.Event.SurfaceBehindAnimationChange(it)
                }
            }
        }
    }

    /** Switches between scenes based on ever-changing application state. */
    private fun automaticallySwitchScenes() {
        handleBouncerImeVisibility()
        handleBouncerHiding()
        handleSimUnlock()
        handleDeviceEntry()
        handlePowerState()
        handleDisableFlags()
    }

    private fun handleBouncerImeVisibility() {
        applicationScope.launch {
            // TODO (b/308001302): Move this to a bouncer specific interactor.
            bouncerInteractor.onImeHiddenByUser.collectLatest {
                sceneInteractor.hideOverlay(
                    overlay = Overlays.Bouncer,
                    loggingReason = "IME hidden.",
                )
            }
        }
    }

    private fun handleBouncerHiding() {
        applicationScope.launch {
            repeatWhen(
                condition =
                    authenticationInteractor
                        .get()
                        .authenticationMethod
                        .map { !it.isSecure }
                        .distinctUntilChanged()
            ) {
                sceneInteractor.hideOverlay(
                    overlay = Overlays.Bouncer,
                    loggingReason = "Authentication method changed to a non-secure one.",
                )
            }
        }
    }

    private fun handleSimUnlock() {
        applicationScope.launch {
            simBouncerInteractor.get().isAnySimSecure.collect { isAnySimLocked ->
                val unlockStatus = deviceUnlockedInteractor.deviceUnlockStatus.value
                when {
                    isAnySimLocked -> {
                        switchToScene(
                            targetSceneKey = Scenes.Lockscreen,
                            loggingReason = "SIM unlock required",
                            hideOverlays =
                                HideOverlayCommand.HideSome(
                                    overlays =
                                        listOf(
                                            Overlays.NotificationsShade,
                                            Overlays.QuickSettingsShade,
                                        )
                                ),
                        )
                        sceneInteractor.showOverlay(
                            overlay = Overlays.Bouncer,
                            loggingReason = "Need to authenticate locked SIM card.",
                        )
                    }
                    unlockStatus.isUnlocked &&
                        deviceEntryInteractor.canSwipeToEnter.value == false -> {
                        val loggingReason =
                            "All SIM cards unlocked and device already unlocked and" +
                                " lockscreen doesn't require a swipe to dismiss."
                        switchToScene(targetSceneKey = Scenes.Gone, loggingReason = loggingReason)
                    }
                    else -> {
                        val loggingReason =
                            "All SIM cards unlocked and device still locked" +
                                " or lockscreen still requires a swipe to dismiss."
                        switchToScene(
                            targetSceneKey = Scenes.Lockscreen,
                            loggingReason = loggingReason,
                        )
                    }
                }
            }
        }
    }

    private fun handleDeviceEntry() {
        deviceEntryInteractor.handleDeviceUnlockStatusChange()
        deviceEntryInteractor.handleDeviceEntryFromBiometricWhenAlreadyUnlocked()
        deviceEntryInteractor.handleDeviceEntryMetricsLogging()
    }

    private fun handlePowerState() {
        applicationScope.launch {
            powerInteractor.powerButtonLaunchEvents.collect {
                // If we were entered when the gesture started, we can unlock and return to Gone. We
                // also should do this if we launched while not entered, but can wake directly to
                // Gone (we should never end up Occluded in this case).
                if (
                    it == PowerButtonLaunchEvent.LAUNCH_FROM_ENTERED ||
                        wakeDirectlyToGoneInteractor.canWakeDirectlyToGone.value
                ) {
                    deviceUnlockedInteractor.unlockNowForPowerButtonGesture(
                        "double-tap power gesture arrived and we were asleep/waking from " +
                            "entered"
                    )
                    switchToScene(
                        targetSceneKey = Scenes.Gone,
                        loggingReason = "double-tap power gesture",
                        instantlySnapScenes = true,
                        forDoubleTapPowerGesture = true,
                    )
                } else if (it == PowerButtonLaunchEvent.LAUNCH_FROM_NOT_ENTERED) {
                    switchToScene(
                        Scenes.Occluded,
                        "double tap power while not entered when going to sleep",
                    )
                }
            }
        }
        applicationScope.launch {
            powerInteractor.isAsleep.collect { isAsleep ->
                if (isAsleep) {
                    alternateBouncerInteractor.hide()
                    dismissCallbackRegistry.notifyDismissCancelled()
                    val isAodAvailable = keyguardInteractor.isAodAvailable.value

                    switchToScene(
                        targetSceneKey = Scenes.Lockscreen,
                        loggingReason = "device is starting to sleep",
                        transitionKey = ToAlwaysOnDisplay.takeIf { isAodAvailable },
                        keyguardState = getKeyguardStateForWakefulness(isAwake = false),
                        freezeAndAnimateToCurrentState = true,
                    )
                } else {
                    if (wakeDirectlyToGoneInteractor.canWakeDirectlyToGone.value) {
                        switchToScene(
                            targetSceneKey = Scenes.Gone,
                            loggingReason =
                                "device is waking up while we can wake directly to gone",
                            // If we're waking directly to Gone from DOZING (no AOD), there's
                            // nothing visible on screen to animate out, so we should snap.
                            instantlySnapScenes = !keyguardInteractor.isAodAvailable.value,
                        )
                    } else if (
                        authenticationInteractor.get().authenticationMethod.value ==
                            AuthenticationMethodModel.Sim
                    ) {
                        sceneInteractor.showOverlay(
                            overlay = Overlays.Bouncer,
                            loggingReason = "device is starting to wake up with a locked sim",
                        )
                    } else if (
                        occlusionInteractor.isKeyguardOccluded.value &&
                            !keyguardInteractor.isDreaming.value
                    ) {
                        switchToScene(
                            targetSceneKey = Scenes.Occluded,
                            loggingReason = "device is waking up while occluded",
                        )
                    }
                }
            }
        }

        applicationScope.launch {
            // Mainly used for tests that are frequently changing keyguard enabled state. There is a
            // race condition on wake up, where checks for suppression happen on background threads
            // that lead to calls to wakeDirectlyToGoneInteractor.canWakeDirectlyToGone.value
            // retrieving the value too early. See [KeyguardEnabledInteractor#isKeyguardSuppressed]
            combine(
                    wakeDirectlyToGoneInteractor.shouldSuppressKeyguard,
                    wakeDirectlyToGoneInteractor.canWakeDirectlyToGone,
                    ::Pair,
                )
                .collect { (shouldSuppressKeyguard, canWakeDirectlyToGone) ->
                    if (shouldSuppressKeyguard && canWakeDirectlyToGone) {
                        switchToScene(
                            targetSceneKey = Scenes.Gone,
                            loggingReason = "keyguard suppressed and can wake to gone",
                            instantlySnapScenes = !keyguardInteractor.isAodAvailable.value,
                        )
                    }
                }
        }
    }

    private fun handleDisableFlags() {
        applicationScope.launch {
            launch {
                sceneInteractor.currentScene.collectLatest { currentScene ->
                    disabledContentInteractor.repeatWhenDisabled(currentScene) {
                        switchToScene(
                            targetSceneKey = SceneFamilies.Home,
                            loggingReason =
                                "Current scene ${currentScene.debugName} became" + " disabled",
                        )
                    }
                }
            }

            launch {
                sceneInteractor.currentOverlays.collectLatest { overlays ->
                    overlays.forEach { overlay ->
                        launch {
                            disabledContentInteractor.repeatWhenDisabled(overlay) {
                                sceneInteractor.hideOverlay(
                                    overlay = overlay,
                                    loggingReason =
                                        "Overlay ${overlay.debugName} became" + " disabled",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleDeviceEntryHapticsWhileDeviceNotGone() {
        applicationScope.launch {
            sceneInteractor.currentScene.collectLatest { currentScene ->
                // Only check for haptics signals before device is entered
                if (currentScene != Scenes.Gone) {
                    coroutineScope {
                        launch {
                            deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry.collect {
                                currentScene ->
                                if (Flags.msdlFeedback()) {
                                    msdlPlayer.playToken(
                                        MSDLToken.UNLOCK,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.vibrateAuthSuccess(
                                        "$TAG, $currentScene device-entry::success"
                                    )
                                }
                            }
                        }

                        launch {
                            deviceEntryHapticsInteractor.playErrorHaptic.collect { currentScene ->
                                if (Flags.msdlFeedback()) {
                                    msdlPlayer.playToken(
                                        MSDLToken.FAILURE,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.vibrateAuthError(
                                        "$TAG, $currentScene device-entry::error"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Keeps [SysUiState] up-to-date */
    private fun hydrateSystemUiState() {
        applicationScope.launch {
            combine(
                    sceneInteractor.transitionStateFlow
                        // Reconcile even when a cancelled transition returns to the same Idle.
                        .mapNotNull { it as? ObservableTransitionState.Idle },
                    sceneInteractor.isVisibleFlow,
                    shadePendingDisplayId,
                    sceneBackInteractor.backStack,
                    shadeModeInteractor.shadeMode,
                ) { idleState, isVisible, displayId, backStack, shadeMode ->
                    displayId to
                        SceneContainerPlugin.SceneContainerPluginState(
                            scene = idleState.currentScene,
                            sceneBehind = backStack.peek(),
                            overlays = idleState.currentOverlays,
                            isVisible = isVisible,
                            shadeMode = shadeMode,
                        )
                }
                .map { (displayId, sceneContainerPluginState) ->
                    displayId to
                        SceneContainerPluginImpl.EvaluatorByFlag.map { (flag, evaluator) ->
                            flag to evaluator(sceneContainerPluginState)
                        }
                }
                // The Scene plugin can override these computed values at setFlag time.
                // Deduplicate actual committed flags in SysUiState, not this expected flag map.
                .collect { (displayId: Int, flagMap: List<Pair<Long, Boolean>>) ->
                    sysuiStateInteractor.setFlagsExclusivelyToDisplay(
                        targetDisplayId = displayId,
                        stateChanges = StateChange.from(flagMap),
                    )
                }
        }
    }

    private fun hydrateWindowController() {
        applicationScope.launch {
            sceneInteractor.transitionStateFlow
                .map {
                    !it.isIdle(Scenes.Gone) ||
                        // We must be idle on Gone here, so we check if the overlays are empty
                        (it is ObservableTransitionState.Idle && it.currentOverlays.isNotEmpty())
                }
                .distinctUntilChanged()
                .collect { windowController.setNotificationShadeFocusable(it) }
        }

        applicationScope.launch {
            combine(
                    deviceEntryInteractor.isDeviceEntered,
                    sceneInteractor.transitionStateFlow,
                    ::Pair,
                )
                .map { (isDeviceEntered, transitionState) ->
                    !isDeviceEntered ||
                        transitionState.isTransitioningSets(
                            from = setOf(Scenes.Lockscreen, Scenes.Occluded, Overlays.Bouncer),
                            to = setOf(Scenes.Gone),
                        )
                }
                .distinctUntilChanged()
                .collect { windowController.setKeyguardShowing(it) }
        }

        applicationScope.launch {
            occlusionInteractor.isKeyguardOccluded.collect { isKeyguardOccluded ->
                windowController.setKeyguardOccluded(isKeyguardOccluded)
            }
        }
    }

    /** Collects and reports signals into the falsing system. */
    private fun collectFalsingSignals() {
        applicationScope.launch {
            deviceEntryInteractor.isDeviceEntered.collect { isLockscreenDismissed ->
                if (isLockscreenDismissed) {
                    falsingCollector.onSuccessfulUnlock()
                }
            }
        }

        applicationScope.launch {
            keyguardInteractor.isDozing.collect { isDozing ->
                falsingCollector.setShowingAod(isDozing)
            }
        }

        applicationScope.launch {
            powerInteractor.detailedWakefulness
                .distinctUntilChangedBy { it.isAwake() }
                .collect { wakefulness ->
                    when {
                        wakefulness.isAwakeFromTouch() -> falsingCollector.onScreenOnFromTouch()
                        wakefulness.isAwake() -> falsingCollector.onScreenTurningOn()
                        wakefulness.isAsleep() -> falsingCollector.onScreenOff()
                    }
                }
        }

        applicationScope.launch {
            sceneInteractor.currentOverlays
                .map { Overlays.Bouncer in it }
                .distinctUntilChanged()
                .collect { switchedToBouncerOverlay ->
                    if (switchedToBouncerOverlay) {
                        falsingCollector.onBouncerShown()
                    } else {
                        falsingCollector.onBouncerHidden()
                    }
                }
        }
    }

    /** Switches to the lockscreen when falsing is detected. */
    private fun respondToFalsingDetections() {
        applicationScope.launch {
            conflatedCallbackFlow {
                    val listener = FalsingBeliefListener { trySend(Unit) }
                    falsingManager.addFalsingBeliefListener(listener)
                    awaitClose { falsingManager.removeFalsingBeliefListener(listener) }
                }
                .collect {
                    val loggingReason = "Falsing detected."
                    switchToScene(targetSceneKey = Scenes.Lockscreen, loggingReason = loggingReason)
                }
        }
    }

    /** Keeps the interaction state of [CentralSurfaces] up-to-date. */
    private fun hydrateInteractionState() {
        applicationScope.launch {
            deviceUnlockedInteractor.deviceUnlockStatus
                .map { !it.isUnlocked }
                .flatMapLatest { isDeviceLocked ->
                    if (isDeviceLocked) {
                        sceneInteractor.transitionStateFlow
                            .mapNotNull { it as? ObservableTransitionState.Idle }
                            .map { it.currentScene to it.currentOverlays }
                            .distinctUntilChanged()
                            .map { (currentScene, currentOverlays) ->
                                when {
                                    // When locked, showing the lockscreen scene should be reported
                                    // as "interacting" while showing other scenes should report as
                                    // "not interacting".
                                    //
                                    // This is done here in order to match the legacy
                                    // implementation. The real reason why is lost to lore and myth.
                                    Overlays.NotificationsShade in currentOverlays -> false
                                    Overlays.QuickSettingsShade in currentOverlays -> null
                                    Overlays.Bouncer in currentOverlays -> false
                                    currentScene == Scenes.Lockscreen -> true
                                    currentScene == Scenes.Shade -> false
                                    else -> null
                                }
                            }
                    } else {
                        flowOf(null)
                    }
                }
                .collect { isInteractingOrNull ->
                    isInteractingOrNull?.let { isInteracting ->
                        centralSurfaces?.setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            isInteracting,
                        )
                    }
                }
        }
    }

    private fun handleBouncerOverscroll() {
        applicationScope.launch {
            sceneInteractor.transitionStateFlow
                // Only consider transitions.
                .filterIsInstance<ObservableTransitionState.Transition>()
                // Only consider user-initiated (e.g. drags) that go from bouncer to lockscreen.
                .filter { transition ->
                    transition.fromContent == Overlays.Bouncer &&
                        transition.toContent == Scenes.Lockscreen &&
                        transition.isInitiatedByUserInput
                }
                .flatMapLatest { it.progress }
                // Figure out the direction of scrolling.
                .map { progress ->
                    when {
                        progress > 0 -> 1
                        progress < 0 -> -1
                        else -> 0
                    }
                }
                .distinctUntilChanged()
                // Only consider negative scrolling, AKA overscroll.
                .filter { it == -1 }
                .collect { faceUnlockInteractor.onSwipeUpOnBouncer() }
        }
    }

    private fun handleOcclusion() {
        DriveDreamStateFromOcclusion.assertInLegacyMode()
        applicationScope.launch {
            occlusionInteractor.isKeyguardOccluded
                .sample(
                    combine(
                        keyguardInteractor.isDreamingNotDozing,
                        sceneBackInteractor.backScene,
                        powerInteractor.isAwake,
                        ::Triple,
                    )
                ) { occluded, (dreaming, backScene, isAwake) ->
                    Quad(occluded, dreaming, backScene, isAwake)
                }
                .collect { (occluded, dreaming, backScene, isAwake) ->
                    // Dreaming is a special case where the keyguard is occluded, and is handled
                    // separately. See [handleDreamState].
                    if (occluded && !dreaming) {
                        // This does not use the scene family to resolve, as there is a race
                        // condition when they both update state based off of the isKeyguardOccluded
                        // value.
                        switchToScene(Scenes.Occluded, "isKeyguardOccluded == true")
                    } else if (sceneInteractor.currentScene.value == Scenes.Occluded) {
                        if (backScene == Scenes.Communal) {
                            switchToScene(Scenes.Communal, "unoccluded and previously on communal")
                        } else if (deviceEntryInteractor.isDeviceEntered.value) {
                            switchToScene(Scenes.Gone, "unoccluded and device entered")
                        } else if (
                            sceneInteractor.currentOverlays.value.contains(Overlays.Bouncer)
                        ) {
                            // We've unoccluded while the bouncer was showing over an occluding
                            // activity. This can happen if the occluding activity crashed or
                            // finished itself behind the bouncer. It can also happen if a CTS
                            // test/very adversarial user launched a non-SHOW_WHEN_LOCKED activity
                            // with FLAG_DISMISS_KEYGUARD over a SHOW_WHEN_LOCKED activity. In that
                            // case, FLAG_DISMISS_KEYGUARD will cause the bouncer to show, but then
                            // the lack of SHOW_WHEN_LOCKED will cause WM to kill the activity. CTS
                            // tests expect to be able to enter the PIN and unlock the device in
                            // this case, so leave the bouncer visible.
                            switchToScene(
                                Scenes.Lockscreen,
                                "unoccluded and device not entered, " +
                                    "bouncer was showing; leaving it up",
                                hideOverlays = HideOverlayCommand.HideNone,
                                keyguardState = getKeyguardStateForWakefulness(isAwake),
                            )
                        } else {
                            switchToScene(
                                Scenes.Lockscreen,
                                "unoccluded and device not entered",
                                keyguardState = getKeyguardStateForWakefulness(isAwake),
                            )
                        }
                    }
                    if (!occluded) {
                        sceneBackInteractor.removeOccludedSceneOnBackStack(
                            reason = "removing occluded from backstack, if present"
                        )
                    }
                }
        }
    }

    private fun getOcclusionTargetScene(
        showWhenLockedActivityInfo: ShowWhenLockedActivityInfoModel,
        occlusionState: OcclusionStateModel,
    ): SceneKey? {
        // Use showWhenLocked activity info instead of OcclusionStateModel here to also
        // handle the case where the dream is showing and the device is unlocked / keyguard
        // is not showing.
        val isDream = showWhenLockedActivityInfo.isDream()
        val isAppOccluded = occlusionState == OcclusionStateModel.APP

        return when {
            isDream -> Scenes.Dream
            isAppOccluded -> Scenes.Occluded
            else -> null
        }
    }

    private fun transitionToDream() {
        val currentScene = sceneInteractor.currentScene.value
        val currentOverlays = sceneInteractor.currentOverlays.value

        if (currentScene == Scenes.Lockscreen && Overlays.Bouncer in currentOverlays) {
            switchToScene(
                targetSceneKey = Scenes.Dream,
                loggingReason = "Snap to dream behind bouncer",
                hideOverlays = HideOverlayCommand.HideNone,
                instantlySnapScenes = true,
            )
            sceneInteractor.hideOverlay(
                overlay = Overlays.Bouncer,
                loggingReason = "Hiding bouncer to reveal dream",
            )
        } else {
            switchToScene(targetSceneKey = Scenes.Dream, loggingReason = "Dream started")
        }
    }

    private fun handleUnocclude(backScene: SceneKey?, isAwake: Boolean) {
        val currentScene = sceneInteractor.currentScene.value
        val currentOverlays = sceneInteractor.currentOverlays.value
        val isDeviceEntered = deviceEntryInteractor.isDeviceEntered.value
        val isDream = currentScene == Scenes.Dream
        val isOccluded = currentScene == Scenes.Occluded

        if (isDream || isOccluded) {
            val targetScene =
                when {
                    isDeviceEntered -> Scenes.Gone
                    backScene == Scenes.Communal -> Scenes.Communal
                    else -> Scenes.Lockscreen
                }

            val instantlySnapScenes = Overlays.Bouncer in currentOverlays
            val hideOverlays =
                if (instantlySnapScenes) {
                    HideOverlayCommand.HideNone
                    // TODO(b/495429533): Refactor to remove usage of legacy
                    // isKeyguardShowing flow.
                } else if (isDream && keyguardInteractor.isKeyguardShowing.value) {
                    HideOverlayCommand.HideNone
                } else {
                    HideOverlayCommand.HideAll
                }

            switchToScene(
                targetSceneKey = targetScene,
                loggingReason = if (isDream) "Dream stopped" else "App occlusion stopped",
                instantlySnapScenes = instantlySnapScenes,
                hideOverlays = hideOverlays,
                keyguardState =
                    if (!isDeviceEntered) getKeyguardStateForWakefulness(isAwake) else null,
            )
        }

        sceneBackInteractor.removeOccludedSceneOnBackStack(
            reason = "removing occluded from backstack, if present"
        )
    }

    private fun handleOcclusionAndDreaming() {
        if (DriveDreamStateFromOcclusion.isUnexpectedlyInLegacyMode()) {
            return
        }
        applicationScope.launch {
            combine(
                    occlusionInteractor.showWhenLockedActivityInfo,
                    occlusionInteractor.occlusionState,
                    ::getOcclusionTargetScene,
                )
                .distinctUntilChanged()
                .sample(
                    combine(sceneBackInteractor.backScene, powerInteractor.isAwake, ::Pair),
                    ::Pair,
                )
                .collect { (nextTarget, sampledValues) ->
                    val (backScene, isAwake) = sampledValues

                    when (nextTarget) {
                        Scenes.Dream -> transitionToDream()
                        Scenes.Occluded -> {
                            switchToScene(
                                targetSceneKey = Scenes.Occluded,
                                loggingReason = "occlusionState is APP",
                            )
                        }
                        null -> handleUnocclude(backScene, isAwake)
                    }
                }
        }
    }

    private fun handleKeyguardEnabledness() {
        // Automatically switches scenes when keyguard is enabled or disabled, as needed.
        applicationScope.launch {
            keyguardEnabledInteractor.isKeyguardEnabled
                .filter { enabled -> !enabled }
                .sample(deviceUnlockedInteractor.isInLockdown)
                .collect { inLockdown ->
                    if (!inLockdown && !deviceEntryInteractor.isDeviceEntered.value) {
                        switchToScene(Scenes.Gone, "Keyguard was disabled")
                    }
                }
        }
    }

    private fun switchToScene(
        targetSceneKey: SceneKey,
        loggingReason: String,
        transitionKey: TransitionKey? = null,
        keyguardState: KeyguardState? = null,
        freezeAndAnimateToCurrentState: Boolean = false,
        hideOverlays: HideOverlayCommand = HideOverlayCommand.HideAll,
        instantlySnapScenes: Boolean = false,
        forDoubleTapPowerGesture: Boolean = false,
    ) {
        if (instantlySnapScenes) {
            if (forDoubleTapPowerGesture) {
                // Special case to skip validation, since unlock flows may not emit by the time the
                // scene transition starts.
                sceneInteractor.snapToGoneForUnlockedPowerLaunchGesture(
                    keyguardState = keyguardState,
                    loggingReason = loggingReason,
                    hideOverlays = hideOverlays,
                )
            } else {
                sceneInteractor.snapToScene(
                    toScene = targetSceneKey,
                    keyguardState = keyguardState,
                    loggingReason = loggingReason,
                    hideOverlays = hideOverlays,
                )
            }
        } else {
            sceneInteractor.changeScene(
                toScene = targetSceneKey,
                loggingReason = loggingReason,
                transitionKey = transitionKey,
                keyguardState = keyguardState,
                forceSettleToTargetScene = freezeAndAnimateToCurrentState,
                hideOverlays = hideOverlays,
            )
        }
    }

    private fun hydrateBackStack() {
        applicationScope.launch {
            sceneInteractor.currentScene.pairwise().collect { (from, to) ->
                sceneBackInteractor.onSceneChange(from = from, to = to)
                if (
                    to == Scenes.Shade &&
                        statusBarStateController.leaveOpenOnKeyguardHide() &&
                        !deviceEntryInteractor.isAuthenticationRequired()
                ) {
                    sceneBackInteractor.replaceLockscreenSceneOnBackStack(
                        reason =
                            "onSceneChange to Shade in an unlocked state with a pending request" +
                                " to show shade on keyguard hide (leaveOpenOnKeyguardHide=true)"
                    )
                }
            }
        }
    }

    private fun notifyKeyguardDismissCancelledCallbacks() {
        applicationScope.launch {
            combine(deviceEntryInteractor.isUnlocked, sceneInteractor.currentOverlays.pairwise()) {
                    isUnlocked,
                    overlayChange ->
                    val difference = overlayChange.previousValue - overlayChange.newValue
                    !isUnlocked &&
                        sceneInteractor.currentScene.value != Scenes.Gone &&
                        Overlays.Bouncer in difference
                }
                .collect { notifyKeyguardDismissCancelled ->
                    if (notifyKeyguardDismissCancelled) {
                        dismissCallbackRegistry.notifyDismissCancelled()
                    }
                }
        }
    }

    /**
     * Wires the scene framework to activity transition animations that originate from anywhere. A
     * subset of these may actually originate from UI inside one of the scenes in the framework.
     *
     * Telling the scene framework about ongoing activity transition animations is critical so the
     * scene framework doesn't make its scene container invisible during a transition.
     *
     * As it turns out, making the scene container view invisible during a transition animation
     * disrupts the animation and causes interaction jank CUJ tracking to ignore reports of the CUJ
     * ending or being canceled.
     */
    private fun hydrateActivityTransitionAnimationState() {
        activityTransitionAnimator.addListener(
            object : ActivityTransitionAnimator.Listener {
                override fun onTransitionAnimationStart() {
                    sceneInteractor.onTransitionAnimationStart()
                }

                override fun onTransitionAnimationEnd(transaction: SurfaceControl.Transaction) {
                    sceneInteractor.onTransitionAnimationEnd()
                }
            }
        )
    }

    private fun lockWhenDeviceBecomesUntrusted() {
        applicationScope.launch {
            trustInteractor.isTrusted.pairwise().collect { (wasTrusted, isTrusted) ->
                if (wasTrusted && !isTrusted && !deviceEntryInteractor.isDeviceEntered.value) {
                    deviceEntryInteractor.lockNow(
                        "Exited trusted environment while not device not entered"
                    )
                }
            }
        }
    }

    private fun lockWhenKeyguardShowWhenAwake() {
        applicationScope.launch {
            keyguardShowWhileAwakeInteractor.showWhileAwakeEvents
                .filter {
                    it == ShowWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON ||
                        it == ShowWhileAwakeReason.KEYGUARD_REENABLED
                }
                .collect { reason ->
                    // If keyguard is enabled, lock and switch to Lockscreen scene if needed.
                    // If it's not enabled, it'll be re-shown when it's enabled again.
                    if (keyguardEnabledInteractor.isKeyguardEnabled.value) {
                        deviceEntryInteractor.lockNow("Screen timed out or WM#lockNow() called")

                        val isOccludedOrDreaming =
                            if (DriveDreamStateFromOcclusion.isEnabled) {
                                getOcclusionTargetScene(
                                    occlusionInteractor.showWhenLockedActivityInfo.value,
                                    occlusionInteractor.occlusionState.value,
                                ) != null
                            } else {
                                val dreamingNotDozing = keyguardInteractor.isDreamingNotDozing.value
                                val isOccluded = occlusionInteractor.isKeyguardOccluded.value
                                dreamingNotDozing || isOccluded
                            }

                        // If we're dreaming/occluded, DreamStartable or handleOcclusionAndDreaming
                        // will take us to Scenes.Dream or Scenes.Occluded. In this case, avoid
                        // forcing the lockscreen scene as this would result in the lockscreen
                        // displaying over the occluding activity.
                        if (!isOccludedOrDreaming) {
                            switchToScene(Scenes.Lockscreen, "Not dreaming/occluded, and $reason")
                        }
                    }
                }
        }
    }

    /**
     * Handles showing the keyguard (but *not* locking) when a foldable device is folded when the
     * "swipe up to continue using apps on fold" (or whatever that setting is currently called) is
     * enabled.
     *
     * This should only happen if we're enabled, and unlike other reasons for showing keyguard while
     * it's disabled, should not cause us to re-show keyguard when it's re-enabled if it was
     * disabled when this request came in (since this wasn't explicitly a request to secure the
     * device).
     */
    private fun showDismissibleKeyguardWhenFolded() {
        applicationScope.launch {
            keyguardShowWhileAwakeInteractor.showWhileAwakeEvents
                .filter { it == ShowWhileAwakeReason.FOLDED_WITH_SWIPE_UP_TO_CONTINUE }
                .collect {
                    if (
                        keyguardEnabledInteractor.isKeyguardEnabled.value &&
                            !occlusionInteractor.isKeyguardOccluded.value
                    ) {
                        switchToScene(Scenes.Lockscreen, "folded with swipe up to continue")
                    }
                }
        }
    }

    /**
     * Wake up the device if we're dozing and no longer displaying the lockscreen Scene. This
     * includes both Scene and Overlay transitions.
     */
    private fun wakeFromDozingOnContentChange() {
        applicationScope.launch {
            launch {
                sceneInteractor.transitionStateFlow
                    .filter {
                        it.isTransitioning(from = Scenes.Lockscreen) ||
                            !it.isIdle(Scenes.Lockscreen)
                    }
                    .distinctUntilChanged()
                    .collect {
                        powerInteractor.wakeUpIfDozing(
                            "Wake-up from dozing. Transitioning away from Scenes.Lockscreen",
                            PowerManager.WAKE_REASON_GESTURE,
                        )
                    }
            }
        }
    }

    private fun maybeShowLockscreenOnStart() {
        // This needs to happen immediately upon start(), we can't wait for onSystemReady,
        // onBootCompleted, or any more reasonable events, since otherwise unlocked app content
        // may be visible during boot. Once those events come through, the
        // WindowManagerLockscreenVisibilityViewModel will take over.
        windowManagerLockscreenVisibilityManager.setLockscreenShowing(
            bootInteractor.showLockscreenOnBoot(),
            "initial lockscreen visibility on start()",
        )
    }

    /**
     * Helper to return the appropriate keyguard state given the current wakefulness of the device.
     */
    private fun getKeyguardStateForWakefulness(isAwake: Boolean): KeyguardState {
        return if (isAwake) {
            KeyguardState.LOCKSCREEN
        } else {
            keyguardInteractor.asleepKeyguardState.value
        }
    }

    @VisibleForTesting
    fun hydrateLockScreenUserManager() {
        applicationScope.launch {
            deviceUnlockedInteractor.deviceUnlockStatus
                .distinctUntilChanged { old, new -> old.isUnlocked == new.isUnlocked }
                .collectLatest { unlockStatus ->
                    if (unlockStatus.isUnlocked) {
                        // If the device has just become unlocked and keyguard will be going away,
                        // wait for transition to complete before notifying Notifications to avoid
                        // a flicker during the unlock animation: b/454362854
                        if (unlockStatus.deviceUnlockSource?.dismissesLockscreen == true) {
                            snapshotFlow { !onOrLeavingKeyguard() || onNotifShadeOverlay() }
                                .first { it }
                        }
                    }
                    // If the device has just become locked, notify Notifications so they can make
                    // sure redaction is immediately applied: b/440335509
                    lockscreenUserManager.updatePublicMode()
                }
        }
    }

    /**
     * Returns `true` if currently on the keyguard (defined as the `Lockscreen` scene or `Bouncer`
     * overlay), or if leaving the keyguard; `false` otherwise
     */
    private fun onOrLeavingKeyguard(): Boolean {
        return with(sceneInteractor.transitionState) {
            isIdle(Scenes.Lockscreen) ||
                isTransitioning(from = Scenes.Lockscreen) ||
                isIdle(Overlays.Bouncer) ||
                isTransitioning(from = Overlays.Bouncer)
        }
    }

    private fun onNotifShadeOverlay() =
        Overlays.NotificationsShade in sceneInteractor.transitionState.currentOverlays &&
            !sceneInteractor.transitionState.isTransitioning(from = Overlays.NotificationsShade)

    private suspend fun repeatWhen(condition: Flow<Boolean>, block: suspend () -> Unit) {
        condition.distinctUntilChanged().collectLatest { conditionMet ->
            if (conditionMet) {
                block()
            }
        }
    }

    sealed interface SwitchSceneCommand {
        data object NoOp : SwitchSceneCommand

        data class SwitchToScene(
            val targetSceneKey: SceneKey,
            val loggingReason: String,
            val hideOverlays: HideOverlayCommand = HideOverlayCommand.HideAll,
            val instantlySnapScenes: Boolean = false,
            val transitionKey: TransitionKey? = null,
        ) : SwitchSceneCommand
    }

    companion object {
        private const val TAG = "SceneContainerStartable"
    }
}
