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

package com.android.systemui.qs.tiles.impl.battery.domain.interactor

import android.os.UserHandle
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Observes BatterySaver mode state changes. */
open class BatterySaverTileDataInteractor
@Inject
constructor(private val batteryRepository: BatteryRepository) :
    QSTileDataInteractor<BatterySaverTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<BatterySaverTileModel> =
        combine(
            batteryRepository.isPluggedIn,
            batteryRepository.isPowerSaveEnabled,
            batteryRepository.isExtremePowerSaveEnabled,
            batteryRepository.level,
        ) {
            isPluggedIn: Boolean,
            isPowerSaverEnabled: Boolean,
            isExtremePowerSaverEnabled: Boolean,
            _ // we are only interested in battery level change, not the actual level
             ->
            BatterySaverTileModel.Extreme(
                isPluggedIn,
                isPowerSaverEnabled,
                isExtremePowerSaverEnabled,
            )
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
