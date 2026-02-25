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

import android.os.Bundle;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.concurrent.ConcurrentHashMap;

public final class DownloadStateTracker {

    private static final String EXTRA_PROGRESS = "android.progress";
    private static final String EXTRA_PROGRESS_MAX = "android.progressMax";
    private static final String EXTRA_TITLE = "android.title";

    private static final long STALE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final int ERROR_THRESHOLD_PCT = 5;

    private static final int REASON_APP_CANCEL = 8;
    private static final int REASON_APP_CANCEL_ALL = 9;

    private static final int RESET_DROP_PCT = 25;

    private static final class DownloadSnapshot {
        final String pkg;
        String label;
        int progress;
        long updatedAt;

        DownloadSnapshot(String pkg, String label, int progress) {
            this.pkg = pkg;
            this.label = label;
            this.progress = progress;
            this.updatedAt = System.currentTimeMillis();
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
        Bundle extras = entry.getSbn().getNotification().extras;
        if (extras == null) return;

        int rawProgress = extras.getInt(EXTRA_PROGRESS, -1);
        int rawMax = extras.getInt(EXTRA_PROGRESS_MAX, -1);

        String id = entryKey(entry);
        String pkg = entry.getSbn().getPackageName();

        if (rawProgress < 0 || rawMax <= 0) {
            if (mActive.remove(id) != null) {
                notifyCountChanged();
                publishAggregated();
                fireComplete();
            }
            return;
        }

        int pct = clamp(rawProgress * 100 / rawMax, 0, 100);
        String label = charSeqStr(extras.getCharSequence(EXTRA_TITLE));

        DownloadSnapshot existing = mActive.get(id);
        boolean isNew = existing == null;
        boolean isReset = existing != null && (existing.progress - pct) >= RESET_DROP_PCT;

        if (isReset) mActive.remove(id);
        pruneStale(pkg, id);

        if (isNew || isReset || existing.progress != pct) {
            String usedLabel = (label != null) ? label
                    : (existing != null ? existing.label : null);
            mActive.put(id, new DownloadSnapshot(pkg, usedLabel, pct));
            publishAggregated();
            if (isNew || isReset) notifyCountChanged();
        }

        if (pct >= 100) {
            mActive.remove(id);
            notifyCountChanged();
            publishAggregated();
            fireComplete();
        }
    }

    public void onNotificationRemoved(NotificationEntry entry, int reason) {
        DownloadSnapshot snap = mActive.remove(entryKey(entry));
        if (snap == null) return;

        notifyCountChanged();
        publishAggregated();

        if (snap.progress >= 100) {
            fireComplete();
        } else if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            fireComplete();
        } else if (snap.progress >= ERROR_THRESHOLD_PCT) {
            fireError();
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

    private String entryKey(NotificationEntry e) {
        return e.getSbn().getPackageName() + ":" + e.getSbn().getId();
    }

    private void pruneStale(String pkg, String currentId) {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, DownloadSnapshot> e : mActive.entrySet()) {
            if (!e.getKey().equals(currentId)
                    && e.getValue().pkg.equals(pkg)
                    && now - e.getValue().updatedAt > STALE_TIMEOUT_MS) {
                mActive.remove(e.getKey());
            }
        }
    }

    private void publishAggregated() {
        int avg = 0;
        if (!mActive.isEmpty()) {
            int sum = 0;
            for (DownloadSnapshot s : mActive.values()) sum += s.progress;
            avg = sum / mActive.size();
        }
        fire(mOnProgress, avg);
        publishBestLabel();
    }

    private void publishBestLabel() {
        DownloadSnapshot best = null;
        for (DownloadSnapshot s : mActive.values()) {
            if (best == null || s.progress > best.progress) best = s;
        }
        String lbl = null;
        if (best != null && best.label != null
                && !best.label.toLowerCase().contains("untitled")) {
            lbl = best.label;
        }
        fire(mOnLabelChanged, lbl);
    }

    private void notifyCountChanged() { fire(mOnCountChanged, mActive.size()); }
    private void fireComplete() { if (mOnComplete != null) mOnComplete.run(); }
    private void fireError() { if (mOnError != null) mOnError.run(); }

    private void fire(IntCallback cb, int v) { if (cb != null) cb.onValue(v); }
    private void fire(StringCallback cb, String v) { if (cb != null) cb.onValue(v); }

    private static String charSeqStr(CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
