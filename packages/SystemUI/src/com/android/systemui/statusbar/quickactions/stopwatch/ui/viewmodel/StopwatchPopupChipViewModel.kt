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

package com.android.systemui.statusbar.quickactions.stopwatch.ui.viewmodel

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
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
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.popups.shared.toSendAction
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.stopwatch.shared.model.StopwatchPopupModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** ViewModel backing the stopwatch page inside the dynamic island. */
class StopwatchPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    override val chip: QuickActionChipModel by
        callbackFlow {
                val listener =
                    object : NotifCollectionListener {
                        override fun onEntryAdded(entry: NotificationEntry) {
                            trySend(Unit)
                        }

                        override fun onEntryUpdated(entry: NotificationEntry) {
                            trySend(Unit)
                        }

                        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                            trySend(Unit)
                        }

                        override fun onRankingApplied() {
                            trySend(Unit)
                        }
                    }

                notifCollection.addCollectionListener(listener)
                trySend(Unit)
                awaitClose { notifCollection.removeCollectionListener(listener) }
            }
            .map {
                notifCollection.allNotifs
                    .asSequence()
                    .mapNotNull { entry ->
                        entry.toStopwatchModel(
                            context = context,
                            activityStarter = activityStarter,
                            activityIntentHelper = activityIntentHelper,
                            lockscreenUserManager = lockscreenUserManager,
                            keyguardStateController = keyguardStateController,
                        )
                    }
                    .firstOrNull()
            }
            .map(::toPopupChipModel)
            .combine(
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.STOPWATCH,
                )
            ) { model, enabled ->
                if (enabled) model else QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch)
            }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch),
            )

    private fun toPopupChipModel(model: StopwatchPopupModel?): QuickActionChipModel {
        if (model == null) {
            return QuickActionChipModel.Hidden(QuickActionChipId.Stopwatch)
        }

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Stopwatch,
            icons = listOfNotNull(model.icon?.let { ChipIcon(icon = it, onClick = model.onOpen) }),
            chipContent = null,
            colors = ChipColors.DynamicIsland,
            contentDescription = ContentDescription.Loaded(model.title),
            popupContent = PopupContentModel.Stopwatch(model),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): StopwatchPopupChipViewModel
    }
}

private fun NotificationEntry.toStopwatchModel(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): StopwatchPopupModel? {
    if (!isStopwatchCandidate()) {
        return null
    }

    val notification = sbn.notification
    val chronometer = extractChronometerSnapshot(context) ?: return null
    if (chronometer.isCountDown) {
        return null
    }

    val appLabel = resolveAppLabel(context, sbn.packageName)
    val fallbackIcon =
        Icon.Resource(
            resId = R.drawable.ic_dynamic_island_stopwatch,
            contentDescription = ContentDescription.Resource(R.string.dynamic_island_stopwatch_title),
        )

    return StopwatchPopupModel(
        icon = fallbackIcon,
        title = context.getString(R.string.dynamic_island_stopwatch_title),
        subtitle = appLabel.takeUnless { it.isBlank() },
        baseElapsedRealtimeMs = chronometer.baseElapsedRealtimeMs,
        isRunning = isRunning(notification),
        elapsedTimeText =
            chronometer.elapsedText.takeUnless {
                isRunning(notification) || chronometer.elapsedText.isBlank()
            },
        onOpen =
            sbn.notification.contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            ),
        actions = notification.toStopwatchActions(context),
    )
}

private fun NotificationEntry.isStopwatchCandidate(): Boolean {
    val notification = sbn.notification
    if (notification.contentIntent == null) {
        return false
    }
    if (notification.contentView == null) {
        return false
    }

    val searchText =
        buildString {
                append(sbn.packageName)
                append(' ')
                append(resolveAppLabel(context = null, packageName = sbn.packageName))
                append(' ')
                append(notification.contentIntent?.creatorPackage.orEmpty())
                append(' ')
                append(notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "")
                append(' ')
                append(notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
                append(' ')
                append(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: "")
            }
            .lowercase(Locale.ROOT)

    val hasClockHint =
        searchText.contains("stopwatch") ||
            searchText.contains("deskclock") ||
            sbn.packageName.contains("clock", ignoreCase = true)

    return hasClockHint
}

private fun NotificationEntry.extractChronometerSnapshot(context: Context): ChronometerSnapshot? {
    val packageContext =
        runCatching { context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED) }
            .getOrDefault(context)

    return sequenceOf(
            sbn.notification.contentView,
            sbn.notification.bigContentView,
            sbn.notification.headsUpContentView,
        )
        .filterNotNull()
        .mapNotNull { remoteViews ->
            val rootView =
                runCatching {
                        remoteViews.apply(packageContext, FrameLayout(packageContext))
                    }
                    .getOrNull()
                    ?: return@mapNotNull null
            val chronometer = rootView.findChronometer() ?: return@mapNotNull null
            ChronometerSnapshot(
                baseElapsedRealtimeMs = chronometer.base,
                isCountDown = chronometer.isCountDown,
                elapsedText = chronometer.text?.toString().orEmpty(),
            )
        }
        .firstOrNull()
}

private fun View.findChronometer(): Chronometer? {
    if (this is Chronometer) {
        return this
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findChronometer()?.let { return it }
        }
    }
    return null
}

private data class ChronometerSnapshot(
    val baseElapsedRealtimeMs: Long,
    val isCountDown: Boolean,
    val elapsedText: String,
)

private fun resolveAppLabel(context: Context?, packageName: String): String {
    val safeContext = context ?: return packageName
    return runCatching {
            val pm = safeContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()
        }
        .getOrNull()
        ?.takeUnless { it.isBlank() }
        ?: packageName
}

private fun Notification.toStopwatchActions(context: Context): List<PopupActionModel> {
    val actions = actions.orEmpty()
    if (actions.isEmpty()) {
        return emptyList()
    }

    return if (isRunning(this)) {
        buildList {
            actions.getOrNull(0)?.actionIntent?.toSendAction()?.let {
                add(PopupActionModel(context.getString(R.string.controls_media_button_pause), it))
            }
            actions.getOrNull(1)?.actionIntent?.toSendAction()?.let {
                add(PopupActionModel(context.getString(R.string.dynamic_island_action_lap), it))
            }
        }
    } else {
        buildList {
            actions.getOrNull(0)?.actionIntent?.toSendAction()?.let {
                add(PopupActionModel(context.getString(R.string.dynamic_island_action_resume), it))
            }
            actions.getOrNull(1)?.actionIntent?.toSendAction()?.let {
                add(
                    PopupActionModel(
                        context.getString(R.string.dynamic_island_action_stop),
                        it,
                        emphasized = true,
                    )
                )
            }
        }
    }
}

private fun isRunning(notification: Notification): Boolean {
    return notification.flags and Notification.FLAG_ONGOING_EVENT != 0
}
