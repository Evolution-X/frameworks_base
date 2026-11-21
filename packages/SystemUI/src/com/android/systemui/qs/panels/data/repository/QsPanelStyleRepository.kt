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
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.shared.model.QsPanelStyle
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.shared.model.QsTweaksCodec
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
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

    /** The look of the Remix panel style, a [QsStyle]. */
    val remixStyle: StateFlow<QsStyle> =
        systemSettings
            .intSetting(QsStyle.SETTING, QsStyle.STOCK.value)
            .map { QsStyle.of(it) }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                QsStyle.of(readIntByName(QsStyle.SETTING, QsStyle.STOCK.value)),
            )

    /** The changes made to each Remix look, by the look's value, saved as JSON. */
    private val tweakStore =
        MutableStateFlow(QsTweaksCodec.parseStore(readString(QsTweaksCodec.SETTING)))
    private var saveTweaksJob: Job? = null

    /** The changes made to the selected Remix look. */
    val tweaks: StateFlow<QsTweaks> =
        combine(remixStyle, tweakStore) { look, store -> store[look.value] ?: QsTweaks() }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                tweakStore.value[remixStyle.value.value] ?: QsTweaks(),
            )

    init {
        scope.launch {
            systemSettings.stringSetting(QsTweaksCodec.SETTING).collect { saved ->
                // An unsaved edit is newer than what the setting holds.
                if (saveTweaksJob?.isActive != true) {
                    tweakStore.value = QsTweaksCodec.parseStore(saved)
                }
            }
        }
    }

    /** Picks the look of the Remix panel style. */
    fun setRemixStyle(style: QsStyle) {
        scope.launch { systemSettings.setInt(QsStyle.SETTING, style.value) }
    }

    /** Changes the selected Remix look. Quick edits, like a slider drag, are saved together. */
    fun editTweaks(edit: (QsTweaks) -> QsTweaks) {
        val look = remixStyle.value.value
        tweakStore.update { store ->
            val tweaks = edit(store[look] ?: QsTweaks())
            if (tweaks == QsTweaks()) store - look else store + (look to tweaks)
        }
        saveTweaks()
    }

    /** Takes every change of the selected Remix look away. */
    fun resetTweaks() = editTweaks { QsTweaks() }

    private fun saveTweaks() {
        saveTweaksJob?.cancel()
        saveTweaksJob =
            scope.launch {
                delay(SAVE_TWEAKS_DELAY_MS)
                val json = QsTweaksCodec.writeStore(tweakStore.value)
                withContext(bgDispatcher) {
                    try {
                        Settings.System.putStringForUser(
                            context.contentResolver,
                            QsTweaksCodec.SETTING,
                            json,
                            UserHandle.USER_CURRENT,
                        )
                    } catch (_: Throwable) {
                        // The edit stays on screen, and is saved with the next one.
                    }
                }
            }
    }

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

    private fun readIntByName(name: String, default: Int): Int = readInt(name, default)

    private fun readString(name: String): String? =
        try {
            Settings.System.getStringForUser(
                context.contentResolver,
                name,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            null
        }

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

    private companion object {
        const val SAVE_TWEAKS_DELAY_MS = 300L
    }
}
