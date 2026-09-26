/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

abstract class ConnectivityAutoOffTile extends QSTileImpl<BooleanState> {

    protected static final String WIFI_AUTO_OFF_TIMEOUT = "wifi_auto_off_timeout";
    protected static final String BLUETOOTH_AUTO_OFF_TIMEOUT = "bluetooth_auto_off_timeout";

    private static final String TAG = "ConnectivityAutoOffTile";
    private static final String PREFS_NAME = "connectivity_auto_off_qs";

    private static final long TIMEOUT_DISABLED = 0L;
    private static final long TIMEOUT_MIN = 15_000L;
    private static final long TIMEOUT_MAX = 8 * 60 * 60 * 1000L;
    private static final long DEFAULT_RESTORE_TIMEOUT = 5 * 60 * 1000L;

    @Nullable
    private Icon mIcon;

    private final SharedPreferences mPrefs;
    private final TimeoutObserver mObserver;

    private boolean mListening;

    ConnectivityAutoOffTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            Looper backgroundLooper,
            Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mObserver = new TimeoutObserver(mHandler);
    }

    protected abstract String getSettingKey();

    protected abstract String getRequiredFeature();

    @StringRes
    protected abstract int getLabelResId();

    @DrawableRes
    protected abstract int getIconResId();

    @StringRes
    protected abstract int getAccessibilityOnResId();

    @StringRes
    protected abstract int getAccessibilityOffResId();

    protected abstract Intent getSettingsIntent();

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public boolean isAvailable() {
        return mContext.getPackageManager().hasSystemFeature(getRequiredFeature());
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final long currentTimeout = getTimeout();
        final long newTimeout;

        if (currentTimeout > TIMEOUT_DISABLED) {
            rememberTimeout(currentTimeout);
            newTimeout = TIMEOUT_DISABLED;
        } else {
            newTimeout = getRestoreTimeout();
        }

        if (!Settings.Global.putLong(
                mContext.getContentResolver(), getSettingKey(), newTimeout)) {
            Log.w(TAG, "Unable to update " + getSettingKey());
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return getSettingsIntent();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(getLabelResId());
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(getIconResId());
        }

        final long timeout = getTimeout();
        final boolean enabled = timeout > TIMEOUT_DISABLED;

        state.icon = mIcon;
        state.label = getTileLabel();
        state.value = enabled;
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.secondaryLabel = enabled
                ? DateUtils.formatDuration(timeout)
                : mContext.getString(R.string.quick_settings_connectivity_auto_off_off);
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = mContext.getString(enabled
                ? getAccessibilityOnResId()
                : getAccessibilityOffResId());
        state.expandedAccessibilityClassName = Switch.class.getName();
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
            mObserver.startObserving();
            rememberTimeout(getTimeout());
            refreshState();
        } else {
            mObserver.stopObserving();
        }
    }

    @Override
    protected void handleDestroy() {
        mObserver.stopObserving();
        super.handleDestroy();
    }

    private long getTimeout() {
        final long timeout = Settings.Global.getLong(
                mContext.getContentResolver(), getSettingKey(), TIMEOUT_DISABLED);
        return isValidTimeout(timeout) ? timeout : TIMEOUT_DISABLED;
    }

    private boolean isValidTimeout(long timeout) {
        return timeout == TIMEOUT_DISABLED || (timeout >= TIMEOUT_MIN && timeout <= TIMEOUT_MAX);
    }

    private void rememberTimeout(long timeout) {
        if (timeout <= TIMEOUT_DISABLED || !isValidTimeout(timeout)) {
            return;
        }

        mPrefs.edit().putLong(getLastTimeoutPreferenceKey(), timeout).apply();
    }

    private long getRestoreTimeout() {
        final long timeout = mPrefs.getLong(
                getLastTimeoutPreferenceKey(), DEFAULT_RESTORE_TIMEOUT);
        return timeout > TIMEOUT_DISABLED && isValidTimeout(timeout)
                ? timeout
                : DEFAULT_RESTORE_TIMEOUT;
    }

    private String getLastTimeoutPreferenceKey() {
        return "last_" + getSettingKey();
    }

    private final class TimeoutObserver extends ContentObserver {
        private boolean mObserving;

        TimeoutObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            final long timeout = getTimeout();
            rememberTimeout(timeout);
            refreshState();
        }

        void startObserving() {
            if (mObserving) {
                return;
            }

            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(getSettingKey()), false, this);
            mObserving = true;
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