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

import javax.inject.Inject;

/** Quick Settings tile for Lineage charging control. */
public class ChargingControlTile extends QSTileImpl<BooleanState> {

    private static final String TAG = "ChargingControlTile";

    public static final String TILE_SPEC = "charging_control";

    private static final Intent CHARGING_CONTROL_SETTINGS =
            new Intent("org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS")
                    .setPackage("org.lineageos.lineageparts")
                    .addCategory(Intent.CATEGORY_DEFAULT);

    @Nullable
    private Icon mIcon;

    @Nullable
    private HealthInterface mHealthInterface;

    private final ChargingControlObserver mObserver;

    private boolean mListening;
    private int mUserId;

    @Inject
    public ChargingControlTile(
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
        mObserver = new ChargingControlObserver(mHandler);
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
        if (healthInterface == null || !healthInterface.isChargingControlSupported()) {
            refreshState();
            return;
        }

        final boolean enabled = healthInterface.getEnabled();
        if (!healthInterface.setEnabled(!enabled)) {
            Log.w(TAG, "Unable to change charging control state");
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return CHARGING_CONTROL_SETTINGS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_charging_control_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_control);
        }

        state.icon = mIcon;
        state.label = getTileLabel();
        state.expandedAccessibilityClassName = Switch.class.getName();

        final HealthInterface healthInterface = getHealthInterface();
        final boolean supported = healthInterface != null
                && healthInterface.isChargingControlSupported();

        if (!supported) {
            state.value = false;
            state.secondaryLabel = null;
            state.stateDescription = null;
            state.contentDescription = state.label;
            state.state = Tile.STATE_UNAVAILABLE;
            return;
        }

        final boolean enabled = healthInterface.getEnabled();
        state.value = enabled;
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.secondaryLabel = enabled
                ? getModeLabel(healthInterface)
                : mContext.getString(R.string.quick_settings_charging_control_off);
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = mContext.getString(enabled
                ? R.string.accessibility_quick_settings_charging_control_on
                : R.string.accessibility_quick_settings_charging_control_off);
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

    private CharSequence getModeLabel(HealthInterface healthInterface) {
        switch (healthInterface.getMode()) {
            case HealthInterface.MODE_AUTO:
                return mContext.getString(R.string.quick_settings_charging_control_auto);
            case HealthInterface.MODE_MANUAL:
                return mContext.getString(R.string.quick_settings_charging_control_custom);
            case HealthInterface.MODE_LIMIT:
                return mContext.getString(
                        R.string.quick_settings_charging_control_limit,
                        healthInterface.getLimit());
            default:
                return mContext.getString(R.string.quick_settings_charging_control_on);
        }
    }

    private final class ChargingControlObserver extends ContentObserver {
        private boolean mObserving;

        ChargingControlObserver(Handler handler) {
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

            mObserving = true;
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_ENABLED),
                    false, this, userId);
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_MODE),
                    false, this, userId);
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_LIMIT),
                    false, this, userId);
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