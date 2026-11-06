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

package com.android.systemui.statusbar.quickactions.liveactivity.ui.viewmodel

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.notification.shared.Metric
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.livescore.ui.viewmodel.isLiveScoreCandidate
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.popups.shared.model.SystemEventKind
import com.android.systemui.statusbar.quickactions.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * ViewModel exposing the promoted ongoing notifications of apps (rides, deliveries, timers, ...) as
 * island chips, one per notification. Scores are left to the live score chip and calls to the call
 * event.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveActivityPopupChipsViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : HydratedActivatable() {

    private val activities: Flow<List<LiveActivity>> =
        combine(
            activeNotificationsInteractor.promotedOngoingNotifications,
            activeNotificationsInteractor.allRepresentativeNotifications,
            DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context,
                DynamicIslandFeatureSettings.LIVE_UPDATES,
            ),
        ) { promotedNotifications, allNotifications, enabled ->
            if (!enabled) {
                emptyList()
            } else {
                promotedNotifications
                    .mapNotNull { notif -> allNotifications[notif.key] }
                    .distinctBy { it.key }
                    .mapNotNull { it.toLiveActivity() }
            }
        }

    val chips: List<QuickActionChipModel.PopupChip> by
        activities
            .flatMapLatest { list ->
                if (list.none { it.time is LiveTime.Chronometer }) {
                    flowOf(list.map { it.toChip() })
                } else {
                    // Countdowns and stopwatches are redrawn on the second.
                    flow {
                        while (true) {
                            emit(list.map { it.toChip() })
                            delay(1_000L - SystemClock.elapsedRealtime() % 1_000L)
                        }
                    }
                }
            }
            .hydratedStateOf(traceName = "chips", initialValue = emptyList())

    private fun ActiveNotificationModel.toLiveActivity(): LiveActivity? {
        val content = promotedContent?.privateVersion ?: return null
        if (
            callType != CallType.None ||
                content.style == PromotedNotificationContentModel.Style.Call ||
                content.style == PromotedNotificationContentModel.Style.CollapsedCall ||
                packageName == context.packageName ||
                isLiveScoreCandidate()
        ) {
            return null
        }
        // Metric style notifications, like the Clock app's timers, keep their time in a metric.
        val metric = content.metrics?.firstOrNull()
        val metricTime =
            when (metric) {
                is Metric.TimeDifference.ElapsedRealtime ->
                    LiveTime.Chronometer(metric.zeroElapsedRealtime, metric.isTimer)
                is Metric.TimeDifference.Instant ->
                    LiveTime.Chronometer(
                        SystemClock.elapsedRealtime() +
                            (metric.zeroTime.toEpochMilli() - System.currentTimeMillis()),
                        metric.isTimer,
                    )
                is Metric.TimeDifference.Paused ->
                    LiveTime.Paused(metric.pausedDuration.toMillis())
                else -> null
            }
        val time =
            metricTime
                ?: when (val raw = content.time) {
                    is PromotedNotificationContentModel.When.Chronometer ->
                        LiveTime.Chronometer(raw.elapsedRealtimeMillis, raw.isCountDown)
                    is PromotedNotificationContentModel.When.Time ->
                        LiveTime.Clock(raw.currentTimeMillis)
                    null -> null
                }
        val progress =
            content.newProgress?.takeIf { it.progressMax > 0 }?.let {
                it.progress.toFloat() / it.progressMax
            }
                ?: content.oldProgress?.takeIf { it.max > 0 }?.let {
                    it.progress.toFloat() / it.max
                }
        val title = (content.title ?: metric?.label)?.toString()?.takeIf { it.isNotBlank() }
        val text = content.text?.toString()?.takeIf { it.isNotBlank() }
        if (title == null && text == null) return null
        val metricText = (metric as? Metric.Text)?.textVariants?.firstOrNull()?.toString()
        val contentDescription = ContentDescription.Loaded(appName)
        return LiveActivity(
            key = key,
            packageName = packageName,
            appName = appName,
            icon =
                runCatching { statusBarIcon?.loadDrawable(context) }
                    .getOrNull()
                    ?.let { Icon.Loaded(it, contentDescription) },
            title = title,
            text = text,
            shortText = (content.shortCriticalText ?: metricText)?.takeIf { it.isNotBlank() },
            time = time,
            progress = progress?.coerceIn(0f, 1f),
            onOpen =
                contentIntent.toActivityLaunchAction(
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                ),
        )
    }

    private fun LiveActivity.toChip(): QuickActionChipModel.PopupChip {
        val chipText = chipText()
        val heading = title ?: appName
        val body = text ?: appName.takeIf { title != null }.orEmpty()
        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.SystemEvent("live:$key"),
            icons = listOfNotNull(icon?.let { ChipIcon(icon = it, onClick = onOpen) }),
            chipContent = chipText?.let { ChipContent.Text(it) },
            colors = ChipColors.DynamicIsland,
            contentDescription =
                ContentDescription.Loaded(listOfNotNull(heading, chipText).joinToString(" ")),
            popupContent =
                PopupContentModel.SystemEvent(
                    kind = SystemEventKind.LiveUpdate,
                    title = heading,
                    text = body,
                    actions =
                        listOfNotNull(
                            onOpen?.let {
                                PopupActionModel(
                                    label = context.getString(R.string.dynamic_island_event_open),
                                    onClick = it,
                                )
                            }
                        ),
                    progress = progress,
                    icon = icon,
                    prominentText = chipText,
                    ownerPackage = packageName,
                ),
        )
    }

    private fun LiveActivity.chipText(): String? =
        when (val t = time) {
            is LiveTime.Chronometer -> formatChronometer(t)
            is LiveTime.Paused -> DateUtils.formatElapsedTime(t.durationMs / 1_000L)
            // Only an upcoming time, like an arrival, is worth showing; a past one is just when the
            // notification was posted.
            is LiveTime.Clock ->
                shortText
                    ?: DateFormat.getTimeFormat(context).format(t.currentTimeMs).takeIf {
                        t.currentTimeMs > System.currentTimeMillis()
                    }
            null -> shortText
        }

    private data class LiveActivity(
        val key: String,
        val packageName: String,
        val appName: String,
        val icon: Icon?,
        val title: String?,
        val text: String?,
        /** Short status the app wants in the status bar, like "5 min" or "Arriving". */
        val shortText: String?,
        val time: LiveTime?,
        /** 0..1, or null when the notification has no determinate progress. */
        val progress: Float?,
        val onOpen: (() -> Unit)?,
    )

    private sealed interface LiveTime {
        /** Counts down to [elapsedRealtimeMs], or up from it, on the elapsed-realtime clock. */
        data class Chronometer(val elapsedRealtimeMs: Long, val isCountDown: Boolean) : LiveTime

        /** A wall-clock time, like an arrival. */
        data class Clock(val currentTimeMs: Long) : LiveTime

        /** A paused timer or stopwatch showing [durationMs]. */
        data class Paused(val durationMs: Long) : LiveTime
    }

    @AssistedFactory
    interface Factory {
        fun create(): LiveActivityPopupChipsViewModel
    }

    /** Time left on a countdown or time since the start of a count-up, as "14:54". */
    private fun formatChronometer(time: LiveTime.Chronometer): String {
        val now = SystemClock.elapsedRealtime()
        val ms =
            if (time.isCountDown) time.elapsedRealtimeMs - now else now - time.elapsedRealtimeMs
        // A countdown shows the second it is in, so 14:54.2 left reads 14:55.
        val seconds =
            if (time.isCountDown) (ms.coerceAtLeast(0L) + 999L) / 1_000L else ms / 1_000L
        return DateUtils.formatElapsedTime(seconds.coerceAtLeast(0L))
    }
}
