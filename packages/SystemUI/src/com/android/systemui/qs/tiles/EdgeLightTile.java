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
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
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

/** Quick Settings tile for the Edge Lighting master switch. */
public class EdgeLightTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "edge_light";

    private static final Intent EDGE_LIGHT_SETTINGS =
            new Intent("com.android.settings.EDGE_LIGHT_SETTINGS")
                    .setPackage("com.android.settings")
                    .addCategory(Intent.CATEGORY_DEFAULT);

    @Nullable
    private Icon mIcon;

    private final UserSettingObserver mSetting;

    @Inject
    public EdgeLightTile(
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
                Settings.System.EDGE_LIGHT_ENABLED,
                userTracker.getUserId()
        ) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleDestroy() {
        mSetting.setListening(false);
        super.handleDestroy();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        refreshState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mSetting.setValue(mState.value ? 0 : 1);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return EDGE_LIGHT_SETTINGS;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_edge_light_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;

        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_edge_light);
        }

        state.icon = mIcon;
        state.value = enabled;
        state.label = getTileLabel();
        state.secondaryLabel = mContext.getString(enabled
                ? R.string.quick_settings_state_on
                : R.string.quick_settings_state_off);
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }
}
