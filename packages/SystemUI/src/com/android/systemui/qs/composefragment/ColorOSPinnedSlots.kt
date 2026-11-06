/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Where the choice of the pinned slots is kept: one entry per slot, separated by commas, in the
 * order Wi-Fi card, Bluetooth card, pill next to the ringer. An empty entry keeps the default of
 * the slot, which is what ColorOS has: Wi-Fi, Bluetooth and My Devices.
 */
internal const val COLOROS_PINNED_SETTING = "sysui_coloros_pinned"

/** The two cards of the first row and the pill next to the ringer switch. */
internal const val COLOROS_PINNED_SLOTS = 3

/** Index of the pill next to the ringer, which shows My Devices unless a tile is chosen. */
internal const val COLOROS_PILL_SLOT = 2

internal fun parseColorOSPinned(raw: String?): List<String?> {
    val parts = raw.orEmpty().split(",")
    return List(COLOROS_PINNED_SLOTS) { parts.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
}

internal fun formatColorOSPinned(slots: List<String?>): String =
    slots.take(COLOROS_PINNED_SLOTS).joinToString(",") { it.orEmpty() }

internal fun readColorOSPinned(context: Context): List<String?> =
    parseColorOSPinned(
        Settings.Secure.getStringForUser(
            context.contentResolver,
            COLOROS_PINNED_SETTING,
            UserHandle.USER_CURRENT,
        )
    )

internal fun writeColorOSPinned(context: Context, slots: List<String?>) {
    Settings.Secure.putStringForUser(
        context.contentResolver,
        COLOROS_PINNED_SETTING,
        formatColorOSPinned(slots),
        UserHandle.USER_CURRENT,
    )
}

/** The chosen pinned slots, kept up to date while the setting changes. */
@Composable
internal fun rememberColorOSPinned(): State<List<String?>> {
    val context = LocalContext.current
    val state = remember(context) { mutableStateOf(readColorOSPinned(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = readColorOSPinned(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(COLOROS_PINNED_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        state.value = readColorOSPinned(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

/**
 * What the pinned slots show: the specs of the two cards of the first row, where null is an empty
 * slot, and the spec of the pill next to the ringer, where null is My Devices.
 */
internal data class ColorOSPinned(val cards: List<String?>, val pill: String?) {
    val specs: Set<String>
        get() = (cards + pill).filterNotNull().toSet()
}

/**
 * Resolves the chosen slots against the tiles the panel [present]. A choice that is not a tile of
 * the panel is ignored, and a tile chosen for two slots only keeps the first. The slots with no
 * choice get what ColorOS pins there, the Wi-Fi and Bluetooth tiles with their fallbacks, and My
 * Devices.
 */
internal fun colorOSPinnedOf(present: Set<String>, chosen: List<String?>): ColorOSPinned {
    val taken = mutableSetOf<String>()
    val valid = parseColorOSPinned(formatColorOSPinned(chosen)).map { spec ->
        spec?.takeIf { it in present && taken.add(it) }
    }
    val auto = colorOSCardSlotsOf(present - taken)
    return ColorOSPinned(
        cards = listOf(valid[0] ?: auto[0], valid[1] ?: auto[1]),
        pill = valid[COLOROS_PILL_SLOT],
    )
}
