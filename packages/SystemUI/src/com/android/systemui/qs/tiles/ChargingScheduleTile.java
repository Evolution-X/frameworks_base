/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

import java.util.Calendar;

import javax.inject.Inject;

/** Quick Settings tile for the optional Limit charging schedule. */
public class ChargingScheduleTile extends QSTileImpl<BooleanState> {

    private static final String TAG = "ChargingScheduleTile";

    public static final String TILE_SPEC = "charging_schedule";

    private static final Intent CHARGING_CONTROL_SETTINGS =
            new Intent("org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS")
                    .setPackage("org.lineageos.lineageparts")
                    .addCategory(Intent.CATEGORY_DEFAULT);

    @Nullable
    private Icon mIcon;

    @Nullable
    private HealthInterface mHealthInterface;

    private final ChargingScheduleObserver mObserver;

    private boolean mListening;
    private int mUserId;

    @Inject
    public ChargingScheduleTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mUserId = host.getUserContext().getUserId();
        mObserver = new ChargingScheduleObserver(mHandler);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public boolean isAvailable() {
        return HealthInterface.isChargingControlSupported(mContext);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final HealthInterface healthInterface = getHealthInterface();
        if (!isScheduleUsable(healthInterface)) {
            refreshState();
            return;
        }

        final boolean enabled = isScheduleEnabled();
        if (!LineageSettings.System.putIntForUser(
                mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED,
                enabled ? 0 : 1,
                mUserId)) {
            Log.w(TAG, "Unable to change charging schedule state");
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return CHARGING_CONTROL_SETTINGS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_charging_schedule_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_schedule);
        }

        state.icon = mIcon;
        state.label = getTileLabel();
        state.expandedAccessibilityClassName = Switch.class.getName();

        final HealthInterface healthInterface = getHealthInterface();
        if (healthInterface == null || !healthInterface.isChargingControlSupported()) {
            setUnavailableState(state, R.string.quick_settings_charging_schedule_unavailable);
            return;
        }

        try {
            if (!healthInterface.getEnabled()) {
                setUnavailableState(
                        state, R.string.quick_settings_charging_schedule_control_off);
                return;
            }

            if (healthInterface.getMode() != HealthInterface.MODE_LIMIT) {
                setUnavailableState(
                        state, R.string.quick_settings_charging_schedule_limit_required);
                return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read charging control state", e);
            setUnavailableState(state, R.string.quick_settings_charging_schedule_unavailable);
            return;
        }

        final boolean enabled = isScheduleEnabled();
        state.value = enabled;
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        try {
            state.secondaryLabel = enabled
                    ? getScheduleLabel(healthInterface)
                    : mContext.getString(R.string.quick_settings_state_off);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read charging schedule", e);
            setUnavailableState(state, R.string.quick_settings_charging_schedule_unavailable);
            return;
        }
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) {
            return;
        }

        mListening = listening;
        if (listening) {
            mObserver.startObserving(mUserId);
            refreshState();
        } else {
            mObserver.stopObserving();
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mUserId = newUserId;
        if (mListening) {
            mObserver.stopObserving();
            mObserver.startObserving(newUserId);
        }
        refreshState();
    }

    @Override
    protected void handleDestroy() {
        mObserver.stopObserving();
        super.handleDestroy();
    }

    @Nullable
    private HealthInterface getHealthInterface() {
        if (mHealthInterface != null) {
            return mHealthInterface;
        }

        try {
            mHealthInterface = HealthInterface.getInstance(mContext);
        } catch (RuntimeException e) {
            Log.w(TAG, "Charging control service is unavailable", e);
        }
        return mHealthInterface;
    }

    private boolean isScheduleUsable(@Nullable HealthInterface healthInterface) {
        if (healthInterface == null) {
            return false;
        }

        try {
            return healthInterface.isChargingControlSupported()
                    && healthInterface.getEnabled()
                    && healthInterface.getMode() == HealthInterface.MODE_LIMIT;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read charging control state", e);
            return false;
        }
    }

    private boolean isScheduleEnabled() {
        return LineageSettings.System.getIntForUser(
                mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED,
                0,
                mUserId) != 0;
    }

    private CharSequence getScheduleLabel(HealthInterface healthInterface) {
        final int start = LineageSettings.System.getIntForUser(
                mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_LIMIT_START_TIME,
                healthInterface.getStartTime(),
                mUserId);
        final int end = LineageSettings.System.getIntForUser(
                mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_LIMIT_END_TIME,
                healthInterface.getTargetTime(),
                mUserId);
        return mContext.getString(
                R.string.quick_settings_charging_schedule_range,
                formatTime(start),
                formatTime(end));
    }

    private String formatTime(int secondOfDay) {
        final Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, secondOfDay / 3600);
        calendar.set(Calendar.MINUTE, (secondOfDay % 3600) / 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return DateFormat.getTimeFormat(mContext).format(calendar.getTime());
    }

    private void setUnavailableState(BooleanState state, int reasonRes) {
        state.value = false;
        state.state = Tile.STATE_UNAVAILABLE;
        state.secondaryLabel = mContext.getString(reasonRes);
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    private final class ChargingScheduleObserver extends ContentObserver {
        private boolean mObserving;

        ChargingScheduleObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        void startObserving(int userId) {
            if (mObserving) {
                return;
            }

            register(LineageSettings.System.CHARGING_CONTROL_ENABLED, userId);
            register(LineageSettings.System.CHARGING_CONTROL_MODE, userId);
            register(LineageSettings.System.CHARGING_CONTROL_LIMIT_SCHEDULE_ENABLED, userId);
            register(LineageSettings.System.CHARGING_CONTROL_LIMIT_START_TIME, userId);
            register(LineageSettings.System.CHARGING_CONTROL_LIMIT_END_TIME, userId);
            mObserving = true;
        }

        private void register(String key, int userId) {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(key), false, this, userId);
        }

        void stopObserving() {
            if (!mObserving) {
                return;
            }

            mContext.getContentResolver().unregisterContentObserver(this);
            mObserving = false;
        }
    }
}
