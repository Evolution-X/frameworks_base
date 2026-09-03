/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
package com.android.systemui.nowplaying.ambient

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.nowplaying.NowPlayingViewController
import com.android.systemui.res.R
import dagger.Lazy
import javax.inject.Inject
import android.util.Log

@SysUISingleton
class NowPlayingKeyguardSection
@Inject
constructor(
    @Application private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val nowPlayingViewController: NowPlayingViewController,
    private val keyguardBlueprintInteractorLazy: dagger.Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {

    private val isPixel = PixelAmbientIndicationDetector
        .shouldUseNativeAmbientIndication(context)

    private var pillGoesAboveIcon = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (isPixel) return
        if (constraintLayout.findViewById<View>(R.id.now_playing_view) != null) return
        val view = nowPlayingViewController.getNowPlayingView()
        view.id = R.id.now_playing_view
        constraintLayout.addView(view)

        if (keyguardUpdateMonitor.isUdfpsSupported()) {
            constraintLayout.doOnLayout {
                val icon = constraintLayout.findViewById<View>(R.id.device_entry_icon_view) ?: return@doOnLayout
                val indicationArea = constraintLayout.findViewById<View>(R.id.keyguard_indication_area) ?: return@doOnLayout
                val minHeight = context.resources.getDimensionPixelSize(R.dimen.nowplaying_ambient_min_height)
                val gap = indicationArea.top - icon.bottom
                val shouldGoAbove = gap < minHeight
                Log.d("NowPlayingKeyguardSection", "addViews/doOnLayout: gap=$gap minHeight=$minHeight shouldGoAbove=$shouldGoAbove currentFlag=$pillGoesAboveIcon")
                if (shouldGoAbove != pillGoesAboveIcon) {
                    pillGoesAboveIcon = shouldGoAbove
                    Log.d("NowPlayingKeyguardSection", "addViews/doOnLayout: flipping pillGoesAboveIcon -> $shouldGoAbove, requesting refresh")
                    keyguardBlueprintInteractorLazy.get().refreshBlueprint(
                        Config(Type.NoTransition, rebuildSections = listOf(this))
                    )
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (isPixel) return
        constraintSet.constrainWidth(R.id.now_playing_view, ConstraintLayout.LayoutParams.MATCH_PARENT)
        constraintSet.connect(R.id.now_playing_view, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.now_playing_view, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

        if (keyguardUpdateMonitor.isUdfpsSupported()) {
            Log.d("NowPlayingKeyguardSection", "applyConstraints: pillGoesAboveIcon=$pillGoesAboveIcon")
            if (pillGoesAboveIcon) {
                constraintSet.clear(R.id.now_playing_view, ConstraintSet.TOP)
                constraintSet.constrainHeight(R.id.now_playing_view, ConstraintSet.WRAP_CONTENT)
                constraintSet.connect(
                    R.id.now_playing_view, ConstraintSet.BOTTOM,
                    R.id.keyguard_indication_area, ConstraintSet.TOP,
                    context.resources.getDimensionPixelSize(R.dimen.nowplaying_ambient_margin_above_indication),
                )
            } else {
                constraintSet.constrainHeight(R.id.now_playing_view, 0) // MATCH_CONSTRAINT
                constraintSet.constrainMinHeight(
                    R.id.now_playing_view,
                    context.resources.getDimensionPixelSize(R.dimen.nowplaying_ambient_min_height),
                )
                constraintSet.connect(R.id.now_playing_view, ConstraintSet.TOP, R.id.device_entry_icon_view, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.now_playing_view, ConstraintSet.BOTTOM, R.id.keyguard_indication_area, ConstraintSet.TOP)
                constraintSet.setVerticalBias(R.id.now_playing_view, 0f)
            }
        } else {
            constraintSet.clear(R.id.now_playing_view, ConstraintSet.TOP)
            constraintSet.clear(R.id.now_playing_view, ConstraintSet.BOTTOM)
            constraintSet.constrainHeight(R.id.now_playing_view, ConstraintSet.WRAP_CONTENT)
            constraintSet.connect(
                R.id.now_playing_view, ConstraintSet.BOTTOM,
                R.id.device_entry_icon_view, ConstraintSet.TOP,
                context.resources.getDimensionPixelSize(R.dimen.nowplaying_ambient_margin_bottom),
            )
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        // NowPlayingViewController is already self-contained and
        // listens to ScrimUtils/media session state on its own.
        // Nothing extra to bind here.
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (isPixel) return
        constraintLayout.findViewById<View>(R.id.now_playing_view)
            ?.let { constraintLayout.removeView(it) }
    }
}
