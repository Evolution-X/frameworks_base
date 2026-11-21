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

package com.android.systemui.qs.panels.data.repository

import android.content.res.Resources
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.QsPanelStyle
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.kotlin.mapDirect
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class LargeTileSpanRepository
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    qsPanelStyleRepository: QsPanelStyleRepository,
) {
    val useExtraLargeTiles: Flow<Boolean> =
        configurationRepository.onConfigurationChange
            .emitOnStart()
            .mapDirect { currentUseExtraLargeTiles }
            .distinctUntilChanged()

    val tileMaxWidth: Flow<Int> =
        configurationRepository.onConfigurationChange
            .emitOnStart()
            .mapDirect { currentTileMaxWidth }
            .distinctUntilChanged()

    val defaultTileMaxWidth: Int = DEFAULT_LARGE_TILE_WIDTH

    val currentUseExtraLargeTiles: Boolean
        get() = resources.configuration.fontScale >= FONT_SCALE_THRESHOLD

    val currentTileMaxWidth: Int
        get() = resources.getInteger(R.integer.quick_settings_infinite_grid_tile_max_width)

    private companion object {
        const val FONT_SCALE_THRESHOLD = 1.8f
        const val DEFAULT_LARGE_TILE_WIDTH = 2
    }

    val classicStyle: Flow<Boolean> =
        qsPanelStyleRepository.style
            .map { it == QsPanelStyle.CLASSIC }
            .distinctUntilChanged()
}
