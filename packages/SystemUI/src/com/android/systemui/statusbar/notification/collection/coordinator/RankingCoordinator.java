/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.NotificationChannel;

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.BundleEntry;
import com.android.systemui.statusbar.notification.collection.BundleSpec;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.PipelineEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.dagger.AlertingHeader;
import com.android.systemui.statusbar.notification.dagger.SilentHeader;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Filters out NotificationEntries based on its Ranking and dozing state.
 * Assigns alerting / silent section based on the importance of the notification entry.
 * We check the NotificationEntry's Ranking for:
 * - whether the notification's app is suspended or hiding its notifications
 * - whether DND settings are hiding notifications from ambient display or the notification list
 */
@CoordinatorScope
public class RankingCoordinator implements Coordinator {
    public static final boolean SHOW_ALL_SECTIONS = false;
    private final StatusBarStateController mStatusBarStateController;
    private final HighPriorityProvider mHighPriorityProvider;
    private final NodeController mSilentNodeController;
    private final SectionHeaderController mSilentHeaderController;
    private final NodeController mAlertingHeaderController;
    private boolean mHasSilentEntries;
    private boolean mHasMinimizedEntries;

    // Define the explicit sort order for bundle keys
    private static final Map<String, Integer> BUNDLE_KEY_SORT_ORDER = new HashMap<>();
    static {
        BUNDLE_KEY_SORT_ORDER.put(NotificationChannel.SOCIAL_MEDIA_ID, 1);
        BUNDLE_KEY_SORT_ORDER.put(NotificationChannel.NEWS_ID, 2);
        BUNDLE_KEY_SORT_ORDER.put(NotificationChannel.RECS_ID, 3);
        BUNDLE_KEY_SORT_ORDER.put(NotificationChannel.PROMOTIONS_ID, 4);
        BUNDLE_KEY_SORT_ORDER.put("debug_bundle", 99);
    }

    @Inject
    public RankingCoordinator(
            StatusBarStateController statusBarStateController,
            HighPriorityProvider highPriorityProvider,
            @AlertingHeader NodeController alertingHeaderController,
            @SilentHeader SectionHeaderController silentHeaderController,
            @SilentHeader NodeController silentNodeController) {
        mStatusBarStateController = statusBarStateController;
        mHighPriorityProvider = highPriorityProvider;
        mAlertingHeaderController = alertingHeaderController;
        mSilentNodeController = silentNodeController;
        mSilentHeaderController = silentHeaderController;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mStatusBarStateController.addCallback(mStatusBarStateCallback);

        pipeline.addPreGroupFilter(mSuspendedFilter);
        if (com.android.systemui.Flags.notificationAmbientSuppressionAfterInflation()) {
            pipeline.addPreGroupFilter(mDndPreGroupFilter);
            pipeline.addFinalizeFilter(mDndVisualEffectsFilter);
        } else {
            pipeline.addPreGroupFilter(mDndVisualEffectsFilter);
        }
    }

    public NotifSectioner getAlertingSectioner() {
        return mAlertingNotifSectioner;
    }

    public NotifSectioner getSilentSectioner() {
        return mSilentNotifSectioner;
    }

    public NotifSectioner getMinimizedSectioner() {
        return mMinimizedNotifSectioner;
    }

    private final NotifSectioner mAlertingNotifSectioner = new NotifSectioner("Alerting",
            NotificationPriorityBucketKt.BUCKET_ALERTING) {
        @Override
        public boolean isInSection(PipelineEntry entry) {
            if (BundleUtil.Companion.isClassified(entry)) {
                return false;
            }
            return mHighPriorityProvider.isHighPriority(entry);
        }

        @Nullable
        @Override
        public NodeController getHeaderNodeController() {
            // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and mAlertingHeaderController
            if (SHOW_ALL_SECTIONS) {
                return mAlertingHeaderController;
            }
            return null;
        }
    };

