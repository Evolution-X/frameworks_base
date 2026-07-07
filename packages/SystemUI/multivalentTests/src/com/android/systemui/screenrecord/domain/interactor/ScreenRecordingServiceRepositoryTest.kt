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

package com.android.systemui.screenrecord.domain.interactor

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.applicationContext
import android.content.mockedContext
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.projection.StopReason
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.advanceTimeBy
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.screencapture.record.shared.screenRecordingLogger
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.data.repository.ScreenRecordingServiceRepository
import com.android.systemui.screenrecord.screenRecordUxController
import com.android.systemui.screenrecord.service.FakeScreenRecordingServiceCallbackWrapper
import com.android.systemui.screenrecord.service.callbackStatus
import com.android.systemui.screenrecord.service.fakeScreenRecordingService
import com.android.systemui.screenrecord.shared.model.ScreenRecording
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import com.android.systemui.testKosmosNew
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private val componentName = ComponentName("com.android.systemui", "test")
private val defaultParams =
    ScreenRecordingParameters(
        captureTarget = null,
        audioSource = ScreenRecordingAudioSource.NONE,
        displayId = 0,
        shouldShowTaps = false,
    )

@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenRecordingServiceRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmosNew().apply { applicationContext = mockedContext }

    private var serviceConnection: ServiceConnection? = null

    private val underTest: ScreenRecordingServiceRepository by lazy {
        // Use custom instance of the ScreenRecordingServiceRepository because the one in the
        // Kosmos simplifies setting up other tests, where's here we want to check that
        // it correctly interacts with the Context
        with(kosmos) {
            ScreenRecordingServiceRepository(
                applicationCoroutineScope,
                screenRecordUxController,
                ScreenRecordingServiceRepository.bindServiceAsAFlow(
                    applicationContext,
                    userRepository,
                ) { _, _ ->
                    fakeScreenRecordingService
                },
                screenRecordingLogger,
            )
        }
    }

    @Before
    fun setUp() {
        with(kosmos) {
            whenever(applicationContext.createContextAsUser(any(), any()))
                .thenReturn(applicationContext)
            whenever(
                    applicationContext.bindService(
                        any<Intent>(),
                        any<ServiceConnection>(),
                        anyInt(),
                    )
                )
                .then {
                    serviceConnection =
                        (it.arguments[1] as ServiceConnection).apply {
                            onServiceConnected(componentName, fakeScreenRecordingService)
                        }
                    true
                }
        }
    }

    @Test
    fun testStartRecording_startsRecording() =
        kosmos.runTest {
            val interactorStatus: ScreenRecordingStatus? by collectLastValue(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            underTest.startRecording()

            assertThat(interactorStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Started::class.java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStartRecording_startsRecording_externalDisplay() =
        kosmos.runTest {
            val interactorStatus: ScreenRecordingStatus? by collectLastValue(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            val externalDisplayParams =
                ScreenRecordingParameters(
                    captureTarget = null,
                    audioSource = ScreenRecordingAudioSource.NONE,
                    displayId = 2,
                    shouldShowTaps = false,
                )
            underTest.startRecording(externalDisplayParams)

            assertThat(interactorStatus)
                .isEqualTo(ScreenRecordingStatus.Started(externalDisplayParams))
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Started::class.java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStopRecording_stopsRecording() =
        kosmos.runTest {
            val interactorStatus: ScreenRecordingStatus? by collectLastValue(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)
            underTest.startRecording()

            underTest.stopRecording(StopReason.STOP_HOST_APP)

            assertThat(interactorStatus)
                .isEqualTo(ScreenRecordingStatus.Stopped(StopReason.STOP_HOST_APP))
            assertThat(serviceStatus)
                .isEqualTo(ScreenRecordingStatus.Stopped(StopReason.STOP_HOST_APP))
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Interrupted::class
                        .java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStartRecordingDelayed_startsRecordingAfterDelay() =
        kosmos.runTest {
            val interactorStatuses: List<ScreenRecordingStatus>? by collectValues(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            underTest.startRecordingDelayed()
            advanceTimeBy(3.seconds)

            assertThat(interactorStatuses)
                .containsExactly(
                    ScreenRecordingStatus.Stopped(
                        ScreenRecordingStatus.Stopped.STOP_REASON_NOT_STARTED
                    ),
                    ScreenRecordingStatus.Starting(3.seconds, defaultParams),
                    ScreenRecordingStatus.Starting(2.seconds, defaultParams),
                    ScreenRecordingStatus.Starting(1.seconds, defaultParams),
                    ScreenRecordingStatus.Starting(0.seconds, defaultParams),
                    ScreenRecordingStatus.Started(defaultParams),
                )
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Started::class.java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStartRecording_overridesStartWithDelay() =
        kosmos.runTest {
            val interactorStatuses: List<ScreenRecordingStatus>? by collectValues(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            underTest.startRecordingDelayed()
            underTest.startRecording()

            assertThat(interactorStatuses)
                .containsExactly(
                    ScreenRecordingStatus.Stopped(
                        ScreenRecordingStatus.Stopped.STOP_REASON_NOT_STARTED
                    ),
                    ScreenRecordingStatus.Starting(3.seconds, defaultParams),
                    ScreenRecordingStatus.Started(defaultParams),
                )
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Started::class.java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStartRecordingDelayed_noOpWhenAlreadyStarted() =
        kosmos.runTest {
            val interactorStatuses: List<ScreenRecordingStatus>? by collectValues(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            underTest.startRecording()
            underTest.startRecordingDelayed()

            assertThat(interactorStatuses)
                .containsExactly(
                    ScreenRecordingStatus.Stopped(
                        ScreenRecordingStatus.Stopped.STOP_REASON_NOT_STARTED
                    ),
                    ScreenRecordingStatus.Started(defaultParams),
                )
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Started::class.java)
            assertThat(callbackStatus)
                .isInstanceOf(
                    FakeScreenRecordingServiceCallbackWrapper.RecordingStatus.Started::class.java
                )
            assertThat(fakeScreenRecordingService.currentCallback).isNotNull()
        }

    @Test
    fun testStopRecording_stopsDelayedRecording() =
        kosmos.runTest {
            val interactorStatuses: List<ScreenRecordingStatus>? by collectValues(underTest.status)
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)
            val callbackStatus: FakeScreenRecordingServiceCallbackWrapper.RecordingStatus? by
                collectLastValue(fakeScreenRecordingService.callbackStatus)

            underTest.startRecordingDelayed()
            underTest.stopRecording(StopReason.STOP_HOST_APP)

            assertThat(interactorStatuses)
                .containsExactly(
                    ScreenRecordingStatus.Stopped(
                        ScreenRecordingStatus.Stopped.STOP_REASON_NOT_STARTED
                    ),
                    ScreenRecordingStatus.Starting(3.seconds, defaultParams),
                    ScreenRecordingStatus.Stopped(StopReason.STOP_HOST_APP),
                )
            assertThat(serviceStatus).isInstanceOf(ScreenRecordingStatus.Stopped::class.java)
            assertThat(callbackStatus).isNull()
            assertThat(fakeScreenRecordingService.currentCallback).isNull()
        }

    @Test
    fun testSavingRecording_emitsValues() =
        kosmos.runTest {
            val recordingStatus: List<ScreenRecording> by collectValues(underTest.screenRecordings)
            underTest.startRecording()
            underTest.stopRecording(StopReason.STOP_HOST_APP)

            val uri = "content://test".toUri()
            val thumbnail = Icon.createWithBitmap(createBitmap(1, 1, Bitmap.Config.RGB_565))
            with(fakeScreenRecordingService.currentCallback!!) {
                onSavingRecording(uri, 1)
                onRecordingSaved(uri, thumbnail, 1)
            }

            assertThat(recordingStatus)
                .containsExactly(
                    ScreenRecording.Saving(uri, 1),
                    ScreenRecording.Saved(uri, thumbnail, 1),
                )
        }

    @Test
    fun testRecordingSaved_unbindsService() =
        kosmos.runTest {
            underTest.startRecording()
            runCurrent()
            underTest.stopRecording(StopReason.STOP_HOST_APP)
            runCurrent()
            val boundConnection = serviceConnection!!
            verify(applicationContext, never()).unbindService(any())

            val uri = "content://test".toUri()
            val thumbnail = Icon.createWithBitmap(createBitmap(1, 1, Bitmap.Config.RGB_565))
            fakeScreenRecordingService.currentCallback!!
                .onRecordingSaved(uri, thumbnail, 1)
            runCurrent()

            verify(applicationContext).unbindService(same(boundConnection))
        }

    @Test
    fun testRecordingSaveError_unbindsService() =
        kosmos.runTest {
            underTest.startRecording()
            runCurrent()
            underTest.stopRecording(StopReason.STOP_HOST_APP)
            runCurrent()
            val boundConnection = serviceConnection!!
            verify(applicationContext, never()).unbindService(any())

            fakeScreenRecordingService.currentCallback!!
                .onRecordingSaveError("content://test".toUri(), 1)
            runCurrent()

            verify(applicationContext).unbindService(same(boundConnection))
        }

    @Test
    fun testServiceDisconnectedAfterStart_recordingIsStopped() {
        kosmos.runTest {
            val status: ScreenRecordingStatus? by collectLastValue(underTest.status)
            underTest.startRecording()

            serviceConnection!!.onServiceDisconnected(ComponentName("test.pkg", "test.cls"))

            assertThat(status).isEqualTo(ScreenRecordingStatus.Stopped(StopReason.STOP_ERROR))
        }
    }

    @Test
    fun testUpdatingParameters_afterStartRecording() =
        kosmos.runTest {
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)

            underTest.startRecording(defaultParams.copy(shouldShowTaps = false))

            advanceTimeBy(1.minutes)

            underTest.updateParameters { copy(shouldShowTaps = true) }

            assertThat((serviceStatus as ScreenRecordingStatus.Started).parameters.shouldShowTaps)
                .isTrue()
        }

    @Test
    fun testUpdatingParameters_afterStartRecordingDelayed() =
        kosmos.runTest {
            val serviceStatus: ScreenRecordingStatus? by
                collectLastValue(fakeScreenRecordingService.status)

            underTest.startRecordingDelayed(
                parameters = defaultParams.copy(shouldShowTaps = false),
                delay = 3.seconds,
            )

            advanceTimeBy(1.minutes)

            underTest.updateParameters { copy(shouldShowTaps = true) }

            assertThat((serviceStatus as ScreenRecordingStatus.Started).parameters.shouldShowTaps)
                .isTrue()
        }
}

private fun ScreenRecordingServiceRepository.startRecording() {
    startRecording(defaultParams)
}

private fun ScreenRecordingServiceRepository.startRecordingDelayed() {
    startRecordingDelayed(defaultParams, 3.seconds)
}
