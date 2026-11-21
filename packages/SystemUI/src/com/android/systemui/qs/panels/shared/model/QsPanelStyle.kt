/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

import android.provider.Settings

/** The values of [Settings.System.QS_PANEL_STYLE]. */
enum class QsPanelStyle(val value: Int) {
    /** The default card style. */
    DEFAULT(0),
    CLASSIC(1),
    COLOROS(2),
    NOTHING(3);

    companion object {
        fun of(value: Int): QsPanelStyle = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
