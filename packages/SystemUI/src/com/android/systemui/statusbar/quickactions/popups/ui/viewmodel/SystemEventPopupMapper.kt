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
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import javax.inject.Inject

/** Turns the events of the shared island sources into dynamic island chips and popups. */
class SystemEventPopupMapper @Inject constructor(@Application private val context: Context) {

    /**
     * A connected Bluetooth device. The chip carries the Bluetooth glyph and, when the device
     * reports one, its Fast Pair artwork; the popup adds the battery levels and a Disconnect action.
     */
    fun toChip(
        event: IslandEvent.Bluetooth,
        disconnect: (String) -> Unit,
    ): QuickActionChipModel.PopupChip {
        val earbudBatteries =
            listOfNotNull(
                event.leftBatteryLevel
                    ?.takeIf { it in 0..100 }
                    ?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_left_earbud),
                            level = it,
                            component = BluetoothBatteryComponent.LeftEarbud,
                        )
                    },
                event.rightBatteryLevel
                    ?.takeIf { it in 0..100 }
                    ?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_right_earbud),
                            level = it,
                            component = BluetoothBatteryComponent.RightEarbud,
                        )
                    },
                event.caseBatteryLevel
                    ?.takeIf { it in 0..100 }
                    ?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_case),
                            level = it,
                            component = BluetoothBatteryComponent.Case,
                        )
                    },
            )
        val batteryLevel = event.batteryLevel.takeIf { it in 0..100 }
        val text =
            event.deviceTypeLabel.ifBlank {
                context.getString(R.string.dynamic_island_event_connected)
            }
        val actions =
            if (event.address.isNotEmpty()) {
                listOf(
                    PopupActionModel(
                        label = context.getString(R.string.dynamic_island_event_disconnect),
                        onClick = { disconnect(event.address) },
                    )
                )
            } else {
                emptyList()
            }
        val perDeviceBattery = batteryLevel.takeIf { earbudBatteries.isEmpty() }
        val description =
            listOfNotNull(event.deviceName, text, batteryLevel?.let { "$it%" }).joinToString(": ")

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.SystemEvent(event.id),
            icons =
                listOf(ChipIcon(icon = Icon.Resource(R.drawable.dynamic_island_bluetooth, null))),
            colors = ChipColors.DynamicIsland,
            contentDescription = ContentDescription.Loaded(description),
            popupContent =
                PopupContentModel.SystemEvent(
                    kind = SystemEventKind.Bluetooth,
                    title = event.deviceName,
                    text = text,
                    actions = actions,
                    progress = perDeviceBattery?.let { it / 100f },
                    icon = event.deviceIcon?.let { Icon.Loaded(it, null) },
                    prominentText = perDeviceBattery?.let { "$it%" },
                    image = event.deviceImage?.let { Icon.Loaded(it, null) },
                    bluetoothBatteries = earbudBatteries,
                ),
        )
    }
}
