/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.applicationContext
import android.os.powerManager
import android.view.accessibility.accessibilityManagerWrapper
import com.android.internal.logging.uiEventLogger
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.inputdevice.data.repository.pointerDeviceRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.securelockdevice.domain.interactor.secureLockDeviceInteractor
import com.android.systemui.shade.pulsingGestureListener
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.android.systemui.util.time.fakeSystemClock
import com.android.systemui.wallpapers.domain.interactor.wallpaperFocalAreaInteractor

val Kosmos.keyguardTouchHandlingInteractor by
    Kosmos.Fixture {
        KeyguardTouchHandlingInteractor(
            context = applicationContext,
            scope = applicationCoroutineScope,
            transitionInteractor = keyguardTransitionInteractor,
            repository = keyguardRepository,
            logger = uiEventLogger,
            broadcastDispatcher = broadcastDispatcher,
            accessibilityManager = accessibilityManagerWrapper,
            statusBarKeyguardViewManager = statusBarKeyguardViewManager,
            pulsingGestureListener = pulsingGestureListener,
            faceAuthInteractor = deviceEntryFaceAuthInteractor,
            powerInteractor = powerInteractor,
            deviceEntryInteractor = deviceEntryInteractor,
            secureSettingsRepository = userAwareSecureSettingsRepository,
            powerManager = powerManager,
            systemClock = fakeSystemClock,
            pointerDeviceRepository = pointerDeviceRepository,
            secureLockDeviceInteractor = { secureLockDeviceInteractor },
            wallpaperFocalAreaInteractor = wallpaperFocalAreaInteractor,
            sceneInteractor = { sceneInteractor },
        )
    }
