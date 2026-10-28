package com.android.systemui.dt2s;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.android.systemui.CoreStartable;
import com.android.systemui.LauncherProxyService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardWmStateRefactor;
import com.android.systemui.keyguard.data.repository.InWindowLauncherUnlockAnimationRepository;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.shared.system.smartspace.SmartspaceState;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.tuner.TunerService;
import com.android.wm.shell.shared.FocusTransitionListener;
import com.android.wm.shell.shared.ShellTransitions;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

import dagger.Lazy;

import lineageos.providers.LineageSettings;

/**
 * Double tap to sleep for the Pixel Launcher home screen.
 *
 * The input monitor is only registered while every condition in
 * {@link #canHandleGesture()} holds, so it costs nothing in other apps, in recents, while the
 * keyboard is up or while anything else (an app window, an expanded bubble) has focus. Taps on
 * the launcher's At a Glance widget, search bar and dock are ignored as well: the At a Glance
 * area is taken from the bounds the launcher reports to SystemUI, with a fixed band as
 * fallback, and the search bar and dock use a fixed band at the bottom.
 */
@SysUISingleton
public final class DoubleTapToSleepController implements CoreStartable, TunerService.Tunable {

    private static final String TAG = "DT2SController";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String MONITOR_NAME = "dt2s-monitor";
    private static final String DOUBLE_TAP_SLEEP_GESTURE =
            "lineagesystem:" + LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE;

    // Regions of the home screen where a double tap is ignored: status bar + At a Glance at the
    // top, search bar + dock at the bottom. Capped as a fraction of the screen for landscape.
    // The top band is only a fallback for when the launcher hasn't reported its At a Glance bounds.
    private static final float TOP_EXCLUDED_DP = 160f;
    private static final float BOTTOM_EXCLUDED_DP = 200f;
    private static final float TOP_EXCLUDED_MAX_FRACTION = 0.20f;
    private static final float BOTTOM_EXCLUDED_MAX_FRACTION = 0.25f;
    // Extra room kept below the reported At a Glance bounds, and the lowest those bounds may end
    // (as a fraction of the screen height) to still be trusted.
    private static final float SMARTSPACE_MARGIN_DP = 8f;
    private static final float SMARTSPACE_MAX_BOTTOM_FRACTION = 0.5f;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final FalsingManager mFalsingManager;
    private final Handler mMainHandler;
    private final Executor mBackgroundExecutor;
    private final CommandQueue mCommandQueue;
    private final TunerService mTunerService;
    private final ShellTransitions mShellTransitions;
    private final LauncherProxyService mLauncherProxyService;
    private final Lazy<KeyguardUnlockAnimationController> mKeyguardUnlockAnimationController;
    private final Lazy<InWindowLauncherUnlockAnimationRepository> mInWindowLauncherRepository;

    private InputMonitorCompat mInputMonitorCompat;
    private InputChannelCompat.InputEventReceiver mInputEventReceiver;

    private GestureDetector mGestureDetector;

    private final AtomicBoolean mEnabled = new AtomicBoolean(false);

    // All of the following are only touched on the main thread.
    // True only while the launcher home is the globally focused task, not just the latest one.
    private boolean mLauncherHomeForeground = false;
    private boolean mBubbleExpanded = false;
    private boolean mOverviewShown = false;
    private boolean mImeVisible = false;

    private boolean mMonitoringActive = false;
    // True while the current touch started inside the allowed region and should reach the detector.
    private boolean mTrackingTouch = false;

    @Inject
    public DoubleTapToSleepController(
            Context context,
            PowerManager powerManager,
            FalsingManager falsingManager,
            CommandQueue commandQueue,
            @Main Handler mainHandler,
            @Background Executor backgroundExecutor,
            TunerService tunerService,
            ShellTransitions shellTransitions,
            LauncherProxyService launcherProxyService,
            Lazy<KeyguardUnlockAnimationController> keyguardUnlockAnimationController,
            Lazy<InWindowLauncherUnlockAnimationRepository> inWindowLauncherRepository) {
        mContext = context;
        mPowerManager = powerManager;
        mFalsingManager = falsingManager;
        mCommandQueue = commandQueue;
        mMainHandler = mainHandler;
        mBackgroundExecutor = backgroundExecutor;
        mTunerService = tunerService;
        mShellTransitions = shellTransitions;
        mLauncherProxyService = launcherProxyService;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mInWindowLauncherRepository = inWindowLauncherRepository;
    }

