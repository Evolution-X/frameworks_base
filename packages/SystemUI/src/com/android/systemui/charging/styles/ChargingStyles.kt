/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.charging.styles

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.UserHandle
import android.provider.Settings
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.res.R
import kotlin.math.roundToInt

/** The plug-in charging animation picked in Settings.System [SETTING]. */
object ChargingStyles {
    const val SETTING = "charging_plug_animation"

    const val DEFAULT = 0
    const val ONEUI = 1
    const val HYPEROS = 2
    const val ORIGINOS = 3
    const val SUPERCHARGE = 4

    // Below this the reading is a charger still settling, so it is left out of the label.
    private const val MIN_WATTS = 5
    private const val FAST_WATTS = 15
    private const val SUPER_WATTS = 45

    fun selected(context: Context): Int =
        Settings.System.getIntForUser(
            context.contentResolver,
            SETTING,
            DEFAULT,
            UserHandle.USER_CURRENT,
        )

    fun drawn(context: Context, style: Int): ChargingStyleView? =
        when (style) {
            ONEUI -> OneUiCapsuleView(context)
            HYPEROS -> HyperBubblesView(context)
            ORIGINOS -> OriginOrbView(context)
            SUPERCHARGE -> SuperChargeView(context)
            else -> null
        }

    fun watts(sticky: Intent?): Int =
        if (sticky == null) 0 else (BatteryStatus(sticky).maxChargingWattage / 1_000_000f).roundToInt()

    fun speed(sticky: Intent?): Int {
        if (sticky == null) return ChargingStyleView.SPEED_NORMAL
        val w = watts(sticky)
        return when {
            sticky.getBooleanExtra(BatteryManager.EXTRA_OEM_CHARGER, false) ->
                ChargingStyleView.SPEED_SUPER
            w >= SUPER_WATTS -> ChargingStyleView.SPEED_SUPER
            w >= FAST_WATTS -> ChargingStyleView.SPEED_FAST
            else -> ChargingStyleView.SPEED_NORMAL
        }
    }

    fun label(context: Context, speed: Int, watts: Int): String {
        val state =
            context.getString(
                when (speed) {
                    ChargingStyleView.SPEED_SUPER -> R.string.charging_style_super
                    ChargingStyleView.SPEED_FAST -> R.string.charging_style_fast
                    else -> R.string.charging_style_normal
                }
            )
        return if (watts >= MIN_WATTS) {
            context.getString(R.string.charging_style_watts, state, watts)
        } else {
            state
        }
    }
}
