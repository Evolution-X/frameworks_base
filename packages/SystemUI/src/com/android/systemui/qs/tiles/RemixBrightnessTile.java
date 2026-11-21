/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
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

import javax.inject.Inject;

public class RemixBrightnessTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "rrbrightness";

    private static final Uri MODE_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);

    private boolean mListening;

    private final ContentObserver mObserver;

    @Inject
    public RemixBrightnessTile(
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
        mObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
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
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    MODE_URI, false, mObserver, UserHandle.USER_ALL);
            refreshState();
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    private boolean isAuto() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                isAuto() ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_brightness_tile_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isAuto();
        state.icon = maybeLoadResourceIcon(state.value
                ? R.drawable.ic_qs_brightness_auto_on : R.drawable.ic_brightness_full);
        state.label = getTileLabel();
        state.secondaryLabel = mContext.getString(state.value
                ? R.string.qs_brightness_tile_auto : R.string.qs_brightness_tile_manual);
        state.contentDescription = state.label;
        state.stateDescription = state.secondaryLabel;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }
}
