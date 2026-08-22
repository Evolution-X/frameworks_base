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
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.nowplaying.NowPlayingViewController
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class NowPlayingKeyguardSection
@Inject
constructor(
    @Application private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val nowPlayingViewController: NowPlayingViewController,
) : KeyguardSection() {

    private val isPixel = PixelAmbientIndicationDetector
        .shouldUseNativeAmbientIndication(context)

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (isPixel) return
        if (constraintLayout.findViewById<View>(R.id.now_playing_view) != null) return
        val view = nowPlayingViewController.getNowPlayingView()
        view.id = R.id.now_playing_view
        constraintLayout.addView(view)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (isPixel) return
        constraintSet.constrainWidth(
            R.id.now_playing_view,
            ConstraintLayout.LayoutParams.MATCH_PARENT,
        )
        if (keyguardUpdateMonitor.isUdfpsSupported()) {
            constraintSet.constrainHeight(R.id.now_playing_view, 0)
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.TOP,
                R.id.device_entry_icon_view,
                ConstraintSet.BOTTOM,
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.BOTTOM,
                R.id.keyguard_indication_area,
                ConstraintSet.TOP,
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
        } else {
            constraintSet.constrainHeight(
                R.id.now_playing_view,
                ConstraintSet.WRAP_CONTENT,
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.BOTTOM,
                R.id.device_entry_icon_view,
                ConstraintSet.TOP,
                context.resources.getDimensionPixelSize(
                    R.dimen.nowplaying_ambient_margin_bottom
                ),
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            constraintSet.connect(
                R.id.now_playing_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
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
