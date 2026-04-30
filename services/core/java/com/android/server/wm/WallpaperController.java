/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WallpaperManager.COMMAND_FREEZE;
import static android.app.WallpaperManager.COMMAND_UNFREEZE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WALLPAPER_DRAW_PENDING_TIMEOUT;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.RemoteException;
import android.os.Trace;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ScreenCaptureInternal;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.wallpaper.WallpaperCropper;
import com.android.server.wallpaper.WallpaperDefaultDisplayInfo;
import com.android.window.flags.Flags;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Controls wallpaper windows visibility, ordering, and so on.
 * NOTE: All methods in this class must be called with the window manager service lock held.
 */
class WallpaperController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WallpaperController" : TAG_WM;
    private WindowManagerService mService;
    private DisplayContent mDisplayContent;

    // Larger index has higher z-order.
    private final ArrayList<WallpaperWindowToken> mWallpaperTokens = new ArrayList<>();

    // If non-null, this is the currently visible window that is associated
    // with the wallpaper.
    private WindowState mWallpaperTarget = null;

    private static final float WALLPAPER_ZOOM_EPSILON = 0.003f;
    private float mLastWallpaperZoomOut = 0;
    private WindowState mMaxZoomOutWindow = null;

    // Whether COMMAND_FREEZE was dispatched.
    private boolean mLastFrozen = false;

    private float mMinWallpaperScale;
    private float mMaxWallpaperScale;

    // The last time we had a timeout when waiting for a wallpaper.
    private long mLastWallpaperTimeoutTime;
    // We give a wallpaper up to 150ms to finish scrolling.
    private static final long WALLPAPER_TIMEOUT = 150;
    // Time we wait after a timeout before trying to wait again.
    private static final long WALLPAPER_TIMEOUT_RECOVERY = 10000;

    // We give a wallpaper up to 500ms to finish drawing before playing app transitions.
    private static final long WALLPAPER_DRAW_PENDING_TIMEOUT_DURATION = 500;
    private static final int WALLPAPER_DRAW_NORMAL = 0;
    private static final int WALLPAPER_DRAW_PENDING = 1;
    private static final int WALLPAPER_DRAW_TIMEOUT = 2;
    private int mWallpaperDrawState = WALLPAPER_DRAW_NORMAL;

    private final FindWallpaperTargetResult mFindResults = new FindWallpaperTargetResult();

    // This is for WallpaperCropper, which has cropping logic for the default display only.
    // This is lazily initialization by getOrCreateDefaultDisplayInfo. DO NOT use this member
    // variable directly.
    // TODO(b/400685784) make the WallpaperCropper operate on every display independently
    @Nullable
    private WallpaperDefaultDisplayInfo mDefaultDisplayInfo = null;

    private final ToBooleanFunction<WindowState> mFindWallpaperTargetFunction = w -> {
        final ActivityRecord ar = w.mActivityRecord;
        // The animating window can still be visible on screen if it is in transition, so we
        // should check whether this window can be wallpaper target even when visibleRequested
        // is false.
        if (ar != null && !ar.isVisibleRequested() && !ar.isVisible()) {
            // An activity that is not going to remain visible shouldn't be the target.
            return false;
        }
        if (DEBUG_WALLPAPER) Slog.v(TAG, "Win " + w + ": isOnScreen=" + w.isOnScreen()
                + " mDrawState=" + w.mWinAnimator.mDrawState);

        if (mService.mPolicy.isKeyguardLocked()) {
            if (w.canShowWhenLocked()) {
                if (mService.mPolicy.isKeyguardOccluded() || w.inTransition()) {
                    // The lowest show-when-locked window decides whether to show wallpaper.
                    mFindResults.mNeedsShowWhenLockedWallpaper = !isFullscreen(w.mAttrs)
                            || (w.mActivityRecord != null && !w.mActivityRecord.fillsParent());
                }
            } else if (w.hasWallpaper() && mService.mPolicy.isKeyguardHostWindow(w.mAttrs)
                    && w.mTransitionController.hasTransientLaunch(mDisplayContent)) {
                // If we have no candidates at all, notification shade is allowed to be the target
                // of last resort even if it has not been made visible yet.
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Found keyguard as wallpaper target: " + w);
                mFindResults.setWallpaperTarget(w);
                return false;
            }
        } else if (Flags.aodTransition() && mDisplayContent.isKeyguardLockedOrAodShowing()) {
            if (mService.mPolicy.isKeyguardHostWindow(w.mAttrs)
                    && w.mTransitionController.isInAodAppearTransition() && w.hasWallpaper()) {
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Found aod transition wallpaper target: " + w);
                mFindResults.setWallpaperTarget(w);
                return true;
            }
        }

        if (ar != null && ar.mTransitionController.isLaunchingRecents(ar) && w.hasWallpaper()) {
            // Recents transition can start before its window is drawn (w.isOnScreen() is false).
            mFindResults.setWallpaperTarget(w);
            return true;
        }
        if (isBackNavigationTarget(w)) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Found back animation wallpaper target: " + w);
            mFindResults.setWallpaperTarget(w);
            return true;
        } else if (w.hasWallpaper()
                && (w.mActivityRecord != null ? w.isOnScreen() : w.isReadyForDisplay())) {
            if (!w.isVisibleRequested() && !mFindResults.hasInvisibleRequestedTarget) {
                // If the first target is not visibleRequested, set it as a candidate and search for
                // the next visible target.
                if (DEBUG_WALLPAPER) {
                    Slog.v(TAG, "Found candidate wallpaper target: " + w);
                }
                mFindResults.hasInvisibleRequestedTarget = true;
                mFindResults.setWallpaperTarget(w);
                mFindResults.setIsWallpaperTargetForLetterbox(
                        w.hasWallpaperForLetterboxBackground());
                return false;
            } else {
                if (mFindResults.hasInvisibleRequestedTarget) {
                    if (w.isVisibleRequested()) {
                        // Find the next visible target behind the candidate, and stop.
                        if (DEBUG_WALLPAPER) {
                            Slog.v(TAG, "Found wallpaper target behind candidate: " + w);
                        }
                        mFindResults.setWallpaperTarget(w);
                        mFindResults.setIsWallpaperTargetForLetterbox(
                                w.hasWallpaperForLetterboxBackground());
                    } else {
                        // Search for the next potential window.
                        return false;
                    }
                } else {
                    if (DEBUG_WALLPAPER) Slog.v(TAG, "Found wallpaper target: " + w);
                    mFindResults.setWallpaperTarget(w);
                    mFindResults.setIsWallpaperTargetForLetterbox(
                            w.hasWallpaperForLetterboxBackground());
                }
            }
            // While the keyguard is going away, both notification shade and a normal activity such
            // as a launcher can satisfy criteria for a wallpaper target. In this case, we should
            // chose the normal activity, otherwise wallpaper becomes invisible when a new animation
            // starts before the keyguard going away animation finishes.
            if (w.mActivityRecord == null && mDisplayContent.isKeyguardGoingAway()) {
                return false;
            }
            return true;
        }
        return false;
    };

    private boolean isBackNavigationTarget(WindowState w) {
        // The window is in animating by back navigation and set to show wallpaper.
        return mService.mAtmService.mBackNavigationController.isWallpaperVisible(w);
    }

    /**
     * @see #computeLastWallpaperZoomOut()
     */
    private Consumer<WindowState>  mComputeMaxZoomOutFunction = windowState -> {
        if (!windowState.mIsWallpaper
                && Float.compare(windowState.mWallpaperZoomOut, mLastWallpaperZoomOut) > 0) {
            mLastWallpaperZoomOut = windowState.mWallpaperZoomOut;
            mMaxZoomOutWindow = windowState;
        }
    };

    WallpaperController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        Resources resources = service.mContext.getResources();
        mMinWallpaperScale =
                resources.getFloat(com.android.internal.R.dimen.config_wallpaperMinScale);
        mMaxWallpaperScale = resources.getFloat(R.dimen.config_wallpaperMaxScale);
    }

    @VisibleForTesting
    void setMinWallpaperScale(float minScale) {
        mMinWallpaperScale = minScale;
    }

    @VisibleForTesting
    void setMaxWallpaperScale(float maxScale) {
        mMaxWallpaperScale = maxScale;
    }

    WindowState getWallpaperTarget() {
        return mWallpaperTarget;
    }

    boolean isWallpaperTarget(WindowState win) {
        return win == mWallpaperTarget;
    }

    boolean isBelowWallpaperTarget(WindowState win) {
        return mWallpaperTarget != null && mWallpaperTarget.mLayer >= win.mBaseLayer;
    }

    boolean isWallpaperVisible() {
        for (int i = mWallpaperTokens.size() - 1; i >= 0; --i) {
            if (mWallpaperTokens.get(i).isVisible()) return true;
        }
        return false;
    }

    void hideWallpapers(final WindowState winGoingAway) {
        if (mWallpaperTarget != null && mWallpaperTarget != winGoingAway) {
            return;
        }
        if (mFindResults.useTopWallpaperAsTarget) {
            // wallpaper target is going away but there has request to use top wallpaper
            // Keep wallpaper visible.
            return;
        }
        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(i);
            if (token.isVisible()) {
                ProtoLog.d(WM_DEBUG_WALLPAPER,
                        "Hiding wallpaper %s from %s target=%s callers=%s",
                        token, winGoingAway, mWallpaperTarget,
                        Debug.getCallers(5));
            }
            token.setVisibility(false);
        }
    }

    boolean updateWallpaperOffset(WindowState wallpaperWin) {
        // Size of the display the wallpaper is rendered on.
        final Rect lastWallpaperBounds = wallpaperWin.getParentFrame();
        int screenWidth = lastWallpaperBounds.width();
        int screenHeight = lastWallpaperBounds.height();
        float screenRatio = (float) screenWidth / screenHeight;
        Point screenSize = new Point(screenWidth, screenHeight);
        Point bitmapSize = new Point(
                wallpaperWin.mRequestedWidth, wallpaperWin.mRequestedHeight);

        WallpaperWindowToken token = wallpaperWin.mToken.asWallpaperToken();

        /* Defines which sub-area of the wallpaper to show for a given screen orientation. */
        SparseArray<Rect> cropHints = token.getCropHints();

        /*
         * Sub-area of the wallpaper that may be used for the current screen size. May have more
         * width/height ratio than the screen for parallax.
         */
        final Rect wallpaperFrame = bitmapSize.x <= 0 || bitmapSize.y <= 0 ? wallpaperWin.getFrame()
                : WallpaperCropper.getCrop(screenSize, getOrCreateDefaultDisplayInfo(),
                        bitmapSize, cropHints, wallpaperWin.isRtl());
        int frameWidth = wallpaperFrame.width();
        int frameHeight = wallpaperFrame.height();
        float frameRatio = (float) frameWidth / frameHeight;

        // If the crop is proportionally wider/taller than the screen, scale it so that its
        // height/width matches the screen height/width, and use the additional width/height
        // for parallax (respectively).
        boolean scaleHeight = frameRatio >= screenRatio;

        /*
         * The values cropZoom, cropOffsetX and cropOffsetY are only used if multiCrop is true.
         * Zoom and offsets to be applied in order to show wallpaperFrame on screen.
         */
        final float cropZoom = wallpaperFrame.isEmpty() ? 1f : scaleHeight
                ? (float) screenHeight / frameHeight / wallpaperWin.mVScale
                : (float) screenWidth / frameWidth / wallpaperWin.mHScale;

        // The dimensions of the frame, without the additional width or height for parallax.
        float w = scaleHeight ? frameHeight * screenRatio : frameWidth;
        float h = scaleHeight ? frameHeight : frameWidth / screenRatio;

        // Note: a positive x/y offset shifts the wallpaper to the right/bottom respectively.
        final int cropOffsetX = -wallpaperFrame.left + (int) ((cropZoom - 1f) * w / 2f);
        final int cropOffsetY = -wallpaperFrame.top + (int) ((cropZoom - 1f) * h / 2f);

        /*
         * Difference of width/height between the wallpaper and the screen.
         * This is the additional room that we have to apply offsets (i.e. parallax).
         */
        final int diffWidth;
        final int diffHeight;

        /*
         * zoom, offsetX and offsetY are not related to cropping the wallpaper:
         *  - zoom is used to apply an additional zoom (e.g. for launcher animations).
         *  - offsetX, offsetY are used to apply an offset to the wallpaper (e.g. parallax effect).
         */
        final float zoom;
        int offsetX;
        int offsetY;

        // Available width or height for parallax
        diffWidth = (int) ((frameWidth - w) * wallpaperWin.mHScale);
        diffHeight = (int) ((frameHeight - h) * wallpaperWin.mVScale);

        boolean rawChanged = false;
        // Set the default wallpaper x-offset to either edge of the screen (depending on RTL), to
        // match the behavior of most Launchers
        float defaultWallpaperX = wallpaperWin.isRtl() ? 1f : 0f;
        // "Wallpaper X" is the previous x-offset of the wallpaper (in a 0 to 1 scale).
        // The 0 to 1 scale is because the "length" varies depending on how many home screens you
        // have, so 0 is the left of the first home screen, and 1 is the right of the last one (for
        // LTR, and the opposite for RTL).
        float wpx = token.mWallpaperX >= 0 ? token.mWallpaperX : defaultWallpaperX;
        // "Wallpaper X step size" is how much of that 0-1 is one "page" of the home screen
        // when scrolling.
        float wpxs = token.mWallpaperXStep >= 0 ? token.mWallpaperXStep : -1.0f;

        offsetX = diffWidth > 0 ? -(int) (diffWidth * wpx + .5f) : 0;
        if (token.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            // if device is LTR, then offset wallpaper to the left (the wallpaper is drawn
            // always starting from the left of the screen).
            offsetX += token.mWallpaperDisplayOffsetX;
        }
        offsetX += (int) (cropOffsetX * wallpaperWin.mHScale);

        if (wallpaperWin.mWallpaperX != wpx || wallpaperWin.mWallpaperXStep != wpxs) {
            wallpaperWin.mWallpaperX = wpx;
            wallpaperWin.mWallpaperXStep = wpxs;
            rawChanged = true;
        }

        float wpy = token.mWallpaperY >= 0 ? token.mWallpaperY : 0.5f;
        float wpys = token.mWallpaperYStep >= 0 ? token.mWallpaperYStep : -1.0f;
        offsetY = diffHeight > 0 ? -(int) (diffHeight * wpy + .5f) : 0;
        if (token.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            offsetY += token.mWallpaperDisplayOffsetY;
        }
        offsetY += (int) (cropOffsetY * wallpaperWin.mVScale);

        if (wallpaperWin.mWallpaperY != wpy || wallpaperWin.mWallpaperYStep != wpys) {
            wallpaperWin.mWallpaperY = wpy;
            wallpaperWin.mWallpaperYStep = wpys;
            rawChanged = true;
        }

        if (Float.compare(wallpaperWin.mWallpaperZoomOut, mLastWallpaperZoomOut) != 0) {
            wallpaperWin.mWallpaperZoomOut = mLastWallpaperZoomOut;
            rawChanged = true;
        }
        zoom = wallpaperWin.mShouldScaleWallpaper
                ? zoomOutToScale(wallpaperWin.mWallpaperZoomOut) : 1f;
        final float totalZoom = zoom * cropZoom;
        boolean changed = wallpaperWin.setWallpaperOffset(offsetX, offsetY, totalZoom);

        if (rawChanged && (wallpaperWin.mAttrs.privateFlags &
                WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS) != 0) {
            try {
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Report new wp offset "
                        + wallpaperWin + " x=" + wallpaperWin.mWallpaperX
                        + " y=" + wallpaperWin.mWallpaperY
                        + " zoom=" + wallpaperWin.mWallpaperZoomOut);
                wallpaperWin.mClient.dispatchWallpaperOffsets(
                        wallpaperWin.mWallpaperX, wallpaperWin.mWallpaperY,
                        wallpaperWin.mWallpaperXStep, wallpaperWin.mWallpaperYStep,
                        wallpaperWin.mWallpaperZoomOut);
            } catch (RemoteException e) {
                // Ignored
            }
        }

        return changed;
    }
    private WallpaperDefaultDisplayInfo getOrCreateDefaultDisplayInfo() {
        if (mDefaultDisplayInfo != null) {
            return mDefaultDisplayInfo;
        }
        WindowManager windowManager = mService.mContext.getSystemService(WindowManager.class);
        Resources resources = mService.mContext.getResources();
        mDefaultDisplayInfo = new WallpaperDefaultDisplayInfo(windowManager, resources);
        return mDefaultDisplayInfo;
    }

    void setWindowWallpaperPosition(
            WindowState window, float x, float y, float xStep, float yStep) {
        if (window.mWallpaperX != x || window.mWallpaperY != y)  {
            window.mWallpaperX = x;
            window.mWallpaperY = y;
            window.mWallpaperXStep = xStep;
            window.mWallpaperYStep = yStep;
            updateWallpaperOffsetLocked(window);
        }
    }

    void setWallpaperZoomOut(WindowState window, float zoom) {
        if (Math.abs(window.mWallpaperZoomOut - zoom) < WALLPAPER_ZOOM_EPSILON) {
            return;
        }
        final float oldMaxZoom = mLastWallpaperZoomOut;
        window.mWallpaperZoomOut = zoom;

        if (zoom > mLastWallpaperZoomOut + WALLPAPER_ZOOM_EPSILON) {
            mLastWallpaperZoomOut = zoom;
            mMaxZoomOutWindow = window;
        } else if (window == mMaxZoomOutWindow) {
            computeLastWallpaperZoomOut();
        }

        if (Math.abs(oldMaxZoom - mLastWallpaperZoomOut) >= WALLPAPER_ZOOM_EPSILON) {
            for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
                final WallpaperWindowToken token = mWallpaperTokens.get(i);
                token.updateWallpaperOffset();
            }
        }
    }

    void setShouldZoomOutWallpaper(WindowState window, boolean shouldZoom) {
        if (shouldZoom != window.mShouldScaleWallpaper) {
            window.mShouldScaleWallpaper = shouldZoom;
            updateWallpaperOffsetLocked(window);
        }
    }

    void setWindowWallpaperDisplayOffset(WindowState window, int x, int y) {
        if (window.mWallpaperDisplayOffsetX != x || window.mWallpaperDisplayOffsetY != y)  {
            window.mWallpaperDisplayOffsetX = x;
            window.mWallpaperDisplayOffsetY = y;
            updateWallpaperOffsetLocked(window);
        }
    }

    void sendWindowWallpaperCommandUnchecked(
            WindowState window, String action, int x, int y, int z, Bundle extras) {
        sendWindowWallpaperCommand(action, x, y, z, extras);
    }

    private void sendWindowWallpaperCommand(
                String action, int x, int y, int z, Bundle extras) {
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            token.sendWindowWallpaperCommand(action, x, y, z, extras);
        }
    }

    private void updateWallpaperOffsetLocked(WindowState changingTarget) {
        WindowState target = mWallpaperTarget;
        if (target == null && changingTarget.mToken.isVisible()
                && changingTarget.mTransitionController.inTransition()) {
            // If the wallpaper target was cleared during transition, still allows the visible
            // window which may have been requested to be invisible to update the offset, e.g.
            // zoom effect from home.
            target = changingTarget;
        }

        WallpaperWindowToken token = getTokenForTarget(target);
        if (token == null) return;

        if (target.mWallpaperX >= 0) {
            token.mWallpaperX = target.mWallpaperX;
        } else if (changingTarget.mWallpaperX >= 0) {
            token.mWallpaperX = changingTarget.mWallpaperX;
        }
        if (target.mWallpaperY >= 0) {
            token.mWallpaperY = target.mWallpaperY;
        } else if (changingTarget.mWallpaperY >= 0) {
            token.mWallpaperY = changingTarget.mWallpaperY;
        }
        if (target.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetX = target.mWallpaperDisplayOffsetX;
        } else if (changingTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetX = changingTarget.mWallpaperDisplayOffsetX;
        }
        if (target.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetY = target.mWallpaperDisplayOffsetY;
        } else if (changingTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetY = changingTarget.mWallpaperDisplayOffsetY;
        }
        if (target.mWallpaperXStep >= 0) {
            token.mWallpaperXStep = target.mWallpaperXStep;
        } else if (changingTarget.mWallpaperXStep >= 0) {
            token.mWallpaperXStep = changingTarget.mWallpaperXStep;
        }
        if (target.mWallpaperYStep >= 0) {
            token.mWallpaperYStep = target.mWallpaperYStep;
        } else if (changingTarget.mWallpaperYStep >= 0) {
            token.mWallpaperYStep = changingTarget.mWallpaperYStep;
        }
        token.updateWallpaperOffset();
    }

    private WallpaperWindowToken getTokenForTarget(WindowState target) {
        if (target == null) return null;
        WindowState window = mFindResults.getTopWallpaper(
                (target.canShowWhenLocked() && mService.isKeyguardLocked())
                        || (Flags.aodTransition() && mDisplayContent.isAodShowing()));
        return window == null ? null : window.mToken.asWallpaperToken();
    }

    void clearLastWallpaperTimeoutTime() {
        mLastWallpaperTimeoutTime = 0;
    }

    private void findWallpaperTarget() {
        mFindResults.reset();
        findWallpapers();
        mDisplayContent.forAllWindows(mFindWallpaperTargetFunction, true /* traverseTopToBottom */);
        if (mFindResults.mNeedsShowWhenLockedWallpaper) {
            // Keep wallpaper visible if the show-when-locked activities doesn't fill screen.
            mFindResults.setUseTopWallpaperAsTarget(true);
        }

        if (mFindResults.wallpaperTarget == null && mFindResults.useTopWallpaperAsTarget) {
            mFindResults.setWallpaperTarget(
                    mFindResults.getTopWallpaper(Flags.aodTransition()
                            ? mDisplayContent.isKeyguardLockedOrAodShowing()
                            : mDisplayContent.isKeyguardLocked()));
        }
    }

    private void findWallpapers() {
        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(i);
            final boolean canShowWhenLocked = token.canShowWhenLocked();
            for (int j = token.getChildCount() - 1; j >= 0; j--) {
                final WindowState w = token.getChildAt(j);
                if (!w.mIsWallpaper) continue;
                if (canShowWhenLocked && !mFindResults.hasTopShowWhenLockedWallpaper()) {
                    mFindResults.setTopShowWhenLockedWallpaper(w);
                } else if (!canShowWhenLocked && !mFindResults.hasTopHideWhenLockedWallpaper()) {
                    mFindResults.setTopHideWhenLockedWallpaper(w);
                }
            }
        }
    }

    void collectTopWallpapers(Transition transition) {
        if (mFindResults.hasTopShowWhenLockedWallpaper()) {
            transition.collect(mFindResults.mTopWallpaper.mTopShowWhenLockedWallpaper.mToken);

        }
        if (mFindResults.hasTopHideWhenLockedWallpaper()) {
            transition.collect(mFindResults.mTopWallpaper.mTopHideWhenLockedWallpaper.mToken);
        }
    }

    private boolean isFullscreen(WindowManager.LayoutParams attrs) {
        return attrs.x == 0 && attrs.y == 0
                && attrs.width == MATCH_PARENT && attrs.height == MATCH_PARENT;
    }

    /** Updates the target wallpaper if needed and returns true if an update happened. */
    private void updateWallpaperWindowsTarget(FindWallpaperTargetResult result) {
        WindowState wallpaperTarget = result.wallpaperTarget;

        if (mWallpaperTarget == wallpaperTarget) {
            return;
        }

        ProtoLog.v(WM_DEBUG_WALLPAPER, "New wallpaper target: %s prevTarget: %s caller=%s",
                wallpaperTarget, mWallpaperTarget, Debug.getCallers(5));

        final WindowState prevWallpaperTarget = mWallpaperTarget;
        mWallpaperTarget = wallpaperTarget;

        if (prevWallpaperTarget == null && wallpaperTarget != null) {
            updateWallpaperOffsetLocked(mWallpaperTarget);
        }
    }

    /**
     * Change the visibility of the top wallpaper to {@code visibility} and hide all the others.
     */
    private void updateWallpaperTokens(boolean visibility, boolean keyguardLocked) {
        ProtoLog.v(WM_DEBUG_WALLPAPER, "updateWallpaperTokens requestedVisibility=%b on"
                + " keyguardLocked=%b", visibility, keyguardLocked);
        WindowState topWallpaper = mFindResults.getTopWallpaper(keyguardLocked);
        WallpaperWindowToken topWallpaperToken =
                topWallpaper == null ? null : topWallpaper.mToken.asWallpaperToken();
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            token.updateWallpaperWindows(visibility && (token == topWallpaperToken));
        }
    }

    void adjustWallpaperWindows() {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "adjustWallpaperWindows");
        // First find top-most window that has asked to be on top of the wallpaper;
        // all wallpapers go behind it.
        findWallpaperTarget();
        updateWallpaperWindowsTarget(mFindResults);
        WallpaperWindowToken token = getTokenForTarget(mWallpaperTarget);

        // The window is visible to the compositor...but is it visible to the user?
        // That is what the wallpaper cares about.
        final boolean visible = token != null;

        if (visible) {
            if (mWallpaperTarget.mWallpaperX >= 0) {
                token.mWallpaperX = mWallpaperTarget.mWallpaperX;
                token.mWallpaperXStep = mWallpaperTarget.mWallpaperXStep;
            }
            if (mWallpaperTarget.mWallpaperY >= 0) {
                token.mWallpaperY = mWallpaperTarget.mWallpaperY;
                token.mWallpaperYStep = mWallpaperTarget.mWallpaperYStep;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                token.mWallpaperDisplayOffsetX = mWallpaperTarget.mWallpaperDisplayOffsetX;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                token.mWallpaperDisplayOffsetY = mWallpaperTarget.mWallpaperDisplayOffsetY;
            }
        }

        final boolean visibleRequested =
                (mWallpaperTarget != null && mWallpaperTarget.isVisibleRequested())
                || (mDisplayContent.mTransitionController.inTransition() && isWallpaperVisible());
        updateWallpaperTokens(visibleRequested,
                Flags.aodTransition()
                        ? mDisplayContent.isKeyguardLockedOrAodShowing()
                        : mDisplayContent.isKeyguardLocked());

        ProtoLog.v(WM_DEBUG_WALLPAPER,
                "Wallpaper at display %d - visibility: %b, keyguardLocked: %b",
                mDisplayContent.getDisplayId(), visible,
                Flags.aodTransition()
                        ? mDisplayContent.isKeyguardLockedOrAodShowing()
                        : mDisplayContent.isKeyguardLocked());

        if (visible && mLastFrozen != mFindResults.isWallpaperTargetForLetterbox) {
            mLastFrozen = mFindResults.isWallpaperTargetForLetterbox;
            sendWindowWallpaperCommand(
                    mFindResults.isWallpaperTargetForLetterbox ? COMMAND_FREEZE : COMMAND_UNFREEZE,
                    /* x= */ 0, /* y= */ 0, /* z= */ 0, /* extras= */ null);
        }

        ProtoLog.d(WM_DEBUG_WALLPAPER, "Wallpaper target=%s", mWallpaperTarget);
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    boolean processWallpaperDrawPendingTimeout() {
        if (mWallpaperDrawState == WALLPAPER_DRAW_PENDING) {
            mWallpaperDrawState = WALLPAPER_DRAW_TIMEOUT;
            if (DEBUG_WALLPAPER) {
                Slog.v(TAG, "*** WALLPAPER DRAW TIMEOUT");
            }

            // If there was a pending back navigation animation that would show wallpaper, start
            // the animation due to it was skipped in previous surface placement.
            mService.mAtmService.mBackNavigationController.startAnimation();
            return true;
        }
        return false;
    }

    boolean wallpaperTransitionReady() {
        boolean transitionReady = true;
        boolean wallpaperReady = true;
        for (int curTokenIndex = mWallpaperTokens.size() - 1;
                curTokenIndex >= 0 && wallpaperReady; curTokenIndex--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenIndex);
            if (token.hasVisibleNotDrawnWallpaper()) {
                // We've told this wallpaper to be visible, but it is not drawn yet
                wallpaperReady = false;
                if (mWallpaperDrawState != WALLPAPER_DRAW_TIMEOUT) {
                    // wait for this wallpaper until it is drawn or timeout
                    transitionReady = false;
                }
                if (mWallpaperDrawState == WALLPAPER_DRAW_NORMAL) {
                    mWallpaperDrawState = WALLPAPER_DRAW_PENDING;
                    mService.mH.removeMessages(WALLPAPER_DRAW_PENDING_TIMEOUT, this);
                    mService.mH.sendMessageDelayed(
                                mService.mH.obtainMessage(WALLPAPER_DRAW_PENDING_TIMEOUT, this),
                                WALLPAPER_DRAW_PENDING_TIMEOUT_DURATION);

                }
                ProtoLog.v(WM_DEBUG_WALLPAPER,
                        "Wallpaper should be visible but has not been drawn yet. "
                                + "mWallpaperDrawState=%d", mWallpaperDrawState);
                break;
            }
        }
        if (wallpaperReady) {
            mWallpaperDrawState = WALLPAPER_DRAW_NORMAL;
            mService.mH.removeMessages(WALLPAPER_DRAW_PENDING_TIMEOUT, this);
        }

        return transitionReady;
    }

    void addWallpaperToken(WallpaperWindowToken token) {
        mWallpaperTokens.add(token);
    }

    void removeWallpaperToken(WallpaperWindowToken token) {
        mWallpaperTokens.remove(token);
    }

    void onWallpaperTokenReordered() {
        if (mWallpaperTokens.size() > 1) {
            mWallpaperTokens.sort(null /* by WindowContainer#compareTo */);
        }
    }

    @VisibleForTesting
    boolean canScreenshotWallpaper() {
        return canScreenshotWallpaper(getTopVisibleWallpaper());
    }

    private boolean canScreenshotWallpaper(WindowState wallpaperWindowState) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return false;
        }

        if (wallpaperWindowState == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "No visible wallpaper to screenshot");
            }
            return false;
        }
        return true;
    }

    /**
     * Take a screenshot of the wallpaper if it's visible.
     *
     * @return Bitmap of the wallpaper
     */
    Bitmap screenshotWallpaperLocked() {
        final WindowState wallpaperWindowState = getTopVisibleWallpaper();
        if (!canScreenshotWallpaper(wallpaperWindowState)) {
            return null;
        }

        final Rect bounds = wallpaperWindowState.getBounds();
        bounds.offsetTo(0, 0);

        ScreenCaptureInternal.ScreenshotHardwareBuffer wallpaperBuffer =
                ScreenCaptureInternal.captureLayers(
                        wallpaperWindowState.getSurfaceControl(), bounds, 1 /* frameScale */);

        if (wallpaperBuffer == null) {
            Slog.w(TAG_WM, "Failed to screenshot wallpaper");
            return null;
        }
        return Bitmap.wrapHardwareBuffer(
                wallpaperBuffer.getHardwareBuffer(), wallpaperBuffer.getColorSpace());
    }

    /**
     * Mirrors the visible wallpaper if it's available.
     * <p>
     * We mirror at the WallpaperWindowToken level because scale and translation is applied at
     * the WindowState level and mirroring the WindowState's SurfaceControl will remove any local
     * scale and translation.
     *
     * @return A SurfaceControl for the parent of the mirrored wallpaper.
     */
    SurfaceControl mirrorWallpaperSurface() {
        final WindowState wallpaperWindowState = getTopVisibleWallpaper();
        final SurfaceControl wallpaperSurfaceControl = wallpaperWindowState != null
            ? wallpaperWindowState.mToken.getSurfaceControl()
            : null;
        return wallpaperSurfaceControl != null
                ? SurfaceControl.mirrorSurface(wallpaperSurfaceControl)
                : null;
    }

    WindowState getTopVisibleWallpaper() {
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            for (int i = token.getChildCount() - 1; i >= 0; i--) {
                final WindowState w = token.getChildAt(i);
                if (w.mWinAnimator.getShown() && w.mWinAnimator.mLastAlpha > 0f) {
                    return w;
                }
            }
        }
        return null;
    }

    /**
     * Each window can request a zoom, example:
     * - User is in overview, zoomed out.
     * - User also pulls down the shade.
     *
     * This means that we always have to choose the largest zoom out that we have, otherwise
     * we'll have conflicts and break the "depth system" mental model.
     */
    private void computeLastWallpaperZoomOut() {
        mLastWallpaperZoomOut = 0;
        mMaxZoomOutWindow = null;
        mDisplayContent.forAllWindows(mComputeMaxZoomOutFunction, true);
    }


    private float zoomOutToScale(float zoomOut) {
        return MathUtils.lerp(mMinWallpaperScale, mMaxWallpaperScale, 1 - zoomOut);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("displayId="); pw.println(mDisplayContent.getDisplayId());
        pw.print(prefix); pw.print("mWallpaperTarget="); pw.println(mWallpaperTarget);
        pw.print(prefix); pw.print("mLastWallpaperZoomOut="); pw.println(mLastWallpaperZoomOut);

        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken t = mWallpaperTokens.get(i);
            pw.print(prefix); pw.println("token " + t + ":");
            dumpValue(pw, prefix, "mWallpaperX", t.mWallpaperX);
            dumpValue(pw, prefix, "mWallpaperY", t.mWallpaperY);
            dumpValue(pw, prefix, "mWallpaperXStep", t.mWallpaperXStep);
            dumpValue(pw, prefix, "mWallpaperYStep", t.mWallpaperYStep);
            dumpValue(pw, prefix, "mWallpaperDisplayOffsetX", t.mWallpaperDisplayOffsetX);
            dumpValue(pw, prefix, "mWallpaperDisplayOffsetY", t.mWallpaperDisplayOffsetY);
        }
    }

    private void dumpValue(PrintWriter pw, String prefix, String valueName, float value) {
        pw.print(prefix); pw.print("  " + valueName + "=");
        pw.println(value >= 0 ? value : "NA");
    }

    /** Helper class for storing the results of a wallpaper target find operation. */
    final private static class FindWallpaperTargetResult {

        static final class TopWallpaper {
            // A wp that can be visible on home screen only
            WindowState mTopHideWhenLockedWallpaper;
            // A wallpaper that has permission to be visible on lock screen (lock or shared wp)
            WindowState mTopShowWhenLockedWallpaper;

            void reset() {
                mTopHideWhenLockedWallpaper = null;
                mTopShowWhenLockedWallpaper = null;
            }
        }

        TopWallpaper mTopWallpaper = new TopWallpaper();
        boolean mNeedsShowWhenLockedWallpaper;
        boolean useTopWallpaperAsTarget;
        WindowState wallpaperTarget;
        boolean isWallpaperTargetForLetterbox;
        boolean hasInvisibleRequestedTarget;

        void setTopHideWhenLockedWallpaper(WindowState win) {
            if (mTopWallpaper.mTopHideWhenLockedWallpaper != win) {
                ProtoLog.d(WM_DEBUG_WALLPAPER, "New home screen wallpaper: %s, prev: %s",
                        win, mTopWallpaper.mTopHideWhenLockedWallpaper);
            }
            mTopWallpaper.mTopHideWhenLockedWallpaper = win;
        }

        void setTopShowWhenLockedWallpaper(WindowState win) {
            if (mTopWallpaper.mTopShowWhenLockedWallpaper != win) {
                ProtoLog.d(WM_DEBUG_WALLPAPER, "New lock/shared screen wallpaper: %s, prev: %s",
                        win, mTopWallpaper.mTopShowWhenLockedWallpaper);
            }
            mTopWallpaper.mTopShowWhenLockedWallpaper = win;
        }

        boolean hasTopHideWhenLockedWallpaper() {
            return mTopWallpaper.mTopHideWhenLockedWallpaper != null;
        }

        boolean hasTopShowWhenLockedWallpaper() {
            return mTopWallpaper.mTopShowWhenLockedWallpaper != null;
        }

        WindowState getTopWallpaper(boolean isKeyguardLocked) {
            if (!isKeyguardLocked && hasTopHideWhenLockedWallpaper()) {
                return mTopWallpaper.mTopHideWhenLockedWallpaper;
            } else {
                return mTopWallpaper.mTopShowWhenLockedWallpaper;
            }
        }

        void setWallpaperTarget(WindowState win) {
            wallpaperTarget = win;
        }

        void setUseTopWallpaperAsTarget(boolean topWallpaperAsTarget) {
            useTopWallpaperAsTarget = topWallpaperAsTarget;
        }

        void setIsWallpaperTargetForLetterbox(boolean isWallpaperTargetForLetterbox) {
            this.isWallpaperTargetForLetterbox = isWallpaperTargetForLetterbox;
        }

        void reset() {
            mTopWallpaper.reset();
            mNeedsShowWhenLockedWallpaper = false;
            wallpaperTarget = null;
            useTopWallpaperAsTarget = false;
            isWallpaperTargetForLetterbox = false;
            hasInvisibleRequestedTarget = false;
        }
    }
}
