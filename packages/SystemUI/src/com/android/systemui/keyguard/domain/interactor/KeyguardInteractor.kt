/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.keyguard.domain.interactor

import android.app.StatusBarManager
import android.graphics.Point
import android.util.Log
import android.util.MathUtils
import androidx.compose.runtime.Stable
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.CameraLaunchType
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.data.repository.ShadeConfigRepository
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.sample
import com.android.systemui.wallpapers.domain.interactor.WallpaperFocalAreaInteractor
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Encapsulates business-logic related to the keyguard but not to a more specific part within it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Stable
@SysUISingleton
class KeyguardInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
    bouncerRepository: KeyguardBouncerRepository,
    private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    shadeRepository: ShadeRepository,
    shadeConfigRepository: ShadeConfigRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    sceneInteractorProvider: Provider<SceneInteractor>,
    private val fromGoneTransitionInteractor: Provider<FromGoneTransitionInteractor>,
    private val fromLockscreenTransitionInteractor: Provider<FromLockscreenTransitionInteractor>,
    private val fromOccludedTransitionInteractor: Provider<FromOccludedTransitionInteractor>,
    private val fromDreamingTransitionInteractor: Provider<FromDreamingTransitionInteractor>,
    private val fromAlternateBouncerTransitionInteractor:
        Provider<FromAlternateBouncerTransitionInteractor>,
    private val lockPatternUtils: LockPatternUtils,
    @Application applicationScope: CoroutineScope,
) {
    // TODO(b/296118689): move to a repository
    private val _notificationPlaceholderBounds = MutableStateFlow(NotificationContainerBounds())

    /** Bounds of the notification container. */
    val notificationContainerBounds: StateFlow<NotificationContainerBounds> by lazy {
        SceneContainerFlag.assertInLegacyMode()
        combineTransform(
                _notificationPlaceholderBounds,
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = LOCKSCREEN, to = AOD)
                ),
                shadeConfigRepository.legacyUseSplitShade,
                configurationInteractor.dimensionPixelSize(R.dimen.keyguard_split_shade_top_margin),
            ) { bounds, isTransitioningToAod, useSplitShade, keyguardSplitShadeTopMargin ->
                if (isTransitioningToAod) {
                    // Keep bounds stable during this transition, to prevent cases like smartspace
                    // popping in and adjusting the bounds. A prime example would be media playing,
                    // which then updates smartspace on transition to AOD.
                    return@combineTransform
                }

                // We offset the placeholder bounds by the configured top margin to account for
                // legacy placement behavior within notifications for splitshade.
                emit(
                    if (useSplitShade) {
                        bounds.copy(bottom = bounds.bottom - keyguardSplitShadeTopMargin)
                    } else {
                        bounds
                    }
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = NotificationContainerBounds(),
            )
    }

    fun setNotificationContainerBounds(position: NotificationContainerBounds) {
        SceneContainerFlag.assertInLegacyMode()
        _notificationPlaceholderBounds.value = position
    }

    /** Whether the system is in doze mode. */
    val isDozing: StateFlow<Boolean> = repository.isDozing

    /** Receive an event for doze time tick */
    val dozeTimeTick: Flow<Long> = repository.dozeTimeTick

    /** Whether Always-on Display mode is available. */
    val isAodAvailable: StateFlow<Boolean> = repository.isAodAvailable

    /**
     * The amount of doze the system is in, where `1.0` is fully dozing and `0.0` is not dozing at
     * all.
     */
    val dozeAmount: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            isAodAvailable.flatMapLatest { isAodAvailable ->
                combine(
                    keyguardTransitionInteractor.transitionValue(
                        if (isAodAvailable) AOD else DOZING
                    ),
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(from = Scenes.Occluded, to = AOD)
                    ),
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(from = AOD, to = Scenes.Occluded)
                    ),
                ) { dozeAmt, isTransitioningToAod, isTransitioningFromAod ->
                    if (isTransitioningToAod || isTransitioningFromAod) {
                        return@combine 1f
                    } else {
                        return@combine dozeAmt
                    }
                }
            }
        } else {
            isAodAvailable.flatMapLatest { isAodAvailable ->
                keyguardTransitionInteractor.transitionValue(if (isAodAvailable) AOD else DOZING)
            }
        }

    /** Doze transition information. */
    val dozeTransitionModel: StateFlow<DozeTransitionModel> = repository.dozeTransitionModel

    val isPulsing: Flow<Boolean> = dozeTransitionModel.map { it.to == DozeStateModel.DOZE_PULSING }

    /** Whether the system is dreaming with an overlay active */
    val isDreamingWithOverlay: Flow<Boolean> = repository.isDreamingWithOverlay

    /**
     * Whether the system is dreaming. [KeyguardRepository.isDreaming] will be always be true when
     * [isDozing] is true, but not vice-versa. Also accounts for [isDreamingWithOverlay].
     */
    val isDreaming: StateFlow<Boolean> =
        merge(repository.isDreaming, repository.isDreamingWithOverlay)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /** Whether any dreaming is running, including the doze dream. */
    val isDreamingAny: Flow<Boolean> = repository.isDreaming

    /** Event for when the camera gesture is detected */
    val onCameraLaunchDetected: Flow<CameraLaunchSourceModel> =
        repository.onCameraLaunchDetected.filter { it.type != CameraLaunchType.IGNORE }

    /** Event for when an unlocked keyguard has been requested, such as on device fold */
    val showDismissibleKeyguard: Flow<Long> = repository.showDismissibleKeyguard.asStateFlow()

    /**
     * Whether device is in screensaver dream mode.
     *
     * Dozing and dreaming have overlapping events, because doze is a type of dream. This flow
     * reflects the screen on / interactive type of dream, also known as screensavers, and not doze
     * or AOD (always-on-display).
     *
     * Allow a brief moment to prevent rapidly oscillating between true/false signals. The amount of
     * time is [IS_DREAMING_NOT_DOZING_DELAY_MS] - consumers should consider waiting for that long
     * before examining the value of this flow, to let other consumers have enough time to also see
     * that same new value.
     */
    @OptIn(FlowPreview::class)
    val isDreamingNotDozing: StateFlow<Boolean> =
        dozeTransitionModel
            .flatMapLatest { dozeTransitionModel ->
                if (isDozeOff(dozeTransitionModel.to)) {
                    // When dozing stops, it is a very early signal that the device is exiting the
                    // dream state. DreamManagerService eventually notifies window manager, which
                    // invokes SystemUI through KeyguardService. Because of this substantial delay,
                    // do not immediately process any dreaming information when exiting AOD. It
                    // should actually be quite strange to leave AOD and then go straight to
                    // DREAMING so this should be fine.
                    delay(IS_DREAMING_NOT_DOZING_DELAY_MS)
                    isDreaming.debounce(50L)
                } else {
                    flowOf(false)
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /** Whether the keyguard is showing or not. */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState")
    val isKeyguardShowing: StateFlow<Boolean> = repository.isKeyguardShowing

    /**
     * Whether the keyguard is unlocked or not. This is always true when keyguard has been dismissed
     * or can be dismissed by a swipe.
     */
    val isKeyguardDismissible: StateFlow<Boolean> = repository.isKeyguardDismissible

    /**
     * Whether device entry believes the device is trusted. This can be true or false when keyguard
     * has been dismissed depending on biometric and trust states.
     */
    val hasTrust: StateFlow<Boolean> = repository.hasTrust

    /** Whether the keyguard is occluded (covered by an activity). */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState.OCCLUDED")
    val isKeyguardOccluded: StateFlow<Boolean> = repository.isKeyguardOccluded

    /** Whether the keyguard is going away. */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState.GONE")
    val isKeyguardGoingAway: SharedFlow<Boolean> = repository.isKeyguardGoingAway.asSharedFlow()

    /** Keyguard can be clipped at the top as the shade is dragged */
    val topClippingBounds: Flow<Int?> by lazy {
        combineTransform(
                keyguardTransitionInteractor
                    .transitionValue(content = Scenes.Gone, stateWithoutSceneContainer = GONE)
                    .map { it == 1f }
                    .onStart { emit(false) }
                    .distinctUntilChanged(),
                repository.topClippingBounds,
            ) { isGone, topClippingBounds ->
                if (!isGone) {
                    emit(topClippingBounds)
                }
            }
            .distinctUntilChanged()
    }

    /** Last point that [KeyguardRootView] view was tapped */
    val lastRootViewTapPosition: Flow<Point?> = repository.lastRootViewTapPosition.asStateFlow()

    /** Is the ambient indication area visible? */
    val ambientIndicationVisible: Flow<Boolean> = repository.ambientIndicationVisible.asStateFlow()

    /** Whether the primary bouncer is showing or about to show soon. */
    @JvmField
    val primaryBouncerShowing: StateFlow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
                sceneInteractorProvider.get().transitionStateFlow.map {
                    it.isIdle(Overlays.Bouncer) || it.isTransitioning(to = Overlays.Bouncer)
                }
            } else {
                combine(
                    bouncerRepository.primaryBouncerShow,
                    bouncerRepository.primaryBouncerShowingSoon,
                ) { showing, showingSoon ->
                    showing || showingSoon
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether the alternate bouncer is showing or not. */
    val alternateBouncerShowing: StateFlow<Boolean> = bouncerRepository.alternateBouncerVisible

    /** Observable for the [StatusBarState] */
    val statusBarState: StateFlow<StatusBarState> = repository.statusBarState

    /** Observable for [BiometricUnlockModel] when biometrics are used to unlock the device. */
    val biometricUnlockState: StateFlow<BiometricUnlockModel> = repository.biometricUnlockState

    /** Keyguard is present and is not occluded. */
    val isKeyguardVisible: Flow<Boolean> =
        combine(isKeyguardShowing, isKeyguardOccluded) { showing, occluded -> showing && !occluded }

    /**
     * Event types that affect whether secure camera is active. Only used by [isSecureCameraActive].
     */
    private enum class SecureCameraRelatedEventType {
        KeyguardBecameVisible,
        PrimaryBouncerBecameVisible,
        SecureCameraLaunched,
        InGoneState,
    }

    /** Whether camera is launched over keyguard. */
    val isSecureCameraActive: Flow<Boolean> =
        merge(
                onCameraLaunchDetected
                    .filter { it.type == CameraLaunchType.POWER_DOUBLE_TAP && it.isSecureCamera }
                    .map { SecureCameraRelatedEventType.SecureCameraLaunched },
                isKeyguardVisible
                    .filter { it }
                    .map { SecureCameraRelatedEventType.KeyguardBecameVisible },
                primaryBouncerShowing
                    .filter { it }
                    .map { SecureCameraRelatedEventType.PrimaryBouncerBecameVisible },
                keyguardTransitionInteractor
                    .transitionValue(content = Scenes.Gone, stateWithoutSceneContainer = GONE)
                    .filter { it == 1f }
                    .map { SecureCameraRelatedEventType.InGoneState },
            )
            .map {
                when (it) {
                    SecureCameraRelatedEventType.SecureCameraLaunched -> true
                    // When secure camera is closed, either the keyguard or the primary bouncer will
                    // have to show, so those events tell us that secure camera is no longer active.
                    SecureCameraRelatedEventType.KeyguardBecameVisible -> false
                    SecureCameraRelatedEventType.PrimaryBouncerBecameVisible -> false
                    SecureCameraRelatedEventType.InGoneState -> false
                }
            }
            .distinctUntilChanged()
            .onEach { Log.v(TAG, "isSecureCameraActive: $it") }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** The approximate location on the screen of the fingerprint sensor, if one is available. */
    val fingerprintSensorLocation: Flow<Point?> = repository.fingerprintSensorLocation

    /** The approximate location on the screen of the face unlock sensor, if one is available. */
    val faceSensorLocation: Flow<Point?> = repository.faceSensorLocation

    /** Temporary shim for fading out content when the brightness slider is used */
    @Deprecated("SceneContainer uses NotificationStackAppearanceInteractor")
    val panelAlpha: StateFlow<Float> = repository.panelAlpha.asStateFlow()

    /** Sets the zoom out scale of spatial model pushback from e.g. pulling down the shade. */
    val zoomOut: StateFlow<Float> = repository.zoomOut

    /**
     * When the lockscreen can be dismissed, emit an alpha value as the user swipes up. This is
     * useful just before the code commits to moving to GONE.
     *
     * This uses legacyShadeExpansion to process swipe up events. In the future, the touch input
     * signal should be sent directly to transitions.
     */
    val dismissAlpha: Flow<Float> =
        shadeRepository.legacyShadeExpansion
            .sample(
                keyguardTransitionInteractor.isFinishedIn(Scenes.Communal, GLANCEABLE_HUB),
                ::Pair,
            )
            .filter {
                !keyguardTransitionInteractor.transitionState.value.transitionState
                    .isTransitioning()
            }
            .transform { (legacyShadeExpansion, onGlanceableHub) ->
                val currentKeyguardState = keyguardTransitionInteractor.currentKeyguardState.value
                val isKeyguardDismissible = isKeyguardDismissible.value

                if (shadeRepository.qsExpansion.value > 0f) return@transform
                if (
                    statusBarState.value == StatusBarState.KEYGUARD &&
                        isKeyguardDismissible &&
                        currentKeyguardState == LOCKSCREEN &&
                        legacyShadeExpansion != 1f
                ) {
                    emit(MathUtils.constrainedMap(0f, 1f, 0.82f, 1f, legacyShadeExpansion))
                } else if (
                    !onGlanceableHub &&
                        isKeyguardDismissible &&
                        (legacyShadeExpansion == 0f || legacyShadeExpansion == 1f)
                ) {
                    // Resets alpha state
                    emit(1f)
                }
            }
            .onStart { emit(1f) }
            .distinctUntilChanged()

    val keyguardTranslationY: Flow<Float> =
        if (SceneContainerFlag.isEnabled) flowOf(0f)
        else {
            configurationInteractor
                .dimensionPixelSize(R.dimen.keyguard_translate_distance_on_swipe_up)
                .flatMapLatest { translationDistance ->
                    combineTransform(
                        shadeRepository.legacyShadeExpansion.onStart { emit(0f) },
                        keyguardTransitionInteractor.transitionValue(GONE).onStart { emit(0f) },
                    ) { legacyShadeExpansion, goneValue ->
                        val isLegacyShadeInResetPosition =
                            legacyShadeExpansion == 0f || legacyShadeExpansion == 1f
                        if (goneValue == 1f || (goneValue == 0f && isLegacyShadeInResetPosition)) {
                            // Reset the translation value
                            emit(0f)
                        } else if (!isLegacyShadeInResetPosition) {
                            // On swipe up, translate the keyguard to reveal the bouncer, OR a GONE
                            // transition is running, which means this is a swipe to dismiss. Values
                            // of
                            // 0f and 1f need to be ignored in the legacy shade expansion. These can
                            // flip arbitrarily as the legacy shade is reset, and would cause the
                            // translation value to jump around unexpectedly.
                            emit(MathUtils.lerp(translationDistance, 0, legacyShadeExpansion))
                        }
                    }
                }
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = 0f,
                )
        }

    /** Whether to animate the next doze mode transition. */
    val animateDozingTransitions: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isEnabled) {
            sceneInteractorProvider
                .get()
                .transitioningTo
                .map { it == Scenes.Lockscreen }
                .distinctUntilChanged()
                .flatMapLatest { isTransitioningToLockscreenScene ->
                    if (isTransitioningToLockscreenScene) {
                        flowOf(false)
                    } else {
                        repository.animateBottomAreaDozingTransitions
                    }
                }
        } else {
            repository.animateBottomAreaDozingTransitions
        }
    }

    /** Which keyguard state to use when the device goes to sleep. */
    val asleepKeyguardState: StateFlow<KeyguardState> =
        repository.isAodAvailable
            .map { aodAvailable -> if (aodAvailable) AOD else DOZING }
            .stateIn(applicationScope, SharingStarted.Eagerly, DOZING)

    /**
     * Whether the primary authentication is required for the given user due to lockdown or
     * encryption after reboot.
     */
    val isEncryptedOrLockdown: Flow<Boolean> = repository.isEncryptedOrLockdown

    /** Whether to enable "Sign out" button on keyguard's status bar */
    val isSignOutButtonOnStatusBarEnabled: Boolean
        get() = repository.isSignOutButtonOnStatusBarEnabledInConfig

    fun dozeTransitionTo(vararg states: DozeStateModel): Flow<DozeTransitionModel> {
        return dozeTransitionModel.filter { states.contains(it.to) }
    }

    fun isKeyguardCurrentlyShowing(): Boolean {
        return repository.isKeyguardShowing()
    }

    private fun cameraLaunchSourceIntToType(value: Int): CameraLaunchType {
        return when (value) {
            StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE -> CameraLaunchType.WIGGLE
            StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP ->
                CameraLaunchType.POWER_DOUBLE_TAP
            StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER -> CameraLaunchType.LIFT_TRIGGER
            StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE ->
                CameraLaunchType.QUICK_AFFORDANCE
            StatusBarManager.CAMERA_LAUNCH_SOURCE_SCREEN_GESTURE -> CameraLaunchType.SCREEN_GESTURE
            else -> throw IllegalArgumentException("Invalid CameraLaunchType value: $value")
        }
    }

    /** Sets whether quick settings or quick-quick settings is visible. */
    fun setQuickSettingsVisible(isVisible: Boolean) {
        repository.setQuickSettingsVisible(isVisible)
    }

    fun setPanelAlpha(alpha: Float) {
        repository.setPanelAlpha(alpha)
    }

    fun setZoomOut(zoomOutFromShadeRadius: Float) {
        repository.setZoomOut(zoomOutFromShadeRadius)
    }

    fun setAnimateDozingTransitions(animate: Boolean) {
        repository.setAnimateDozingTransitions(animate)
    }

    fun setLastRootViewTapPosition(point: Point?) {
        repository.lastRootViewTapPosition.value = point
    }

    fun setAmbientIndicationVisible(isVisible: Boolean) {
        repository.ambientIndicationVisible.value = isVisible
    }

    fun keyguardDoneAnimationsFinished() {
        repository.keyguardDoneAnimationsFinished()
    }

    fun setTopClippingBounds(top: Int?) {
        repository.topClippingBounds.value = top
    }

    fun setDreaming(isDreaming: Boolean) {
        repository.setDreaming(isDreaming)
    }

    /** Temporary shim, until [KeyguardWmStateRefactor] is enabled */
    fun showKeyguard() {
        fromGoneTransitionInteractor.get().showKeyguard()
    }

    /** Temporary shim, until [KeyguardWmStateRefactor] is enabled */
    fun dismissKeyguard() {
        when (keyguardTransitionInteractor.transitionState.value.to) {
            LOCKSCREEN -> fromLockscreenTransitionInteractor.get().dismissKeyguard()
            OCCLUDED -> fromOccludedTransitionInteractor.get().dismissFromOccluded()
            DREAMING -> fromDreamingTransitionInteractor.get().dismissFromDreaming()
            ALTERNATE_BOUNCER ->
                fromAlternateBouncerTransitionInteractor.get().dismissAlternateBouncer()
            else -> Log.v(TAG, "Keyguard was dismissed, no direct transition call needed")
        }
    }

    fun onCameraLaunchDetected(source: Int, isSecureCamera: Boolean) {
        repository.onCameraLaunchDetected.value =
            CameraLaunchSourceModel(
                type = cameraLaunchSourceIntToType(source),
                isSecureCamera = isSecureCamera,
            )
    }

    fun showDismissibleKeyguard() {
        repository.showDismissibleKeyguard()
    }

    fun setShortcutAbsoluteTop(top: Float) {
        wallpaperFocalAreaInteractor.setShortcutTop(top)
    }

    fun setIsKeyguardGoingAway(isGoingAway: Boolean) {
        repository.isKeyguardGoingAway.tryEmit(isGoingAway)
    }

    fun setNotificationStackAbsoluteBottom(bottom: Float) {
        wallpaperFocalAreaInteractor.setNotificationStackAbsoluteBottom(bottom)
    }

    fun notifyWatchDisconnected() {
        lockPatternUtils.requireStrongAuth(
            LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_WATCH_DISCONNECTED,
            0,
        )
    }

    suspend fun hydrateTableLogBuffer(tableLogBuffer: TableLogBuffer) {
        coroutineScope {
            launch {
                isDozing
                    .logDiffsForTable(
                        tableLogBuffer = tableLogBuffer,
                        columnName = "isDozing",
                        initialValue = isDozing.value,
                    )
                    .collect()
            }
            launch {
                isSecureCameraActive
                    .logDiffsForTable(
                        tableLogBuffer = tableLogBuffer,
                        columnName = "isSecureCameraActive",
                        initialValue = false,
                    )
                    .collect()
            }
        }
    }

    companion object {
        private const val TAG = "KeyguardInteractor"
        /**
         * Amount of time that [KeyguardInteractor.isDreamingNotDozing] is delayed; consumers of
         * that flow should consider waiting this amount of time before check the value of this
         * flow, to let other consumers have enough time to see the new value.
         */
        const val IS_DREAMING_NOT_DOZING_DELAY_MS = 500L
    }
}
