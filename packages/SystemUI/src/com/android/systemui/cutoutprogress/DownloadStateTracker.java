/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.cutoutprogress;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;

import java.util.Locale;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks determinate, ongoing progress notifications without treating every progress-style
 * notification transition as a completed download.
 *
 * The tracker intentionally keeps the last known determinate percentage when an already tracked
 * transfer temporarily becomes indeterminate. This avoids false completion animations and ring
 * flicker during network/app state transitions.
 */
public final class DownloadStateTracker {

    private static final int COMPLETE_THRESHOLD_PCT = 99;
    private static final int APP_CANCEL_COMPLETE_THRESHOLD_PCT = 90;

    private static final class DownloadSnapshot {
        String label;
        int progress;
        DownloadSnapshot(String label, int progress) {
            this.label = label;
            this.progress = progress;
        }
    }

    private final ConcurrentHashMap<String, DownloadSnapshot> mActive =
            new ConcurrentHashMap<>();

    public interface IntCallback { void onValue(int value); }
    public interface StringCallback { void onValue(String value); }

    private IntCallback mOnProgress;
    private Runnable mOnComplete;
    private Runnable mOnError;
    private IntCallback mOnCountChanged;
    private StringCallback mOnLabelChanged;

    public void setOnProgress(IntCallback cb) { mOnProgress = cb; }
    public void setOnComplete(Runnable cb) { mOnComplete = cb; }
    public void setOnError(Runnable cb) { mOnError = cb; }
    public void setOnCountChanged(IntCallback cb) { mOnCountChanged = cb; }
    public void setOnLabelChanged(StringCallback cb) { mOnLabelChanged = cb; }

    public void onNotificationChanged(NotificationEntry entry) {
        if (entry == null || entry.getSbn() == null) return;

        final Notification notification = entry.getSbn().getNotification();
        if (notification == null) return;

        final Bundle extras = notification.extras;
        if (extras == null) return;

        final String id = entryKey(entry);
        final DownloadSnapshot existing = mActive.get(id);

        final int rawProgress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
        final int rawMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
        final boolean indeterminate =
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);

        final boolean hasAnyProgressPayload =
                extras.containsKey(Notification.EXTRA_PROGRESS)
                        || extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
                        || extras.containsKey(Notification.EXTRA_PROGRESS_INDETERMINATE);
        final boolean hasDeterminatePayload =
                extras.containsKey(Notification.EXTRA_PROGRESS)
                        && extras.containsKey(Notification.EXTRA_PROGRESS_MAX);
        final boolean ongoing =
                (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        final boolean progressCategory =
                Notification.CATEGORY_PROGRESS.equals(notification.category);
        final boolean looksLikeProgress = ongoing || progressCategory;

        final boolean determinate = hasDeterminatePayload && !indeterminate
                && rawProgress >= 0 && rawMax > 0;
        final int pct = determinate
                ? clamp((int) (((long) rawProgress * 100L) / rawMax), 0, 100)
                : -1;

        // A completion update may clear FLAG_ONGOING before SystemUI receives it. Complete only
        // a transfer that we actually tracked; a newly posted 100% notification should not flash.
        if (pct >= 100) {
            if (existing != null) {
                mActive.remove(id);
                notifyCountChanged();
                // Preserve the last visible progress when the final transfer completes. Sending
                // an intermediate zero would reset the view's minimum-visible timer before the
                // completion animation starts.
                if (mActive.isEmpty()) {
                    fire(mOnLabelChanged, null);
                    fireComplete();
                } else {
                    publishAggregated();
                }
            }
            return;
        }

        // Ignore unrelated transient payloads. If a notification we tracked stops looking like a
        // progress operation altogether, remove it quietly rather than pretending it completed.
        if (!hasAnyProgressPayload || !looksLikeProgress) {
            removeQuietly(id);
            return;
        }

        // A tracked transfer may legitimately drop EXTRA_PROGRESS_MAX while switching to an
        // indeterminate/reconnecting stage. Preserve its last determinate percentage until the
        // notification becomes determinate again or is actually removed.
        if (!determinate) {
            if (existing != null) {
                String label = title(extras);
                if (label != null) existing.label = label;
                publishAggregated();
            }
            return;
        }

        final String label = title(extras);

        if (existing == null) {
            mActive.put(id, new DownloadSnapshot(label, pct));
            notifyCountChanged();
        } else {
            existing.progress = pct;
            if (label != null && !Objects.equals(label, existing.label)) {
                existing.label = label;
            }
        }

        publishAggregated();
    }

    public void onNotificationRemoved(NotificationEntry entry, int reason) {
        if (entry == null || entry.getSbn() == null) return;

        final DownloadSnapshot snap = mActive.remove(entryKey(entry));
        if (snap == null) return;

        notifyCountChanged();
        if (!mActive.isEmpty()) {
            publishAggregated();
            return;
        }
        fire(mOnLabelChanged, null);

        if (snap.progress >= COMPLETE_THRESHOLD_PCT
                || ((reason == NotificationListenerService.REASON_APP_CANCEL
                        || reason == NotificationListenerService.REASON_APP_CANCEL_ALL)
                        && snap.progress >= APP_CANCEL_COMPLETE_THRESHOLD_PCT)) {
            fireComplete();
        } else if (reason == NotificationListenerService.REASON_ERROR) {
            fireError();
        } else {
            fire(mOnProgress, 0);
        }
    }

    public void reset() {
        mActive.clear();
        notifyCountChanged();
        fire(mOnProgress, 0);
        fire(mOnLabelChanged, null);
    }

    public int getActiveCount() {
        return mActive.size();
    }

    private void removeQuietly(String id) {
        if (mActive.remove(id) != null) {
            notifyCountChanged();
            publishAggregated();
        }
    }

    private void publishAggregated() {
        int avg = 0;
        if (!mActive.isEmpty()) {
            long sum = 0;
            for (DownloadSnapshot s : mActive.values()) {
                sum += s.progress;
            }
            avg = (int) (sum / mActive.size());
        }
        fire(mOnProgress, avg);
        publishBestLabel();
    }

    private void publishBestLabel() {
        DownloadSnapshot best = null;
        for (DownloadSnapshot s : mActive.values()) {
            if (best == null || s.progress > best.progress) best = s;
        }

        String label = null;
        if (best != null && best.label != null
                && !best.label.toLowerCase(Locale.ROOT).contains("untitled")) {
            label = best.label;
        }
        fire(mOnLabelChanged, label);
    }

    private String entryKey(NotificationEntry entry) {
        // StatusBarNotification#getKey includes user/package/id/tag and avoids collisions that can
        // occur when only package + numeric id are used.
        return entry.getSbn().getKey();
    }

    private static String title(Bundle extras) {
        CharSequence value = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (value == null) return null;
        String title = value.toString().trim();
        return title.isEmpty() ? null : title;
    }

    private void notifyCountChanged() {
        fire(mOnCountChanged, mActive.size());
    }

    private void fireComplete() {
        if (mOnComplete != null) mOnComplete.run();
    }

    private void fireError() {
        if (mOnError != null) mOnError.run();
    }

    private void fire(IntCallback cb, int value) {
        if (cb != null) cb.onValue(value);
    }

    private void fire(StringCallback cb, String value) {
        if (cb != null) cb.onValue(value);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
