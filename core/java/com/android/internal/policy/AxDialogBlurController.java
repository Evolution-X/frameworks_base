/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.policy;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.function.Consumer;

/** @hide */
public final class AxDialogBlurController {
    private static final String BLUR_RADIUS = "system_blur_radius";
    private static final float MAX_BLUR_RADIUS_DP = 34f;
    private static final int SURFACE_ALPHA = 0x8A;

    private final Context mContext;
    private final Window mWindow;
    private final View mRootView;
    private final WindowManager.LayoutParams mToastParams;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final WindowManager mWindowManager;
    private final ArrayMap<View, BackgroundRenderer> mBackgrounds = new ArrayMap<>();
    private final ArraySet<BackgroundRenderer> mActiveRenderers = new ArraySet<>();
    private final ContentObserver mSettingsObserver;
    private final Consumer<Boolean> mBlurListener;
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        updateBackgrounds();
        return true;
    };
    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    start();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    stop();
                }
            };
    private boolean mStarted;
    private boolean mCrossWindowBlurEnabled;
    private int mRadius;
    private int mGeneration;

    public static AxDialogBlurController create(Window window, DecorView decor, boolean isDialog) {
        if ((!isDialog && !window.isFloating()
                && !(window.getCallback() instanceof AlertActivity))
                || !isSupported(window.getContext())) {
            return null;
        }
        return new AxDialogBlurController(window.getContext(), decor, window, null,
                window.getWindowManager());
    }

    public static AxDialogBlurController createToast(Context context, View view,
            WindowManager windowManager, WindowManager.LayoutParams params) {
        if (params.type != WindowManager.LayoutParams.TYPE_TOAST || !isSupported(context)) {
            return null;
        }
        return new AxDialogBlurController(context, view, null, params, windowManager);
    }

    private static boolean isSupported(Context context) {
        final ApplicationInfo app = context.getApplicationInfo();
        return (app.isSystemApp() || app.isUpdatedSystemApp())
                && CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED
                && ActivityManager.isHighEndGfx()
                && !SystemProperties.getBoolean("persist.sysui.disableBlur", false);
    }

    private AxDialogBlurController(Context context, View rootView, Window window,
            WindowManager.LayoutParams toastParams, WindowManager windowManager) {
        mContext = context;
        mWindow = window;
        mRootView = rootView;
        mToastParams = toastParams;
        mResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = windowManager;
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateSettings();
            }
        };
        mBlurListener = enabled -> {
            mCrossWindowBlurEnabled = enabled;
            updateSettings();
        };
    }

    public void prepare() {
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        readSettings();
        updateBackgrounds();
    }

    public void attachToView() {
        mRootView.addOnAttachStateChangeListener(mAttachListener);
        prepare();
        if (mRootView.isAttachedToWindow()) {
            start();
        }
    }

    public void start() {
        if (mStarted) return;
        mStarted = true;
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS), false,
                mSettingsObserver);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(BLUR_RADIUS), false,
                mSettingsObserver);
        mWindowManager.addCrossWindowBlurEnabledListener(mHandler::post, mBlurListener);
        mRootView.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        updateSettings();
    }

    public void stop() {
        mRootView.removeOnAttachStateChangeListener(mAttachListener);
        if (mStarted) {
            mStarted = false;
            mResolver.unregisterContentObserver(mSettingsObserver);
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
            if (mRootView.getViewTreeObserver().isAlive()) {
                mRootView.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            }
        }
        mRadius = 0;
        updateBackgrounds();
        for (int i = mActiveRenderers.size() - 1; i >= 0; i--) {
            final BackgroundRenderer renderer = mActiveRenderers.valueAt(i);
            renderer.clear();
            renderer.mView.invalidate();
        }
    }

    boolean hasBlurBackgrounds() {
        return !mBackgrounds.isEmpty();
    }

    private void updateSettings() {
        if (!mStarted) return;
        readSettings();
        updateBackgrounds();
        for (int i = 0; i < mActiveRenderers.size(); i++) {
            final BackgroundRenderer renderer = mActiveRenderers.valueAt(i);
            renderer.mBlur.setBlurRadius(mRadius);
            renderer.mView.invalidate();
        }
        for (int i = 0; i < mBackgrounds.size(); i++) {
            mBackgrounds.keyAt(i).invalidate();
        }
        mRootView.invalidate();
    }

    private void readSettings() {
        final int defaultDisabled =
                SystemProperties.getBoolean("ro.custom.blur.enable", true) ? 0 : 1;
        final boolean enabled = mCrossWindowBlurEnabled
                && Settings.Global.getInt(mResolver, Settings.Global.DISABLE_WINDOW_BLURS,
                        defaultDisabled) == 0;
        final float maxRadius = MAX_BLUR_RADIUS_DP
                * mContext.getResources().getDisplayMetrics().density;
        final float radius = Settings.Secure.getFloat(mResolver, BLUR_RADIUS, maxRadius);
        mRadius = enabled ? Math.round(Float.isFinite(radius)
                ? Math.max(0f, Math.min(radius, maxRadius)) : maxRadius) : 0;
    }

    private void updateBackgrounds() {
        final boolean hadBackgrounds = hasBlurBackgrounds();
        mGeneration++;
        if (mRadius > 0) {
            addBackgrounds(mRootView);
        }
        for (int i = mBackgrounds.size() - 1; i >= 0; i--) {
            final BackgroundRenderer renderer = mBackgrounds.valueAt(i);
            if (renderer.mGeneration != mGeneration) {
                final View view = mBackgrounds.keyAt(i);
                if (view.getAxDialogBlurRenderer() == renderer) {
                    view.setAxDialogBlurRenderer(null);
                } else {
                    renderer.clear();
                }
                mBackgrounds.removeAt(i);
            }
        }
        if (mWindow != null && hadBackgrounds != hasBlurBackgrounds()) {
            ((DecorView) mRootView).updateSystemDialogBlurOpacity();
        }
    }

    private void addBackgrounds(View view) {
        if (Boolean.TRUE.equals(view.getTag(R.id.ax_dialog_blur_owned))) return;
        final Drawable background = view.getBackground();
        final boolean fullScreenDecor = mWindow != null && view == mRootView
                && mWindow.getAttributes().width == ViewGroup.LayoutParams.MATCH_PARENT
                && mWindow.getAttributes().height == ViewGroup.LayoutParams.MATCH_PARENT;
        BackgroundRenderer renderer = mBackgrounds.get(view);
        if (!fullScreenDecor && background != null) {
            if (renderer != null && renderer.mBackground == background
                    && view.getAxDialogBlurRenderer() == renderer) {
                renderer.mGeneration = mGeneration;
                return;
            }
            if (background.getOpacity() != PixelFormat.TRANSPARENT) {
                if (surfaceDrawable(background) != null) {
                    renderer = new BackgroundRenderer(view, background);
                    mBackgrounds.put(view, renderer);
                    view.setAxDialogBlurRenderer(renderer);
                    renderer.mGeneration = mGeneration;
                }
                return;
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View child = group.getChildAt(i);
                if (child instanceof ViewGroup) {
                    addBackgrounds(child);
                }
            }
        }
    }

    private static Drawable surfaceDrawable(Drawable drawable) {
        if (drawable == null || drawable instanceof BackgroundBlurDrawable) return null;
        if (drawable.getCurrent() != drawable) {
            return surfaceDrawable(drawable.getCurrent());
        }
        if (drawable instanceof DrawableWrapper) {
            return surfaceDrawable(((DrawableWrapper) drawable).getDrawable());
        }
        if (drawable instanceof LayerDrawable) {
            final LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (layers.getDrawable(i) instanceof BackgroundBlurDrawable) return null;
            }
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                final Drawable surface = surfaceDrawable(layers.getDrawable(i));
                if (surface != null && surface.getOpacity() != PixelFormat.TRANSPARENT) {
                    return surface;
                }
            }
            return null;
        }
        if (drawable instanceof ColorDrawable) return drawable;
        if (drawable instanceof GradientDrawable) {
            final GradientDrawable gradient = (GradientDrawable) drawable;
            return gradient.getShape() == GradientDrawable.RECTANGLE ? gradient : null;
        }
        return null;
    }

    public final class BackgroundRenderer {
        private final View mView;
        private final Drawable mBackground;
        private BackgroundBlurDrawable mBlur;
        private ViewRootImpl mBlurRoot;
        private int mGeneration;

        private BackgroundRenderer(View view, Drawable background) {
            mView = view;
            mBackground = background;
        }

        public BackgroundRenderer copyForView(View view) {
            return new BackgroundRenderer(view, mBackground);
        }

        public void draw(Canvas canvas, Drawable background) {
            final ViewRootImpl root = mView.getViewRootImpl();
            if (mRadius <= 0 || !canvas.isHardwareAccelerated() || root == null
                    || background.getAlpha() == 0
                    || Boolean.TRUE.equals(mView.getTag(R.id.ax_dialog_blur_owned))) {
                clear();
                background.draw(canvas);
                return;
            }
            final Drawable surface = surfaceDrawable(background);
            if (surface == null || surface.getBounds().isEmpty()) {
                clear();
                background.draw(canvas);
                return;
            }
            if (mBlurRoot != root) {
                clear();
            }
            if (mBlur == null) {
                mBlurRoot = root;
                mBlur = root.createBackgroundBlurDrawable();
                mBlur.setColor(Color.TRANSPARENT);
                mActiveRenderers.add(this);
            }
            mBlur.setBounds(surface.getBounds());
            mBlur.setBlurRadius(mRadius);
            final float windowAlpha =
                    mWindow != null ? mWindow.getAttributes().alpha : mToastParams.alpha;
            final float viewAlpha = mView.getAlpha();
            mBlur.setAlpha(Float.isFinite(windowAlpha) && Float.isFinite(viewAlpha)
                    ? Math.round(background.getAlpha()
                            * Math.max(0f, Math.min(1f, windowAlpha))
                            * Math.max(0f, Math.min(1f, viewAlpha))) : 0);
            if (surface instanceof GradientDrawable) {
                final GradientDrawable gradient = (GradientDrawable) surface;
                final float[] radii = gradient.getCornerRadii();
                if (radii != null && radii.length >= 8) {
                    mBlur.setCornerRadius((radii[0] + radii[1]) * 0.5f,
                            (radii[2] + radii[3]) * 0.5f,
                            (radii[6] + radii[7]) * 0.5f,
                            (radii[4] + radii[5]) * 0.5f);
                } else {
                    mBlur.setCornerRadius(gradient.getCornerRadius());
                }
            } else {
                mBlur.setCornerRadius(0f);
            }
            mBlur.draw(canvas);
            final Rect bounds = background.getBounds();
            final int save = canvas.saveLayerAlpha(bounds.left, bounds.top,
                    bounds.right, bounds.bottom, SURFACE_ALPHA);
            try {
                background.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        public void clear() {
            if (mBlur != null) {
                mBlur.setBlurRadius(0);
                mBlur = null;
            }
            mBlurRoot = null;
            mActiveRenderers.remove(this);
        }
    }
}
