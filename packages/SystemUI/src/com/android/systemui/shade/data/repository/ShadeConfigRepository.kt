/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.data.repository

import android.content.res.Resources
import android.provider.Settings
import androidx.annotation.BoolRes
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Provides shade configuration values for the window currently hosting the shade. This is mainly a
 * convenience wrapper; the same values may be accessed directly from a [ShadeDisplayAware] context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeConfigRepository
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware private val configurationRepository: ConfigurationRepository,
    secureSettingsRepository: SecureSettingsRepository,
    featureFlags: FeatureFlagsClassic,
) {
    /** @see ShadeModeInteractor.isFullWidthShade */
    val isFullWidthShade: Flow<Boolean> =
        booleanConfigFlow(R.bool.config_isFullWidthShade, sceneContainerOnly = false)

    /**
     * Whether Dual Shade should be enabled in the absence of an explicit preference set by the
     * user.
     */
    val isDualShadeEnabledByDefault: Flow<Boolean> =
        booleanConfigFlow(R.bool.config_dualShadeEnabledByDefault)

    /**
     * Whether an explicit preference set by the user enabled (`true`) or disabled (`false`) Dual
     * Shade. If no preference was set explicitly, this emits the same values as
     * [isDualShadeEnabledByDefault].
     */
    val isDualShadeSettingEnabled: Flow<Boolean> =
        isDualShadeEnabledByDefault.flatMapLatest { defaultValue ->
            secureSettingsRepository
                .boolSetting(Settings.Secure.DUAL_SHADE, defaultValue = defaultValue)
                .flowOn(backgroundDispatcher)
        }

    /**
     * Whether to allow the user to choose between Single and Dual Shade modes via the Settings app.
     * When `false`, the preference and associated Settings Page are absent from the Settings app
     * (including any shortcuts to them), the user preference value is ignored, and
     * [isDualShadeEnabledByDefault] is used.
     */
    val useDualShadeSetting: Flow<Boolean> = flowOf(true)

    /**
     * Whether notifications shade should be in the top end position (e.g. the right side of the
     * screen in an LTR locale) on wide screens. Only used in Dual Shade mode, when
     * `config_isFullWidthShade` is `false`. Ignored in all other cases.
     */
    val isNotificationShadeOnTopEnd: Flow<Boolean> =
        booleanConfigFlow(R.bool.config_notificationShadeOnTopEnd)

    /**
     * Whether the shade layout should be Split Shade (`true`) or Single Shade (`false`). Only
     * applicable when Dual Shade is disabled.
     */
    val legacyUseSplitShade: Flow<Boolean> =
        configurationRepository.onConfigurationChange
            .emitOnStart()
            .map {
                with(resources) {
                    // Based on the logic in the deprecated `SplitShadeStateController`.
                    getBoolean(R.bool.config_use_split_notification_shade) ||
                        (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE) &&
                            getBoolean(R.bool.force_config_use_split_notification_shade))
                }
            }
            .distinctUntilChanged()

    /** @see isFullWidthShade (the `val` above) */
    fun isFullWidthShade(): Boolean = resources.getBoolean(R.bool.config_isFullWidthShade)

    /** @see isDualShadeEnabledByDefault (the `val` above) */
    fun isDualShadeEnabledByDefault(): Boolean =
        resources.getBoolean(R.bool.config_dualShadeEnabledByDefault)

    // TODO(444424242): Instead of having a separate flow for each resource, read them all at the
    //  same time whenever the configuration changes, and expose as a combined data class.
    private fun booleanConfigFlow(
        @BoolRes resID: Int,
        sceneContainerOnly: Boolean = true,
    ): Flow<Boolean> {
        return if (sceneContainerOnly && !SceneContainerFlag.isEnabled) {
            flowOf(false)
        } else {
            configurationRepository.onConfigurationChange
                .emitOnStart()
                .map { resources.getBoolean(resID) }
                .flowOn(backgroundDispatcher)
                .distinctUntilChanged()
        }
    }
}
