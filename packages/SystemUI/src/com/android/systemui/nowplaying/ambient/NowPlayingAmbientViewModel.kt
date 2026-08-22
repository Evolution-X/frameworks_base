/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
package com.android.systemui.nowplaying.ambient

import android.graphics.Point
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Supplies AOD burn-in-protection translation and tap-outside-to-collapse signaling for
 * NowPlayingAmbientContainer, ported from Google's KeyguardAmbientIndicationViewModel and
 * KeyguardAmbientIndicationAreaViewBinder. Reuses the same BurnInInteractor offsets as the
 * rest of the keyguard so the ambient pill drifts in sync with other burn-in-protected
 * elements rather than sitting static in one spot for hours at a time.
 */
@SysUISingleton
class NowPlayingAmbientViewModel
@Inject
constructor(
    burnInInteractor: BurnInInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardRootViewModel: KeyguardRootViewModel,
    @Background private val bgCoroutineContext: CoroutineContext,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {
    private val burnInFlow: Flow<BurnInModel> =
        combine(
                burnInInteractor.burnIn(
                    xDimenResourceId = R.dimen.burn_in_prevention_offset_x,
                    yDimenResourceId = R.dimen.default_burn_in_prevention_offset,
                ),
                keyguardTransitionInteractor.transitionValue(KeyguardState.AOD),
            ) { burnIn, aodFactor ->
                BurnInModel(
                    translationX = (burnIn.translationX * aodFactor).toInt(),
                    translationY = (burnIn.translationY * aodFactor).toInt(),
                    scale = burnIn.scale,
                    scaleClockOnly = burnIn.scaleClockOnly,
                )
            }
            .distinctUntilChanged()
            .flowOn(bgCoroutineContext)

    val translationX: Flow<Float> = burnInFlow.map { it.translationX.toFloat() }.flowOn(mainDispatcher)

    val translationY: Flow<Float> = burnInFlow.map { it.translationY.toFloat() }.flowOn(mainDispatcher)

    /** Emits whenever the user taps anywhere on the keyguard root view. */
    val rootViewTapPosition: Flow<Point> = keyguardRootViewModel.lastRootViewTapPosition.filterNotNull()
}
