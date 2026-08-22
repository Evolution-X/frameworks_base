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
 *
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import com.android.systemui.lockscreen.LockScreenWidgets
import com.android.systemui.shared.R as sharedR
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds

class KeyguardWidgetViewSection
@Inject
constructor(
    private val context: Context,
    private val keyguardClockViewModel: KeyguardClockViewModel,
) : KeyguardSection() {

    private var widgetView: LockScreenWidgets? = null
    private val TAG = "KeyguardWidgetViewSection"

    private fun createWidgetView(): LockScreenWidgets? {
        Log.d(TAG, "Creating LockScreenWidgets view")
        return try {
            val layoutInflater = android.view.LayoutInflater.from(context)
            // Try to inflate from your actual layout file
            val view = layoutInflater.inflate(R.layout.keyguard_clock_widgets, null) as LockScreenWidgets
            
            view.apply {
                // Override the ID to match what the keyguard system expects
                id = R.id.keyguard_widgets
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                )
                visibility = View.VISIBLE
            }
            
            Log.d(TAG, "Successfully inflated LockScreenWidgets from keyguard_clock_widgets layout")
            view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate from keyguard_clock_widgets, trying direct instantiation", e)
            try {
                // Create a dummy AttributeSet for direct instantiation
                val parser = context.resources.getLayout(android.R.layout.simple_list_item_1)
                val attrs = android.util.Xml.asAttributeSet(parser)
                
                LockScreenWidgets(context, attrs).apply {
                    id = R.id.keyguard_widgets
                    layoutParams = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.WRAP_CONTENT
                    )
                    visibility = View.VISIBLE
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create LockScreenWidgets directly", e2)
                null
            }
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        Log.d(TAG, "addViews called")
        
        // Check if the widget view already exists in the layout
        val existingView = constraintLayout.findViewById<View?>(R.id.keyguard_widgets)
        
        if (existingView != null) {
            Log.d(TAG, "Found existing widget view")
            widgetView = existingView as? LockScreenWidgets
            return
        }
        
        // Check if we already have a widget view instance
        if (widgetView != null) {
            Log.d(TAG, "Reusing existing widget view instance")
            // Remove from any previous parent
            (widgetView?.parent as? ViewGroup)?.removeView(widgetView)
        } else {
            Log.d(TAG, "Creating new widget view")
            widgetView = createWidgetView()
        }
        
        widgetView?.let { view ->
            try {
                constraintLayout.addView(view)
                Log.d(TAG, "Successfully added widget view to constraint layout")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add widget view", e)
            }
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        Log.d(TAG, "bindData called")
        // Ensure the widget view is properly initialized and visible
        widgetView?.let { view ->
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                Log.d(TAG, "Set widget view visibility to VISIBLE")
            }
            
            // Force a layout pass to ensure the view is measured and laid out
            view.requestLayout()
            keyguardClockViewModel.burnInLayer?.apply {
                addView(view)
                updatePostLayout(constraintLayout)
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        widgetView ?: run {
            Log.w(TAG, "Widget view is null, skipping constraints")
            return
        }

        try {
            constraintSet.apply {
                connect(R.id.keyguard_widgets, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(R.id.keyguard_widgets, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

                // Always chain below keyguard_info_widgets, to keep a fixed order:
                // clock -> smartspace -> keyguard_info_widgets -> keyguard_widgets
                val topAnchor = if (constraintSet.getConstraint(R.id.keyguard_info_widgets) != null) {
                    R.id.keyguard_info_widgets
                } else {
                    // Fallback chain if info widgets aren't present for some reason
                    sequenceOf(
                        sharedR.id.bc_smartspace_view,
                        R.id.clock_ls,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                    ).firstOrNull { constraintSet.getConstraint(it) != null }
                        ?: ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                }

                connect(R.id.keyguard_widgets, ConstraintSet.TOP, topAnchor, ConstraintSet.BOTTOM, 8)

                constrainHeight(R.id.keyguard_widgets, ConstraintSet.WRAP_CONTENT)
                constrainWidth(R.id.keyguard_widgets, ConstraintSet.MATCH_CONSTRAINT)
                setMargin(R.id.keyguard_widgets, ConstraintSet.START, 0)
                setMargin(R.id.keyguard_widgets, ConstraintSet.END, 0)
                setElevation(R.id.keyguard_widgets, 2f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply constraints", e)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        Log.d(TAG, "removeViews called")
        widgetView?.let { view ->
            keyguardClockViewModel.burnInLayer?.apply {
                removeView(view)
                updatePostLayout(constraintLayout)
            }
            try {
                constraintLayout.removeView(view)
                Log.d(TAG, "Successfully removed widget view")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove widget view", e)
            }
        }
        widgetView = null
    }
}
