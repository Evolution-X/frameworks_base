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
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.SystemEventFeature
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Runs its own session of the island event sources while the dynamic island is enabled and exposes
 * their events as island chips, each behind its own [SystemEventFeature] switch.
 * [ConnectivityIslandManager] is not a singleton, so this session never shares listeners or state
 * with the Ax dynamic bar.
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
            .flatMapLatest { enabled -> if (enabled) connectivityChips() else flowOf(emptyList()) }
            .hydratedStateOf(traceName = "chips", initialValue = emptyList())

    /** The events whose own switch is on, which changes as the switches are flipped. */
    private fun enabledFeatures(): Flow<Set<SystemEventFeature>> {
        val features = SystemEventFeature.values().toList()
        return combine(
                features.map { feature ->
                    DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                        context,
                        feature.settingKey,
                    )
                }
            ) { enabled ->
                features.filterIndexed { index, _ -> enabled[index] }.toSet()
            }
            .distinctUntilChanged()
    }

    private fun connectivityChips(): Flow<List<QuickActionChipModel.PopupChip>> = channelFlow {
        val connectivity = connectivityProvider.get()
        // The source keeps the event for as long as the device stays connected, so each
        // connection is shown for a short while rather than for as long as it lasts.
        val bluetooth =
            BriefEvent<IslandEvent.Bluetooth>(
                scope = this,
                durationMs = BLUETOOTH_CHIP_MS,
                keyOf = { it.address },
                clear = connectivity::clearBluetooth,
            )
        try {
            launch {
                enabledFeatures().collect { enabled ->
                    if (SystemEventFeature.BLUETOOTH in enabled) connectivity.startBluetooth()
                    else connectivity.stopBluetooth()
                }
            }
            connectivity.bluetoothEvent.collect { event ->
                bluetooth.onEvent(event)
                send(
                    listOfNotNull(
                        event?.let { mapper.toChip(it, connectivity::disconnectBluetooth) }
                    )
                )
            }
        } finally {
            bluetooth.cancel()
            connectivity.stopBluetooth()
        }
    }

    /**
     * Clears an event [durationMs] after it first shows up, and again for every later event whose
     * [keyOf] differs, so a source that holds its event can still be shown briefly.
     */
    private class BriefEvent<T : Any>(
        private val scope: CoroutineScope,
        private val durationMs: Long,
        private val keyOf: (T) -> Any,
        private val clear: () -> Unit,
    ) {
        private var shownKey: Any? = null
        private var expiry: Job? = null

        fun onEvent(event: T?) {
            if (event == null) {
                shownKey = null
                expiry?.cancel()
                return
            }
            val key = keyOf(event)
            if (key == shownKey) return
            shownKey = key
            expiry?.cancel()
            expiry =
                scope.launch {
                    delay(durationMs)
                    clear()
                }
        }

        fun cancel() {
            expiry?.cancel()
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
