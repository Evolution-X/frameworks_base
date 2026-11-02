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

package com.android.systemui.statusbar.quickactions.charging.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.quickactions.charging.shared.model.ChargingDetail
import com.android.systemui.statusbar.quickactions.charging.shared.model.ChargingPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The island's charging chip: a bolt and the battery level while a charger is connected, with a
 * popup showing the time remaining and what the charger reports. Off unless
 * [DynamicIslandFeatureSettings.CHARGING] is set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChargingPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    private val activityStarter: ActivityStarter,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    private val featureEnabled: Flow<Boolean> =
        DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context,
                DynamicIslandFeatureSettings.CHARGING,
                defaultValue = false,
            )
            .distinctUntilChanged()

    override val chip: QuickActionChipModel by
        featureEnabled
            .flatMapLatest { enabled ->
                if (enabled) chargingState() else flowOf<ChargingState?>(null)
            }
            .map(::toChipModel)
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Charging),
            )

    /**
     * Emits each time a charger is connected while the feature is on. A charger that was already
     * connected when listening began is not a plug-in.
     */
    val plugIns: Flow<Unit> =
        featureEnabled.flatMapLatest { enabled -> if (enabled) pluggedInTransitions() else emptyFlow() }

    private fun pluggedInTransitions(): Flow<Unit> = flow {
        var wasPluggedIn = batteryController.isPluggedIn
        batteryInteractor.isCharging.distinctUntilChanged().collect { isCharging ->
            if (isCharging && !wasPluggedIn) emit(Unit)
            wasPluggedIn = isCharging
        }
    }

    private fun chargingState(): Flow<ChargingState?> =
        batteryInteractor.isCharging
            .distinctUntilChanged()
            .flatMapLatest { isCharging ->
                if (!isCharging) {
                    flowOf<ChargingState?>(null)
                } else {
                    combine(
                        batteryInteractor.level,
                        batteryInteractor.powerSave,
                        batteryInteractor.batteryTimeRemainingEstimate,
                        chargingDetails(),
                    ) {
                        level: Int?,
                        isPowerSave: Boolean,
                        timeRemaining: String?,
                        details: List<ChargingDetail> ->
                        level?.let {
                            ChargingState(
                                level = it,
                                isPowerSave = isPowerSave,
                                timeRemaining = timeRemaining,
                                details = details,
                                isWireless = batteryController.isWirelessCharging,
                            )
                        }
                    }
                }
            }
            .distinctUntilChanged()

    /** What the charger reports, re-read on every battery change. */
    private fun chargingDetails(): Flow<List<ChargingDetail>> =
        callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            trySend(intent.toChargingDetails())
                        }
                    }
                val sticky =
                    context.registerReceiver(
                        receiver,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                trySend(sticky?.toChargingDetails().orEmpty())
                awaitClose { context.unregisterReceiver(receiver) }
            }
            .distinctUntilChanged()

    private fun Intent.toChargingDetails(): List<ChargingDetail> {
        val status = BatteryStatus(this)
        if (!status.isPluggedIn()) return emptyList()

        val locale = context.resources.configuration.locales[0]
        // BatteryStatus falls back to 5 V when the charger reports no voltage, which would make the
        // voltage and the power derived from it look measured.
        val reportsVoltage = getIntExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, -1) > 0
        return buildList {
            // The extras are in micro-amps, micro-volts and micro-watts.
            val milliAmps = status.maxChargingCurrent / MICRO_PER_MILLI
            if (milliAmps > 0f) {
                val inAmps = milliAmps >= 1000f
                add(
                    ChargingDetail(
                        label = context.getString(R.string.dynamic_island_charging_max_current),
                        value =
                            String.format(
                                locale,
                                if (inAmps) "%.1f" else "%.0f",
                                if (inAmps) milliAmps / 1000f else milliAmps,
                            ),
                        unit = if (inAmps) "A" else "mA",
                    )
                )
            }
            if (reportsVoltage && status.maxChargingWattage > 0f) {
                add(
                    ChargingDetail(
                        label = context.getString(R.string.dynamic_island_charging_max_power),
                        value =
                            String.format(locale, "%.1f", status.maxChargingWattage / MICRO_PER_UNIT),
                        unit = "W",
                    )
                )
            }
            if (reportsVoltage) {
                add(
                    ChargingDetail(
                        label = context.getString(R.string.dynamic_island_charging_max_voltage),
                        value =
                            String.format(locale, "%.1f", status.maxChargingVoltage / MICRO_PER_UNIT),
                        unit = "V",
                    )
                )
            }
            // Reported in tenths of a degree Celsius.
            if (status.temperature > 0f) {
                add(
                    ChargingDetail(
                        label = context.getString(R.string.dynamic_island_charging_temperature),
                        value = String.format(locale, "%.1f", status.temperature / 10f),
                        unit = "\u00B0C",
                    )
                )
            }
        }
    }

    private fun toChipModel(state: ChargingState?): QuickActionChipModel {
        if (state == null) return QuickActionChipModel.Hidden(QuickActionChipId.Charging)

        val title =
            context.getString(
                if (state.isWireless) R.string.dynamic_island_charging_wireless_title
                else R.string.dynamic_island_charging_title
            )
        val subtitle =
            listOfNotNull(
                    state.timeRemaining,
                    context.getString(R.string.dynamic_island_charging_battery_saver).takeIf {
                        state.isPowerSave
                    },
                )
                .joinToString(" \u00B7 ")
        val model =
            ChargingPopupModel(
                title = title,
                subtitle = subtitle,
                level = state.level,
                isWireless = state.isWireless,
                isPowerSave = state.isPowerSave,
                details = state.details,
                onOpenSettings = ::openBatterySettings,
            )
        val description = ContentDescription.Loaded("$title, ${state.level}%")
        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Charging,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_charging,
                                contentDescription = description,
                            )
                    )
                ),
            chipContent = ChipContent.Text("${state.level}%"),
            colors = ChipColors.DynamicIsland,
            contentDescription = description,
            popupContent = PopupContentModel.Charging(model),
        )
    }

    private fun openBatterySettings() {
        activityStarter.startActivity(
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            /* dismissShade= */ true,
        )
    }

    private data class ChargingState(
        val level: Int,
        val isPowerSave: Boolean,
        val timeRemaining: String?,
        val details: List<ChargingDetail>,
        val isWireless: Boolean,
    )

    @AssistedFactory
    interface Factory {
        fun create(): ChargingPopupChipViewModel
    }

    private companion object {
        const val MICRO_PER_MILLI = 1000f
        const val MICRO_PER_UNIT = 1_000_000f
    }
}
