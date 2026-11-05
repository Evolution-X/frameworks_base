/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.domain.interactor

import android.util.Log
import androidx.compose.ui.Alignment
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.scene.domain.SceneFrameworkTableLog
import com.android.systemui.shade.data.repository.ShadeConfigRepository
import com.android.systemui.shade.shared.flag.DualShadeFlag
import com.android.systemui.shade.shared.model.ShadeMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Defines interface for classes that can provide state and business logic related to the mode of
 * the shade.
 */
interface ShadeModeInteractor {

    /** The version of the shade layout to use. */
    val shadeMode: StateFlow<ShadeMode>

    /**
     * Whether the shade layout should be full width (true) or floating (false).
     *
     * In a floating (aka wide) layout, notifications and quick settings each take up only up to
     * half the screen width (whether they are shown at the same time or not).
     *
     * In a full width (aka narrow) layout, they can each be as wide as the entire screen.
     *
     * Note: In non-Dual-Shade modes, this value may be `true` even when the screen is wide.
     */
    val isFullWidthShade: StateFlow<Boolean>

    /**
     * The horizontal alignment of the notification stack on the screen. This determines the
     * position of the notifications shade, lockscreen content columns, HUNs, etc.
     */
    val notificationStackHorizontalAlignment: StateFlow<Alignment.Horizontal>

    /** Convenience shortcut for querying whether the current [shadeMode] is [ShadeMode.Dual]. */
    val isDualShade: Boolean
        get() = shadeMode.value is ShadeMode.Dual

    /** Convenience shortcut for querying whether the current [shadeMode] is [ShadeMode.Split]. */
    val isSplitShade: Boolean
        get() = shadeMode.value is ShadeMode.Split
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShadeModeInteractorImpl
@Inject
constructor(
    @Background applicationScope: CoroutineScope,
    private val shadeConfigRepository: ShadeConfigRepository,
    @SceneFrameworkTableLog private val tableLogBuffer: TableLogBuffer,
) : ShadeModeInteractor {

    private val isDualShadeEnabled: StateFlow<Boolean> =
        if (DualShadeFlag.isEnabled) {
            shadeConfigRepository.useDualShadeSetting
                .flatMapLatest { useSetting ->
                    if (useSetting) {
                        Log.d(TAG, "Using Dual Shade setting")
                        shadeConfigRepository.isDualShadeSettingEnabled.onEach {
                            Log.d(
                                TAG,
                                "Dual Shade is ${if (it) "enabled" else "disabled"} by the setting",
                            )
                        }
                    } else {
                        Log.d(TAG, "Overriding Dual Shade setting")
                        shadeConfigRepository.isDualShadeEnabledByDefault.onEach {
                            Log.d(
                                TAG,
                                "Dual Shade is ${if (it) "enabled" else "disabled"} by default",
                            )
                        }
                    }
                }
                .stateIn(
                    applicationScope,
                    SharingStarted.Eagerly,
                    initialValue = shadeConfigRepository.isDualShadeEnabledByDefault(),
                )
        } else {
            Log.d(TAG, "The Dual Shade feature flag is disabled")
            MutableStateFlow(false)
        }

    override val isFullWidthShade: StateFlow<Boolean> =
        if (DualShadeFlag.isEnabled) {
                isDualShadeEnabled.flatMapLatest { isDualShadeEnabled ->
                    if (isDualShadeEnabled) {
                        // Dual Shade should be shown
                        Log.d(TAG, "Shade layout is derived from the Dual Shade config")
                        shadeConfigRepository.isFullWidthShade
                    } else {
                        // Single shade should be shown
                        Log.d(TAG, "Single shade is always full-width")
                        flowOf(true)
                    }
                }
            } else {
                Log.d(TAG, "Shade layout is derived from the legacy config")
                shadeConfigRepository.legacyUseSplitShade.map { !it }
            }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                initialValue = shadeConfigRepository.isFullWidthShade(),
                columnName = "isFullWidthShade",
            )
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = shadeConfigRepository.isFullWidthShade(),
            )

    private val shadeModeInitialValue: ShadeMode
        get() =
            determineShadeMode(
                isDualShadeEnabled = isDualShadeEnabled.value,
                isFullWidthShade = isFullWidthShade.value,
            )

    override val shadeMode: StateFlow<ShadeMode> =
        combine(isDualShadeEnabled, isFullWidthShade, ::determineShadeMode)
            .logDiffsForTable(tableLogBuffer = tableLogBuffer, initialValue = shadeModeInitialValue)
            .stateIn(applicationScope, SharingStarted.Eagerly, initialValue = shadeModeInitialValue)

    private fun determineShadeMode(
        isDualShadeEnabled: Boolean,
        isFullWidthShade: Boolean,
    ): ShadeMode {
        return if (isDualShadeEnabled) {
            ShadeMode.Dual
        } else if (isFullWidthShade) {
            ShadeMode.Single
        } else {
            ShadeMode.Split
        }
    }

    override val notificationStackHorizontalAlignment: StateFlow<Alignment.Horizontal> =
        combine(shadeMode, isFullWidthShade, shadeConfigRepository.isNotificationShadeOnTopEnd) {
                shadeMode,
                isFullWidthShade,
                isNotificationShadeOnTopEnd ->
                when (shadeMode) {
                    is ShadeMode.Single -> Alignment.CenterHorizontally
                    is ShadeMode.Split -> Alignment.End
                    is ShadeMode.Dual ->
                        if (isFullWidthShade) {
                            Alignment.CenterHorizontally
                        } else if (isNotificationShadeOnTopEnd) {
                            Alignment.End
                        } else {
                            Alignment.Start
                        }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = Alignment.CenterHorizontally,
            )

    companion object {
        private const val TAG = "ShadeModeInteractorImpl"
    }
}

open class ShadeModeInteractorEmptyImpl @Inject constructor() : ShadeModeInteractor {

    override val shadeMode: StateFlow<ShadeMode> = MutableStateFlow(ShadeMode.Single)

    override val isFullWidthShade: StateFlow<Boolean> = MutableStateFlow(true)

    override val notificationStackHorizontalAlignment: StateFlow<Alignment.Horizontal> =
        MutableStateFlow(Alignment.CenterHorizontally)
}
