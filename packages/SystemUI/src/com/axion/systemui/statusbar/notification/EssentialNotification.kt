/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.axion.systemui.statusbar.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils

class EssentialNotification {

    companion object {
        private val NEED_CREATE_CONVERSATION_CHANNEL_PACKAGES = setOf(
            "com.snapchat.android",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.skype.raider",
            "jp.naver.line.android",
            "com.kakao.talk"
        )

        private const val EXTRA_TITLE = "android.title"
        private const val EXTRA_TEXT = "android.text"
        private const val EXTRA_SUB_TEXT = "android.subText"
        private const val EXTRA_BIG_TEXT = "android.bigText"
        private const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"
        private const val EXTRA_MESSAGES = "android.messages"
        private const val EXTRA_TEXT_LINES = "android.textLines"

        @JvmStatic
        fun getKeyFromSbn(sbn: StatusBarNotification?): String {
            return sbn?.let { "${it.packageName}_${it.uid}_${it.id}" } ?: ""
        }

        @JvmStatic
        fun isNeedCreateConversationChannel(packageName: String): Boolean {
            return packageName in NEED_CREATE_CONVERSATION_CHANNEL_PACKAGES
        }
    }

    var sbn: StatusBarNotification? = null
        private set
    var notificationChannel: NotificationChannel? = null
        private set
    var ranking: NotificationListenerService.Ranking? = null
        private set
    var key: String? = null
        private set
    var packageName: String? = null
        private set
    var channelId: String? = null
        private set
    var ringtone: String? = null
        private set
    var userId: Int = 0
        private set
    var isGroupSummaryNotification: Boolean = false
        private set
    var isInheritEssential: Boolean = false
    val contents: MutableList<String> = mutableListOf()
    val senderInfos: MutableList<String> = mutableListOf()

    constructor(sbn: StatusBarNotification?, channel: NotificationChannel?, fillContents: Boolean) {
        sbn?.let {
            userId = it.user.identifier
            packageName = it.packageName
            key = getKeyFromSbn(it)
            if (fillContents) {
                it.notification?.let { notif -> fillContentsAndSender(notif) }
            }
        }
        this.sbn = sbn
        updateNotificationChannel(channel)
    }

    constructor(sbn: StatusBarNotification?, ranking: NotificationListenerService.Ranking?, fillContents: Boolean) {
        sbn?.let {
            userId = it.user.identifier
            packageName = it.packageName
            key = getKeyFromSbn(it)
            if (fillContents) {
                it.notification?.let { notif -> fillContentsAndSender(notif) }
            }
        }
        this.sbn = sbn
        this.ranking = ranking
        updateNotificationChannel(ranking?.channel)
    }

    constructor(key: String?) {
        this.key = key
    }

    private fun fillContentsAndSender(notification: Notification) {
        val bundle = notification.extras ?: return
        fillContentAndSender(bundle.getCharSequence(EXTRA_TITLE, null))
        fillContent(bundle.getCharSequence(EXTRA_TEXT, null))
        fillContent(bundle.getCharSequence(EXTRA_SUB_TEXT, null))
        fillContent(bundle.getCharSequence(EXTRA_BIG_TEXT, null))
        fillSender(bundle.getCharSequence(EXTRA_CONVERSATION_TITLE, null))

        @Suppress("UNCHECKED_CAST")
        (bundle.get(EXTRA_MESSAGES) as? Array<*>)?.forEach { msg ->
            (msg as? android.os.Bundle)?.let { msgBundle ->
                fillSender(msgBundle.getCharSequence("sender"))
                fillContent(msgBundle.getCharSequence("text"))
            }
        }
    }

    private fun fillSender(charSequence: CharSequence?) {
        charSequence?.let { senderInfos.add(it.toString()) }
    }

    private fun fillContent(charSequence: CharSequence?) {
        charSequence?.let { contents.add(it.toString()) }
    }

    private fun fillContentAndSender(charSequence: CharSequence?) {
        charSequence?.let {
            contents.add(it.toString())
            senderInfos.add(it.toString())
        }
    }

    private fun updateNotificationChannel(channel: NotificationChannel?) {
        notificationChannel = channel
        channel?.let {
            channelId = it.id
            ringtone = when {
                it.sound != null && it.sound != Uri.EMPTY -> it.sound.toString()
                it.sound == null -> "null"
                else -> null
            }
        } ?: run {
            channelId = null
        }
    }

    fun canBeEssentialNotification(): Boolean {
        if (isInheritEssential) return true
        return isImportantNotification() && ringtone != null && hasNotificationContent()
    }

    fun hasNotificationContent(): Boolean {
        if (!isGroupSummaryNotification) return true
        return sbn?.notification?.extras?.containsKey(EXTRA_TEXT_LINES) == true
    }

    fun isBubbleNotification(): Boolean {
        return sbn?.notification?.let { (it.flags and Notification.FLAG_BUBBLE) != 0 } ?: false
    }

    fun isImportantNotification(): Boolean {
        return notificationChannel?.importance?.let { it >= NotificationManager.IMPORTANCE_DEFAULT } ?: false
    }

    fun getName(): CharSequence {
        ranking?.conversationShortcutInfo?.label?.let { return it }
        sbn?.notification?.extras?.let { extras ->
            var name = extras.getCharSequence(EXTRA_CONVERSATION_TITLE)
            if (TextUtils.isEmpty(name)) {
                name = extras.getCharSequence(EXTRA_TITLE)
            }
            if (!TextUtils.isEmpty(name)) return name!!
        }
        return "fallback"
    }
}
