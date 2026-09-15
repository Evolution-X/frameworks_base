/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.binder

import android.content.res.Resources
import com.android.systemui.shared.R as sharedR
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.customization.clocks.utils.ViewUtils.animateToAlpha
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController.Companion.updateTheme
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.DIM_ALPHA
import kotlinx.coroutines.flow.combine

/** Binder for the small clock view, large clock view. */
object KeyguardPreviewClockViewBinder {
    val lockViewId = View.generateViewId()

    @JvmStatic
    fun bind(
        largeClockHostView: View,
        smallClockHostView: View,
        viewModel: KeyguardPreviewClockViewModel,
    ) {
        largeClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.isLargeClockVisible") {
                    viewModel.isLargeClockVisible.collect { largeClockHostView.isVisible = it }
                }
            }
        }

        smallClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.isSmallClockVisible") {
                    viewModel.isSmallClockVisible.collect { smallClockHostView.isVisible = it }
                }
            }
        }
    }

    @JvmStatic
    fun bind(
        rootView: ConstraintLayout,
        viewModel: KeyguardPreviewClockViewModel,
        clockRegistry: ClockRegistry,
        updateClockAppearance: suspend (ClockController, Resources) -> Unit,
        clockPreviewConfig: ClockPreviewConfig,
    ) {
        rootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastClock: ClockController? = null
                launch("$TAG#viewModel.previewClock") {
                        combine(
                                viewModel.previewClock,
                                viewModel.previewClockSize,
                                viewModel.showClock,
                                ::Triple,
                            )
                            .collect { (currentClock, clockSize, showClock) ->
                                lastClock?.let { clock ->
                                    (clock.largeClock.layout.views + clock.smallClock.layout.views)
                                        .forEach { rootView.removeView(it) }
                                }
                                lastClock = currentClock
                                updateClockAppearance(currentClock, rootView.context.resources)

                                if (viewModel.shouldHighlightSelectedAffordance) {
                                    (currentClock.largeClock.layout.views +
                                            currentClock.smallClock.layout.views)
                                        .forEach { it.alpha = DIM_ALPHA }
                                }
                                currentClock.largeClock.layout.views.forEach {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    rootView.addView(it)
                                }

                                currentClock.smallClock.layout.views.forEach {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    rootView.addView(it)
                                }
                                applyPreviewConstraints(
                                    clockPreviewConfig,
                                    rootView,
                                    currentClock,
                                    clockSize,
                                    showClock,
                                )
                            }
                    }
                    .invokeOnCompletion {
                        // recover seed color especially for Transit clock
                        lastClock?.apply {
                            smallClock.updateTheme { it.copy(seedColor = clockRegistry.seedColor) }
                            largeClock.updateTheme { it.copy(seedColor = clockRegistry.seedColor) }
                        }
                        lastClock = null
                    }
            }
        }
    }

    // Track the current show clock flag. If it turns from false to true, animate fade-in.
    private var currentShowClock: Boolean? = null

    private fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        rootView: ConstraintLayout,
        previewClock: ClockController,
        clockSize: ClockSizeSetting?,
        showClock: Boolean,
    ) {
        val shouldFadeIn = (currentShowClock == false) && showClock

        val cs = ConstraintSet().apply { clone(rootView) }
        val configWithUpdatedLockId =
            if (rootView.getViewById(lockViewId) != null) {
                clockPreviewConfig.copy(lockViewId = lockViewId)
            } else {
                clockPreviewConfig
            }
        // Reset the date/weather bias before the clock re-applies its own (mirrors the real
        // ClockSection), so switching the previewed clock re-centres unless it left-aligns.
        cs.setHorizontalBias(sharedR.id.date_smartspace_view_large, 0.5f)
        previewClock.largeClock.layout.applyPreviewConstraints(configWithUpdatedLockId, cs)
        previewClock.smallClock.layout.applyPreviewConstraints(configWithUpdatedLockId, cs)
        cs.applyTo(rootView)

        // When previewClockSize is the initial value, make both clocks invisible to avoid flicker
        val largeClockVisibility =
            if (showClock)
                when (clockSize) {
                    ClockSizeSetting.DYNAMIC -> VISIBLE
                    ClockSizeSetting.SMALL -> INVISIBLE
                    null -> INVISIBLE
                }
            else INVISIBLE
        val smallClockVisibility =
            if (showClock)
                when (clockSize) {
                    ClockSizeSetting.DYNAMIC -> INVISIBLE
                    ClockSizeSetting.SMALL -> VISIBLE
                    null -> INVISIBLE
                }
            else INVISIBLE
        setVisibility(previewClock.largeClock.layout.views, largeClockVisibility, shouldFadeIn)
        setVisibility(previewClock.smallClock.layout.views, smallClockVisibility, shouldFadeIn)
        if (shouldFadeIn) {
            if (largeClockVisibility == VISIBLE) {
                previewClock.largeClock.layout.views.forEach { it.animateToAlpha(1F) }
            }
            if (smallClockVisibility == VISIBLE) {
                previewClock.smallClock.layout.views.forEach { it.animateToAlpha(1F) }
            }
        }
        currentShowClock = showClock
    }

    private fun setVisibility(views: Iterable<View>, visibility: Int, shouldFadeIn: Boolean) {
        views.forEach { view ->
            if (shouldFadeIn && visibility == VISIBLE) {
                view.alpha = 0F
            }
            view.visibility = visibility
        }
    }

    private const val TAG = "KeyguardPreviewClockViewBinder"
}