    @Override
    public void start() {
        mTunerService.addTunable(this, DOUBLE_TAP_SLEEP_GESTURE);

        TaskStackChangeListeners.getInstance().registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskStackChanged() {
                        refreshForegroundState();
                    }

                    @Override
                    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                        refreshForegroundState();
                    }
                });
        // Focus can move to a bubble or another window without any task stack change.
        mShellTransitions.setFocusTransitionListener(new FocusTransitionListener() {
            @Override
            public void onFocusedTaskChanged(ActivityManager.RunningTaskInfo taskInfo,
                    boolean isFocusedOnDisplay, boolean isFocusedGlobally) {
                if (isFocusedGlobally) refreshForegroundState();
            }

            @Override
            public void onFocusedDisplayChanged(int displayId) {
                refreshForegroundState();
            }
        }, mBackgroundExecutor);
        refreshForegroundState();

        mLauncherProxyService.addCallback(new LauncherProxyService.LauncherProxyListener() {
            @Override
            public void onConnectionChanged(boolean isConnected) {
                // The launcher process went away (or just came back): overview can't be open.
                if (!isConnected) setOverviewShown(false);
            }

            @Override
            public void onOverviewVisibilityChanged(boolean visible) {
                setOverviewShown(visible);
            }
        });

        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void setImeWindowStatus(int displayId, int vis, int backDisposition,
                    boolean showImeSwitcher) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                final boolean visible = (vis & InputMethodService.IME_VISIBLE) != 0;
                if (visible == mImeVisible) return;
                mImeVisible = visible;
                Log.d(TAG, "IME visible=" + visible);
                updateMonitoring();
            }
        });
    }

    /**
     * Single place that decides whether a double tap may put the device to sleep: the Pixel
     * Launcher home screen has focus, with no overview, soft keyboard or expanded bubble on top
     * of it. The lockscreen, shade and QS panel have their own handlers on the same toggle and
     * are left alone.
     */
    private boolean canHandleGesture() {
        return mEnabled.get()
                && mLauncherHomeForeground
                && !mOverviewShown
                && !mImeVisible
                && !mBubbleExpanded;
    }

    private void refreshForegroundState() {
        mBackgroundExecutor.execute(() -> {
            final boolean home = isLauncherHome(getFocusedTask());
            mMainHandler.post(() -> {
                mLauncherHomeForeground = home;
                // Overview lives inside the launcher; once something else is on top it is gone.
                if (!home) mOverviewShown = false;
                updateMonitoring();
            });
        });
    }

    /**
     * The globally focused root task. The latest running task is not enough: an expanded bubble
     * or another window can be above the launcher while it is still the latest home task.
     */
    private static TaskInfo getFocusedTask() {
        try {
            return ActivityTaskManager.getService().getFocusedRootTaskInfo();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get focused task: " + e.getMessage());
            return null;
        }
    }

    private static boolean isLauncherHome(TaskInfo task) {
        return task != null
                && task.topActivity != null
                && LAUNCHER_PKG.equals(task.topActivity.getPackageName())
                && task.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_HOME;
    }

    private void setOverviewShown(boolean shown) {
        mMainHandler.post(() -> {
            if (mOverviewShown == shown) return;
            mOverviewShown = shown;
            Log.d(TAG, "Overview shown=" + shown);
            updateMonitoring();
        });
    }

    /** Called when the bubble stack expands or collapses. */
    public void onBubbleExpandChanged(boolean expanded) {
        mMainHandler.post(() -> {
            if (mBubbleExpanded == expanded) return;
            mBubbleExpanded = expanded;
            Log.d(TAG, "Bubble expanded=" + expanded);
            updateMonitoring();
        });
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!DOUBLE_TAP_SLEEP_GESTURE.equals(key)) return;
        final boolean enabled = TunerService.parseIntegerSwitch(newValue,
                mContext.getResources().getBoolean(
                        org.lineageos.platform.internal.R.bool.config_dt2sGestureEnabledByDefault));
        mMainHandler.post(() -> {
            if (mEnabled.getAndSet(enabled) == enabled) return;
            Log.d(TAG, "DT2S " + (enabled ? "enabled" : "disabled"));
            updateMonitoring();
        });
    }

    /** Registers the input monitor only while the gesture is allowed, and drops it otherwise. */
    private void updateMonitoring() {
        if (canHandleGesture()) {
            startInputMonitoring();
        } else {
            stopInputMonitoring();
        }
    }

    private GestureDetector createGestureDetector() {
        final GestureDetector detector = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTapEvent(MotionEvent e) {
                        if (e.getActionMasked() != MotionEvent.ACTION_UP) return false;
                        if (!canHandleGesture()) return false;
                        if (e.getPointerCount() > 1) return false;
                        if (mFalsingManager.isFalseDoubleTap()) return false;

                        triggerSleep(e.getEventTime());
                        return true;
                    }
                });
        detector.setIsLongpressEnabled(false);
        return detector;
    }

    private void startInputMonitoring() {
        if (mMonitoringActive) return;
        try {
            // Fresh detector so a tap from a previous session can't pair with a new one.
            mGestureDetector = createGestureDetector();
            mTrackingTouch = false;

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
        mGestureDetector = null;
        mTrackingTouch = false;
        mMonitoringActive = false;
        Log.d(TAG, "Input monitoring stopped");
    }

    private void onInputEvent(InputEvent event) {
        if (!(event instanceof MotionEvent) || mGestureDetector == null) return;
        if (!canHandleGesture()) return;

        final MotionEvent me = (MotionEvent) event;
        final int action = me.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mTrackingTouch = me.getPointerCount() == 1 && isInAllowedRegion(me);
            if (!mTrackingTouch) {
                // A tap on the search bar, widget area or dock must not pair with another tap.
                mGestureDetector = createGestureDetector();
            }
        }
        if (!mTrackingTouch) return;

        if (me.getPointerCount() == 1) {
            mGestureDetector.onTouchEvent(me);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mTrackingTouch = false;
        }
    }

    private boolean isInAllowedRegion(MotionEvent me) {
        final DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        final float top = getTopExcludedPx(dm);
        final float bottom = dm.heightPixels - Math.min(BOTTOM_EXCLUDED_DP * dm.density,
                BOTTOM_EXCLUDED_MAX_FRACTION * dm.heightPixels);
        final float y = me.getRawY();
        return y > top && y < bottom;
    }

    /**
     * Bottom edge of the area at the top of the screen where taps are ignored. Uses the At a
     * Glance bounds the launcher reports, and falls back to a fixed band when there are none.
     */
    private float getTopExcludedPx(DisplayMetrics dm) {
        final Rect bounds = getLauncherSmartspaceBounds();
        if (bounds != null && !bounds.isEmpty()
                && bounds.bottom <= SMARTSPACE_MAX_BOTTOM_FRACTION * dm.heightPixels) {
            return bounds.bottom + SMARTSPACE_MARGIN_DP * dm.density;
        }
        return Math.min(TOP_EXCLUDED_DP * dm.density,
                TOP_EXCLUDED_MAX_FRACTION * dm.heightPixels);
    }

    /** The launcher's At a Glance bounds on screen, or null if it isn't reporting any. */
    private Rect getLauncherSmartspaceBounds() {
        try {
            final SmartspaceState state = KeyguardWmStateRefactor.isEnabled()
                    ? mInWindowLauncherRepository.get().getLauncherSmartspaceState().getValue()
                    : mKeyguardUnlockAnimationController.get().getLauncherSmartspaceState();
            if (state == null || !state.getVisibleOnScreen()) return null;
            return state.getBoundsOnScreen();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read launcher smartspace state: " + e.getMessage());
            return null;
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
