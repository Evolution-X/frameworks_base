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
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.customization.clocks.R as custR
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.weather.WeatherImageView
import com.android.systemui.weather.WeatherTextView
import javax.inject.Inject

class KeyguardWeatherViewSection @Inject constructor(
    private val context: Context,
) : KeyguardSection() {

    private var weatherImageView: WeatherImageView? = null
    private var weatherTextView: WeatherTextView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {

        val weatherContainer = constraintLayout.findViewById<ViewGroup?>(R.id.keyguard_weather)
        
        if (weatherContainer != null) {
            weatherImageView = weatherContainer.findViewById(R.id.default_weather_image)
            weatherTextView = weatherContainer.findViewById(R.id.default_weather_text)

            if (weatherContainer.parent !== constraintLayout) {
                (weatherContainer.parent as? ViewGroup)?.removeView(weatherContainer)
                constraintLayout.addView(weatherContainer)
            }
        } else {
            createWeatherViews(constraintLayout)
        }

        initializeWeatherViews()
    }

    private fun createWeatherViews(constraintLayout: ConstraintLayout) {
        weatherImageView = WeatherImageView(context, isCustomClock = false).apply {
            id = R.id.default_weather_image
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        weatherTextView = WeatherTextView(context, isCustomClock = false).apply {
            id = R.id.default_weather_text
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(context.getColor(android.R.color.white))
            textSize = 16f
            visibility = View.GONE
        }

        weatherImageView?.let { constraintLayout.addView(it) }
        weatherTextView?.let { constraintLayout.addView(it) }
    }

    private fun initializeWeatherViews() {
        // Weather views initialize automatically when attached to window
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        // Weather data binding handled by individual weather views
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {

        constraintSet.apply {
            val startMargin = context.resources.getDimensionPixelSize(custR.dimen.clock_padding_start) +
                context.resources.getDimensionPixelSize(custR.dimen.status_view_margin_horizontal)

            val isCustomClockEnabled = Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                0,
                UserHandle.USER_CURRENT
            ) != 0

            // Weather positioning - below CLOCK (2nd in hierarchy)
            if (constraintSet.getConstraint(R.id.keyguard_weather) != null) {
                connect(R.id.keyguard_weather, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, startMargin)
                connect(R.id.keyguard_weather, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

                // Chain to whichever clock is actually active
                if (isCustomClockEnabled && constraintSet.getConstraint(R.id.clock_ls) != null) {
                    connect(R.id.keyguard_weather, ConstraintSet.TOP, R.id.clock_ls, ConstraintSet.BOTTOM, 8)
                } else if (constraintSet.getConstraint(sharedR.id.bc_smartspace_view) != null) {
                    connect(R.id.keyguard_weather, ConstraintSet.TOP, sharedR.id.bc_smartspace_view, ConstraintSet.BOTTOM, 8)
                } else {
                    connect(R.id.keyguard_weather, ConstraintSet.TOP, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM, 8)
                }

                constrainHeight(R.id.keyguard_weather, ConstraintSet.WRAP_CONTENT)
                constrainWidth(R.id.keyguard_weather, ConstraintSet.MATCH_CONSTRAINT)
            } else {
                applyWeatherImageConstraints(constraintSet, startMargin, isCustomClockEnabled)
                applyWeatherTextConstraints(constraintSet)
            }
        }
    }

    private fun applyWeatherImageConstraints(
        constraintSet: ConstraintSet,
        startMargin: Int,
        isCustomClockEnabled: Boolean,
    ) {
        if (constraintSet.getConstraint(R.id.default_weather_image) != null) {
            constraintSet.apply {
                connect(R.id.default_weather_image, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, startMargin)

                if (isCustomClockEnabled && constraintSet.getConstraint(R.id.clock_ls) != null) {
                    connect(R.id.default_weather_image, ConstraintSet.TOP, R.id.clock_ls, ConstraintSet.BOTTOM, 8)
                } else {
                    connect(R.id.default_weather_image, ConstraintSet.TOP, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM, 8)
                }

                constrainHeight(R.id.default_weather_image, ConstraintSet.WRAP_CONTENT)
                constrainWidth(R.id.default_weather_image, ConstraintSet.WRAP_CONTENT)
            }
        }
    }

    private fun applyWeatherTextConstraints(constraintSet: ConstraintSet) {
        if (constraintSet.getConstraint(R.id.default_weather_text) != null) {
            constraintSet.apply {
                connect(R.id.default_weather_text, ConstraintSet.START, R.id.default_weather_image, ConstraintSet.END, 12)
                connect(R.id.default_weather_text, ConstraintSet.TOP, R.id.default_weather_image, ConstraintSet.TOP)
                connect(R.id.default_weather_text, ConstraintSet.BOTTOM, R.id.default_weather_image, ConstraintSet.BOTTOM)
                constrainHeight(R.id.default_weather_text, ConstraintSet.WRAP_CONTENT)
                constrainWidth(R.id.default_weather_text, ConstraintSet.WRAP_CONTENT)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.findViewById<ViewGroup?>(R.id.keyguard_weather)?.let { weatherContainer ->
            constraintLayout.removeView(weatherContainer)
        }
        
        weatherImageView?.let { constraintLayout.removeView(it) }
        weatherTextView?.let { constraintLayout.removeView(it) }
        
        weatherImageView = null
        weatherTextView = null
    }
}
