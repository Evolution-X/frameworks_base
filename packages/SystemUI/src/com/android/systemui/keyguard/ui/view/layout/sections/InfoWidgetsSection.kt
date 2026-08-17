/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
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
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import javax.inject.Inject

class InfoWidgetsSection
@Inject
constructor(
    private val context: Context,
) : KeyguardSection() {
    
    private var infoWidgetsView: View? = null
    
    override fun addViews(constraintLayout: ConstraintLayout) {
        
        constraintLayout.findViewById<View?>(R.id.keyguard_info_widgets)?.let { existingView ->
            (existingView.parent as? ViewGroup)?.removeView(existingView)
        }
        
        infoWidgetsView = LayoutInflater.from(context).inflate(
            R.layout.keyguard_info_widgets,
            constraintLayout,
            false
        ).apply {
            id = R.id.keyguard_info_widgets
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        constraintLayout.addView(infoWidgetsView)
    }
    
    override fun bindData(constraintLayout: ConstraintLayout) {
        // ProgressImageView components handle their own data binding
    }
    
    override fun applyConstraints(constraintSet: ConstraintSet) {

        constraintSet.apply {
            // Info widgets positioning - below WEATHER (3rd in hierarchy)
            connect(R.id.keyguard_info_widgets, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.keyguard_info_widgets, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            val isCustomClockEnabled = Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                0,
                UserHandle.USER_CURRENT
            ) != 0

            val topAnchor = when {
                constraintSet.getConstraint(R.id.keyguard_weather) != null &&
                    constraintSet.getVisibility(R.id.keyguard_weather) != ConstraintSet.GONE -> R.id.keyguard_weather
                constraintSet.getConstraint(R.id.default_weather_image) != null &&
                    constraintSet.getVisibility(R.id.default_weather_image) != ConstraintSet.GONE -> R.id.default_weather_image
                constraintSet.getConstraint(sharedR.id.bc_smartspace_view) != null -> sharedR.id.bc_smartspace_view
                isCustomClockEnabled && constraintSet.getConstraint(R.id.clock_ls) != null -> R.id.clock_ls
                else -> ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            }

            connect(R.id.keyguard_info_widgets, ConstraintSet.TOP, topAnchor, ConstraintSet.BOTTOM, 12)

            constrainHeight(R.id.keyguard_info_widgets, ConstraintSet.WRAP_CONTENT)
            constrainWidth(R.id.keyguard_info_widgets, ConstraintSet.MATCH_CONSTRAINT)
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.START, 0)
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.END, 0)
            setMargin(R.id.keyguard_info_widgets, ConstraintSet.BOTTOM, 6)
            setElevation(R.id.keyguard_info_widgets, 1f)
        }
    }
    
    override fun removeViews(constraintLayout: ConstraintLayout) {
        infoWidgetsView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        infoWidgetsView = null
    }
}
