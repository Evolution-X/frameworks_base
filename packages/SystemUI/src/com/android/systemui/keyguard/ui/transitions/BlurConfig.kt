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

package com.android.systemui.keyguard.ui.transitions

import android.os.UserHandle
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Config that provides the max and min blur radius for the window blurs. */
class BlurConfig(
    val minBlurRadiusPx: Float,
    private val defaultMaxBlurRadiusPx: Float,
    private val secureSettings: SecureSettings?,
) {
    // No-op config that will be used by dagger of other SysUI variants which don't blur the
    // background surface.
    @Inject constructor() : this(0.0f, 0.0f, null)

    constructor(minBlurRadiusPx: Float, maxBlurRadiusPx: Float) :
        this(minBlurRadiusPx, maxBlurRadiusPx, null)

    val maxBlurRadiusPx: Float
        get() {
            val percent = secureSettings?.getFloatForUser(
                KEY_BLUR_RADIUS_PCT,
                DEFAULT_BLUR_RADIUS_PCT,
                UserHandle.USER_CURRENT,
            ) ?: return defaultMaxBlurRadiusPx
            if (!percent.isFinite()) return defaultMaxBlurRadiusPx
            return defaultMaxBlurRadiusPx *
                percent.coerceIn(MIN_BLUR_RADIUS_PCT, MAX_BLUR_RADIUS_PCT) /
                MAX_BLUR_RADIUS_PCT
        }

    /**
     * Scales [maxBlurRadiusPx] down as refresh rate climbs past [REFRESH_RATE_SCALE_START_HZ],
     * reaching [HIGH_REFRESH_RATE_MIN_SCALE] at [REFRESH_RATE_SCALE_END_HZ]. The blur's
     * per-frame GPU cost is fixed by radius alone, so a radius that fits comfortably in a
     * 90Hz frame budget can blow a 120Hz budget; this keeps blur affordable at high refresh
     * rates instead of relying on the user to manually lower blur intensity.
     */
    fun maxBlurRadiusPxForRefreshRate(refreshRateHz: Float): Float {
        val base = maxBlurRadiusPx
        if (refreshRateHz <= REFRESH_RATE_SCALE_START_HZ) return base
        val t = ((refreshRateHz - REFRESH_RATE_SCALE_START_HZ) /
            (REFRESH_RATE_SCALE_END_HZ - REFRESH_RATE_SCALE_START_HZ)).coerceIn(0f, 1f)
        val scale = 1f - t * (1f - HIGH_REFRESH_RATE_MIN_SCALE)
        return base * scale
    }

    val maxBlurRadiusFlow: Flow<Float> = secureSettings?.let { settings ->
        settings.observerFlow(KEY_BLUR_RADIUS_PCT)
            .onStart { emit(Unit) }
            .map { maxBlurRadiusPx }
            .distinctUntilChanged()
    } ?: emptyFlow()

    companion object {
        const val KEY_BLUR_RADIUS_PCT = "system_blur_radius_pct"
        private const val MIN_BLUR_RADIUS_PCT = 0f
        private const val MAX_BLUR_RADIUS_PCT = 100f
        private const val DEFAULT_BLUR_RADIUS_PCT = MAX_BLUR_RADIUS_PCT
        private const val REFRESH_RATE_SCALE_START_HZ = 90f
        private const val REFRESH_RATE_SCALE_END_HZ = 120f
        private const val HIGH_REFRESH_RATE_MIN_SCALE = 0.6f
    }
}
