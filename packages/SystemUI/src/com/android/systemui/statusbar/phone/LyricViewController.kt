/*
 * Copyright (C) 2022 Project Kaleidoscope
 * Copyright (C) 2025-2026 RisingOS (revived) Android Project
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

package com.android.systemui.statusbar.phone

import android.app.Notification
import android.content.Context
import android.graphics.Rect
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextSwitcher
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationListener

abstract class LyricViewController(
    private val context: Context,
    statusBar: View,
) : DarkIconDispatcher.DarkReceiver, NotificationListener.NotificationHandler {

    private val textSwitcher: TextSwitcher = statusBar.findViewById(R.id.lyric_text)
    private val lyricContainer: View = statusBar.findViewById(R.id.lyric_container)

    private var enabled = false
    private var started = false

    private var currentNotificationPackage: String? = null
    private var currentNotificationId = 0

    private val lyricsFetcher: LyricsFetcher
    private val lyricsCallback: LyricsFetcher.Callback
    private var mediaLyricsActive = false

    init {
        val animationIn = AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_in)
        val animationOut = AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_out)

        textSwitcher.inAnimation = animationIn
        textSwitcher.outAnimation = animationOut

        lyricContainer.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideLyricView(true)
                v.postDelayed({ showLyricView(true) }, HIDE_LYRIC_DELAY.toLong())
            }
            false
        }

        lyricsFetcher = LyricsFetcher.getInstance(context)
        lyricsCallback = object : LyricsFetcher.Callback {
            override fun onSyncedLineChanged(prevLine: String?, line: String?, nextLine: String?) {
                if (!enabled) return
                if (line == null) {
                    if (mediaLyricsActive) {
                        mediaLyricsActive = false
                        stopLyric()
                    }
                    return
                }
                mediaLyricsActive = true
                textSwitcher.setText(line)
                startLyric()
            }

            override fun onPlainLyricsAvailable(plainLyrics: String) {
                if (!enabled) return
                mediaLyricsActive = true
                textSwitcher.setText(plainLyrics)
                startLyric()
            }

            override fun onLyricsCleared() {
                if (mediaLyricsActive) {
                    mediaLyricsActive = false
                    stopLyric()
                }
            }
        }

        Dependency.get(DarkIconDispatcher::class.java).addDarkReceiver(this)
        Dependency.get(NotificationListener::class.java).addNotificationHandler(this)
        lyricsFetcher.addCallback(lyricsCallback)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!this.enabled && started) {
            stopLyric()
        }
    }

    fun isEnabled(): Boolean = enabled

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!enabled || mediaLyricsActive) return

        val notification = sbn.notification
        val isLyric = (notification.flags and Notification.FLAG_ALWAYS_SHOW_TICKER) != 0

        val isCurrentNotification = currentNotificationId == sbn.id &&
            TextUtils.equals(sbn.packageName, currentNotificationPackage)

        if (!isLyric) {
            if (isCurrentNotification) {
                stopLyric()
            }
        } else {
            currentNotificationPackage = sbn.packageName
            currentNotificationId = sbn.id

            if (notification.tickerText == null) {
                stopLyric()
                return
            }
            startLyric()
            textSwitcher.setText(notification.tickerText)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val isCurrentNotification = currentNotificationId == sbn.id &&
            TextUtils.equals(sbn.packageName, currentNotificationPackage)
        if (isCurrentNotification) {
            stopLyric()
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        onNotificationRemoved(sbn, rankingMap)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    override fun onNotificationsInitialized() {}

    fun destroy() {
        lyricsFetcher.removeCallback(lyricsCallback)
    }

    fun startLyric() {
        if (!started) {
            started = true
            showLyricView(true)
        }
    }

    fun stopLyric() {
        if (started) {
            started = false
            hideLyricView(true)
            currentNotificationPackage = null
            currentNotificationId = 0
        }
    }

    abstract fun showLyricView(animate: Boolean)

    abstract fun hideLyricView(animate: Boolean)

    fun isLyricStarted(): Boolean = started

    val view: View
        get() = lyricContainer

    override fun onDarkChanged(area: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        val tintColor = DarkIconDispatcher.getTint(area ?: ArrayList(), lyricContainer, tint)

        (textSwitcher.currentView as TextView).setTextColor(tintColor)
        (textSwitcher.nextView as TextView).setTextColor(tintColor)
    }

    companion object {
        private const val HIDE_LYRIC_DELAY = 1200
    }
}
