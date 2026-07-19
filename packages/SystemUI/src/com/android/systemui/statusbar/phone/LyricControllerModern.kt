/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View

/**
 * Standalone lyric controller for modern status bar pipeline.
 * Manages lyric display independently without requiring CollapsedStatusBarFragment.
 */
class LyricControllerModern(
    private val context: Context,
    private val lyricContainer: View,
    private val leftSideView: View?,
    private val centeredAreaView: View?,
) : LyricViewController(context, lyricContainer.rootView) {

    private var settingsObserver: ContentObserver? = null
    private var isNotificationIconsVisible = true
    private var hideForHun = false
    private var hasOngoingActivity = false

    override fun showLyricView(animate: Boolean) {
        // Only show if all conditions are met
        if (!isNotificationIconsVisible || hideForHun || hasOngoingActivity || !isLyricStarted()) {
            hideLyricView(animate)
            return
        }
        lyricContainer.visibility = View.VISIBLE
        // Hide left side and centered area when showing lyrics
        leftSideView?.visibility = View.GONE
        centeredAreaView?.visibility = View.GONE
    }

    override fun hideLyricView(animate: Boolean) {
        lyricContainer.visibility = View.GONE
        // Restore left side and centered area visibility when hiding lyrics
        // These views are managed by the view model, but we need to show them
        // when lyrics are hidden to restore the normal status bar layout
        leftSideView?.visibility = View.VISIBLE
        centeredAreaView?.visibility = View.VISIBLE
    }

    fun attach() {
        if (settingsObserver != null) {
            return
        }

        settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val enabled = Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.STATUS_BAR_SHOW_LYRIC,
                    0,
                    UserHandle.USER_CURRENT
                ) != 0
                setEnabled(enabled)
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_SHOW_LYRIC),
            false,
            settingsObserver!!,
            UserHandle.USER_ALL
        )

        // Initialize enabled state
        val enabled = Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.STATUS_BAR_SHOW_LYRIC,
            0,
            UserHandle.USER_CURRENT
        ) != 0
        setEnabled(enabled)
    }

    fun detach() {
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
        }
        destroy()
    }

    fun updateVisibility(
        isNotificationIconsVisible: Boolean,
        hideForHun: Boolean,
        hasOngoingActivity: Boolean
    ) {
        this.isNotificationIconsVisible = isNotificationIconsVisible
        this.hideForHun = hideForHun
        this.hasOngoingActivity = hasOngoingActivity

        if (isLyricStarted()) {
            showLyricView(true)
        } else {
            hideLyricView(true)
        }
    }
}
