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

package com.android.systemui.statusbar.notification.collection

import android.app.NotificationChannel
import android.service.notification.Adjustment
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.internal.R
import com.android.systemui.statusbar.notification.stack.BUCKET_ESSENTIAL
import com.android.systemui.statusbar.notification.stack.BUCKET_NEWS
import com.android.systemui.statusbar.notification.stack.BUCKET_PROMO
import com.android.systemui.statusbar.notification.stack.BUCKET_RECS
import com.android.systemui.statusbar.notification.stack.BUCKET_SOCIAL
import com.android.systemui.statusbar.notification.stack.PriorityBucket

data class BundleSpec(
    val key: String,
    @StringRes val titleText: Int,
    @StringRes val summaryText: Int,
    @DrawableRes val icon: Int,
    @PriorityBucket val bucket: Int,

    /**
     * This is the id / [type] that identifies the bundle when calling APIs of
     * [android.app.INotificationManager]
     */
    @Adjustment.Types val bundleType: Int,
) {
    companion object {
        @JvmField
        val ESSENTIAL_KEY = "ax_essential"

        val ESSENTIAL =
            BundleSpec(
                key = ESSENTIAL_KEY,
                titleText = com.android.systemui.res.R.string.essential_notifications_title,
                summaryText = com.android.systemui.res.R.string.essential_notifications_summary,
                icon = com.android.systemui.res.R.drawable.ic_important_outline,
                bucket = BUCKET_ESSENTIAL,
                bundleType = Adjustment.TYPE_OTHER,
            )
        val PROMOTIONS =
            BundleSpec(
                key = NotificationChannel.PROMOTIONS_ID,
                titleText = R.string.promotional_notification_channel_label,
                summaryText =
                    com.android.systemui.res.R.string.notification_guts_promotions_summary,
                icon = com.android.settingslib.R.drawable.ic_promotions,
                bucket = BUCKET_PROMO,
                bundleType = Adjustment.TYPE_PROMOTION,
            )
        val SOCIAL_MEDIA =
            BundleSpec(
                key = NotificationChannel.SOCIAL_MEDIA_ID,
                titleText = R.string.social_notification_channel_label,
                summaryText = com.android.systemui.res.R.string.notification_guts_social_summary,
                icon = com.android.settingslib.R.drawable.ic_social,
                bucket = BUCKET_SOCIAL,
                bundleType = Adjustment.TYPE_SOCIAL_MEDIA,
            )
        val NEWS =
            BundleSpec(
                key = NotificationChannel.NEWS_ID,
                titleText = R.string.news_notification_channel_label,
                summaryText = com.android.systemui.res.R.string.notification_guts_news_summary,
                icon = com.android.settingslib.R.drawable.ic_news,
                bucket = BUCKET_NEWS,
                bundleType = Adjustment.TYPE_NEWS,
            )
        val RECOMMENDED =
            BundleSpec(
                key = NotificationChannel.RECS_ID,
                titleText = R.string.recs_notification_channel_label,
                summaryText = com.android.systemui.res.R.string.notification_guts_recs_summary,
                icon = com.android.settingslib.R.drawable.ic_recs,
                bucket = BUCKET_RECS,
                bundleType = Adjustment.TYPE_CONTENT_RECOMMENDATION,
            )
    }
}
