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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.graphics.Rect
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Flags.doubleTapToSleep
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.inputdevice.data.repository.PointerDeviceRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor
import com.android.systemui.shade.PulsingGestureListener
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.time.SystemClock
import com.android.systemui.wallpapers.domain.interactor.WallpaperFocalAreaInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Business logic for use-cases related to top-level touch handling in the lock screen. */
@SysUISingleton
class KeyguardTouchHandlingInteractor
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Application private val scope: CoroutineScope,
    transitionInteractor: KeyguardTransitionInteractor,
    private val repository: KeyguardRepository,
    private val logger: UiEventLogger,
    broadcastDispatcher: BroadcastDispatcher,
    private val accessibilityManager: AccessibilityManagerWrapper,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val pulsingGestureListener: PulsingGestureListener,
    private val faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val powerInteractor: PowerInteractor,
    private val secureSettingsRepository: SecureSettingsRepository,
    private val powerManager: PowerManager,
    private val systemClock: SystemClock,
    private val pointerDeviceRepository: PointerDeviceRepository,
    private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor,
    secureLockDeviceInteractor: Lazy<SecureLockDeviceInteractor>,
    private val sceneInteractor: Lazy<SceneInteractor>,
) {
    private val _udfpsAccessibilityOverlayBounds: MutableStateFlow<Rect?> = MutableStateFlow(null)

    /** Bounds of the UDFPS accessibility overlay */
    val udfpsAccessibilityOverlayBounds: Flow<Rect?> =
        _udfpsAccessibilityOverlayBounds.asStateFlow()

    fun setUdfpsAccessibilityOverlayBounds(bounds: Rect?) {
        _udfpsAccessibilityOverlayBounds.value = bounds
    }

    /** Whether the long-press handling feature should be enabled. */
    val isLongPressHandlingEnabled: StateFlow<Boolean> =
        if (isLongPressFeatureEnabled()) {
                combine(
                    transitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN),
                    repository.isQuickSettingsVisible,
                    secureLockDeviceInteractor.get().isSecureLockDeviceEnabled,
                ) {
                    isFullyTransitionedToLockScreen,
                    isQuickSettingsVisible,
                    isSecureLockDeviceEnabled ->
                    isFullyTransitionedToLockScreen &&
                        !isQuickSettingsVisible &&
                        !isSecureLockDeviceEnabled
                }
            } else {
                flowOf(false)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * True while a bouncer overlay show/hide transition is actively in flight.
     *
     * Double-tap-to-sleep must not fire while this is true: the double-tap gesture can land on
     * the lockscreen surface at the same moment a bouncer overlay transition is settling (e.g.
     * triggered by a near-simultaneous single tap or face-auth), and invoking
     * PowerManager#goToSleep mid-transition causes the scene state machine to repeatedly
     * re-enter ShowOrHideOverlay, flooding the main thread and ANRing input dispatch.
     */
    private fun isBouncerOverlayTransitioning(): Flow<Boolean> {
        if (!SceneContainerFlag.isEnabled) {
            return flowOf(false)
        }
        return sceneInteractor.get().transitionStateFlow.map { state ->
            state is ObservableTransitionState.Transition &&
                state is ObservableTransitionState.Transition.ShowOrHideOverlay &&
                state.overlay == Overlays.Bouncer
        }
    }

    /** Whether the double tap handling handling feature should be enabled. */
    val isDoubleTapHandlingEnabled: StateFlow<Boolean> =
        if (isDoubleTapFeatureEnabled()) {
                combine(
                    transitionInteractor.transitionValue(KeyguardState.LOCKSCREEN),
                    repository.isQuickSettingsVisible,
                    isDoubleTapSettingEnabled(),
                    secureLockDeviceInteractor.get().isSecureLockDeviceEnabled,
                    isBouncerOverlayTransitioning(),
                ) {
                    isFullyTransitionedToLockScreen,
                    isQuickSettingsVisible,
                    isDoubleTapSettingEnabled,
                    isSecureLockDeviceEnabled,
                    isBouncerOverlayTransitioning ->
                    isFullyTransitionedToLockScreen == 1f &&
                        !isQuickSettingsVisible &&
                        isDoubleTapSettingEnabled &&
                        !isSecureLockDeviceEnabled &&
                        !isBouncerOverlayTransitioning
                }
            } else {
                flowOf(false)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /* Cache value of `isAnyPointerDeviceConnected` so it can
     * be easily checked. */
    private val _isAnyPointerDeviceConnected =
        pointerDeviceRepository.isAnyPointerDeviceConnected.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    private val _isMenuVisible = MutableStateFlow(false)
    /** Model for whether the menu should be shown. */
    val isMenuVisible: StateFlow<Boolean> =
        isLongPressHandlingEnabled
            .flatMapLatest { isEnabled ->
                if (isEnabled) {
                    _isMenuVisible.asStateFlow()
                } else {
                    // Reset the state so we don't see a menu when long-press handling is enabled
                    // again in the future.
                    _isMenuVisible.value = false
                    flowOf(false)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    private val _shouldOpenSettings = MutableStateFlow(false)
    /**
     * Whether the long-press accessible "settings" flow should be opened.
     *
     * Note that [onSettingsShown] must be invoked to consume this, once the settings are opened.
     */
    val shouldOpenSettings = _shouldOpenSettings.asStateFlow()

    private var delayedHideMenuJob: Job? = null

    init {
        if (isLongPressFeatureEnabled()) {
            broadcastDispatcher
                .broadcastFlow(IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                .onEach { hideMenu() }
                .launchIn(scope)
        }
    }

    /**
     * Notifies that the user has long-pressed on the lock screen.
     */
    fun onLongPress() {
        if (!isLongPressHandlingEnabled.value) {
            return
        }

        showMenu()
    }

    /** Notifies that the user has touched outside of the pop-up. */
    fun onTouchedOutside() {
        hideMenu()
    }

    /** Notifies that the user has started a touch gesture on the menu. */
    fun onMenuTouchGestureStarted() {
        cancelAutomaticMenuHiding()
    }

    /** Notifies that the user has started a touch gesture on the menu. */
    fun onMenuTouchGestureEnded(isClick: Boolean) {
        if (isClick) {
            hideMenu()
            logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
            showSettings()
        } else {
            scheduleAutomaticMenuHiding()
        }
    }

    /** Notifies that the settings UI has been shown, consuming the event to show it. */
    fun onSettingsShown() {
        _shouldOpenSettings.value = false
    }

    /** Notifies that anything in the lockscreen scene has been clicked at position [x], [y]. */
    fun onSceneClick(x: Float, y: Float) {
        if (SceneContainerFlag.isEnabled) {
            pulsingGestureListener.onSingleTapUp(x, y)
        }
    }

    /** Notifies that the lockscreen has been clicked at position [x], [y]. */
    fun onClick(x: Float, y: Float) {
        if (!SceneContainerFlag.isEnabled) {
            pulsingGestureListener.onSingleTapUp(x, y)
        }
        if (faceAuthInteractor.canFaceAuthRun()) {
            faceAuthInteractor.onNotificationPanelClicked()
        } else if (_isAnyPointerDeviceConnected.value) {
            attemptDeviceEntry(loggingReason = "Lockscreen clicked")
        }
        if (wallpaperFocalAreaInteractor.hasFocalArea.value) {
            wallpaperFocalAreaInteractor.sendTapPosition(x, y)
        }
        if (SceneContainerFlag.isEnabled) {
            repository.lastRootViewTapPosition.value = Point(x.roundToInt(), y.roundToInt())
        }
    }

    /** Notifies that the lockscreen has been double clicked. */
    fun onDoubleClick() {
        if (isDoubleTapHandlingEnabled.value) {
            Log.d(TAG, "going to sleep due to double tap")
            powerManager.goToSleep(systemClock.uptimeMillis())
        } else {
            pulsingGestureListener.onDoubleTapEvent()
        }
    }

    private fun isDoubleTapSettingEnabled(): Flow<Boolean> {
        return secureSettingsRepository.boolSetting(Settings.Secure.DOUBLE_TAP_TO_SLEEP)
    }

    private fun showSettings() {
        _shouldOpenSettings.value = true
    }

    private fun isLongPressFeatureEnabled(): Boolean {
        return context.resources.getBoolean(R.bool.long_press_keyguard_customize_lockscreen_enabled)
    }

    private fun isDoubleTapFeatureEnabled(): Boolean {
        return doubleTapToSleep() &&
            context.resources.getBoolean(com.android.internal.R.bool.config_supportDoubleTapSleep)
    }

    /** Updates application state to ask to show the menu. */
    private fun showMenu() {
        _isMenuVisible.value = true
        scheduleAutomaticMenuHiding()
        logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
    }

    private fun scheduleAutomaticMenuHiding() {
        cancelAutomaticMenuHiding()
        delayedHideMenuJob =
            scope.launch {
                delay(timeOutMs())
                hideMenu()
            }
    }

    /** Updates application state to ask to hide the menu. */
    private fun hideMenu() {
        cancelAutomaticMenuHiding()
        _isMenuVisible.value = false
    }

    private fun cancelAutomaticMenuHiding() {
        delayedHideMenuJob?.cancel()
        delayedHideMenuJob = null
    }

    private fun timeOutMs(): Long {
        return accessibilityManager
            .getRecommendedTimeoutMillis(
                DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_ICONS or
                    AccessibilityManager.FLAG_CONTENT_TEXT or
                    AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )
            .toLong()
    }

    private fun attemptDeviceEntry(loggingReason: String) {
        if (isDeviceAwake()) {
            if (SceneContainerFlag.isEnabled) {
                deviceEntryInteractor.attemptDeviceEntry(loggingReason)
            } else {
                statusBarKeyguardViewManager.showPrimaryBouncer(
                    true,
                    "KeyguardTouchHandlingInteractor#attemptDeviceEntry",
                )
            }
        }
    }

    private fun isDeviceAwake(): Boolean {
        return powerInteractor.detailedWakefulness.value.isAwake()
    }

    enum class LogEvents(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The lock screen was long-pressed and we showed the settings popup menu.")
        LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN(1292),
        @UiEvent(doc = "The lock screen long-press popup menu was clicked.")
        LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED(1293);

        override fun getId() = _id
    }

    companion object {
        private const val TAG = "KeyguardTouchHandlingInteractor"
        @VisibleForTesting const val DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS = 5000L
    }
}
