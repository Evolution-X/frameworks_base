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
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.AxDynamicBarKeyguardExpansion
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.ui.composable.elements.LockscreenLowerRegionElementProvider
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.aodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.dozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.dreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.glanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.goneToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenLowerRegionViewModelFactory
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.occludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.offToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.primaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.toAodEndStateTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.toDozingEndStateTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.toLockscreenEndStateTransitionViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.shade.domain.interactor.shadeInteractor
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

val Kosmos.lockscreenLowerRegionElementProvider by
    Kosmos.Fixture {
        LockscreenLowerRegionElementProvider(
            context = testableContext,
            viewModelFactory = lockscreenLowerRegionViewModelFactory,
            quickAffordancesCombinedViewModel =
                KeyguardQuickAffordancesCombinedViewModel(
                    applicationScope = applicationCoroutineScope,
                    quickAffordanceInteractor = keyguardQuickAffordanceInteractor,
                    keyguardInteractor = keyguardInteractor,
                    shadeInteractor = shadeInteractor,
                    aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
                    dozingToLockscreenTransitionViewModel = dozingToLockscreenTransitionViewModel,
                    dreamingToLockscreenTransitionViewModel =
                        dreamingToLockscreenTransitionViewModel,
                    goneToLockscreenTransitionViewModel = goneToLockscreenTransitionViewModel,
                    occludedToLockscreenTransitionViewModel =
                        occludedToLockscreenTransitionViewModel,
                    offToLockscreenTransitionViewModel = offToLockscreenTransitionViewModel,
                    primaryBouncerToLockscreenTransitionViewModel =
                        primaryBouncerToLockscreenTransitionViewModel,
                    glanceableHubToLockscreenTransitionViewModel =
                        glanceableHubToLockscreenTransitionViewModel,
                    lockscreenToAodTransitionViewModel = lockscreenToAodTransitionViewModel,
                    lockscreenToDozingTransitionViewModel = lockscreenToDozingTransitionViewModel,
                    lockscreenToDreamingTransitionViewModel =
                        lockscreenToDreamingTransitionViewModel,
                    lockscreenToGoneTransitionViewModel = lockscreenToGoneTransitionViewModel,
                    lockscreenToOccludedTransitionViewModel =
                        lockscreenToOccludedTransitionViewModel,
                    lockscreenToPrimaryBouncerTransitionViewModel =
                        lockscreenToPrimaryBouncerTransitionViewModel,
                    lockscreenToGlanceableHubTransitionViewModel =
                        lockscreenToGlanceableHubTransitionViewModel,
                    toAodEndStateTransitionViewModel = toAodEndStateTransitionViewModel,
                    toDozingEndStateTransitionViewModel = toDozingEndStateTransitionViewModel,
                    toLockscreenEndStateTransitionViewModel =
                        toLockscreenEndStateTransitionViewModel,
                    transitionInteractor = keyguardTransitionInteractor,
                ),
            axDynamicBarChipViewModel = hiddenAxDynamicBarChipViewModel(),
        )
    }

/** Lockscreen scene tests compose the dynamic bar. Keep it hidden so they do not need the real pipeline. */
private fun hiddenAxDynamicBarChipViewModel(): AxDynamicBarChipViewModel {
    val settings = mock<AxDynamicBarSettings>()
    val interactor =
        mock<AxDynamicBarInteractor>().apply {
            whenever(this.settings).thenReturn(settings)
            whenever(this.mediaControlChipModel).thenReturn(MutableStateFlow(null))
        }
    val expansion = mock<AxDynamicBarKeyguardExpansion>()
    return mock<AxDynamicBarChipViewModel>().apply {
        whenever(this.interactor).thenReturn(interactor)
        whenever(this.keyguardExpansion).thenReturn(expansion)
        whenever(this.chipState).thenReturn(MutableStateFlow(null))
        whenever(this.isOnKeyguard).thenReturn(MutableStateFlow(false))
        whenever(this.isEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.isKeyguardEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.isKeyguardMusicPillEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.mediaUseWaveform).thenReturn(MutableStateFlow(false))
        whenever(this.isLowUdfps).thenReturn(MutableStateFlow(false))
        whenever(this.isKeyguardExpanded).thenReturn(MutableStateFlow(false))
        whenever(this.keyguardBatteryChipMode).thenReturn(MutableStateFlow(0))
        whenever(this.keyguardBatteryInfo)
            .thenReturn(MutableStateFlow(KeyguardBatteryInfo(0, false, false, false, null)))
        whenever(this.batteryString).thenReturn(MutableStateFlow(""))
    }
}
