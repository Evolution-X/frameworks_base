/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
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

public class RemixVolumeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "rrvolume";

    private static final int STREAM = AudioManager.STREAM_MUSIC;

    private final AudioManager mAudio;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private boolean mListening;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    @Inject
    public RemixVolumeTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mBroadcastDispatcher = broadcastDispatcher;
        mAudio = mContext.getSystemService(AudioManager.class);
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
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
            filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
            mBroadcastDispatcher.registerReceiver(mReceiver, filter);
            refreshState();
        } else {
            mBroadcastDispatcher.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (mAudio.getStreamVolume(STREAM) > 0) {
            mAudio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0);
        } else {
            mAudio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0);
            if (mAudio.getStreamVolume(STREAM) == 0) {
                mAudio.setStreamVolume(STREAM, 1, 0);
            }
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_volume_tile_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int level = mAudio.getStreamVolume(STREAM);
        state.value = level > 0;
        state.icon = maybeLoadResourceIcon(
                state.value ? R.drawable.ic_volume_media : R.drawable.ic_volume_media_mute);
        state.label = getTileLabel();
        state.secondaryLabel = state.value
                ? null
                : mContext.getString(R.string.qs_volume_tile_muted);
        state.contentDescription = state.label;
        state.stateDescription = state.secondaryLabel;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }
}
