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
import android.widget.PopupMenu
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

import com.android.settingslib.widget.preference.menu.R
import com.google.android.material.button.MaterialButton
import androidx.core.content.withStyledAttributes

class MenuPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes), MenuHandler {

    override var menuResId: Int = 0
    override var popupMenu: PopupMenu? = null
    override var menuItemClickListener: MenuHandler.OnMenuItemClickListener? = null
    override var menuButton: MaterialButton? = null
    override var preference: Preference? = this

    init {
        layoutResource =
            com.android.settingslib.widget.theme.R.layout.settingslib_expressive_preference
        widgetLayoutResource = R.layout.settingslib_expressive_button_menu
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.MenuPreference) {
                menuResId = getResourceId(R.styleable.MenuPreference_menu, 0)
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedBelow = false
        holder.isDividerAllowedAbove = false

        menuButton = holder.findViewById(R.id.settingslib_menu_button) as MaterialButton

        // setup the onClickListener
        setupMenuButton(context)
    }
}
