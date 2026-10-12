/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (c) 2017 The LineageOS Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.util.Log;

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

/**
 * Quick Settings tile that keeps the display awake for a selected amount of time.
 *
 * <p>Screen wake locks can be ignored by device power policies. While Caffeine is enabled,
 * the tile additionally refreshes the user-activity timeout. Unlike a window flag, this
 * works when switching apps. Explicit screen-off always ends the session.
 */
public class CaffeineTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "caffeine";

    private static final String TAG = "CaffeineTile";
    private static final long CLICK_CYCLE_WINDOW_MILLIS = 5_000L;
    private static final long USER_ACTIVITY_INTERVAL_MILLIS = 3_000L;
    private static final int[] DURATIONS_SECONDS = {
        5 * 60,   // 5 minutes
        10 * 60,  // 10 minutes
        30 * 60,  // 30 minutes
        -1,       // Until manually stopped
    };
    private static final int INFINITE_DURATION_INDEX = DURATIONS_SECONDS.length - 1;

    @Nullable
    private Icon mIcon;

    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;
    private final Receiver mReceiver = new Receiver();
    private boolean mCanRefreshUserActivity = true;

    private final Runnable mKeepScreenAwake = new Runnable() {
        @Override
        public void run() {
            if (!mWakeLock.isHeld()) {
                return;
            }
            // User-initiated sleep or another display-off event must always win.
            if (!mPowerManager.isInteractive()) {
                stopCaffeine();
                refreshState();
                return;
            }
            refreshUserActivity();
            if (mCanRefreshUserActivity) {
                mHandler.postDelayed(this, USER_ACTIVITY_INTERVAL_MILLIS);
            }
        }
    };

    @Nullable
    private CountDownTimer mCountdownTimer;
    private int mDurationIndex = -1;
    private int mSecondsRemaining;
    private long mDeadlineElapsedMillis;
    private long mLastClickTime = -1;

    @Inject
    public CaffeineTile(
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
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK, "CaffeineTile");
        // The tile represents a single active request, not a collection of nested locks.
        mWakeLock.setReferenceCounted(false);
        mReceiver.init();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        mReceiver.destroy();
        stopCaffeine();
        super.handleDestroy();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        Log.d(TAG, "QS tap, held=" + mWakeLock.isHeld()
                + ", durationIndex=" + mDurationIndex);
        final long now = SystemClock.elapsedRealtime();
        if (mWakeLock.isHeld() && mLastClickTime >= 0
                && now - mLastClickTime < CLICK_CYCLE_WINDOW_MILLIS) {
            // Consecutive taps cycle through 5, 10, 30 minutes, infinity, then off.
            final int nextIndex = mDurationIndex + 1;
            if (nextIndex >= DURATIONS_SECONDS.length) {
                stopCaffeine();
            } else {
                startCaffeine(nextIndex);
            }
        } else if (mWakeLock.isHeld()) {
            stopCaffeine();
        } else {
            startCaffeine(0);
        }

        mLastClickTime = mWakeLock.isHeld() ? now : -1;
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (mWakeLock.isHeld() && mDurationIndex == INFINITE_DURATION_INDEX) {
            return;
        }
        startCaffeine(INFINITE_DURATION_INDEX);
        // The next short tap should stop infinity, not unexpectedly cycle durations.
        mLastClickTime = -1;
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        // Long press is handled in-place rather than opening another activity.
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_caffeine_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private void startCaffeine(int durationIndex) {
        stopCountDown();
        mDurationIndex = durationIndex;
        mSecondsRemaining = DURATIONS_SECONDS[durationIndex];

        if (!mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire();
            } catch (SecurityException | IllegalArgumentException e) {
                Log.e(TAG, "Unable to acquire the display wake lock", e);
                stopCaffeine();
                return;
            }
        }

        // A screen wake lock is not sufficient on all modern device power policies.
        // Refresh the system's actual inactivity timer as well, without permanently
        // modifying the user's SCREEN_OFF_TIMEOUT preference.
        mHandler.removeCallbacks(mKeepScreenAwake);
        if (mPowerManager.isInteractive()) {
            refreshUserActivity();
            if (mCanRefreshUserActivity) {
                mHandler.postDelayed(mKeepScreenAwake, USER_ACTIVITY_INTERVAL_MILLIS);
            }
        } else {
            Log.w(TAG, "Activation rejected: display is not interactive");
            stopCaffeine();
            return;
        }
        Log.d(TAG, "Caffeine active, durationIndex=" + durationIndex
                + ", wakeLockHeld=" + mWakeLock.isHeld());

        if (durationIndex == INFINITE_DURATION_INDEX) {
            mDeadlineElapsedMillis = 0;
            return;
        }

        final long durationMillis = mSecondsRemaining * 1_000L;
        // elapsedRealtime is monotonic: changing the wall clock cannot extend the timer.
        mDeadlineElapsedMillis = SystemClock.elapsedRealtime() + durationMillis;
        mCountdownTimer = new CountDownTimer(durationMillis, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Ignore callbacks from a timer that has already been replaced or cancelled.
                if (mCountdownTimer != this || !mWakeLock.isHeld()) {
                    return;
                }
                updateRemainingSeconds();
                if (mSecondsRemaining == 0) {
                    stopCaffeine();
                }
                refreshState();
            }

            @Override
            public void onFinish() {
                if (mCountdownTimer != this) {
                    return;
                }
                stopCaffeine();
                refreshState();
            }
        }.start();
    }

    private void refreshUserActivity() {
        if (!mCanRefreshUserActivity) {
            return;
        }
        try {
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
        } catch (SecurityException e) {
            // Keep the legacy display lock active even on ROMs without this permission.
            mCanRefreshUserActivity = false;
            Log.w(TAG, "User activity fallback unavailable; using screen wake lock only", e);
        }
    }

    private void stopCaffeine() {
        mHandler.removeCallbacks(mKeepScreenAwake);
        stopCountDown();
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mDurationIndex = -1;
        mSecondsRemaining = 0;
        mDeadlineElapsedMillis = 0;
        mLastClickTime = -1;
    }

    private void stopCountDown() {
        final CountDownTimer timer = mCountdownTimer;
        mCountdownTimer = null;
        if (timer != null) {
            timer.cancel();
        }
    }

    private void updateRemainingSeconds() {
        if (mDurationIndex == INFINITE_DURATION_INDEX) {
            mSecondsRemaining = -1;
        } else if (mDurationIndex >= 0) {
            final long remainingMillis =
                    Math.max(0L, mDeadlineElapsedMillis - SystemClock.elapsedRealtime());
            // Round up so an active timer does not prematurely display 00:00.
            mSecondsRemaining = (int) ((remainingMillis + 999L) / 1_000L);
        }
    }

    private String formatValueWithRemainingTime() {
        if (mSecondsRemaining == -1) {
            return "∞";
        }
        return String.format("%02d:%02d", mSecondsRemaining / 60,
                mSecondsRemaining % 60);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        // The system may release a display wake lock externally, e.g. on forced screen-off.
        if (!mWakeLock.isHeld() && mDurationIndex >= 0) {
            stopCaffeine();
        } else if (mWakeLock.isHeld() && mDurationIndex != INFINITE_DURATION_INDEX) {
            updateRemainingSeconds();
            if (mSecondsRemaining == 0) {
                stopCaffeine();
            }
        }

        state.value = mWakeLock.isHeld();
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_caffeine);
        }
        state.icon = mIcon;
        final CharSequence caffeineLabel = mContext.getString(R.string.quick_settings_caffeine_label);
        // Some compact QS layouts omit secondaryLabel altogether. Show the countdown
        // as the primary label while active so the time remains visible.
        state.label = state.value ? formatValueWithRemainingTime() : caffeineLabel;
        state.handlesLongClick = true;
        state.hasLongClickEffect = true;
        if (state.value) {
            state.secondaryLabel = caffeineLabel;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_on)
                    + ", " + formatValueWithRemainingTime();
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    // Package-private for lifecycle regression tests.
    void handleScreenOff() {
        stopCaffeine();
        refreshState();
    }

    private final class Receiver extends BroadcastReceiver {
        public void init() {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handleScreenOff();
            }
        }
    }
}
