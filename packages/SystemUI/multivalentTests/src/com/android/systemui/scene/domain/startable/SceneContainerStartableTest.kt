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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.app.ActivityManager.RunningTaskInfo
import android.app.StatusBarManager
import android.app.WindowConfiguration
import android.hardware.face.FaceManager
import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.security.Flags.FLAG_SECURE_LOCK_DEVICE
import android.service.dreams.Flags.FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION
import android.view.Display
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.internal.widget.lockPatternUtils
import com.android.keyguard.AuthInteractionProperties
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.Flags.FLAG_DUAL_SHADE
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.activityTransitionAnimator
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.classifier.falsingManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.deviceentry.data.repository.deviceEntryRepository
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryBypassRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryHapticsInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.keyevent.data.repository.fakeKeyEventRepository
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.ShowWhileAwakeReason
import com.android.systemui.keyguard.domain.interactor.biometricUnlockInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardServiceShowLockscreenInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardSurfaceBehindInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardWakeDirectlyToGoneInteractor
import com.android.systemui.keyguard.domain.interactor.lockAfterDelayInteractor
import com.android.systemui.keyguard.domain.interactor.scenetransition.lockscreenSceneTransitionInteractor
import com.android.systemui.keyguard.shared.DriveDreamStateFromOcclusion
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardTransitionKeys.AodToGoneTransition
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.advanceTimeBy
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.SysUiState
import com.android.systemui.model.SysUiStateImpl
import com.android.systemui.model.fakeSysUIStatePerDisplayRepository
import com.android.systemui.model.sceneContainerPluginImpl
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.data.repository.unlockDevice
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.securelockdevice.data.repository.fakeSecureLockDeviceRepository
import com.android.systemui.securelockdevice.domain.interactor.secureLockDeviceInteractor
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.enableSingleShade
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.centralSurfaces
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableSceneContainer
class SceneContainerStartableTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        const val SECONDARY_DISPLAY = Display.DEFAULT_DISPLAY + 1

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()
    private val authInteractionProperties = AuthInteractionProperties()
    private val mockFalsingCollector =
        mock<FalsingCollector>().also { kosmos.falsingCollector = it }
    private val mockVibratorHelper = mock<VibratorHelper>().also { kosmos.vibratorHelper = it }
    private val mockActivityTransitionAnimator =
        mock<ActivityTransitionAnimator>().also { kosmos.activityTransitionAnimator = it }
    private val mockSysUiState by lazy {
        kosmos.fakeSysUIStatePerDisplayRepository[Display.DEFAULT_DISPLAY]!!
    }
    private val secondaryDisplaySysUIState by lazy {
        kosmos.fakeSysUIStatePerDisplayRepository[SECONDARY_DISPLAY]!!
    }

    private val underTest by lazy { kosmos.sceneContainerStartable }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
            .thenReturn(true)
    }

    @Test
    fun hydrateVisibility_basedOnDeviceProvisioning() =
        kosmos.runTest {
            prepareState()
            underTest.start()
            unlockDevice()
            sceneInteractor.changeScene(Scenes.Lockscreen, "Change to LS to force visible")
            runCurrent()
            assertThat(sceneInteractor.currentSceneAsState).isEqualTo(Scenes.Lockscreen)
            assertThat(sceneInteractor.isVisible).isTrue()

            fakeDeviceProvisioningRepository.setDeviceProvisioned(false)
            runCurrent()
            assertThat(sceneInteractor.currentSceneAsState).isEqualTo(Scenes.Lockscreen)
            assertThat(sceneInteractor.isVisible).isFalse()

            fakeDeviceProvisioningRepository.setDeviceProvisioned(true)
            runCurrent()
            assertThat(sceneInteractor.currentSceneAsState).isEqualTo(Scenes.Lockscreen)
            assertThat(sceneInteractor.isVisible).isTrue()
        }

    // Edge case when resuming SetupWizard for a user that has already set an authentication factor.
    @Test
    fun hydrateVisibility_deviceNotProvisionedAndLocked() =
        kosmos.runTest {
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                isDeviceProvisioned = false,
            )

            underTest.start()
            assertThat(sceneInteractor.isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_basedOnOcclusion() =
        kosmos.runTest {
            underTest.start()
            runCurrent()
            assertThat(sceneInteractor.isVisible).isTrue()

            sceneInteractor.changeScene(Scenes.Occluded, "switch to occluded")
            runCurrent()

            assertThat(sceneInteractor.isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_basedOnAlternateBouncer() =
        kosmos.runTest {
            underTest.start()
            assertThat(sceneInteractor.isVisible).isTrue()

            // WHEN the device is occluded,
            sceneInteractor.changeScene(Scenes.Occluded, "switch to occluded")
            // THEN scenes are not visible
            assertThat(sceneInteractor.isVisible).isFalse()

            // WHEN the alternate bouncer is visible
            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()
            // THEN scenes visible
            assertThat(sceneInteractor.isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_whileDreaming() =
        kosmos.runTest {
            // GIVEN the device is dreaming
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Dream)
            underTest.start()
            assertThat(sceneInteractor.isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_surfaceBehindAnimating() =
        kosmos.runTest {
            underTest.start()
            assertThat(sceneInteractor.isVisible).isTrue()

            // Unlock, leaving the surface behind animation running even after the transition ends.
            unlockDevice()
            sceneInteractor.changeScene(
                Scenes.Gone,
                "unlocking for test",
                forceSettleToTargetScene = true,
            )
            sceneInteractor.onIdleSceneEnteredComposition(Scenes.Gone)
            keyguardSurfaceBehindInteractor.setAnimatingSurface(true)
            runCurrent()

            // Scene container should remain visible so the flows controlling the surface behind
            // animation continue emitting.
            assertThat(sceneInteractor.isVisible).isTrue()

            // Once the animation settles...
            keyguardSurfaceBehindInteractor.setAnimatingSurface(false)
            runCurrent()

            // ...we no longer need to be visible.
            assertThat(sceneInteractor.isVisible).isFalse()
        }

    @Test
    fun startsInLockscreenScene() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState()

            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
                startsAwake = false,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            underTest.start()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked() =
        kosmos.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
            )
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchFromLockscreenToGoneAndHideAltBouncerWhenDeviceUnlocked() =
        kosmos.runTest {
            val alternateBouncerVisible by
                collectLastValue(keyguardBouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            assertThat(alternateBouncerVisible).isTrue()

            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(alternateBouncerVisible).isFalse()
        }

    @Test
    fun stayOnCurrentSceneAndHideAltBouncerWhenDeviceUnlocked_whenLeaveOpenShade() =
        kosmos.runTest {
            enableSingleShade()
            val alternateBouncerVisible by
                collectLastValue(keyguardBouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            statusBarStateController.setLeaveOpenOnKeyguardHide(true) // leave shade open
            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            assertThat(alternateBouncerVisible).isTrue()

            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.QuickSettings))
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)
            assertThat(alternateBouncerVisible).isFalse()
        }

    @Test
    fun switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade_singleShade() =
        kosmos.runTest {
            enableSingleShade()
            switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade(
                switchToQs = {
                    sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
                    ObservableTransitionState.Idle(currentScene = Scenes.QuickSettings)
                },
                expectedSceneWhileBouncerIsShowing = Scenes.QuickSettings,
                expectedOverlaysWhileBouncerIsShowing = setOf(Overlays.Bouncer),
                expectedLastBackStackSceneWhileBouncerIsShowing = Scenes.Lockscreen,
                expectedSceneAfterUnlock = Scenes.QuickSettings,
                expectedOverlaysAfterUnlock = emptySet(),
                expectedLastBackStackSceneAfterUnlock = Scenes.Gone,
            )
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade_dualShade() =
        kosmos.runTest {
            enableDualShade()
            switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade(
                switchToQs = {
                    sceneInteractor.showOverlay(
                        Overlays.QuickSettingsShade,
                        "switching to qs for test",
                    )
                    ObservableTransitionState.Idle(
                        currentScene = Scenes.Lockscreen,
                        currentOverlays = setOf(Overlays.QuickSettingsShade),
                    )
                },
                expectedSceneWhileBouncerIsShowing = Scenes.Lockscreen,
                expectedOverlaysWhileBouncerIsShowing =
                    setOf(Overlays.QuickSettingsShade, Overlays.Bouncer),
                expectedLastBackStackSceneWhileBouncerIsShowing = null,
                expectedSceneAfterUnlock = Scenes.Gone,
                expectedOverlaysAfterUnlock = setOf(Overlays.QuickSettingsShade),
                expectedLastBackStackSceneAfterUnlock = null,
            )
        }

    @Test
    fun hydrateNotifLockscreenUserManager_waitsForKeyguardDismiss() =
        kosmos.runTest {
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Lockscreen)
            underTest.hydrateLockScreenUserManager()
            runCurrent()
            clearInvocations(notificationLockscreenUserManager)

            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(
                    isUnlocked = true,
                    deviceUnlockSource = DeviceUnlockSource.Fingerprint,
                )
            runCurrent()

            verify(notificationLockscreenUserManager, never()).updatePublicMode()

            sceneInteractor.changeScene(Scenes.Gone, "dismissing keyguard from fingerprint unlock")
            runCurrent()

            verify(notificationLockscreenUserManager).updatePublicMode()
        }

    @Test
    fun hydrateNotifLockscreenUserManager_unlockOnSingleShade() =
        kosmos.runTest {
            enableSingleShade()
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            underTest.hydrateLockScreenUserManager()
            runCurrent()
            clearInvocations(notificationLockscreenUserManager)

            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(
                    isUnlocked = true,
                    deviceUnlockSource = DeviceUnlockSource.Fingerprint,
                )
            runCurrent()

            verify(notificationLockscreenUserManager).updatePublicMode()
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateNotifLockscreenUserManager_unlockOnDualShade() =
        kosmos.runTest {
            enableDualShade()
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.NotificationsShade),
            )
            underTest.hydrateLockScreenUserManager()
            runCurrent()
            clearInvocations(notificationLockscreenUserManager)

            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(
                    isUnlocked = true,
                    deviceUnlockSource = DeviceUnlockSource.Fingerprint,
                )
            runCurrent()

            verify(notificationLockscreenUserManager).updatePublicMode()
        }

    /**
     * Runs through the scenario where the bouncer is accessed while QS is being shown and the
     * device gets unlocked. This is a helper that can help multiple scenarios.
     *
     * @param switchToQs A function that switches to the QS scene or overlay and returns the `Idle`
     *   representation of the expected current scene and overlays
     * @param expectedSceneWhileBouncerIsShowing The expected scene while the bouncer is showing.
     *   The helper function will check that the current _scene_ is this while the bouncer is
     *   showing
     * @param expectedLastBackStackSceneWhileBouncerIsShowing The expected last back stack scene
     *   while the bouncer is showing. The helper function will check that this is the last scene on
     *   the back stack while the bouncer is showing
     * @param expectedSceneAfterUnlock The expected scene once the device is unlocked. The helper
     *   function will check that this is the current _scene_ once the device is unlocked
     * @param expectedOverlaysAfterUnlock The expected overlays once the device is unlocked. The
     *   helper function will check that these are the current _overlays_ once the device is
     *   unlocked
     * @param expectedLastBackStackSceneAfterUnlock The expected last back stack scene after the
     *   device is unlocked. The helper function will check that this is the last scene on the back
     *   stack once the device is unlocked
     */
    private fun Kosmos.switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade(
        switchToQs: Kosmos.() -> ObservableTransitionState.Idle,
        expectedSceneWhileBouncerIsShowing: SceneKey,
        expectedOverlaysWhileBouncerIsShowing: Set<OverlayKey>,
        expectedLastBackStackSceneWhileBouncerIsShowing: SceneKey?,
        expectedSceneAfterUnlock: SceneKey,
        expectedOverlaysAfterUnlock: Set<OverlayKey>,
        expectedLastBackStackSceneAfterUnlock: SceneKey?,
    ) {
        val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
        val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
        val backStack by collectLastValue(sceneBackInteractor.backStack)
        statusBarStateController.setLeaveOpenOnKeyguardHide(true) // leave shade open

        prepareState(
            authenticationMethod = AuthenticationMethodModel.Pin,
            isDeviceUnlocked = false,
            initialSceneKey = Scenes.Lockscreen,
        )
        assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        underTest.start()
        runCurrent()

        val idleOnQs = switchToQs()
        setSceneTransition(idleOnQs)
        runCurrent()
        assertThat(currentSceneKey).isEqualTo(idleOnQs.currentScene)
        assertThat(currentOverlays).isEqualTo(idleOnQs.currentOverlays)

        sceneInteractor.showOverlay(Overlays.Bouncer, "showing bouncer for test")
        setSceneTransition(
            ObservableTransitionState.Idle(
                expectedSceneWhileBouncerIsShowing,
                expectedOverlaysWhileBouncerIsShowing,
            )
        )
        runCurrent()
        assertThat(currentOverlays).isEqualTo(expectedOverlaysWhileBouncerIsShowing)
        assertThat(backStack?.asIterable()?.lastOrNull())
            .isEqualTo(expectedLastBackStackSceneWhileBouncerIsShowing)

        updateFingerprintAuthStatus(isSuccess = true)
        assertThat(currentSceneKey).isEqualTo(expectedSceneAfterUnlock)
        assertThat(currentOverlays).isEqualTo(expectedOverlaysAfterUnlock)
        assertThat(backStack?.asIterable()?.lastOrNull())
            .isEqualTo(expectedLastBackStackSceneAfterUnlock)
    }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked_whenDoNotLeaveOpenShade() =
        kosmos.runTest {
            enableSingleShade()
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            statusBarStateController.setLeaveOpenOnKeyguardHide(false) // don't leave shade open

            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.QuickSettings))
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)

            sceneInteractor.showOverlay(Overlays.Bouncer, "showing bouncer for test")
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.QuickSettings, setOf(Overlays.Bouncer))
            )
            runCurrent()
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isBypassEnabled = true,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceUnlocksWithBypassOff() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isBypassEnabled = false, initialSceneKey = Scenes.Lockscreen)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            fakeDeviceEntryFaceAuthRepository.isCurrentUserAuthenticated.value = true

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun enterDeviceWhileInShadeWhenDeviceIsUnlockedViaFingerPrint() =
        kosmos.runTest {
            enableSingleShade()
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isBypassEnabled = true,
                authenticationMethod = AuthenticationMethodModel.Pin,
                initialSceneKey = Scenes.Lockscreen,
            )
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.Shade, "switch to shade")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Shade))
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isEqualTo(MSDLToken.UNLOCK)
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    fun hideAlternateBouncerAndNotifyDismissCancelledWhenDeviceSleeps() =
        kosmos.runTest {
            enableSingleShade()
            val alternateBouncerVisible by
                collectLastValue(fakeKeyguardBouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            underTest.start()
            runCurrent()

            // Run all pending dismiss succeeded/cancelled calls from setup:
            fakeExecutor.runAllReady()

            val dismissCallback: IKeyguardDismissCallback = mock()
            dismissCallbackRegistry.addCallback(dismissCallback)
            powerInteractor.setAsleepForTest()
            runCurrent()
            fakeExecutor.runAllReady()

            assertThat(alternateBouncerVisible).isFalse()
            verify(dismissCallback).onDismissCancelled()
        }

    @Test
    fun switchToLockscreenWhenDeviceSleepsLocked() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
            underTest.start()
            powerInteractor.setAsleepForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToAOD_whenAvailable_whenDeviceSleepsLocked_transitionFlagEnabled() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(keyguardInteractor.asleepKeyguardState)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            keyguardRepository.setAodAvailable(true)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.AOD)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            )
            runCurrent()

            assertThat(keyguardTransitionRepository.currentTransitionInfo.to)
                .isEqualTo(KeyguardState.AOD)
        }

    @Test
    fun switchToDozing_whenAodUnavailable_whenDeviceSleepsLocked_transitionFlagEnabled() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(keyguardInteractor.asleepKeyguardState)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            keyguardRepository.setAodAvailable(false)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.DOZING)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            setSceneTransition(Transition(from = Scenes.Shade, to = Scenes.Lockscreen))
            runCurrent()

            assertThat(keyguardTransitionRepository.currentTransitionInfo.to)
                .isEqualTo(KeyguardState.DOZING)
        }

    @Test
    fun switchToGoneWhenDoubleTapPowerGestureIsTriggeredFromGone() =
        kosmos.runTest {
            val isUnlocked by
                collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked })
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            underTest.start()
            runCurrent()

            fakePowerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastSleepReason = WakeSleepReason.POWER_BUTTON,
                powerButtonLaunchGestureTriggered = true,
                asleepOrWakingFromPreviouslyEnteredDevice = true,
            )

            setSceneTransition(Transition(from = Scenes.Gone, to = Scenes.Lockscreen))
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            kosmos.powerInteractor.onCameraLaunchGestureDetected()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun switchToOccludedWhenDoubleTapPowerGestureIsTriggeredFromLockscreen() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON
            )
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            // KeyguardTransitionRepository needs to be going to sleep for the power button gesture
            // to trigger.
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                KeyguardState.LOCKSCREEN,
                KeyguardState.AOD,
                testScope,
            )
            kosmos.powerInteractor.setAwakeForTest(
                reason = PowerManager.WAKE_REASON_POWER_BUTTON,
                powerButtonGestureTriggered = true,
            )
            kosmos.powerInteractor.onCameraLaunchGestureDetected()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Occluded)
        }

    @Test
    fun switchToOccluded_whenWakingUp_whileOccluded() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Occluded)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptics_onSuccessfulLockscreenAuth_udfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            verify(mockVibratorHelper).vibrateAuthSuccess(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessMsdlHaptics_onSuccessfulLockscreenAuth_udfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isEqualTo(MSDLToken.UNLOCK)
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptics_onSuccessfulLockscreenAuth_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            verify(mockVibratorHelper).vibrateAuthSuccess(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessMsdlHaptics_onSuccessfulLockscreenAuth_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isEqualTo(MSDLToken.UNLOCK)
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptics_onFailedLockscreenAuth_udfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            verify(mockVibratorHelper).vibrateAuthError(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playMsdlErrorHaptics_onFailedLockscreenAuth_udfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isEqualTo(MSDLToken.FAILURE)
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptics_onFailedLockscreenAuth_sfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            verify(mockVibratorHelper).vibrateAuthError(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playMsdlErrorHaptics_onFailedLockscreenAuth_sfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isEqualTo(MSDLToken.FAILURE)
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsSuccessHaptics_whenPowerButtonDown_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(isPowerButtonDown = true)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMsdlSuccessHaptics_whenPowerButtonDown_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(isPowerButtonDown = true)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isNull()
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsSuccessHaptics_whenPowerButtonRecentlyPressed_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(lastPowerPress = 50)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMsdlSuccessHaptics_whenPowerButtonRecentlyPressed_sfps() =
        kosmos.runTest {
            whenever(keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(lastPowerPress = 50)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isNull()
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsErrorHaptics_whenPowerButtonDown_sfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            fakeKeyEventRepository.setPowerButtonDown(true)
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMsdlErrorHaptics_whenPowerButtonDown_sfps() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            fakeKeyEventRepository.setPowerButtonDown(true)
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isNull()
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsFaceErrorHaptics_nonSfps_coEx() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true, hasFace = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFaceAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            verify(mockVibratorHelper, never()).vibrateAuthError(anyString())
            verify(mockVibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMsdlFaceErrorHaptics_nonSfps_coEx() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true, hasFace = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFaceAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            assertThat(fakeMSDLPlayer.latestTokenPlayed).isNull()
            assertThat(fakeMSDLPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateSystemUiState_cancelledNotificationOverlay_clearsPluginOverride() =
        kosmos.runTest {
            val state = installRealSceneSysUiState()
            enableDualShade()
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            sceneInteractor.onIdleSceneEnteredComposition(Scenes.Gone)
            underTest.start()
            runCurrent()
            val notifications = mutableListOf<Long>()
            state.addCallback { flags, displayId ->
                if (displayId == Display.DEFAULT_DISPLAY) notifications.add(flags)
            }
            val expanded = QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
            assertThat(state.isFlagEnabled(expanded)).isFalse()
            assertThat(sceneInteractor.isVisibleFlow.value).isFalse()
            val backStackBefore = sceneBackInteractor.backStack.value

            setSceneTransition(
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = Overlays.NotificationsShade,
                    fromContent = Scenes.Gone,
                    toContent = Overlays.NotificationsShade,
                    currentScene = Scenes.Gone,
                    currentOverlays = flowOf(emptySet<OverlayKey>()),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                ),
                // Keep snapshot visibility fixed while advancing its independently collected Flow.
                skipChangeScene = true,
            )
            assertThat(sceneInteractor.isVisibleFlow.value).isFalse()
            assertThat(sceneBackInteractor.backStack.value).isEqualTo(backStackBefore)
            // A legacy writer requests false, but the real plugin protects shade opening.
            state.setFlag(expanded, false).commitUpdate()
            assertThat(state.isFlagEnabled(expanded)).isTrue()
            notifications.clear()

            // Cancellation returns to the same Idle and expected flag map as before.
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.Gone),
                skipChangeScene = true,
            )
            runCurrent()
            assertThat(state.isFlagEnabled(expanded)).isFalse()
            assertThat(sceneInteractor.transitionStateFlow.value)
                .isEqualTo(ObservableTransitionState.Idle(Scenes.Gone))
            assertThat(sceneInteractor.isVisibleFlow.value).isFalse()
            assertThat(sceneBackInteractor.backStack.value).isEqualTo(backStackBefore)
            assertThat(notifications).containsExactly(state.flags)
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateSystemUiState_qsToNotifications_visibilityFalseBeforeIdle_clearsPluginOverride() =
        kosmos.runTest {
            val state = installRealSceneSysUiState()
            enableDualShade()
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            sceneInteractor.onIdleSceneEnteredComposition(Scenes.Gone)
            underTest.start()
            runCurrent()
            // Drain startup's SIM-unlock Gone request before opening the test overlay.
            setSceneTransition(
                ObservableTransitionState.Idle(
                    currentScene = Scenes.Gone,
                    currentOverlays = setOf(Overlays.QuickSettingsShade),
                )
            )
            runCurrent()
            assertThat(sceneInteractor.isVisibleFlow.value).isTrue()

            val expanded = QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
            val visible = QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE
            val qsExpanded = QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED
            assertThat(state.isFlagEnabled(expanded)).isFalse()
            assertThat(state.isFlagEnabled(visible)).isTrue()
            assertThat(state.isFlagEnabled(qsExpanded)).isTrue()
            val backStackBefore = sceneBackInteractor.backStack.value
            val notifications = mutableListOf<Long>()
            state.addCallback { flags, displayId ->
                if (displayId == Display.DEFAULT_DISPLAY) notifications.add(flags)
            }

            setSceneTransition(
                ObservableTransitionState.Transition.ReplaceOverlay(
                    fromOverlay = Overlays.QuickSettingsShade,
                    toOverlay = Overlays.NotificationsShade,
                    currentScene = Scenes.Gone,
                    currentOverlays = flowOf(setOf(Overlays.QuickSettingsShade)),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                ),
                skipChangeScene = true,
            )
            assertThat(sceneInteractor.isVisibleFlow.value).isTrue()

            // Deliver snapshot visibility first while its independent Flow remains in transition.
            fakeSceneDataSource.transitionState = TransitionState.Idle(Scenes.Gone)
            runCurrent()
            assertThat(sceneInteractor.transitionStateFlow.value)
                .isInstanceOf(ObservableTransitionState.Transition.ReplaceOverlay::class.java)
            assertThat(sceneInteractor.isVisibleFlow.value).isFalse()
            assertThat(sceneBackInteractor.backStack.value).isEqualTo(backStackBefore)
            // Hydration requests false; the real transition override still commits expanded=true.
            assertThat(state.isFlagEnabled(expanded)).isTrue()
            assertThat(state.isFlagEnabled(visible)).isFalse()
            assertThat(state.isFlagEnabled(qsExpanded)).isFalse()
            notifications.clear()

            // This new Idle computes the same all-false shade flags as the visibility update.
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.Gone),
                skipChangeScene = true,
            )
            runCurrent()
            assertThat(sceneInteractor.transitionStateFlow.value)
                .isEqualTo(ObservableTransitionState.Idle(Scenes.Gone))
            assertThat(sceneInteractor.isVisibleFlow.value).isFalse()
            assertThat(state.isFlagEnabled(expanded)).isFalse()
            assertThat(state.isFlagEnabled(visible)).isFalse()
            assertThat(state.isFlagEnabled(qsExpanded)).isFalse()
            assertThat(notifications).containsExactly(state.flags)
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateSystemUiState_realNotificationOverlay_retainsProtectionUntilGone() =
        kosmos.runTest {
            val state = installRealSceneSysUiState()
            enableDualShade()
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            sceneInteractor.onIdleSceneEnteredComposition(Scenes.Gone)
            underTest.start()
            runCurrent()
            // Startup may hide overlays while handling the initial unlocked SIM state.
            setSceneTransition(
                ObservableTransitionState.Idle(
                    currentScene = Scenes.Gone,
                    currentOverlays = setOf(Overlays.NotificationsShade),
                )
            )
            runCurrent()
            assertThat(sceneInteractor.isVisibleFlow.value).isTrue()
            val expanded = QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
            assertThat(state.isFlagEnabled(expanded)).isTrue()
            // The fix must not bypass the genuine expanded shade's gesture protection.
            state.setFlag(expanded, false).commitUpdate()
            assertThat(state.isFlagEnabled(expanded)).isTrue()

            setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            runCurrent()
            assertThat(state.isFlagEnabled(expanded)).isFalse()
            assertThat(
                    state.isFlagEnabled(
                        QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE
                    )
                )
                .isFalse()
        }

    private fun Kosmos.installRealSceneSysUiState(): SysUiState {
        // Replace the default fake before either SysUiState or the startable reads this fixture.
        set("sceneContainerPlugin", sceneContainerPluginImpl)
        return fakeSysUIStatePerDisplayRepository[Display.DEFAULT_DISPLAY]!!.also {
            assertThat(it).isInstanceOf(SysUiStateImpl::class.java)
        }
    }

    @Test
    fun hydrateSystemUiState_onLockscreen_basedOnOcclusion() =
        kosmos.runTest {
            prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            clearInvocations(mockSysUiState)

            sceneInteractor.changeScene(Scenes.Occluded, "switch to occluded")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Occluded))

            runCurrent()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED != 0L
                )
                .isTrue()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING != 0L
                )
                .isFalse()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED != 0L
                )
                .isFalse()

            sceneInteractor.changeScene(Scenes.Lockscreen, "switch to lockscreen")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Lockscreen))

            runCurrent()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED != 0L
                )
                .isFalse()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING != 0L
                )
                .isTrue()
            assertThat(
                    mockSysUiState.flags and
                        QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED != 0L
                )
                .isTrue()
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_lockscreenDisabledByLockPatternUtils() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val canWakeDirectlyToGone by
                collectLastValue(keyguardWakeDirectlyToGoneInteractor.canWakeDirectlyToGone)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = true,
            )

            // Do not suppress keyguard at start
            whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false)

            // Even when lockscreen is disabled, device must be unlocked to make sure locked SIM
            // state is detected.
            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(true, deviceUnlockSource = null)

            powerInteractor.setAsleepForTest()
            underTest.start()
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(canWakeDirectlyToGone).isFalse()

            // Change disabled state, which will be read when the device is awake
            whenever(lockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(true)

            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodNone_canIgnoreAuth() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val canWakeDirectlyToGone by
                collectLastValue(keyguardWakeDirectlyToGoneInteractor.canWakeDirectlyToGone)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = true,
            )
            powerInteractor.setAsleepForTest()
            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            kosmos.keyguardRepository.setCanIgnoreAuthAndReturnToGone(true)
            // Even when lockscreen is disabled, device must be unlocked to make sure locked SIM
            // state is detected.
            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(true, deviceUnlockSource = null)
            runCurrent()
            assertThat(canWakeDirectlyToGone).isTrue()

            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_whenDeviceNotProvisioned() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val canWakeDirectlyToGone by
                collectLastValue(keyguardWakeDirectlyToGoneInteractor.canWakeDirectlyToGone)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = true,
                isDeviceProvisioned = false,
            )
            powerInteractor.setAsleepForTest()
            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(canWakeDirectlyToGone).isTrue()

            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUpWhileTransitioningToLockscreenAndCanIgnoreAuth() =
        kosmos.runTest {
            enableSingleShade()
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val canWakeDirectlyToGone by
                collectLastValue(keyguardWakeDirectlyToGoneInteractor.canWakeDirectlyToGone)
            prepareState(
                initialSceneKey = Scenes.Shade,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = false,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()

            // Device unlock status is reset only when the lock screen timeout expires
            deviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(true, deviceUnlockSource = null)
            runCurrent()
            kosmos.keyguardRepository.setCanIgnoreAuthAndReturnToGone(true)
            runCurrent()
            assertThat(canWakeDirectlyToGone).isTrue()

            setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            )

            powerInteractor.setAwakeForTest()
            runCurrent()

            // The device should transition to Gone if it `canWakeDirectlyToGone`, even when it's
            // already transitioning to the Lockscreen.
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun doesNotSwitchToGoneWhenDeviceStartsToWakeUp_authMethodNone_canNotIgnoreAuth() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = true,
            )
            powerInteractor.setAsleepForTest()
            kosmos.keyguardRepository.setCanIgnoreAuthAndReturnToGone(false)
            underTest.start()
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun stayOnLockscreenWhenDeviceStartsToWakeUp_authMethodSwipe() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isKeyguardEnabled = true,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenDeviceStartsToWakeUp_authMethodSecure() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenDeviceStartsToWakeUp_ifAlreadyTransitioningToLockscreen() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitioningTo by collectLastValue(sceneInteractor.transitioningTo)
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(transitioningTo).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(transitioningTo).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodSecure_deviceUnlocked() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                unlockStateInt = BiometricUnlockController.MODE_WAKE_AND_DISMISS,
                biometricUnlockSource = BiometricUnlockSource.FINGERPRINT_SENSOR,
            )
            runCurrent()
            powerInteractor.setAwakeForTest()
            runCurrent()
            assertThat(fakeSceneDataSource.currentSceneTransitionKey).isEqualTo(AodToGoneTransition)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun collectFalsingSignals_onSuccessfulUnlock() =
        kosmos.runTest {
            enableSingleShade()
            val currentScene by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(mockFalsingCollector, never()).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Lockscreen).forEach {
                sceneKey ->
                setSceneTransition(ObservableTransitionState.Idle(sceneKey))
                runCurrent()
                verify(mockFalsingCollector, never()).onSuccessfulUnlock()
            }

            // Changing to the Gone scene should report a successful unlock.
            authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            runCurrent()
            // Make sure that the startable changed the scene to Gone because the device unlocked.
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            // Make the transition state match the current state
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            runCurrent()
            verify(mockFalsingCollector).onSuccessfulUnlock()

            // Move around scenes without changing back to Lockscreen, shouldn't report another
            // unlock.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Gone).forEach { sceneKey
                ->
                sceneInteractor.changeScene(sceneKey, "reason")
                setSceneTransition(ObservableTransitionState.Idle(sceneKey))
                runCurrent()
                verify(mockFalsingCollector, times(1)).onSuccessfulUnlock()
            }

            // Putting the device to sleep to lock it again, which shouldn't report another
            // successful unlock.
            powerInteractor.setAsleepForTest()
            runCurrent()
            kosmos.lockAfterDelayInteractor.timeoutElapsedForTesting()
            runCurrent()
            // Verify that the startable changed the scene to Lockscreen because the device locked
            // following the sleep.
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            // Make the transition state match the current state.
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Lockscreen))
            // Wake up the device again before continuing with the test.
            powerInteractor.setAwakeForTest()
            runCurrent()
            // Verify that the current scene is still the Lockscreen scene, now that the device is
            // still locked.
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(mockFalsingCollector, times(1)).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Lockscreen).forEach {
                sceneKey ->
                sceneInteractor.changeScene(sceneKey, "reason")
                setSceneTransition(ObservableTransitionState.Idle(sceneKey))
                runCurrent()
                verify(mockFalsingCollector, times(1)).onSuccessfulUnlock()
            }

            authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            runCurrent()
            // Make sure that the startable changed the scene to Gone because the device unlocked.
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            // Make the transition state match the current scene.
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            runCurrent()
            verify(mockFalsingCollector, times(2)).onSuccessfulUnlock()
        }

    @Test
    fun collectFalsingSignals_setShowingAod() =
        kosmos.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(mockFalsingCollector).setShowingAod(false)

            fakeKeyguardRepository.setIsDozing(true)
            runCurrent()
            verify(mockFalsingCollector).setShowingAod(true)

            fakeKeyguardRepository.setIsDozing(false)
            runCurrent()
            verify(mockFalsingCollector, times(2)).setShowingAod(false)
        }

    @Test
    fun bouncerImeHidden_shouldTransitionBackToLockscreen() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Password,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            bouncerInteractor.onImeHiddenByUser()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff() =
        kosmos.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()
            verify(mockFalsingCollector, never()).onScreenTurningOn()
            verify(mockFalsingCollector, never()).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(1)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(mockFalsingCollector, times(1)).onScreenTurningOn()
            verify(mockFalsingCollector, never()).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(1)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(mockFalsingCollector, times(1)).onScreenTurningOn()
            verify(mockFalsingCollector, never()).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(2)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(mockFalsingCollector, times(1)).onScreenTurningOn()
            verify(mockFalsingCollector, times(1)).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(2)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(mockFalsingCollector, times(1)).onScreenTurningOn()
            verify(mockFalsingCollector, times(1)).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(3)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(mockFalsingCollector, times(2)).onScreenTurningOn()
            verify(mockFalsingCollector, times(1)).onScreenOnFromTouch()
            verify(mockFalsingCollector, times(3)).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_bouncerVisibility() =
        kosmos.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(mockFalsingCollector).onBouncerHidden()

            sceneInteractor.showOverlay(Overlays.Bouncer, "reason")
            runCurrent()
            verify(mockFalsingCollector).onBouncerShown()

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()
            sceneInteractor.hideOverlay(Overlays.Bouncer, "reason")
            runCurrent()
            verify(mockFalsingCollector, times(2)).onBouncerHidden()
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun switchesToBouncer_whenSimBecomesLocked() =
        kosmos.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            enableDualShade()

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays =
                    setOf(
                        Overlays.NotificationsShade,
                        Overlays.QuickSettingsShade,
                        Overlays.Bouncer,
                    ),
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            fakeMobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            assertThat(currentOverlays).contains(Overlays.Bouncer)
        }

    @Test
    fun switchesToLockscreenWhenInGone_whenSimBecomesLocked() =
        kosmos.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
                authenticationMethod = AuthenticationMethodModel.None,
            )
            underTest.start()

            fakeMobileConnectionsRepository.isAnySimSecure.value = true

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchesToLockscreen_whenSimBecomesUnlocked() =
        kosmos.runTest {
            fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchesToGone_whenSimBecomesUnlocked_ifDeviceUnlockedAndLockscreenDisabled() =
        kosmos.runTest {
            fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isDeviceUnlocked = true,
                isKeyguardEnabled = false,
            )
            underTest.start()
            runCurrent()
            fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun hydrateWindowController_setNotificationShadeFocusable() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
            )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            verify(notificationShadeWindowController, never())
                .setNotificationShadeFocusable(anyBoolean())

            underTest.start()
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(false)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            )
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(false)
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.transitionState = TransitionState.Idle(Scenes.Shade)
            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Shade))
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            setSceneTransition(
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Gone,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            )
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
            runCurrent()
            verify(notificationShadeWindowController, times(2)).setNotificationShadeFocusable(false)
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateWindowController_setNotificationShadeFocusable_dual_shade() =
        kosmos.runTest {
            enableDualShade()
            runCurrent()
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            verify(notificationShadeWindowController, never())
                .setNotificationShadeFocusable(anyBoolean())

            underTest.start()
            runCurrent()

            // By default, the notification shade window should not be focusable.
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(false)
            verify(notificationShadeWindowController, times(0)).setNotificationShadeFocusable(true)

            sceneInteractor.showOverlay(Overlays.QuickSettingsShade, loggingReason = "")
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.Gone, setOf(Overlays.QuickSettingsShade))
            )

            // When showing the Quick Settings shade with the `Gone` scene, the notification shade
            // window should be focusable.
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(false)
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(true)

            sceneInteractor.showOverlay(Overlays.NotificationsShade, loggingReason = "")
            sceneInteractor.hideOverlay(Overlays.QuickSettingsShade, loggingReason = "")
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.Gone, setOf(Overlays.NotificationsShade))
            )

            // When showing the notification shade with the `Gone` scene, the notification shade
            // window should be focusable.
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(false)
            verify(notificationShadeWindowController, times(1)).setNotificationShadeFocusable(true)
        }

    @Test
    fun hydrateWindowController_setKeyguardShowing() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            underTest.start()
            prepareState(initialSceneKey = Scenes.Lockscreen)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(notificationShadeWindowController).setKeyguardShowing(true)

            emulateOverlayTransition(Overlays.Bouncer)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(Scenes.Lockscreen)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(Scenes.Shade)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(Scenes.Lockscreen)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)
        }

    @Test
    fun hydrateWindowController_setKeyguardOccluded() =
        kosmos.runTest {
            underTest.start()
            prepareState(initialSceneKey = Scenes.Lockscreen)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(notificationShadeWindowController, never()).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(false)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(false)

            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(2)).setKeyguardOccluded(false)
        }

    @Test
    fun hydrateInteractionState_whileLocked() =
        kosmos.runTest {
            enableSingleShade()
            prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun hydrateInteractionState_whileUnlocked() =
        kosmos.runTest {
            enableSingleShade()
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
            )
            underTest.start()
            verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateInteractionState_dualShade_whileLocked() =
        kosmos.runTest {
            enableDualShade()
            val currentDesiredOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
            assertThat(currentDesiredOverlays).isEmpty()

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.NotificationsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.QuickSettingsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun hydrateInteractionState_dualShade_whileUnlocked() =
        kosmos.runTest {
            enableDualShade()
            val currentDesiredOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
            )
            underTest.start()
            verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
            assertThat(currentDesiredOverlays).isEmpty()

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.NotificationsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                toOverlay = Overlays.QuickSettingsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun respondToFalsingDetections() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState()
            underTest.start()
            runCurrent()
            emulateOverlayTransition(toOverlay = Overlays.Bouncer)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            falsingManager.sendFalsingBelief()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun handleBouncerOverscroll() =
        kosmos.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState()
            underTest.start()
            runCurrent()
            emulateOverlayTransition(toOverlay = Overlays.Bouncer)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            setSceneTransition(
                ObservableTransitionState.Transition.hideOverlay(
                    overlay = Overlays.Bouncer,
                    toScene = Scenes.Lockscreen,
                    currentOverlays = flowOf(setOf(Overlays.Bouncer)),
                    progress = flowOf(-0.4f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                )
            )
            runCurrent()

            assertThat(fakeDeviceEntryFaceAuthRepository.isAuthRunning.value).isTrue()
        }

    @Test
    fun handleOcclusion_exitsToGone() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            unlockDevice()
            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun handleOcclusion_exitsToLockscreen() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            prepareState(isDeviceUnlocked = false)
            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun handleOcclusion_exitsToCommunal() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            underTest.start()
            runCurrent()
            sceneInteractor.changeScene(Scenes.Communal, "test")
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Communal)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Communal)
        }

    @Test
    fun handleOcclusion_unoccludeBehindBouncer_bouncerStaysVisible() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)
            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
        }

    @Test
    fun handleOcclusion_unoccludeBehindShade_occludedRemovedFromBackStack() =
        kosmos.runTest {
            enableSingleShade()

            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val backStack by collectLastValue(sceneBackInteractor.backStack)

            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            runCurrent()

            assertThat(backStack?.asIterable()?.toList()).isEqualTo(listOf(Scenes.Lockscreen))
            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            sceneInteractor.changeScene(Scenes.Shade, "")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.toList())
                .isEqualTo(listOf(Scenes.Occluded, Scenes.Lockscreen))

            // Changing occlusion state underneath the shade should remove it from the backstack
            keyguardOcclusionInteractor.setOccludedFromWm(false)
            runCurrent()

            assertThat(backStack?.asIterable()?.toList()).isEqualTo(listOf(Scenes.Lockscreen))
            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_switchesToDream() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, dreamTaskInfo)

            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_switchesToDream_whenNoneButDreaming() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            keyguardOcclusionRepository.setOccludedFromRemoteAnimation(true, dreamTaskInfo)

            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_switchesToOccluded() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val appTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_STANDARD
                }
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, appTaskInfo)

            assertThat(currentScene).isEqualTo(Scenes.Occluded)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_unoccludeFromDreamToLockscreen() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            prepareState()
            underTest.start()
            runCurrent()

            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, dreamTaskInfo)
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            keyguardOcclusionInteractor.setOccludedFromWm(false)

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_unoccludeFromDreamToGone_whenUnlocked() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            prepareState(isDeviceUnlocked = true)
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            // Start dreaming
            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, dreamTaskInfo)
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            // Stop dreaming
            keyguardOcclusionInteractor.setOccludedFromWm(false)

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_switchesToDream_hidesBouncer() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            prepareState()
            underTest.start()
            runCurrent()

            // Show bouncer
            sceneInteractor.showOverlay(Overlays.Bouncer, "test")
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            // Start dream
            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, dreamTaskInfo)

            // Should transition to Dream and hide bouncer
            assertThat(currentScene).isEqualTo(Scenes.Dream)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun handleOcclusionAndDreaming_unoccludeFromAppToCommunal() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val appTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_STANDARD
                }
            prepareState()
            underTest.start()
            runCurrent()

            // Go to communal, then occlude
            sceneInteractor.changeScene(Scenes.Communal, "test")
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, appTaskInfo)
            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            // Unocclude
            keyguardOcclusionInteractor.setOccludedFromWm(false)

            assertThat(currentScene).isEqualTo(Scenes.Communal)
        }

    @Test
    fun handleOcclusionAndDreaming_unoccludeFromAppBehindBouncer_bouncerStays() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val appTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_STANDARD
                }
            prepareState()
            underTest.start()
            runCurrent()

            // Occlude
            keyguardOcclusionInteractor.setOccludedFromRemoteAnimation(true, appTaskInfo)
            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            // Show bouncer
            sceneInteractor.showOverlay(Overlays.Bouncer, "test")
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            // Unocclude
            keyguardOcclusionInteractor.setOccludedFromWm(false)

            // Should stay on Lockscreen with Bouncer
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
        }

    @Test
    fun switchToLockscreen_whenShadeBecomesNotTouchable() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isShadeTouchable by collectLastValue(shadeInteractor.isShadeTouchable)
            prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            // Flung to bouncer, 90% of the way there:
            setSceneTransition(
                ObservableTransitionState.Transition.showOverlay(
                    overlay = Overlays.Bouncer,
                    fromScene = Scenes.Lockscreen,
                    currentOverlays = flowOf(setOf(Overlays.Bouncer)),
                    progress = flowOf(0.9f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            fakePowerRepository.updateWakefulness(WakefulnessState.ASLEEP)
            runCurrent()
            assertThat(isShadeTouchable).isFalse()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchToGone_whenKeyguardBecomesDisabled() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToGone_whenKeyguardBecomesDisabled_whenOnShadeScene() =
        kosmos.runTest {
            enableSingleShade()
            runCurrent()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(initialSceneKey = Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            underTest.start()

            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun doesNotSwitchToGone_whenKeyguardBecomesDisabled_whenInLockdownMode() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            fakeBiometricSettingsRepository.setIsUserInLockdown(true)
            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenKeyguardBecomesDisabled_whenDeviceEntered() =
        kosmos.runTest {
            enableSingleShade()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isTrue()
            underTest.start()
            runCurrent()
            sceneInteractor.changeScene(Scenes.Shade, "")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(deviceEntryInteractor.isDeviceEntered.value).isTrue()

            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun switchToLockscreen_whenKeyguardBecomesEnabled_afterHidingWhenDisabled() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            fakeBiometricSettingsRepository.setIsUserInLockdown(false)
            runCurrent()
            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToDream_whenKeyguardBecomesEnabled_afterHidingWhenDisabled_ifDreaming() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            if (DriveDreamStateFromOcclusion.isEnabled) {
                keyguardOcclusionRepository.setOccludedFromRemoteAnimation(true, dreamTaskInfo)
            } else {
                keyguardInteractor.setDreaming(true)
                fakeKeyguardRepository.setDozeTransitionModel(
                    DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
                )
                advanceTimeBy(600L)
            }
            runCurrent()
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            fakeBiometricSettingsRepository.setIsUserInLockdown(false)
            runCurrent()
            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @Test
    fun doesNotSwitchToLockscreen_ifDisabled_whileGone() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            underTest.start()
            runCurrent()
            fakeBiometricSettingsRepository.setIsUserInLockdown(false)
            runCurrent()
            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun lockNow_whileKeyguardDisabled_keyguardShowsWhenReenabled() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            underTest.start()
            runCurrent()
            fakeBiometricSettingsRepository.setIsUserInLockdown(false)
            runCurrent()
            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            kosmos.keyguardServiceShowLockscreenInteractor.onKeyguardServiceDoKeyguardTimeout()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)

            kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                unlockStateInt = BiometricUnlockController.MODE_NONE,
                biometricUnlockSource = null,
            )
            keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToLockscreen_whenKeyguardBecomesEnabled_ifAuthMethodBecameInsecure() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            runCurrent()

            keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun notifyKeyguardDismissCallbacks_whenLeavingBouncer_onDismissCancelled() =
        kosmos.runTest {
            val isUnlocked by collectLastValue(deviceEntryInteractor.isUnlocked)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState()
            underTest.start()
            runCurrent()

            // Run all pending dismiss succeeded/cancelled calls from setup:
            fakeExecutor.runAllReady()

            val dismissCallback: IKeyguardDismissCallback = mock()
            dismissCallbackRegistry.addCallback(dismissCallback)

            // Switch to bouncer:
            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            runCurrent()

            // Return to lockscreen when isUnlocked=false:
            sceneInteractor.hideOverlay(Overlays.Bouncer, "")
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isUnlocked).isFalse()
            runCurrent()
            fakeExecutor.runAllReady()

            verify(dismissCallback).onDismissCancelled()
        }

    @Test
    fun handlePowerState_returnsToLsFromBouncer_whenGoesToSleep() =
        kosmos.runTest {
            val authMethod by collectLastValue(authenticationInteractor.authenticationMethod)
            val isUnlocked by
                collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked })
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isAwake by collectLastValue(powerInteractor.isAwake)
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                startsAwake = true,
            )
            underTest.start()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isAwake).isTrue()

            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            assertThat(isAwake).isTrue()

            powerInteractor.setAsleepForTest()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isAwake).isFalse()
        }

    @Test
    fun hidesBouncer_whenAuthMethodChangesToNonSecure() =
        kosmos.runTest {
            val authMethod by collectLastValue(authenticationInteractor.authenticationMethod)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Password,
                initialSceneKey = Scenes.Lockscreen,
            )
            underTest.start()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)

            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            runCurrent()

            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.None)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun replacesLockscreenSceneOnBackStack_whenFaceUnlocked_fromShade_noAlternateBouncer() =
        kosmos.runTest {
            enableSingleShade()
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            underTest.start()
            runCurrent()

            val isUnlocked by
                collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked })
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backStack by collectLastValue(sceneBackInteractor.backStack)
            val isAlternateBouncerVisible by collectLastValue(alternateBouncerInteractor.isVisible)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Change to shade.
            sceneInteractor.changeScene(Scenes.Shade, "")
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Shade))
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Show the alternate bouncer.
            alternateBouncerInteractor.forceShow()
            statusBarStateController.setLeaveOpenOnKeyguardHide(true) // leave shade open
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isTrue()

            // Simulate race condition by hiding the alternate bouncer *before* the face unlock:
            alternateBouncerInteractor.hide()
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Trigger a face unlock.
            updateFaceAuthStatus(isSuccess = true)
            runCurrent()
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Gone)
            assertThat(isAlternateBouncerVisible).isFalse()
        }

    @Test
    fun handleDisableFlags_singleShade() =
        kosmos.runTest {
            underTest.start()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            enableSingleShade()
            runCurrent()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Shade)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            runCurrent()

            assertThat(currentScene).isNotEqualTo(Scenes.Shade)
        }

    @Test
    @EnableFlags(FLAG_DUAL_SHADE)
    fun handleDisableFlags_dualShade() =
        kosmos.runTest {
            underTest.start()
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            enableDualShade()
            runCurrent()
            sceneInteractor.showOverlay(Overlays.NotificationsShade, "reason")
            assertThat(currentOverlays).contains(Overlays.NotificationsShade)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            runCurrent()

            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun hydrateActivityTransitionAnimationState() =
        kosmos.runTest {
            underTest.start()

            assertThat(sceneInteractor.isVisible).isTrue()

            unlockDevice()
            sceneInteractor.changeScene(Scenes.Gone, "Switch to Gone to make isVisible be false.")
            sceneInteractor.onIdleSceneEnteredComposition(Scenes.Gone)
            assertThat(sceneInteractor.isVisible).isFalse()

            val argumentCaptor = argumentCaptor<ActivityTransitionAnimator.Listener>()
            verify(mockActivityTransitionAnimator).addListener(argumentCaptor.capture())

            val listeners = argumentCaptor.allValues
            listeners.forEach { it.onTransitionAnimationStart() }
            assertThat(sceneInteractor.isVisible).isTrue()
            listeners.forEach { it.onTransitionAnimationEnd(mock<SurfaceControl.Transaction>()) }
            assertThat(sceneInteractor.isVisible).isFalse()
        }

    @Test
    fun deviceLocks_whenNoLongerTrusted_whileDeviceNotEntered() =
        kosmos.runTest {
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            underTest.start()

            val isDeviceEntered by collectLastValue(deviceEntryInteractor.isDeviceEntered)
            val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(isDeviceEntered).isTrue()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            fakeTrustRepository.setCurrentUserTrusted(true)
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            runCurrent()
            assertThat(isDeviceEntered).isFalse()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            fakeTrustRepository.setCurrentUserTrusted(false)
            runCurrent()

            assertThat(isDeviceEntered).isFalse()
            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToLockscreen_showDismissibleKeyguard_ifOccluded() =
        kosmos.runTest {
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Occluded)
            underTest.start()

            val currentScene by collectLastValue(sceneInteractor.currentScene)

            kosmos.powerInteractor.setAwakeForTest()
            kosmos.keyguardOcclusionRepository.setOccludedFromRemoteAnimation(true, null)
            kosmos.keyguardServiceShowLockscreenInteractor.showNowEvents.tryEmit(
                ShowWhileAwakeReason.FOLDED_WITH_SWIPE_UP_TO_CONTINUE
            )

            assertThat(currentScene).isEqualTo(Scenes.Occluded)
        }

    @Test
    fun doesNotSwitchToLockscreen_showDismissibleKeyguard_ifDisabled() =
        kosmos.runTest {
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            underTest.start()

            val currentScene by collectLastValue(sceneInteractor.currentScene)

            kosmos.fakeKeyguardRepository.setKeyguardEnabled(false)
            kosmos.keyguardServiceShowLockscreenInteractor.showNowEvents.tryEmit(
                ShowWhileAwakeReason.FOLDED_WITH_SWIPE_UP_TO_CONTINUE
            )

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchesToLockscreen_onTimeout() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            underTest.start()
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            keyguardServiceShowLockscreenInteractor.onKeyguardServiceDoKeyguardTimeout()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToLockscreen_onTimeout_ifOccluded() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false)
            underTest.start()
            runCurrent()

            keyguardOcclusionInteractor.setOccludedFromWm(true)
            assertThat(currentScene).isEqualTo(Scenes.Occluded)

            keyguardServiceShowLockscreenInteractor.onKeyguardServiceDoKeyguardTimeout()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Occluded)
        }

    @Test
    @EnableFlags(FLAG_DRIVE_DREAM_STATE_FROM_OCCLUSION)
    fun doesNotSwitchToLockscreen_onTimeout_ifDreaming() =
        kosmos.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false)
            underTest.start()
            runCurrent()

            val dreamTaskInfo =
                RunningTaskInfo().apply {
                    topActivityType = WindowConfiguration.ACTIVITY_TYPE_DREAM
                }
            keyguardOcclusionRepository.setOccludedFromRemoteAnimation(true, dreamTaskInfo)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            keyguardServiceShowLockscreenInteractor.onKeyguardServiceDoKeyguardTimeout()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @EnableFlags(FLAG_SECURE_LOCK_DEVICE)
    @Test
    fun doesNotUnlock_onFaceAuthSuccess_untilConfirmedAndReadyToDismissInSecureLockDevice() =
        kosmos.runTest {
            val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isSecureLockDeviceEnabled by
                collectLastValue(kosmos.secureLockDeviceInteractor.isSecureLockDeviceEnabled)
            val isFullyUnlockedAndReadyToDismiss by
                collectLastValue(kosmos.secureLockDeviceInteractor.isFullyUnlockedAndReadyToDismiss)

            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            kosmos.fakeSecureLockDeviceRepository.onSecureLockDeviceEnabled()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.showOverlay(Overlays.Bouncer, "showing bouncer for test")
            setSceneTransition(
                ObservableTransitionState.Idle(Scenes.Lockscreen, setOf(Overlays.Bouncer))
            )
            runCurrent()
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            kosmos.fakeSecureLockDeviceRepository.onSuccessfulPrimaryAuth()
            kosmos.secureLockDeviceInteractor.onBiometricAuthRequested()

            updateFaceAuthStatus(isSuccess = true)

            assertThat(isSecureLockDeviceEnabled).isTrue()
            assertThat(isFullyUnlockedAndReadyToDismiss).isFalse()
            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
            assertThat(deviceUnlockStatus?.deviceUnlockSource).isNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            // Face auth confirm button clicked, pending -> confirmed auth animation played.
            kosmos.secureLockDeviceInteractor.onReadyToDismissBiometricAuth()
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.SecureLockDeviceTwoFactorAuth)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    private fun Kosmos.emulateSceneTransition(
        toScene: SceneKey,
        verifyBeforeTransition: (() -> Unit)? = null,
        verifyDuringTransition: (() -> Unit)? = null,
        verifyAfterTransition: (() -> Unit)? = null,
    ) {
        val fromScene = sceneInteractor.currentScene.value
        val fromOverlays = sceneInteractor.currentOverlays.value
        sceneInteractor.changeScene(toScene, "reason")
        runCurrent()
        verifyBeforeTransition?.invoke()

        setSceneTransition(
            if (fromOverlays.isEmpty()) {
                // Regular scene-to-scene transition.
                ObservableTransitionState.Transition.ChangeScene(
                    fromScene = fromScene,
                    toScene = toScene,
                    currentScene = flowOf(fromScene),
                    currentOverlays = fromOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            } else {
                // An overlay is present; hide it.
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = fromOverlays.first(),
                    fromContent = fromOverlays.first(),
                    toContent = toScene,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            }
        )
        runCurrent()
        verifyDuringTransition?.invoke()

        setSceneTransition(ObservableTransitionState.Idle(currentScene = toScene))
        runCurrent()
        verifyAfterTransition?.invoke()
    }

    private fun Kosmos.emulateOverlayTransition(
        toOverlay: OverlayKey,
        verifyBeforeTransition: (() -> Unit)? = null,
        verifyDuringTransition: (() -> Unit)? = null,
        verifyAfterTransition: (() -> Unit)? = null,
    ) {
        val fromScene = sceneInteractor.currentScene.value
        val fromOverlays = sceneInteractor.currentOverlays.value
        sceneInteractor.showOverlay(toOverlay, "reason")
        runCurrent()
        verifyBeforeTransition?.invoke()

        setSceneTransition(
            if (fromOverlays.isEmpty()) {
                // Show a new overlay.
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = toOverlay,
                    fromContent = fromScene,
                    toContent = toOverlay,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            } else {
                // Replace existing overlay.
                ObservableTransitionState.Transition.ReplaceOverlay(
                    fromOverlay = fromOverlays.first(),
                    toOverlay = toOverlay,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            }
        )
        runCurrent()
        verifyDuringTransition?.invoke()

        setSceneTransition(
            ObservableTransitionState.Idle(
                currentScene = fromScene,
                currentOverlays = setOf(toOverlay),
            )
        )
        runCurrent()
        verifyAfterTransition?.invoke()
    }

    private fun Kosmos.prepareState(
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
        initialOverlays: Set<OverlayKey> = emptySet(),
        authenticationMethod: AuthenticationMethodModel? = null,
        isKeyguardEnabled: Boolean = true,
        startsAwake: Boolean = true,
        isDeviceProvisioned: Boolean = true,
        isInteractive: Boolean = true,
    ) {
        if (isDeviceUnlocked) {
            kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                unlockStateInt = BiometricUnlockController.MODE_DISMISS,
                biometricUnlockSource = BiometricUnlockSource.FINGERPRINT_SENSOR,
            )
        }

        check(initialSceneKey != Scenes.Gone || isDeviceUnlocked) {
            "Cannot start on the Gone scene and have the device be locked at the same time."
        }

        fakeDeviceEntryBypassRepository.setBypassEnabled(isBypassEnabled)
        authenticationMethod?.let { fakeAuthenticationRepository.setAuthenticationMethod(it) }
        fakeKeyguardRepository.setKeyguardEnabled(isKeyguardEnabled)

        val initialScene = initialSceneKey ?: Scenes.Lockscreen
        if (isDeviceUnlocked && initialScene != Scenes.Gone) {
            setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
        }
        setSceneTransition(ObservableTransitionState.Idle(initialScene, initialOverlays))

        if (startsAwake) {
            powerInteractor.setAwakeForTest()
        } else {
            powerInteractor.setAsleepForTest()
        }
        fakePowerRepository.setInteractive(isInteractive)

        fakeDeviceProvisioningRepository.setDeviceProvisioned(isDeviceProvisioned)

        runCurrent()
    }

    private fun buildNotificationRows(isPinned: Boolean = false): List<HeadsUpRowRepository> =
        listOf(
            fakeHeadsUpRowRepository(key = "0", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "1", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "2", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "3", isPinned = isPinned),
        )

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean) =
        FakeHeadsUpRowRepository(key = key, elementKey = Any(), isPinned = isPinned)

    private fun Kosmos.setFingerprintSensorType(fingerprintSensorType: FingerprintSensorType) {
        fingerprintPropertyRepository.setProperties(
            sensorId = 0,
            strength = SensorStrength.STRONG,
            sensorType = fingerprintSensorType,
            sensorLocations = mapOf(),
        )
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }

    private fun setFaceEnrolled() {
        kosmos.biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
    }

    private fun Kosmos.allowHapticsOnSfps(
        isPowerButtonDown: Boolean = false,
        lastPowerPress: Long = 10000,
    ) {
        fakeKeyEventRepository.setPowerButtonDown(isPowerButtonDown)

        powerRepository.updateWakefulness(
            WakefulnessState.AWAKE,
            WakeSleepReason.POWER_BUTTON,
            WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = false,
        )

        testScope.advanceTimeBy(lastPowerPress)
        runCurrent()
    }

    private fun Kosmos.setupBiometricAuth(
        hasSfps: Boolean = false,
        hasUdfps: Boolean = false,
        hasFace: Boolean = false,
    ) {
        if (hasSfps) {
            setFingerprintSensorType(FingerprintSensorType.POWER_BUTTON)
        }

        if (hasUdfps) {
            setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
        }

        if (hasFace) {
            setFaceEnrolled()
        }

        prepareState(
            authenticationMethod = AuthenticationMethodModel.Pin,
            isDeviceUnlocked = false,
            initialSceneKey = Scenes.Lockscreen,
        )
    }

    private fun Kosmos.updateFingerprintAuthStatus(isSuccess: Boolean) {
        fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            if (isSuccess) {
                kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                    unlockStateInt = BiometricUnlockController.MODE_DISMISS,
                    biometricUnlockSource = BiometricUnlockSource.FINGERPRINT_SENSOR,
                )
                SuccessFingerprintAuthenticationStatus(0, true)
            } else {
                FailFingerprintAuthenticationStatus
            }
        )
    }

    private fun Kosmos.updateFaceAuthStatus(isSuccess: Boolean) {
        with(fakeDeviceEntryFaceAuthRepository) {
            isCurrentUserAuthenticated.value = isSuccess
            setAuthenticationStatus(
                if (isSuccess) {
                    kosmos.biometricUnlockInteractor.setBiometricUnlockState(
                        unlockStateInt = BiometricUnlockController.MODE_DISMISS,
                        biometricUnlockSource = BiometricUnlockSource.FACE_SENSOR,
                    )
                    SuccessFaceAuthenticationStatus(
                        successResult = Mockito.mock(FaceManager.AuthenticationResult::class.java)
                    )
                } else {
                    FailedFaceAuthenticationStatus()
                }
            )
        }
    }
}
