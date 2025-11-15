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

import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.customization.clocks.ViewUtils.animateToAlpha
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.ClockPreviewConfig
import com.android.systemui.plugins.clocks.ClockViewIds
import com.android.systemui.res.R
import kotlinx.coroutines.flow.combine

/** Binder for the small clock view, large clock view and smartspace. */
object KeyguardPreviewSmartspaceViewBinder {

    // Track the current show smartsapce flag. If it turns from false to true, animate fade-in.
    private var currentShowSmartspace: Boolean? = null

    @JvmStatic
    fun bind(
        parentView: ConstraintLayout,
        viewModel: KeyguardPreviewSmartspaceViewModel,
        previewViewModel: KeyguardPreviewViewModel,
    ) {
        if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
            val largeDateView =
                parentView.findViewById<View>(
                    com.android.systemui.shared.R.id.date_smartspace_view_large
                )
            val smallDateView =
                parentView.findViewById<View>(
                    com.android.systemui.shared.R.id.date_smartspace_view
                )
            parentView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch("$TAG#viewModel.previewClockSize") {
                        combine(viewModel.previewClockSize, viewModel.showSmartspace, ::Pair)
                            .collect { (clockSize, showSmartspace) ->
                                val shouldFadeIn =
                                    (currentShowSmartspace == false) && showSmartspace
                                val largeDateViewVisibility =
                                    if (showSmartspace) {
                                        when (clockSize) {
                                            ClockSizeSetting.DYNAMIC -> {
                                                if (viewModel.shouldDateWeatherBeBelowLargeClock) {
                                                    View.VISIBLE
                                                } else {
                                                    View.INVISIBLE
                                                }
                                            }
                                            ClockSizeSetting.SMALL -> View.INVISIBLE
                                        }
                                    } else {
                                        View.INVISIBLE
                                    }
                                val smallDateViewVisibility =
                                    if (showSmartspace) {
                                        when (clockSize) {
                                            ClockSizeSetting.DYNAMIC -> {
                                                if (viewModel.shouldDateWeatherBeBelowLargeClock) {
                                                    View.INVISIBLE
                                                } else {
                                                    View.VISIBLE
                                                }
                                            }
                                            ClockSizeSetting.SMALL -> View.VISIBLE
                                        }
                                    } else {
                                        View.INVISIBLE
                                    }
                                largeDateView?.let {
                                    if (shouldFadeIn && largeDateViewVisibility == View.VISIBLE) {
                                        it.alpha = 0F
                                    }
                                    it.visibility = largeDateViewVisibility
                                }
                                smallDateView?.let {
                                    if (shouldFadeIn && smallDateViewVisibility == View.VISIBLE) {
                                        it.alpha = 0F
                                    }
                                    it.visibility = smallDateViewVisibility
                                }
                                if (shouldFadeIn) {
                                    if (largeDateViewVisibility == View.VISIBLE) {
                                        largeDateView?.animateToAlpha(1F)
                                    }
                                    if (smallDateViewVisibility == View.VISIBLE) {
                                        smallDateView?.animateToAlpha(1F)
                                    }
                                }
                                val cs = ConstraintSet()
                                cs.clone(parentView)
                                cs.apply {
                                    val smallClockViewId = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                                    val largeClockViewId = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                                    when (clockSize) {
                                        ClockSizeSetting.DYNAMIC -> {
                                            if (viewModel.shouldDateWeatherBeBelowLargeClock) {
                                                largeDateView?.also { view ->
                                                    constrainWidth(
                                                        view.id,
                                                        ConstraintSet.WRAP_CONTENT,
                                                    )
                                                    constrainHeight(
                                                        view.id,
                                                        ConstraintSet.WRAP_CONTENT,
                                                    )
                                                    connect(view.id, START, largeClockViewId, START)
                                                    connect(
                                                        view.id,
                                                        ConstraintSet.END,
                                                        largeClockViewId,
                                                        ConstraintSet.END,
                                                    )
                                                    connect(
                                                        view.id,
                                                        TOP,
                                                        largeClockViewId,
                                                        ConstraintSet.BOTTOM,
                                                        viewModel.getDateWeatherEndPadding(
                                                            view.context
                                                        ),
                                                    )
                                                }
                                            } else {
                                                smallDateView?.also { view ->
                                                    constrainWidth(
                                                        view.id,
                                                        ConstraintSet.WRAP_CONTENT,
                                                    )
                                                    constrainHeight(
                                                        view.id,
                                                        ConstraintSet.WRAP_CONTENT,
                                                    )
                                                    (view as? LinearLayout)?.orientation =
                                                        LinearLayout.HORIZONTAL
                                                    connect(view.id, START, smallClockViewId, START)
                                                    clear(view.id, TOP)
                                                    connect(
                                                        view.id,
                                                        TOP,
                                                        PARENT_ID,
                                                        TOP,
                                                        viewModel.getLargeClockSmartspaceTopPadding(
                                                            parentView.context,
                                                            previewViewModel.buildPreviewConfig(),
                                                        ),
                                                    )
                                                }
                                            }
                                        }

                                        ClockSizeSetting.SMALL -> {
                                            smallDateView?.also { view ->
                                                constrainWidth(view.id, ConstraintSet.WRAP_CONTENT)
                                                constrainHeight(view.id, ConstraintSet.WRAP_CONTENT)
                                                if (viewModel.shouldDateWeatherBeBelowSmallClock) {
                                                    (view as? LinearLayout)?.orientation =
                                                        LinearLayout.HORIZONTAL
                                                    connect(view.id, START, smallClockViewId, START)
                                                    connect(
                                                        view.id,
                                                        TOP,
                                                        smallClockViewId,
                                                        ConstraintSet.BOTTOM,
                                                        view.resources.getDimensionPixelSize(
                                                            R.dimen.smartspace_padding_vertical
                                                        ),
                                                    )
                                                } else {
                                                    (view as? LinearLayout)?.orientation =
                                                        LinearLayout.VERTICAL
                                                    connect(
                                                        view.id,
                                                        START,
                                                        smallClockViewId,
                                                        ConstraintSet.END,
                                                        view.resources.getDimensionPixelSize(
                                                            R.dimen.smartspace_padding_horizontal
                                                        ),
                                                    )
                                                    connect(view.id, TOP, smallClockViewId, TOP)
                                                    connect(
                                                        view.id,
                                                        ConstraintSet.BOTTOM,
                                                        smallClockViewId,
                                                        ConstraintSet.BOTTOM,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                cs.applyTo(parentView)
                                currentShowSmartspace = showSmartspace
                            }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun bind(
        smartspace: View,
        viewModel: KeyguardPreviewSmartspaceViewModel,
        clockPreviewConfig: ClockPreviewConfig,
    ) {
        smartspace.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.previewClockSize") {
                    viewModel.previewClockSize.collect {
                        val topPadding =
                            when (it) {
                                ClockSizeSetting.DYNAMIC ->
                                    viewModel.getLargeClockSmartspaceTopPadding(
                                        smartspace.context,
                                        clockPreviewConfig,
                                    )

                                ClockSizeSetting.SMALL ->
                                    viewModel.getSmallClockSmartspaceTopPadding(
                                        smartspace.context,
                                        clockPreviewConfig,
                                    )
                            }
                        smartspace.setTopPadding(topPadding)
                    }
                }
                launch("$TAG#viewModel.shouldHideSmartspace") {
                    viewModel.shouldHideSmartspace.collect { smartspace.isInvisible = it }
                }
            }
        }
    }

    private fun View.setTopPadding(padding: Int) {
        setPaddingRelative(paddingStart, padding, paddingEnd, paddingBottom)
    }

    private const val TAG = "KeyguardPreviewSmartspaceViewBinder"
}
