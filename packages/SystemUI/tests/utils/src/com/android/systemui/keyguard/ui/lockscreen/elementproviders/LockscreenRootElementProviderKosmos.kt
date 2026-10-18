/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.lockscreen.elementproviders

import android.content.testableContext
import com.android.systemui.axdynamicbar.ui.AxDynamicBarKeyguardExpansion
import com.android.systemui.keyguard.ui.composable.elements.LockscreenAmbientBounds
import com.android.systemui.keyguard.ui.composable.elements.LockscreenRootElementProvider
import com.android.systemui.keyguard.ui.viewmodel.aodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardClockViewModelWithImpl
import com.android.systemui.keyguard.ui.viewmodel.lockscreenRootViewModelFactory
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.wallpapers.data.repository.wallpaperFocalAreaRepository
import org.mockito.kotlin.mock

val Kosmos.lockscreenRootElementProvider by
    Kosmos.Fixture {
        LockscreenRootElementProvider(
            context = testableContext,
            viewModelFactory = lockscreenRootViewModelFactory,
            aodBurnInViewModel = aodBurnInViewModel,
            keyguardClockViewModel = keyguardClockViewModelWithImpl,
            ambientBounds = LockscreenAmbientBounds(),
            wallpaperFocalAreaRepository = wallpaperFocalAreaRepository,
            keyguardExpansion = mock<AxDynamicBarKeyguardExpansion>(),
        )
    }
