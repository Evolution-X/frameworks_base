/*
 * Copyright (C) 2026 The LineageOS Project
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

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

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

/** Quick settings tile: Charging control **/
public class ChargingControlTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "charging_control";

    private static final Intent CHARGING_CONTROL_SETTINGS =
            new Intent("org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS");

    @Nullable
    private Icon mIcon = null;

    private boolean mListening;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }
    };

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
        if (!isAvailable()) {
            return;
        }
        setEnabled(!isEnabled());
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
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        super.handleSetListening(listening);
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_ENABLED), false, mObserver);
            refreshState();
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mListening) {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
            mListening = false;
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean available = isAvailable();
        final boolean enabled = available && isEnabled();

        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_control);
        }

        state.value = enabled;
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_charging_control_label);

        if (available) {
            state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            state.contentDescription = mContext.getString(enabled
                    ? R.string.accessibility_quick_settings_charging_control_on
                    : R.string.accessibility_quick_settings_charging_control_off);
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
            state.contentDescription = state.label;
        }
    }

    private boolean isEnabled() {
        return LineageSettings.System.getInt(mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_ENABLED, 0) != 0;
    }

    private void setEnabled(boolean enabled) {
        LineageSettings.System.putInt(mContext.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_ENABLED, enabled ? 1 : 0);
    }
}
