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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.view.WindowManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.cutoutprogress.ring.CutoutRingView;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;

import javax.inject.Inject;

@SysUISingleton
public class CutoutProgressController implements CoreStartable {

    private static final int WINDOW_TYPE = 2024;

    private final Context mContext;
    private final NotifPipeline mPipeline;
    private final Handler mMainHandler;

    private final CutoutProgressSettings mSettings;
    private final DownloadStateTracker mTracker;
    private CutoutRingView mRingView;

    private boolean mOverlayAttached = false;
    private boolean mListenerRegistered = false;
    private boolean mBatteryReceiverRegistered = false;
    private boolean mIsFullyCharged = false;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            int level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct    = scale > 0 ? level * 100 / scale : 0;

            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            if (!mSettings.isEnabled()) {
                mMainHandler.post(() -> {
                    mRingView.setChargingState(false, 0);
                    mRingView.setBatteryIndicatorState(false, 0);
                });
                return;
            }

            boolean chargingRingOn   = mSettings.isChargingRingEnabled();
            boolean batteryIndOn     = mSettings.isBatteryIndicatorEnabled();
            boolean pulseEnabled     = mSettings.isChargingPulseEnabled();

            mMainHandler.post(() -> {
                mRingView.setChargingPulseEnabled(pulseEnabled);
                if (charging) {
                    mRingView.setBatteryIndicatorState(false, 0);
                    mRingView.setChargingState(chargingRingOn, pct);
                } else {
                    mRingView.setChargingState(false, 0);
                    mRingView.setBatteryIndicatorState(batteryIndOn, pct);
                }
            });
        }
    };

    @Inject
    public CutoutProgressController(
            Context context,
            NotifPipeline notifPipeline,
            @Main Handler mainHandler) {
        mContext = context;
        mPipeline = notifPipeline;
        mMainHandler = mainHandler;
        mSettings = new CutoutProgressSettings(
                context.getContentResolver(), mainHandler);
        mTracker = new DownloadStateTracker();
    }

    @Override
    public void start() {
        mRingView = new CutoutRingView(mContext);
        mRingView.applySettings(mSettings);
        bindTrackerToView();
        mSettings.observe(this::onSettingsChanged);
        onSettingsChanged();
    }

    private void onSettingsChanged() {
        mRingView.applySettings(mSettings);

        if (mSettings.isEnabled()) {
            enableFeature();
        } else {
            disableFeature();
        }
    }

    private void enableFeature() {
        attachOverlay();
        registerPipelineListener();
        registerBatteryReceiver();
    }

    private void disableFeature() {
        mTracker.reset();
        detachOverlay();
        unregisterBatteryReceiver();
    }

    private void attachOverlay() {
        if (mOverlayAttached) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("CutoutProgressOverlay");

        WindowManager wm = mContext.getSystemService(WindowManager.class);
        if (wm != null) {
            wm.addView(mRingView, params);
            mOverlayAttached = true;
        }
    }

    private void detachOverlay() {
        if (!mOverlayAttached) return;
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        if (wm != null) {
            wm.removeView(mRingView);
            mOverlayAttached = false;
        }
    }

    private void registerPipelineListener() {
        if (mListenerRegistered) return;
        mListenerRegistered = true;

        mPipeline.addCollectionListener(new NotifCollectionListener() {

            @Override
            public void onEntryAdded(NotificationEntry entry) {
                if (!mSettings.isEnabled()) return;
                mTracker.onNotificationChanged(entry);
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                if (!mSettings.isEnabled()) return;
                mTracker.onNotificationChanged(entry);
            }

            @Override
            public void onEntryRemoved(NotificationEntry entry, int reason) {
                if (!mSettings.isEnabled()) return;
                mTracker.onNotificationRemoved(entry, reason);
            }
        });
    }

    private void registerBatteryReceiver() {
        if (mBatteryReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBatteryReceiver, filter);
        mBatteryReceiverRegistered = true;
    }

    private void unregisterBatteryReceiver() {
        if (!mBatteryReceiverRegistered) return;
        mContext.unregisterReceiver(mBatteryReceiver);
        mBatteryReceiverRegistered = false;
        mMainHandler.post(() -> {
            mRingView.setChargingState(false, 0);
            mRingView.setBatteryIndicatorState(false, 0);
        });
    }

    private void bindTrackerToView() {
        mTracker.setOnProgress(progress ->
                mMainHandler.post(() -> mRingView.setProgress(progress)));

        mTracker.setOnComplete(() ->
                mMainHandler.post(() -> mRingView.setProgress(100)));

        mTracker.setOnError(() ->
                mMainHandler.post(() -> mRingView.showError()));

        mTracker.setOnCountChanged(count ->
                mMainHandler.post(() -> mRingView.setDownloadCount(count)));

        mTracker.setOnLabelChanged(label ->
                mMainHandler.post(() -> mRingView.setFilenameHint(label)));
    }
}
