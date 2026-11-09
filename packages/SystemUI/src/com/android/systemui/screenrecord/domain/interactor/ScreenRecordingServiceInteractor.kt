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

import android.media.projection.StopReason
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.screenrecord.data.repository.ScreenRecordingServiceRepository
import com.android.systemui.screenrecord.shared.model.ScreenRecording
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

private val defaultRecordingRelay: Duration = 3.seconds

@SysUISingleton
class ScreenRecordingServiceInteractor
@Inject
constructor(private val repository: ScreenRecordingServiceRepository) {

    val status: StateFlow<ScreenRecordingStatus> = repository.status
    val screenRecordings: Flow<ScreenRecording> = repository.screenRecordings

    /** Starts the recording after the [delay]. */
    fun startRecordingDelayed(
        parameters: ScreenRecordingParameters,
        delay: Duration = defaultRecordingRelay,
    ) {
        repository.startRecordingDelayed(parameters, delay)
    }

    fun startRecording(parameters: ScreenRecordingParameters) {
        repository.startRecording(parameters)
    }

    fun stopRecording(@StopReason reason: Int) {
        repository.stopRecording(reason)
    }

    /** Updates shouldShowTaps if there is an ongoing recording */
    fun updateShouldShowTaps(shouldShowTaps: Boolean) {
        repository.updateParameters { copy(shouldShowTaps = shouldShowTaps) }
    }

    /** Updates the selfie preview if there is an ongoing recording. */
    fun updateShouldShowFrontCamera(shouldShowFrontCamera: Boolean) {
        repository.updateParameters { copy(shouldShowFrontCamera = shouldShowFrontCamera) }
    }
}
