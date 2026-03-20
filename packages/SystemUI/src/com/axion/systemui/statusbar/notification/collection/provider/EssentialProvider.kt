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
package com.axion.systemui.statusbar.notification.collection.provider

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.axion.systemui.statusbar.notification.EssentialNotification
import com.axion.systemui.statusbar.notification.EssentialNotificationManager
import javax.inject.Inject

@SysUISingleton
class EssentialProvider @Inject constructor(
    private val essentialNotificationManager: EssentialNotificationManager
) {
    fun isNotificationEntryWithAtLeastOneEssentialChild(entry: ListEntry?): Boolean {
        if (entry == null) return false
        val representativeEntry = entry.representativeEntry ?: return false

        if (entry is GroupEntry) {
            for (child in entry.children) {
                if (isEssentialNotification(child)) return true
            }
        }
        return isEssentialNotification(representativeEntry)
    }

    fun isEssentialNotification(entry: NotificationEntry?): Boolean {
        if (entry?.sbn == null) return false
        val essentialNotification = EssentialNotification(entry.sbn, entry.channel, true)
        essentialNotificationManager.inheritEssentialNotificationIfNeeded(essentialNotification)
        return essentialNotificationManager.shouldAddEssentialNotification(essentialNotification)
    }
}
