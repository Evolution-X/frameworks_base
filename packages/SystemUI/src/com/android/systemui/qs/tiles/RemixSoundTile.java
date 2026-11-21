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
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

public class RemixSoundTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "rrsound";

    private final AudioManager mAudio;
    private final boolean mVibrates;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private boolean mListening;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    @Inject
    public RemixSoundTile(
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
        final Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        mVibrates = vibrator != null && vibrator.hasVibrator();
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mBroadcastDispatcher.registerReceiver(mReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
            refreshState();
        } else {
            mBroadcastDispatcher.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final int mode = mAudio.getRingerModeInternal();
        final int next;
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            next = mVibrates ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
        } else if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            next = AudioManager.RINGER_MODE_SILENT;
        } else {
            next = AudioManager.RINGER_MODE_NORMAL;
        }
        mAudio.setRingerModeInternal(next);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_sound_tile_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        final int mode = mAudio.getRingerModeInternal();
        final int icon;
        final int text;
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            icon = R.drawable.ic_volume_ringer_vibrate;
            text = R.string.qs_sound_tile_vibrate;
        } else if (mode == AudioManager.RINGER_MODE_SILENT) {
            icon = R.drawable.ic_volume_ringer_mute;
            text = R.string.qs_sound_tile_silent;
        } else {
            icon = R.drawable.ic_volume_ringer;
            text = R.string.qs_sound_tile_ring;
        }
        state.icon = maybeLoadResourceIcon(icon);
        state.label = getTileLabel();
        state.secondaryLabel = mContext.getString(text);
        state.contentDescription = state.label;
        state.stateDescription = state.secondaryLabel;
        state.state = mode == AudioManager.RINGER_MODE_NORMAL
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }
}
