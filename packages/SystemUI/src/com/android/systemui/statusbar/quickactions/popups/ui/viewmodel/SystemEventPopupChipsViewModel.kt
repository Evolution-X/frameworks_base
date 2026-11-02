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

package com.android.systemui.statusbar.quickactions.popups.ui.viewmodel

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import com.android.systemui.axdynamicbar.data.source.ConnectivityIslandManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Runs its own session of the island event sources while the dynamic island is enabled and exposes
 * their events as island chips. [ConnectivityIslandManager] is not a singleton, so this session
 * never shares listeners or state with the Ax dynamic bar.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemEventPopupChipsViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val connectivityProvider: Provider<ConnectivityIslandManager>,
    private val mapper: SystemEventPopupMapper,
) : HydratedActivatable() {

    val chips: List<QuickActionChipModel.PopupChip> by
        DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context,
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
                defaultValue = false,
            )
            .distinctUntilChanged()
            .flatMapLatest { enabled -> if (enabled) bluetoothChips() else flowOf(emptyList()) }
            .hydratedStateOf(traceName = "chips", initialValue = emptyList())

    private fun bluetoothChips(): Flow<List<QuickActionChipModel.PopupChip>> = channelFlow {
        val connectivity = connectivityProvider.get()
        var shownAddress: String? = null
        var expiry: Job? = null
        try {
            connectivity.startBluetooth()
            connectivity.bluetoothEvent.collect { event ->
                // The source keeps the event for as long as the device stays connected, so each
                // connection is shown for a short while rather than for as long as it lasts.
                if (event == null) {
                    shownAddress = null
                    expiry?.cancel()
                } else if (event.address != shownAddress) {
                    shownAddress = event.address
                    expiry?.cancel()
                    expiry = launch {
                        delay(BLUETOOTH_CHIP_MS)
                        connectivity.clearBluetooth()
                    }
                }
                send(
                    listOfNotNull(
                        event?.let { mapper.toChip(it, connectivity::disconnectBluetooth) }
                    )
                )
            }
        } finally {
            connectivity.stopBluetooth()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): SystemEventPopupChipsViewModel
    }

    private companion object {
        const val BLUETOOTH_CHIP_MS = 8_000L
    }
}
