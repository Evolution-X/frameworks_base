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

import android.content.Intent
import android.graphics.Point
import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.view.accessibility.accessibilityManagerWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.ObservableTransitionState.Transition.ShowOrHideOverlay
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.logging.uiEventLogger
import com.android.systemui.Flags.FLAG_DOUBLE_TAP_TO_SLEEP
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.inputdevice.data.repository.pointerDeviceRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.securelockdevice.domain.interactor.secureLockDeviceInteractor
import com.android.systemui.shade.PulsingGestureListener
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.android.systemui.util.time.fakeSystemClock
import com.android.systemui.wallpapers.domain.interactor.wallpaperFocalAreaInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardTouchHandlingInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.accessibilityManagerWrapper = mock<AccessibilityManagerWrapper>()
            this.uiEventLogger = mock<UiEventLoggerFake>()
        }

    private lateinit var underTest: KeyguardTouchHandlingInteractor

    private lateinit var logger: UiEventLogger
    private lateinit var testScope: TestScope
    private lateinit var keyguardRepository: KeyguardRepository
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var secureSettingsRepository: SecureSettingsRepository
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager

    @Mock private lateinit var pulsingGestureListener: PulsingGestureListener
    @Mock private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        logger = kosmos.uiEventLogger
        testScope = kosmos.testScope
        keyguardRepository = kosmos.fakeKeyguardRepository
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        secureSettingsRepository = kosmos.userAwareSecureSettingsRepository
        sceneInteractor = kosmos.sceneInteractor
        statusBarKeyguardViewManager = kosmos.statusBarKeyguardViewManager

        MockitoAnnotations.initMocks(this)
        overrideResource(R.bool.long_press_keyguard_customize_lockscreen_enabled, true)
        overrideResource(com.android.internal.R.bool.config_supportDoubleTapSleep, true)
        whenever(kosmos.accessibilityManagerWrapper.getRecommendedTimeoutMillis(anyInt(), anyInt()))
            .thenAnswer { it.arguments[0] }

        runBlocking { createUnderTest() }
    }

    @After
    fun tearDown() {
        val testableResource = mContext.getOrCreateTestableResources()
        testableResource.removeOverride(R.bool.long_press_keyguard_customize_lockscreen_enabled)
        testableResource.removeOverride(com.android.internal.R.bool.config_supportDoubleTapSleep)
    }

    @Test
    fun isLongPressEnabled() =
        testScope.runTest {
            val isEnabled = collectLastValue(underTest.isLongPressHandlingEnabled)
            KeyguardState.values().forEach { keyguardState ->
                setUpState(keyguardState = keyguardState)

                if (keyguardState == KeyguardState.LOCKSCREEN) {
                    assertThat(isEnabled()).isTrue()
                } else {
                    assertThat(isEnabled()).isFalse()
                }
            }
        }

    @Test
    @EnableSceneContainer
    fun onSceneClickNotifiesPulsingGestureListener() {
        underTest.onSceneClick(1f, 2f)
        verify(pulsingGestureListener).onSingleTapUp(1f, 2f)
    }

    @Test
    fun isLongPressEnabled_alwaysFalseWhenQuickSettingsAreVisible() =
        testScope.runTest {
            val isEnabled = collectLastValue(underTest.isLongPressHandlingEnabled)
            KeyguardState.values().forEach { keyguardState ->
                setUpState(keyguardState = keyguardState, isQuickSettingsVisible = true)

                assertThat(isEnabled()).isFalse()
            }
        }

    @Test
    fun isLongPressEnabled_alwaysFalseWhenConfigEnabledBooleanIsFalse() =
        testScope.runTest {
            overrideResource(R.bool.long_press_keyguard_customize_lockscreen_enabled, false)
            createUnderTest()
            val isEnabled by collectLastValue(underTest.isLongPressHandlingEnabled)
            runCurrent()

            assertThat(isEnabled).isFalse()
        }

    @Test
    fun longPressed_menuClicked_showsSettings() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuTouchGestureEnded(/* isClick= */ true)

            assertThat(isMenuVisible).isFalse()
            assertThat(shouldOpenSettings).isTrue()
        }

    @Test
    fun onSettingsShown_consumesSettingsShowEvent() =
        testScope.runTest {
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onLongPress()
            underTest.onMenuTouchGestureEnded(/* isClick= */ true)
            assertThat(shouldOpenSettings).isTrue()

            underTest.onSettingsShown()
            assertThat(shouldOpenSettings).isFalse()
        }

    @Test
    fun onTouchedOutside_neverShowsSettings() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onTouchedOutside()

            assertThat(isMenuVisible).isFalse()
            assertThat(shouldOpenSettings).isFalse()
        }

    @Test
    fun longPressed_closeDialogsBroadcastReceived_popupDismissed() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            )

            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun closesDialogAfterTimeout() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)

            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun closesDialogAfterTimeout_onlyAfterTouchGestureEnded() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()
            underTest.onMenuTouchGestureStarted()

            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuTouchGestureEnded(/* isClick= */ false)
            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun logsWhenMenuIsShown() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()

            verify(logger)
                .log(KeyguardTouchHandlingInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
        }

    @Test
    fun logsWhenMenuIsClicked() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            underTest.onMenuTouchGestureEnded(/* isClick= */ true)

            verify(logger)
                .log(KeyguardTouchHandlingInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
        }

    @Test
    fun showMenu_leaveLockscreen_returnToLockscreen_menuNotVisible() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()
            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            assertThat(isMenuVisible).isFalse()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
            assertThat(isMenuVisible).isFalse()
        }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun isDoubleTapEnabled_flagEnabled_userSettingEnabled_onlyTrueInLockScreenState() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)

            val isEnabled = collectLastValue(underTest.isDoubleTapHandlingEnabled)
            KeyguardState.entries.forEach { keyguardState ->
                setUpState(keyguardState = keyguardState)

                if (keyguardState == KeyguardState.LOCKSCREEN) {
                    assertThat(isEnabled()).isTrue()
                } else {
                    assertThat(isEnabled()).isFalse()
                }
            }
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun isDoubleTapEnabled_flagEnabled_userSettingDisabled_alwaysFalse() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, false)

            val isEnabled = collectLastValue(underTest.isDoubleTapHandlingEnabled)
            KeyguardState.entries.forEach { keyguardState ->
                setUpState(keyguardState = keyguardState)

                assertThat(isEnabled()).isFalse()
            }
        }
    }

    @Test
    @DisableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun isDoubleTapEnabled_flagDisabled_userSettingEnabled_alwaysFalse() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)

            val isEnabled = collectLastValue(underTest.isDoubleTapHandlingEnabled)
            KeyguardState.entries.forEach { keyguardState ->
                setUpState(keyguardState = keyguardState)

                assertThat(isEnabled()).isFalse()
            }
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun isDoubleTapEnabled_flagEnabledAndConfigDisabled_alwaysFalse() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)
            overrideResource(com.android.internal.R.bool.config_supportDoubleTapSleep, false)
            createUnderTest()

            val isEnabled = collectLastValue(underTest.isDoubleTapHandlingEnabled)
            KeyguardState.entries.forEach { keyguardState ->
                setUpState(keyguardState = keyguardState)

                assertThat(isEnabled()).isFalse()
            }
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun isDoubleTapEnabled_quickSettingsVisible_alwaysFalse() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)

            val isEnabled = collectLastValue(underTest.isDoubleTapHandlingEnabled)
            KeyguardState.entries.forEach { keyguardState ->
                setUpState(keyguardState = keyguardState, isQuickSettingsVisible = true)

                assertThat(isEnabled()).isFalse()
            }
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun onDoubleClick_doubleTapEnabled() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)
            val isEnabled by collectLastValue(underTest.isDoubleTapHandlingEnabled)
            runCurrent()

            underTest.onDoubleClick()

            assertThat(isEnabled).isTrue()
            verify(powerManager).goToSleep(anyLong())
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    fun onDoubleClick_doubleTapDisabled() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, false)
            val isEnabled by collectLastValue(underTest.isDoubleTapHandlingEnabled)
            runCurrent()

            underTest.onDoubleClick()

            assertThat(isEnabled).isFalse()
            verify(powerManager, never()).goToSleep(anyLong())
        }
    }

    @Test
    @EnableFlags(FLAG_DOUBLE_TAP_TO_SLEEP)
    @EnableSceneContainer
    fun isDoubleTapEnabled_bouncerOverlayTransitioning_false() {
        testScope.runTest {
            secureSettingsRepository.setBoolean(Settings.Secure.DOUBLE_TAP_TO_SLEEP, true)
            setUpState()

            val isEnabled by collectLastValue(underTest.isDoubleTapHandlingEnabled)
            runCurrent()
            assertThat(isEnabled).isTrue()

            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            val progress = MutableStateFlow(0.4f)
            transitionState.value =
                ShowOrHideOverlay(
                    overlay = Overlays.Bouncer,
                    fromContent = Scenes.Lockscreen,
                    toContent = Overlays.Bouncer,
                    currentScene = Scenes.Lockscreen,
                    currentOverlays = flowOf(emptySet()),
                    progress = progress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            runCurrent()

            assertThat(isEnabled).isFalse()

            underTest.onDoubleClick()
            verify(powerManager, never()).goToSleep(anyLong())

            transitionState.value =
                ObservableTransitionState.Idle(Scenes.Lockscreen, setOf(Overlays.Bouncer))
            runCurrent()
            assertThat(isEnabled).isFalse()

            transitionState.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            runCurrent()
            assertThat(isEnabled).isTrue()
        }
    }

    @Test
    @EnableSceneContainer
    fun onClick_setsLastRootViewTapPosition() =
        kosmos.runTest {
            val lastRootViewTapPosition by
                collectLastValue(keyguardRepository.lastRootViewTapPosition)
            assertThat(lastRootViewTapPosition).isNull()

            underTest.onClick(100f, 100f)
            assertThat(lastRootViewTapPosition).isEqualTo(Point(100, 100))

            underTest.onClick(200f, 100f)
            assertThat(lastRootViewTapPosition).isEqualTo(Point(200, 100))
        }

    private suspend fun createUnderTest(isRevampedWppFeatureEnabled: Boolean = true) {
        // This needs to be re-created for each test outside of kosmos since the flag values are
        // read during initialization to set up flows. Maybe there is a better way to handle that.
        underTest =
            KeyguardTouchHandlingInteractor(
                context = mContext,
                scope = testScope.backgroundScope,
                transitionInteractor = kosmos.keyguardTransitionInteractor,
                repository = keyguardRepository,
                logger = logger,
                broadcastDispatcher = fakeBroadcastDispatcher,
                accessibilityManager = kosmos.accessibilityManagerWrapper,
                statusBarKeyguardViewManager = statusBarKeyguardViewManager,
                pulsingGestureListener = pulsingGestureListener,
                faceAuthInteractor = kosmos.deviceEntryFaceAuthInteractor,
                deviceEntryInteractor = kosmos.deviceEntryInteractor,
                powerInteractor = kosmos.powerInteractor,
                secureSettingsRepository = secureSettingsRepository,
                powerManager = powerManager,
                systemClock = kosmos.fakeSystemClock,
                pointerDeviceRepository = kosmos.pointerDeviceRepository,
                secureLockDeviceInteractor = { kosmos.secureLockDeviceInteractor },
                wallpaperFocalAreaInteractor = kosmos.wallpaperFocalAreaInteractor,
                sceneInteractor = { sceneInteractor },
            )
        setUpState()
    }

    private suspend fun setUpState(
        keyguardState: KeyguardState = KeyguardState.LOCKSCREEN,
        isQuickSettingsVisible: Boolean = false,
    ) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = keyguardState,
            testScope = testScope,
        )
        keyguardRepository.setQuickSettingsVisible(isVisible = isQuickSettingsVisible)
    }
}
