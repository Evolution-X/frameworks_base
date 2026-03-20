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

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

@SysUISingleton
class EssentialNotificationManager @Inject constructor(
    @Application private val context: Context,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController
) {
    companion object {
        private const val TAG = "EssentialNotificationManager"
        private const val KEY_ESSENTIAL_PACKAGES = "essential_app_list"
        private const val MAIN_USER_ID = 0
    }

    interface OnEssentialPackagesChangedListener {
        fun onEssentialPackagesChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsObserver = SettingsObserver()
    private val userNotificationMaps = mutableMapOf<Int, MutableMap<String, EssentialNotification>>()
    private val inheritPackages = mutableSetOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "org.telegram.messenger",
        "com.facebook.orca"
    )
    private val essentialPackages = mutableSetOf<String>()
    private val isEssentialCache = mutableMapOf<String, Boolean>()
    private val listeners = mutableSetOf<OnEssentialPackagesChangedListener>()

    private var currentUserId = 0
    var isEnabled = true
        private set

    init {
        currentUserId = lockscreenUserManager.currentUserId
        keyguardStateController.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {}
        })
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_ESSENTIAL_PACKAGES),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        loadEssentialPackages()
        Log.d(TAG, "Initialized with userId: $currentUserId")
    }

    private fun loadEssentialPackages() {
        essentialPackages.clear()
        try {
            Settings.Secure.getStringForUser(
                context.contentResolver,
                KEY_ESSENTIAL_PACKAGES,
                currentUserId
            )?.split(",")?.forEach { pkg ->
                essentialPackages.add(pkg.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading essential packages from settings", e)
        }
        clearCache()
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { it.onEssentialPackagesChanged() }
    }

    fun addOnEssentialPackagesChangedListener(listener: OnEssentialPackagesChangedListener) {
        listeners.add(listener)
    }

    fun removeOnEssentialPackagesChangedListener(listener: OnEssentialPackagesChangedListener) {
        listeners.remove(listener)
    }

    fun inheritEssentialNotificationIfNeeded(notification: EssentialNotification) {
        if (!isNeedInheritEssentialNotification(notification)) return
        val key = notification.key ?: return
        val userMap = getUserMap(currentUserId)
        userMap[key]?.let { existing ->
            notification.isInheritEssential = isEssentialNotification(existing)
        }
    }

    private fun isNeedInheritEssentialNotification(notification: EssentialNotification): Boolean {
        return notification.packageName in inheritPackages
    }

    fun shouldAddEssentialNotification(notification: EssentialNotification): Boolean {
        return isEssentialNotification(notification) && !notification.isBubbleNotification()
    }

    fun isEssentialNotification(notification: EssentialNotification): Boolean {
        if (!isEnabled) return false
        val isEssential = calculateIsEssential(notification)
        notification.key?.let { isEssentialCache[it] = isEssential }
        return isEssential
    }

    private fun calculateIsEssential(notification: EssentialNotification): Boolean {
        if (notification.isInheritEssential) {
            return notification.hasNotificationContent()
        }
        notification.packageName?.let { pkg ->
            if (pkg in essentialPackages) {
                return notification.hasNotificationContent()
            }
        }
        return false
    }

    fun addEssentialNotificationKey(notification: EssentialNotification) {
        notification.key?.let { key ->
            getUserMap(currentUserId)[key] = notification
        }
    }

    fun removeEssentialNotificationKey(notification: EssentialNotification) {
        notification.key?.let { key ->
            getUserMap(currentUserId).remove(key)
            isEssentialCache.remove(key)
        }
    }

    fun removeEssentialNotificationByKey(key: String?) {
        key?.let {
            getUserMap(currentUserId).remove(it)
            isEssentialCache.remove(it)
        }
    }

    private fun getUserMap(userId: Int): MutableMap<String, EssentialNotification> {
        val resolvedUserId = if (userId < 0) MAIN_USER_ID else userId
        return userNotificationMaps.getOrPut(resolvedUserId) { mutableMapOf() }
    }

    fun clearCache() {
        isEssentialCache.clear()
    }

    fun setCurrentUserId(userId: Int) {
        if (currentUserId != userId) {
            currentUserId = userId
            loadEssentialPackages()
        }
    }

    fun getCurrentUserId(): Int = currentUserId

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) clearCache()
    }

    fun getEssentialPackages(): Set<String> = essentialPackages.toSet()

    private inner class SettingsObserver : android.database.ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            loadEssentialPackages()
        }
    }
}
