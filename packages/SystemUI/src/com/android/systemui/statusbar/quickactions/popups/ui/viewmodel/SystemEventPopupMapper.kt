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
import android.content.Intent
import android.provider.Settings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.quickactions.popups.shared.model.BluetoothBatteryModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.toSendAction
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import javax.inject.Inject

/** Turns the events of the shared island sources into dynamic island chips and popups. */
class SystemEventPopupMapper
@Inject
constructor(
    @Application private val context: Context,
    private val activityStarter: ActivityStarter,
) {

    /**
     * The hotspot while it is on. The chip carries the hotspot glyph and the number of connected
     * devices; the popup adds a button that opens the tethering settings.
     */
    fun toChip(event: IslandEvent.Hotspot): QuickActionChipModel.PopupChip =
        settingsChip(
            event = event,
            kind = SystemEventKind.Hotspot,
            iconRes = R.drawable.dynamic_island_hotspot,
            title = context.getString(R.string.dynamic_island_event_hotspot),
            text =
                context.getString(
                    R.string.dynamic_island_event_connected_devices,
                    event.numDevices,
                ),
            chipContent = ChipContent.Text(event.numDevices.toString()),
            settingsAction = Settings.ACTION_TETHER_SETTINGS,
        )

    /**
     * A VPN connection. The chip carries the VPN glyph; the popup tells whether the connection is
     * up yet and adds a button that opens the VPN settings.
     */
    fun toChip(event: IslandEvent.Vpn): QuickActionChipModel.PopupChip =
        settingsChip(
            event = event,
            kind = SystemEventKind.Vpn,
            iconRes = R.drawable.dynamic_island_vpn,
            title = context.getString(R.string.dynamic_island_event_vpn),
            text =
                context.getString(
                    if (event.isValidated) R.string.dynamic_island_event_connected
                    else R.string.dynamic_island_event_connecting
                ),
            chipContent = null,
            settingsAction = Settings.ACTION_VPN_SETTINGS,
        )

    /**
     * A call. The chip carries the call glyph; the popup shows who is calling, whether the call is
     * ringing, dialling or connected (with a running timer) and the notification's own actions,
     * such as answer and decline.
     */
    fun toChip(event: IslandEvent.Call): QuickActionChipModel.PopupChip {
        val title =
            event.callerName?.toString()?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.dynamic_island_event_call)
        val text =
            when {
                event.connected -> event.number?.toString().orEmpty()
                event.callType == "Phone:incoming" ->
                    context.getString(R.string.dynamic_island_event_incoming_call)
                else -> context.getString(R.string.dynamic_island_event_calling)
            }
        val icon = Icon.Resource(R.drawable.ic_call, null)
        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.SystemEvent(event.id),
            icons = listOf(ChipIcon(icon = icon)),
            colors = ChipColors.DynamicIsland,
            contentDescription = ContentDescription.Loaded("$title: $text"),
            popupContent =
                PopupContentModel.SystemEvent(
                    kind = SystemEventKind.Call,
                    title = title,
                    text = text,
                    actions =
                        event.actions.map { action ->
                            PopupActionModel(
                                label = action.label.toString(),
                                onClick = action.action.actionIntent.toSendAction() ?: {},
                            )
                        },
                    icon = icon,
                    image = event.callerPhoto?.let { Icon.Loaded(it, null) },
                    callStartTimeMs = event.callStartTimeMs.takeIf { event.connected },
                    isRinging = event.callType == "Phone:incoming",
                    ownerPackage = event.sbn.packageName,
                ),
        )
    }

    private fun settingsChip(
        event: IslandEvent,
        kind: SystemEventKind,
        iconRes: Int,
        title: String,
        text: String,
        chipContent: ChipContent?,
        settingsAction: String,
    ): QuickActionChipModel.PopupChip {
        val icon = Icon.Resource(iconRes, null)
        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.SystemEvent(event.id),
            icons = listOf(ChipIcon(icon = icon)),
            chipContent = chipContent,
            colors = ChipColors.DynamicIsland,
            contentDescription = ContentDescription.Loaded("$title: $text"),
            popupContent =
                PopupContentModel.SystemEvent(
                    kind = kind,
                    title = title,
                    text = text,
                    actions =
                        listOf(
                            PopupActionModel(
                                label = context.getString(R.string.dynamic_island_event_settings),
                                onClick = {
                                    activityStarter.postStartActivityDismissingKeyguard(
                                        Intent(settingsAction)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        0,
                                    )
                                },
                            )
                        ),
                    icon = icon,
                ),
        )
    }


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
                            isCharging = event.leftCharging,
                        )
                    },
                event.rightBatteryLevel
                    ?.takeIf { it in 0..100 }
                    ?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_right_earbud),
                            level = it,
                            component = BluetoothBatteryComponent.RightEarbud,
                            isCharging = event.rightCharging,
                        )
                    },
                event.caseBatteryLevel
                    ?.takeIf { it in 0..100 }
                    ?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_case),
                            level = it,
                            component = BluetoothBatteryComponent.Case,
                            isCharging = event.caseCharging,
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
