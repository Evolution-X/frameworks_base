/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.panels.shared.model.QsPanelStyle
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The QS panel style and the options that go with it, as state. Everything that draws a style, or
 * lays out for one, follows these flows, so that a style change applies without restarting
 * SystemUI.
 *
 * The flows start at the value the settings hold, so the first frame already has the right style.
 */
@SysUISingleton
class QsPanelStyleRepository
@Inject
constructor(
    @Application private val context: Context,
    @Application scope: CoroutineScope,
    private val systemSettings: SystemSettingsRepository,
) {
    /** The selected [QsPanelStyle]. */
    val style: StateFlow<QsPanelStyle> =
        systemSettings
            .intSetting(Settings.System.QS_PANEL_STYLE, 0)
            .map { QsPanelStyle.of(it) }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                QsPanelStyle.of(readInt(Settings.System.QS_PANEL_STYLE, 0)),
            )

    /** Whether the Nothing style takes the Nothing OS 5.0 palette, which is any but 0. */
    val nothingOsColors: StateFlow<Boolean> =
        systemSettings
            .intSetting(Settings.System.QS_NOTHING_COLOR_STYLE, 1)
            .map { it != 0 }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                readInt(Settings.System.QS_NOTHING_COLOR_STYLE, 1) != 0,
            )

    /** Whether the media card of the ColorOS style fills with the album art. */
    val colorOSMediaArtFill: StateFlow<Boolean> =
        systemSettings
            .intSetting(Settings.System.QS_COLOROS_MEDIA_ART_FILL, 0)
            .map { it != 0 }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                readInt(Settings.System.QS_COLOROS_MEDIA_ART_FILL, 0) != 0,
            )

    private fun readInt(name: String, default: Int): Int =
        try {
            Settings.System.getIntForUser(
                context.contentResolver,
                name,
                default,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            default
        }
}
