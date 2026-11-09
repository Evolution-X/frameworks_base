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

package com.android.systemui.screencapture.record.domain.interactor

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.interactor.screenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParametersFactory.screenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import com.android.systemui.testKosmosNew
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ScreenCaptureRecordParametersInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmosNew()

    private val underTest by lazy { kosmos.screenCaptureRecordParametersInteractor }

    @Test
    fun changingAudioSource() =
        kosmos.runTest {
            val newAudioSource = ScreenRecordingAudioSource.MIC_AND_INTERNAL
            assertThat(underTest.audioSource).isNotEqualTo(newAudioSource)

            underTest.audioSource = newAudioSource

            assertThat(underTest.audioSource).isEqualTo(newAudioSource)
        }

    @Test
    fun changingAudioSource_recordingIsOngoing_doesntChange() =
        kosmos.runTest {
            screenRecordingServiceInteractor.startRecording(
                ScreenRecordingParameters(
                    captureTarget = null,
                    displayId = Display.DEFAULT_DISPLAY,
                    audioSource = ScreenRecordingAudioSource.NONE,
                    shouldShowTaps = false,
                )
            )
            val newAudioSource = ScreenRecordingAudioSource.MIC_AND_INTERNAL
            assertThat(underTest.audioSource).isNotEqualTo(newAudioSource)

            underTest.audioSource = newAudioSource

            assertThat(underTest.audioSource).isNotEqualTo(newAudioSource)
        }

    @Test
    fun changingShouldShowTaps() =
        kosmos.runTest {
            screenRecordingServiceInteractor.startRecording(screenRecordingParameters())
            val newShouldShowTaps = true
            assertThat(underTest.shouldShowTaps).isNotEqualTo(newShouldShowTaps)

            underTest.shouldShowTaps = newShouldShowTaps

            assertThat(underTest.shouldShowTaps).isEqualTo(newShouldShowTaps)
            val serviceStatus by collectLastValue(screenRecordingServiceInteractor.status)
            assertThat((serviceStatus as ScreenRecordingStatus.Started).parameters.shouldShowTaps)
                .isEqualTo(newShouldShowTaps)
        }

    @Test
    fun changingShouldShowFrontCamera() =
        kosmos.runTest {
            underTest.shouldShowFrontCamera = false
            assertThat(underTest.shouldShowFrontCamera).isFalse()

            underTest.shouldShowFrontCamera = true

            assertThat(underTest.shouldShowFrontCamera).isTrue()
        }

    @Test
    fun suspendFrontCamera_doesNotForgetSavedChoice() =
        kosmos.runTest {
            underTest.shouldShowFrontCamera = true

            underTest.suspendFrontCamera()

            assertThat(underTest.shouldShowFrontCamera).isFalse()

            underTest.restoreFrontCamera()

            assertThat(underTest.shouldShowFrontCamera).isTrue()
        }

    @Test
    fun changingShouldShowFrontCamera_whileRecording_updatesService() =
        kosmos.runTest {
            underTest.shouldShowFrontCamera = false
            screenRecordingServiceInteractor.startRecording(screenRecordingParameters())

            underTest.shouldShowFrontCamera = true

            val serviceStatus by collectLastValue(screenRecordingServiceInteractor.status)
            assertThat(
                    (serviceStatus as ScreenRecordingStatus.Started)
                        .parameters
                        .shouldShowFrontCamera
                )
                .isTrue()
        }

    @Test
    fun setShouldShowFrontCamera_nowNone_enablesMic() =
        kosmos.runTest {
            underTest.audioSource = ScreenRecordingAudioSource.NONE

            underTest.shouldShowFrontCamera = true

            assertThat(underTest.shouldShowFrontCamera).isTrue()
            assertThat(underTest.audioSource).isEqualTo(ScreenRecordingAudioSource.MIC)
        }

    @Test
    fun setShouldShowFrontCamera_nowInternal_enablesMic() =
        kosmos.runTest {
            underTest.audioSource = ScreenRecordingAudioSource.INTERNAL

            underTest.shouldShowFrontCamera = true

            assertThat(underTest.shouldShowFrontCamera).isTrue()
            assertThat(underTest.audioSource).isEqualTo(ScreenRecordingAudioSource.MIC_AND_INTERNAL)
        }

    @Test
    fun setShouldShowFrontCamera_recordingWithInternal_doesntChangeMic() =
        kosmos.runTest {
            underTest.audioSource = ScreenRecordingAudioSource.INTERNAL
            screenRecordingServiceInteractor.startRecording(screenRecordingParameters())

            underTest.shouldShowFrontCamera = true

            assertThat(underTest.shouldShowFrontCamera).isTrue()
            assertThat(underTest.audioSource).isEqualTo(ScreenRecordingAudioSource.INTERNAL)
        }

    @Test
    fun setShouldNotShowFrontCamera_doesntChangeMic() =
        kosmos.runTest {
            ScreenRecordingAudioSource.entries.forEach { source ->
                underTest.audioSource = source

                underTest.shouldShowFrontCamera = false

                assertThat(underTest.shouldShowFrontCamera).isFalse()
                assertThat(underTest.audioSource).isEqualTo(source)
            }
        }
}
