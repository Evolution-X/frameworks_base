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
package com.android.settingslib.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ProgressBar
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.progressbar.R

/**
 * The SimpleProgressBarPreference shows a progress style preference. Support to show the progress
 * set with [setProgress].
 */
class SimpleProgressBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    private var progress: Int = 0

    init {
        layoutResource = R.layout.settingslib_expressive_simple_progress_bar
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedBelow = false
        holder.isDividerAllowedAbove = false

        val progressBar = holder.findViewById(R.id.settingslib_progress) as? ProgressBar
        progressBar?.progress = this.progress
    }

    /**
     * Set the progress of the progress bar.
     *
     * @param progress The progress of the progress bar.
     */
    fun setProgress(progress: Int) {
        this.progress = progress
    }
}
