/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.NotificationChannel.NEWS_ID
import android.app.NotificationChannel.PROMOTIONS_ID
import android.app.NotificationChannel.RECS_ID
import android.app.NotificationChannel.SOCIAL_MEDIA_ID
import android.os.Build
import android.os.SystemProperties
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.notification.Bundles
import com.android.systemui.statusbar.notification.OnboardingAffordanceManager
import com.android.systemui.statusbar.notification.collection.BundleEntry
import com.android.systemui.statusbar.notification.collection.BundleSpec
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifBundler
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.render.BundleBarn
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.NewsHeader
import com.android.systemui.statusbar.notification.dagger.PromoHeader
import com.android.systemui.statusbar.notification.dagger.RecsHeader
import com.android.systemui.statusbar.notification.dagger.SocialHeader
import com.android.systemui.statusbar.notification.row.data.model.AppData
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi
import com.android.systemui.statusbar.notification.stack.BUCKET_ESSENTIAL
import com.android.systemui.statusbar.notification.stack.BUCKET_NEWS
import com.android.systemui.statusbar.notification.stack.BUCKET_PROMO
import com.android.systemui.statusbar.notification.stack.BUCKET_RECS
import com.android.systemui.statusbar.notification.stack.BUCKET_SOCIAL
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.util.time.SystemClock
import com.axion.systemui.statusbar.notification.collection.provider.EssentialProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinator for sections derived from NotificationAssistantService classification. */
@CoordinatorScope
class BundleCoordinator
@Inject
constructor(
    @NewsHeader private val newsHeaderController: NodeController,
    @SocialHeader private val socialHeaderController: NodeController,
    @RecsHeader private val recsHeaderController: NodeController,
    @PromoHeader private val promoHeaderController: NodeController,
    private val bundleBarn: BundleBarn,
    private val systemClock: SystemClock,
    @Application private val coroutineScope: CoroutineScope,
    @Bundles private val onboardingAffordanceManager: OnboardingAffordanceManager,
    private val essentialProvider: EssentialProvider,
    private val headsUpManager: HeadsUpManager,
) : Coordinator {

    val newsSectioner =
        object : NotifSectioner("News", BUCKET_NEWS) {
            override fun isInSection(entry: PipelineEntry): Boolean {
                return entry.asListEntry()?.representativeEntry?.channel?.id == NEWS_ID
            }

            override fun getHeaderNodeController(): NodeController {
                return newsHeaderController
            }
        }

    val socialSectioner =
        object : NotifSectioner("Social", BUCKET_SOCIAL) {
            override fun isInSection(entry: PipelineEntry): Boolean {
                return entry.asListEntry()?.representativeEntry?.channel?.id == SOCIAL_MEDIA_ID
            }

            override fun getHeaderNodeController(): NodeController {
                return socialHeaderController
            }
        }

    val recsSectioner =
        object : NotifSectioner("Recommendations", BUCKET_RECS) {
            override fun isInSection(entry: PipelineEntry): Boolean {
                return entry.asListEntry()?.representativeEntry?.channel?.id == RECS_ID
            }

            override fun getHeaderNodeController(): NodeController {
                return recsHeaderController
            }
        }

    val promoSectioner =
        object : NotifSectioner("Promotions", BUCKET_PROMO) {
            override fun isInSection(entry: PipelineEntry): Boolean {
                return entry.asListEntry()?.representativeEntry?.channel?.id == PROMOTIONS_ID
            }

            override fun getHeaderNodeController(): NodeController {
                return promoHeaderController
            }
        }

    val essentialSectioner =
        object : NotifSectioner("Essential", BUCKET_ESSENTIAL) {
            override fun isInSection(entry: PipelineEntry): Boolean {
                return entry is BundleEntry && entry.key == BundleSpec.ESSENTIAL.key
            }
        }

    val bundler =
        object : NotifBundler("NotifBundler") {
            // Use list instead of set to keep fixed order
            override val bundleSpecs: List<BundleSpec> = buildList {
                add(BundleSpec.ESSENTIAL)
                add(BundleSpec.NEWS)
                add(BundleSpec.SOCIAL_MEDIA)
                add(BundleSpec.PROMOTIONS)
                add(BundleSpec.RECOMMENDED)
            }

            private val bundleIds = this.bundleSpecs.map { it.key }

            /**
             * Return the id string of the bundle this ListEntry belongs in Or null if this
             * ListEntry should not be bundled
             */
            override fun getBundleIdOrNull(entry: ListEntry): String? {
                if (isEssentialEntry(entry)) {
                    if (isHeadsUpEntry(entry)) return null
                    return BundleSpec.ESSENTIAL.key
                }
                if (isFromDebugApp(entry)) {
                    return BundleSpec.RECOMMENDED.key
                }
                if (entry is GroupEntry) {
                    if (entry.children.isEmpty()) return null
                    val summary = entry.summary ?: return null
                    // When the model classifies notifications from the same group into
                    // different bundles, system_server creates new group summaries that we can
                    // check for classification here.
                    return getBundleIdForNotifEntry(summary)
                }
                return getBundleIdForNotifEntry(entry as NotificationEntry)
            }

            private fun isHeadsUpEntry(entry: ListEntry): Boolean {
                if (entry is GroupEntry) {
                    return entry.children.any { child ->
                        headsUpManager.isHeadsUpEntry(child.key)
                    }
                }
                return headsUpManager.isHeadsUpEntry(entry.key)
            }

            private fun isEssentialEntry(entry: ListEntry): Boolean {
                if (entry is GroupEntry) {
                    return entry.children.any { child ->
                        essentialProvider.isEssentialNotification(child)
                    }
                }
                return essentialProvider.isEssentialNotification(
                    entry as? NotificationEntry
                )
            }

            private fun isFromDebugApp(entry: ListEntry): Boolean {
                return !debugBundleAppName.isNullOrEmpty() && entry.key.contains(debugBundleAppName)
            }

            private fun getBundleIdForNotifEntry(notifEntry: NotificationEntry): String? {
                return notifEntry.representativeEntry?.channel?.id?.takeIf { it in this.bundleIds }
            }
        }

    /** Recursively check parents until finding bundle or null */
    private fun PipelineEntry.getBundleOrNull(): BundleEntry? =
        when (this) {
            is BundleEntry -> this
            is ListEntry -> parent?.getBundleOrNull()
        }

    private fun inflateAllBundleEntries(entries: List<PipelineEntry>) {
        entries.forEachBundleEntry { bundleBarn.inflateBundleEntry(it) }
    }

    private val bundleFilter: NotifFilter =
        object : NotifFilter("BundleInflateFilter") {
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
                // TODO(b/399736937) Do not hide notifications if we have a bug that means the
                //  bundle isn't inflated yet. It's better that we just show those notifications in
                //  the silent section than fail to show them to the user at all
                val bundle = entry.getBundleOrNull()
                if (bundle == null) {
                    debugBundleLog(TAG, { "$name bundle null for notifEntry:${entry.key}" })
                    return false
                }
                val isInflated = bundleBarn.isInflated(bundle)
                debugBundleLog(TAG, { "$name isInflated:$isInflated bundle:${bundle.key}" })
                return !isInflated
            }
        }

    /**
     * Updates the total count of [NotificationEntry]s within each bundle. Group summaries are not
     * counted.
     */
    @get:VisibleForTesting
    val bundleCountUpdater = OnBeforeFinalizeFilterListener { entries ->
        entries.forEachBundleEntry { bundleEntry ->
            fun countNotifications(listEntries: List<ListEntry>): Int {
                var count = 0
                for (entry in listEntries) {
                    when (entry) {
                        is NotificationEntry -> {
                            count++
                        }
                        is GroupEntry -> {
                            count += entry.children.size
                        }
                        else -> {
                            error(
                                "bundleCountUpdater: Unexpected ListEntry type: " +
                                    "${entry::class.simpleName} found in bundle " +
                                    "(key: ${bundleEntry.key})"
                            )
                        }
                    }
                }
                return count
            }
            bundleEntry.bundleRepository.numberOfChildren = countNotifications(bundleEntry.children)
        }
    }

    /** Updates each NotificationEntry's bundle membership time. */
    @get:VisibleForTesting
    val bundleMembershipUpdater = OnBeforeRenderListListener { entries ->
        val currentTime = systemClock.uptimeMillis()
        val processed = mutableSetOf<NotificationEntry>()

        fun updateEntryList(entryList: List<PipelineEntry>, currentBundleKey: String?) {
            entryList.forEach { entry ->
                when (entry) {
                    is NotificationEntry -> {
                        if (processed.add(entry)) {
                            entry.updateBundle(currentBundleKey, currentTime)
                        }
                    }
                    is GroupEntry -> {
                        // Process group summary in case we got new update from app or auto-grouping
                        entry.representativeEntry?.let { summary ->
                            if (processed.add(summary)) {
                                summary.updateBundle(currentBundleKey, currentTime)
                            }
                        }
                        updateEntryList(entry.children, currentBundleKey)
                    }
                    is BundleEntry -> {
                        updateEntryList(entry.children, entry.key)
                    }
                    else -> {
                        error(
                            "Unexpected PipelineEntry type: " +
                                "${entry::class.simpleName} with key ${entry.key}"
                        )
                    }
                }
            }
        }
        updateEntryList(entries, /* currentBundleKey */ null)
    }

    /**
     * For each BundleEntry, populate its bundleRepository.appDataList with unique AppData (package
     * name, UserHandle, latest timeAddedToBundle) by recursively checking all NotificationEntry
     * children, including those within groups.
     */
    @get:VisibleForTesting
    val bundleAppDataUpdater = OnBeforeRenderListListener { entries ->
        entries.forEachBundleEntry { bundleEntry ->
            val appDataList = mutableListOf<AppData>()

            fun collectAppData(listEntries: List<ListEntry>) {
                for (listEntry in listEntries) {
                    when (listEntry) {
                        is NotificationEntry -> {
                            appDataList.add(listEntry.toAppData())
                        }

                        is GroupEntry -> {
                            listEntry.representativeEntry?.let { summary ->
                                appDataList.add(summary.toAppData())
                            }
                            collectAppData(listEntry.children)
                        }
                        else -> {
                            error(
                                "bundleAppDataUpdater: unexpected ListEntry type: " +
                                    "${listEntry::class.simpleName} while collecting " +
                                    "AppData for BundleEntry (key: ${bundleEntry.key})"
                            )
                        }
                    }
                }
            }
            collectAppData(bundleEntry.children)

            // Group by package name and user, then for each group, pick the AppData
            // with the maximum (latest) non-zero timeAddedToBundle.
            bundleEntry.bundleRepository.appDataList.value =
                appDataList
                    .filter { it.timeAddedToBundle > 0L }
                    .groupBy { Pair(it.packageName, it.user) }
                    .mapNotNull { (_, appDataListForSameApp) ->
                        appDataListForSameApp.maxByOrNull { it.timeAddedToBundle }
                    }
        }
    }

    override fun attach(pipeline: NotifPipeline) {
        if (NotificationBundleUi.isEnabled) {
            pipeline.setNotifBundler(bundler)
            pipeline.addOnBeforeFinalizeFilterListener(this::inflateAllBundleEntries)
            pipeline.addOnBeforeFinalizeFilterListener(bundleCountUpdater)
            pipeline.addFinalizeFilter(bundleFilter)
            pipeline.addOnBeforeRenderListListener(bundleMembershipUpdater)
            pipeline.addOnBeforeRenderListListener(bundleAppDataUpdater)
            bindOnboardingAffordanceInvalidator(pipeline)
        }
    }

    private fun bindOnboardingAffordanceInvalidator(pipeline: NotifPipeline) {
        val invalidator = object : Invalidator("bundle onboarding") {}
        pipeline.addPreRenderInvalidator(invalidator)
        coroutineScope.launch {
            onboardingAffordanceManager.view.collect {
                invalidator.invalidateList("bundle onboarding view changed")
            }
        }
    }

    private fun List<PipelineEntry>.forEachBundleEntry(block: (BundleEntry) -> Unit) {
        for (entry in this) {
            if (entry is BundleEntry) {
                block(entry)
            }
        }
    }

    companion object {
        @JvmField val TAG: String = "BundleCoordinator"

        @JvmField var debugBundleLogs: Boolean = false

        /**
         * All notifications that contain this String in the key are bundled into the recommended
         * bundle such that bundle code can be easily and deterministically tested.
         *
         * E.g. use this command to bundle all notifications from notify: `adb shell setprop
         * persist.debug.notification_bundle_ui_debug_app_name com.google.cinek.notify && adb
         * reboot`
         */
        val debugBundleAppName: String? =
            if (Build.IS_USERDEBUG || Build.IS_ENG)
                SystemProperties.get("persist.debug.notification_bundle_ui_debug_app_name")
            else null

        @JvmStatic
        fun debugBundleLog(tag: String, stringLambda: () -> String) {
            if (debugBundleLogs) {
                android.util.Log.d(tag, stringLambda())
            }
        }
    }
}

private fun NotificationEntry.toAppData(): AppData =
    AppData(sbn.packageName, UserHandle.of(sbn.normalizedUserId), timeAddedToBundle.second)
