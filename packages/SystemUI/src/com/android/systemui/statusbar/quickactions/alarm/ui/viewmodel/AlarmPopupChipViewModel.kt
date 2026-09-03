/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.alarm.ui.viewmodel

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.text.format.DateFormat
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.quickactions.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.popups.shared.toSendAction
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** ViewModel backing the next-alarm page inside the dynamic island. */
class AlarmPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val nextAlarmController: NextAlarmController,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    override val chip: QuickActionChipModel by
        callbackFlow<AlarmPopupState> {
                var nextAlarm: AlarmManager.AlarmClockInfo? = null
                fun emitState() {
                    trySend(
                        AlarmPopupState(
                            nextAlarm = nextAlarm,
                            activeNotification =
                                notifCollection.allNotifs.firstOrNull { it.isAlarmCandidate() },
                        )
                    )
                }

                val callback =
                    NextAlarmController.NextAlarmChangeCallback { updatedAlarm ->
                        nextAlarm = updatedAlarm
                        emitState()
                    }
                val notificationListener =
                    object : NotifCollectionListener {
                        override fun onEntryAdded(entry: NotificationEntry) = emitState()

                        override fun onEntryUpdated(entry: NotificationEntry) = emitState()

                        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) =
                            emitState()

                        override fun onRankingApplied() = emitState()
                    }

                nextAlarmController.addCallback(callback)
                notifCollection.addCollectionListener(notificationListener)
                emitState()
                awaitClose {
                    nextAlarmController.removeCallback(callback)
                    notifCollection.removeCollectionListener(notificationListener)
                }
            }
            .map(::toPopupChipModel)
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Alarm),
            )

    private fun toPopupChipModel(state: AlarmPopupState): QuickActionChipModel {
        val alarm = state.nextAlarm?.takeIf { it.triggerTime > 0L }
        val activeNotification = state.activeNotification
        if (alarm == null && activeNotification == null) {
            return QuickActionChipModel.Hidden(QuickActionChipId.Alarm)
        }

        val chipTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: activeNotification?.notificationText().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val fullTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: chipTimeText
        val dayText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = daySkeleton()) }
                ?: activeNotification?.notificationTitle().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val onOpen =
            activeNotification?.sbn?.notification?.contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            )
                ?: alarm?.showIntent.toActivityLaunchAction(
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                )

        val model =
            AlarmPopupModel(
                title =
                    if (activeNotification?.hasSnoozeAction() == true) {
                        context.getString(R.string.dynamic_island_alarm_active_title)
                    } else {
                        context.getString(R.string.dynamic_island_alarm_title)
                    },
                triggerTimeMs = alarm?.triggerTime ?: 0L,
                chipTimeText = chipTimeText,
                fullTimeText = fullTimeText,
                dayText = dayText,
                onOpen = onOpen,
                actions =
                    buildList {
                        activeNotification?.snoozeAction()?.toSendAction()?.let {
                            add(
                                PopupActionModel(
                                    context.getString(R.string.dynamic_island_action_snooze),
                                    it,
                                    emphasized = true,
                                )
                            )
                        }
                    },
            )
        val contentDescription =
            alarm?.let {
                ContentDescription.Loaded(
                    context.getString(
                        R.string.accessibility_quick_settings_alarm,
                        formatAlarmTime(it.triggerTime, skeleton = fullDescriptionSkeleton()),
                    )
                )
            } ?: ContentDescription.Loaded(model.title)

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Alarm,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_alarm,
                                contentDescription = ContentDescription.Resource(R.string.status_bar_alarm),
                            ),
                        onClick = model.onOpen,
                    )
                ),
            chipContent = ChipContent.Text(model.chipTimeText),
            colors = ChipColors.DynamicIsland,
            contentDescription = contentDescription,
            popupContent = PopupContentModel.Alarm(model),
        )
    }

    private fun chipSkeleton(): String = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"

    private fun fullDescriptionSkeleton(): String =
        if (DateFormat.is24HourFormat(context)) "EHm" else "Ehma"

    private fun daySkeleton(): String = "EEEE"

    private fun formatAlarmTime(triggerTimeMs: Long, skeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return DateFormat.format(pattern, triggerTimeMs).toString()
    }

    @AssistedFactory
    interface Factory {
        fun create(): AlarmPopupChipViewModel
    }
}

private data class AlarmPopupState(
    val nextAlarm: AlarmManager.AlarmClockInfo?,
    val activeNotification: NotificationEntry?,
)

private fun NotificationEntry.isAlarmCandidate(): Boolean {
    val notification = sbn.notification
    return notification.contentIntent != null &&
        (
            notification.category == Notification.CATEGORY_ALARM ||
                sbn.packageName.contains("clock", ignoreCase = true) ||
                notification.actions.orEmpty().any { action ->
                    action.title?.toString()?.contains("snooze", ignoreCase = true) == true
                }
        )
}

private fun NotificationEntry.notificationTitle(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
}

private fun NotificationEntry.notificationText(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}

private fun NotificationEntry.hasSnoozeAction(): Boolean {
    return snoozeAction() != null
}

private fun NotificationEntry.snoozeAction() =
    sbn.notification.actions.orEmpty().firstOrNull { action ->
        action.title?.toString()?.contains("snooze", ignoreCase = true) == true
    }?.actionIntent
        ?: sbn.notification.actions.orEmpty()
            .takeIf {
                sbn.notification.category == Notification.CATEGORY_ALARM && it.size > 1
            }
            ?.firstOrNull()
            ?.actionIntent
