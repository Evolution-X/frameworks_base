/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.format.DateUtils;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SystemSettings;

import javax.inject.Inject;

/** Quick Settings tile for cycling the display sleep timeout. */
public class ScreenTimeoutTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "screen_timeout";

    private static final Intent DISPLAY_SETTINGS =
            new Intent(Settings.ACTION_DISPLAY_SETTINGS);

    private static final int DEFAULT_TIMEOUT = 30_000;
    private static final int[] TIMEOUTS = new int[] {
            15_000,
            30_000,
            60_000,
            120_000,
            300_000,
            600_000,
            1_800_000,
    };

    private final UserSettingObserver mSetting;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_screen_timeout);

    @Inject
    public ScreenTimeoutTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            SystemSettings systemSettings,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSetting = new UserSettingObserver(
                systemSettings,
                mHandler,
                Settings.System.SCREEN_OFF_TIMEOUT,
                userTracker.getUserId(),
                DEFAULT_TIMEOUT
        ) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mSetting.setValue(getNextTimeout(mSetting.getValue()));
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return DISPLAY_SETTINGS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screen_timeout_label);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        final int timeout = arg instanceof Integer ? (Integer) arg : mSetting.getValue();

        state.icon = mIcon;
        state.label = getTileLabel();
        state.secondaryLabel = DateUtils.formatDuration(Math.max(0, timeout));
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
        state.expandedAccessibilityClassName = Button.class.getName();
        state.state = Tile.STATE_ACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        refreshState();
    }

    @Override
    protected void handleDestroy() {
        mSetting.setListening(false);
        super.handleDestroy();
    }

    private int getNextTimeout(int current) {
        for (int timeout : TIMEOUTS) {
            if (timeout > current) {
                return timeout;
            }
        }
        return TIMEOUTS[0];
    }
}