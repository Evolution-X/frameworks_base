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
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.preference.Preference
import com.android.settingslib.widget.preference.menu.R
import com.google.android.material.button.MaterialButton

/**
 * A trait to add menu functionality to a Preference.
 */
interface MenuHandler {
    var menuResId: Int
    var popupMenu: PopupMenu?
    var menuItemClickListener: OnMenuItemClickListener?
    var menuButton: MaterialButton?
    var preference: Preference?

    interface OnMenuItemClickListener {
        fun onMenuItemClick(item: MenuItem, pref: Preference): Boolean
    }
    /**
     * Shows the popup menu anchored to the specified view.
     *
     * @param view The view to anchor the popup menu to.
     */
    fun showPopupMenu(context: Context, view: View) {
        if (menuResId != 0) {
            popupMenu =
                PopupMenu(context, view, Gravity.END, 0, R.style.SettingslibPopupMenuStyle).apply {
                    menuInflater.inflate(menuResId, menu)
                    setOnMenuItemClickListener { item ->
                        menuItemClickListener?.onMenuItemClick(item, preference!!) ?: false
                    }
                    show()
                }
        }
    }

    /**
     * Sets up the menu button to show the popup menu when clicked.
     *
     * @param context The context.
     */
    fun setupMenuButton(context: Context) {
        menuButton?.setOnClickListener { view ->
            showPopupMenu(context, view)
        }
    }

    /**
     * Sets the menu resource ID for the popup menu.
     *
     * @param menuResId The resource ID of the menu to inflate.
     */
    fun setMenuResource(menuResId: Int) {
        this.menuResId = menuResId
    }

    /**
     * Sets the listener for menu item clicks.
     *
     * @param listener The listener to be notified of menu item clicks.
     */
    fun setOnMenuItemClickListener(listener: OnMenuItemClickListener?) {
        this.menuItemClickListener = listener
    }
}