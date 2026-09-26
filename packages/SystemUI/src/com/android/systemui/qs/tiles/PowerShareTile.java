/*
 * Copyright (C) 2020-2025 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.service.quicksettings.Tile;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;

import org.lineageos.internal.logging.LineageMetricsLogger;

import vendor.lineage.powershare.IPowerShare;

import javax.inject.Inject;

public class PowerShareTile extends QSTileImpl<BooleanState>
        implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "powershare";

    private IPowerShare mPowerShare;
    private BatteryController mBatteryController;
    private NotificationManager mNotificationManager;
    private Notification mNotification;
    private static final String CHANNEL_ID = TILE_SPEC;
    private static final int NOTIFICATION_ID = 273298;

    @Nullable
    private Icon mIcon = null;

    @Inject
    public PowerShareTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mBatteryController = batteryController;
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        mPowerShare = getPowerShare();

        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID,
                mContext.getString(R.string.quick_settings_powershare_label),
                NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(notificationChannel);

        Notification.Builder builder = new Notification.Builder(mContext, CHANNEL_ID);
        builder.setContentTitle(
                mContext.getString(R.string.quick_settings_powershare_enabled_label));
        builder.setSmallIcon(R.drawable.ic_qs_powershare);
        builder.setOnlyAlertOnce(true);
        mNotification = builder.build();
        mNotification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        mNotification.visibility = Notification.VISIBILITY_PUBLIC;

        batteryController.addCallback(this);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public void refreshState() {
        updatePowerShareState();

        super.refreshState();
    }

    private void updatePowerShareState() {
        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null) {
            return;
        }

        if (mBatteryController.isPowerSave()) {
            try {
                powerShare.setEnabled(false);
            } catch (Exception ex) {
                clearPowerShare(powerShare);
                Log.w(TAG, "Unable to disable PowerShare for Battery Saver", ex);
                return;
            }
        }

        try {
            if (powerShare.isEnabled()) {
                mNotificationManager.notify(NOTIFICATION_ID, mNotification);
            } else {
                mNotificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ex) {
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to update PowerShare state", ex);
        }
    }

    @Override
    public boolean isAvailable() {
        return getPowerShare() != null;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null) {
            refreshState();
            return;
        }

        try {
            powerShare.setEnabled(!powerShare.isEnabled());
            refreshState();
        } catch (Exception ex) {
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to change PowerShare state", ex);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        if (mBatteryController.isPowerSave()) {
            return mContext.getString(R.string.quick_settings_powershare_off_powersave_label);
        } else {
            if (getBatteryLevel() < getMinBatteryLevel()) {
                return mContext.getString(R.string.quick_settings_powershare_off_low_battery_label);
            }
        }

        return mContext.getString(R.string.quick_settings_powershare_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!isAvailable()) {
            return;
        }

        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_powershare);
        }
        state.icon = mIcon;
        state.hasLongClickEffect = false;
        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null) {
            return;
        }

        try {
            state.value = powerShare.isEnabled();
        } catch (Exception ex) {
            state.value = false;
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to read PowerShare state", ex);
        }
        state.label = mContext.getString(R.string.quick_settings_powershare_label);

        if (mBatteryController.isPowerSave() || getBatteryLevel() < getMinBatteryLevel()) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else if (!state.value) {
            state.state = Tile.STATE_INACTIVE;
        } else {
            state.state = Tile.STATE_ACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_POWERSHARE;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private synchronized IPowerShare getPowerShare() {
        if (isPowerShareAlive(mPowerShare)) {
            return mPowerShare;
        }

        mPowerShare = null;
        final String fqName = IPowerShare.DESCRIPTOR + "/default";

        try {
            final IBinder binder = ServiceManager.getService(fqName);
            final IPowerShare powerShare = IPowerShare.Stub.asInterface(binder);
            if (!isPowerShareAlive(powerShare)) {
                return null;
            }
            mPowerShare = powerShare;
            return mPowerShare;
        } catch (Exception e) {
            // Handle both RemoteException and ServiceNotFoundException
            Log.e(TAG, "Failed to get PowerShare service", e);
            return null;
        }
    }

    static boolean isPowerShareAlive(@Nullable IPowerShare powerShare) {
        if (powerShare == null) {
            return false;
        }
        final IBinder binder = powerShare.asBinder();
        return binder != null && binder.isBinderAlive();
    }

    private synchronized void clearPowerShare(IPowerShare powerShare) {
        if (mPowerShare == powerShare) {
            mPowerShare = null;
        }
    }

    private int getMinBatteryLevel() {
        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null) {
            return 0;
        }

        try {
            return powerShare.getMinBattery();
        } catch (Exception ex) {
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to read PowerShare minimum battery level", ex);
        }

        return 0;
    }

    private int getBatteryLevel() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
}
