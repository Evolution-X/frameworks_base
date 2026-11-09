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

package com.android.systemui.screencapture.record.ui.viewmodel

import com.android.internal.logging.uiEventLogger
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.screencapture.record.camera.domain.interactor.screenCaptureCameraHintInteractor
import com.android.systemui.screencapture.record.domain.interactor.screenCaptureRecordFeaturesInteractor
import com.android.systemui.screencapture.record.domain.interactor.screenCaptureRecordParametersInteractor
import com.android.systemui.screencapture.record.smallscreen.domain.interactor.recordDetailsTargetInteractor
import com.android.systemui.screenrecord.domain.interactor.screenRecordingServiceInteractor

val Kosmos.screenCaptureRecordParametersViewModel by Fixture {
    screenCaptureRecordParametersViewModelFactory.create()
}

val Kosmos.screenCaptureRecordParametersViewModelFactory by Fixture {
    object : ScreenCaptureRecordParametersViewModel.Factory {
        override fun create(): ScreenCaptureRecordParametersViewModel {
            return ScreenCaptureRecordParametersViewModel(
                interactor = screenCaptureRecordParametersInteractor,
                screenCaptureRecordFeaturesInteractor = screenCaptureRecordFeaturesInteractor,
                recordDetailsTargetInteractor = recordDetailsTargetInteractor,
                screenCaptureCameraHintInteractor = screenCaptureCameraHintInteractor,
                screenRecordingServiceInteractor = screenRecordingServiceInteractor,
                uiEventLogger = uiEventLogger,
            )
        }
    }
}