    private final NotifSectioner mSilentNotifSectioner = new NotifSectioner("Silent",
            NotificationPriorityBucketKt.BUCKET_SILENT) {
        @Override
        public boolean isInSection(PipelineEntry entry) {
            final ListEntry listEntry = entry.asListEntry();
            if (listEntry == null) {
                return entry instanceof BundleEntry be
                        && !BundleSpec.ESSENTIAL_KEY.equals(be.getKey());
            }
            if (BundleUtil.Companion.isClassified(listEntry)) {
                return false;
            }
            return !mHighPriorityProvider.isHighPriority(listEntry)
                    && listEntry.getRepresentativeEntry() != null
                    && !listEntry.getRepresentativeEntry().isAmbient();
        }

        @Nullable
        @Override
        public NodeController getHeaderNodeController() {
            return mSilentNodeController;
        }

        @Override
        public void onEntriesUpdated(@NonNull List<PipelineEntry> entries) {
            mHasSilentEntries = false;
            for (int i = 0; i < entries.size(); i++) {
                final PipelineEntry pipelineEntry = entries.get(i);
                final ListEntry listEntry = pipelineEntry.asListEntry();
                if (listEntry == null) {
                    if (pipelineEntry instanceof BundleEntry bundleEntry) {
                        if (bundleEntry.isClearable()) {
                            mHasSilentEntries = true;
                            break;
                        }
                    }
                    continue;
                }
                final NotificationEntry notifEntry = listEntry.getRepresentativeEntry();
                if (notifEntry == null) {
                    continue;
                }
                if (notifEntry.getSbn().isClearable()) {
                    mHasSilentEntries = true;
                    break;
                }
            }
            mSilentHeaderController.setClearSectionEnabled(
                    mHasSilentEntries | mHasMinimizedEntries);
        }

        private final NotifComparator mSilentSectionComparator = new NotifComparator(
                "SilentSectionComparator") {
            @Override
            public int compare(@NonNull PipelineEntry o1, @NonNull PipelineEntry o2) {
                boolean isBundle1 = o1 instanceof BundleEntry;
                boolean isBundle2 = o2 instanceof BundleEntry;
                if (isBundle1 && isBundle2) {
                    final String key1 = o1.getKey();
                    final String key2 = o2.getKey();
                    // When both are bundles, use the BUNDLE_KEY_SORT_ORDER map to get rankings for
                    // the keys, which are guaranteed to be in fixed order. Default to large value
                    // for unknown bundle keys to sort them last.
                    int rank1 = BUNDLE_KEY_SORT_ORDER.getOrDefault(key1, Integer.MAX_VALUE);
                    int rank2 = BUNDLE_KEY_SORT_ORDER.getOrDefault(key2, Integer.MAX_VALUE);
                    int rankComparison = Integer.compare(rank1, rank2);
                    if (rankComparison != 0) {
                        return rankComparison;
                    }
                    return key1.compareTo(key2);
                }
                // Order bundles before non-bundles
                return -1 * Boolean.compare(isBundle1, isBundle2);
            }
        };


        @Nullable
        @Override
        public NotifComparator getComparator() {
            if (NotificationBundleUi.isEnabled()) {
                return mSilentSectionComparator;
            }
            return null;
        }
    };

    private final NotifSectioner mMinimizedNotifSectioner = new NotifSectioner("Minimized",
            NotificationPriorityBucketKt.BUCKET_SILENT) {
        @Override
        public boolean isInSection(PipelineEntry entry) {
            final ListEntry listEntry = entry.asListEntry();
            if (listEntry == null) {
                // Bundles are never minimized.
                return false;
            }
            if (BundleUtil.Companion.isClassified(listEntry)) {
                return false;
            }
            return !mHighPriorityProvider.isHighPriority(listEntry)
                    && listEntry.getRepresentativeEntry() != null
                    && listEntry.getRepresentativeEntry().isAmbient();
        }

        @Nullable
        @Override
        public NodeController getHeaderNodeController() {
            return mSilentNodeController;
        }

        @Override
        public void onEntriesUpdated(@NonNull List<PipelineEntry> entries) {
            mHasMinimizedEntries = false;
            for (int i = 0; i < entries.size(); i++) {
                final PipelineEntry pipelineEntry = entries.get(i);
                final ListEntry listEntry = pipelineEntry.asListEntry();
                if (listEntry == null) {
                    // Bundles are never minimized
                    throw new IllegalStateException(
                            "non-ListEntry in minimized notif section: " + pipelineEntry.getKey());
                }
                final NotificationEntry notifEntry = listEntry.getRepresentativeEntry();
                if (notifEntry == null) {
                    continue;
                }
                if (notifEntry.getSbn().isClearable()) {
                    mHasMinimizedEntries = true;
                    break;
                }
            }
            mSilentHeaderController.setClearSectionEnabled(
                    mHasSilentEntries | mHasMinimizedEntries);
        }
    };

    /**
     * Checks whether to filter out the given notification based the notification's Ranking object.
     * NotifListBuilder invalidates the notification list each time the ranking is updated,
     * so we don't need to explicitly invalidate this filter on ranking update.
     */
    private final NotifFilter mSuspendedFilter = new NotifFilter("IsSuspendedFilter") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return entry.getRanking().isSuspended();
        }
    };

    private final NotifFilter mDndVisualEffectsFilter = new NotifFilter(
            "DndSuppressingVisualEffects") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            if ((mStatusBarStateController.isDozing()
                    || mStatusBarStateController.getDozeAmount() == 1f)
                    && entry.shouldSuppressAmbient()) {
                return true;
            }

            return !mStatusBarStateController.isDozing() && entry.shouldSuppressNotificationList();
        }
    };

    private final NotifFilter mDndPreGroupFilter = new NotifFilter("DndPreGroupFilter") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            // Entries with both flags set should be suppressed ASAP regardless of dozing state.
            // As a result of being doze-independent, they can also be suppressed early in the
            // pipeline.
            return entry.shouldSuppressNotificationList() && entry.shouldSuppressAmbient();
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateCallback =
            new StatusBarStateController.StateListener() {
                private boolean mPrevDozeAmountIsOne = false;

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    StatusBarStateController.StateListener.super.onDozeAmountChanged(linear, eased);

                    boolean dozeAmountIsOne = linear == 1f;
                    if (mPrevDozeAmountIsOne != dozeAmountIsOne) {
                        mDndVisualEffectsFilter.invalidateList("dozeAmount changed to "
                                + (dozeAmountIsOne ? "one" : "not one"));
                        mPrevDozeAmountIsOne = dozeAmountIsOne;
                    }
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    mDndVisualEffectsFilter.invalidateList("onDozingChanged to " + isDozing);
                }
            };
}
