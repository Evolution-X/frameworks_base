package com.android.systemui.dt2s;

import static android.provider.Settings.System.DOUBLE_TAP_SLEEP_GESTURE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

@SysUISingleton
public final class DoubleTapToSleepController implements CoreStartable {

    private static final String TAG = "DT2SController";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String MONITOR_NAME = "dt2s-monitor";

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final FalsingManager mFalsingManager;
    private final Handler mMainHandler;
    private final ContentResolver mContentResolver;

    private InputMonitorCompat mInputMonitorCompat;
    private InputChannelCompat.InputEventReceiver mInputEventReceiver;

    private GestureDetector mGestureDetector;
    private ContentObserver mSettingsObserver;

    private final AtomicBoolean mEnabled = new AtomicBoolean(false);

    private volatile boolean mLauncherInForeground = false;

    private boolean mMonitoringActive = false;

    @Inject
    public DoubleTapToSleepController(
            Context context,
            PowerManager powerManager,
            FalsingManager falsingManager,
            @Main Handler mainHandler) {
        mContext = context;
        mPowerManager = powerManager;
        mFalsingManager = falsingManager;
        mMainHandler = mainHandler;
        mContentResolver = context.getContentResolver();
    }

    @Override
    public void start() {
        mGestureDetector = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTapEvent(MotionEvent e) {
                        if (e.getActionMasked() != MotionEvent.ACTION_UP) return false;
                        if (!mEnabled.get() || !mLauncherInForeground) return false;
                        if (e.getPointerCount() > 1) return false;
                        if (mFalsingManager.isFalseDoubleTap()) return false;

                        triggerSleep(e.getEventTime());
                        return true;
                    }
                });
        mGestureDetector.setIsLongpressEnabled(false);

        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateEnabledState();
            }
        };
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(DOUBLE_TAP_SLEEP_GESTURE),
                /* notifyForDescendants= */ false,
                mSettingsObserver,
                UserHandle.USER_ALL);
        updateEnabledState();

        TaskStackChangeListeners.getInstance().registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskMovedToFront(
                            android.app.ActivityManager.RunningTaskInfo taskInfo) {
                        if (taskInfo.topActivity == null) return;
                        final boolean isLauncher =
                                LAUNCHER_PKG.equals(taskInfo.topActivity.getPackageName());
                        mMainHandler.post(() -> mLauncherInForeground = isLauncher);
                    }
                });
    }

    private void updateEnabledState() {
        final boolean enabled = Settings.System.getIntForUser(
                mContentResolver,
                DOUBLE_TAP_SLEEP_GESTURE,
                /* def= */ 0,
                UserHandle.USER_CURRENT) != 0;

        final boolean changed = mEnabled.getAndSet(enabled) != enabled;
        if (!changed) return;

        Log.d(TAG, "DT2S " + (enabled ? "enabled" : "disabled"));
        if (enabled) {
            startInputMonitoring();
        } else {
            stopInputMonitoring();
        }
    }

    private void startInputMonitoring() {
        if (mMonitoringActive) return;
        try {
            mInputMonitorCompat = new InputMonitorCompat(
                    MONITOR_NAME, Display.DEFAULT_DISPLAY);

            mInputEventReceiver = mInputMonitorCompat.getInputReceiver(
                    mMainHandler.getLooper(),
                    Choreographer.getInstance(),
                    this::onInputEvent);

            mMonitoringActive = true;
            Log.d(TAG, "Input monitoring started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start input monitoring: " + e.getMessage());
            mInputMonitorCompat = null;
            mInputEventReceiver = null;
            mMonitoringActive = false;
        }
    }

    private void stopInputMonitoring() {
        if (!mMonitoringActive) return;
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitorCompat != null) {
            mInputMonitorCompat.dispose();
            mInputMonitorCompat = null;
        }
        mMonitoringActive = false;
        Log.d(TAG, "Input monitoring stopped");
    }

    private void onInputEvent(InputEvent event) {
        if (!mEnabled.get() || !mLauncherInForeground) return;
        if (!(event instanceof MotionEvent)) return;

        final MotionEvent me = (MotionEvent) event;
        if (me.getPointerCount() == 1) {
            mGestureDetector.onTouchEvent(me);
        }
    }

    private void triggerSleep(long eventTime) {
        Log.d(TAG, "DT2S: goToSleep");
        try {
            mPowerManager.goToSleep(eventTime);
        } catch (SecurityException e) {
            Log.e(TAG, "goToSleep denied — check DEVICE_POWER in privapp-permissions: "
                    + e.getMessage());
        }
    }
}
