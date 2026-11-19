/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution.glass;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;

/**
 *
 * @hide
 */
public final class LiquidTabBars {
    private static final String TAG = "LiquidTabBars";

    public static final String SETTING_ENABLED = "liquid_glass_tab_bars";
    public static final String SETTING_EXCLUDED = "liquid_glass_tab_bars_excluded";
    public static final String SETTING_FLOAT = "liquid_glass_tab_bars_float";

    private static final String[] BAR_CLASSES = {
            "BottomNavigationView", "NavigationBarView", "PivotBar", "BottomNavigation",
            "BottomBar", "TabBar",
    };
    private static final String[] SCROLLER_CLASSES = {
            "RecyclerView", "ListView", "ScrollView", "GridView",
    };
    private static final long SCAN_INTERVAL_MS = 500;

    private final boolean mFloat;
    private final float mDensity;
    private final List<LiquidTabBar> mBars = new ArrayList<>();
    private ComposeTabBar mComposeBar;
    private long mNextComposeScan;
    private int mComposeMisses;
    private final WeakHashMap<View, Boolean> mPadded = new WeakHashMap<>();
    private long mLastScan;
    private boolean mScanPending;

    private LiquidTabBars(Context context, boolean floatOverContent) {
        mFloat = floatOverContent;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    public static LiquidTabBars create(Context context) {
        if (Process.myUid() < Process.FIRST_APPLICATION_UID) return null;
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), SETTING_ENABLED, 0) == 0) {
                return null;
            }
            String pkg = context.getPackageName();
            if (listed(context, SETTING_EXCLUDED, pkg)) return null;
            return new LiquidTabBars(context, listed(context, SETTING_FLOAT, pkg));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean listed(Context context, String key, String pkg) {
        String list = Settings.Secure.getString(context.getContentResolver(), key);
        return list != null && Arrays.asList(list.split(",")).contains(pkg);
    }

    public void onLayout(View root) {
        boolean attached = false;
        for (int i = mBars.size() - 1; i >= 0; i--) {
            LiquidTabBar bar = mBars.get(i);
            if (bar.mBar.isAttachedToWindow()) {
                attached = true;
            } else {
                bar.remove();
                mBars.remove(i);
            }
        }
        if (mComposeBar != null) {
            if (mComposeBar.mView.isAttachedToWindow() && mComposeBar.update()) {
                attached = true;
            } else {
                mComposeBar.remove();
                mComposeBar = null;
            }
        }
        if (attached || !(root instanceof ViewGroup)) return;
        long now = SystemClock.uptimeMillis();
        long wait = SCAN_INTERVAL_MS - (now - mLastScan);
        if (wait > 0) {
            if (!mScanPending) {
                mScanPending = true;
                root.postDelayed(() -> {
                    mScanPending = false;
                    if (root.isAttachedToWindow()) onLayout(root);
                }, wait);
            }
            return;
        }
        mLastScan = now;
        ViewGroup found = find((ViewGroup) root, root.getWidth(), root.getHeight());
        if (found == null) {
            if (now >= mNextComposeScan) {
                findCompose((ViewGroup) root, root.getHeight());
                if (mComposeBar == null) {
                    mComposeMisses = Math.min(mComposeMisses + 1, 4);
                    mNextComposeScan = now + (SCAN_INTERVAL_MS << mComposeMisses);
                } else {
                    mComposeMisses = 0;
                }
            }
            if (mComposeBar == null && !mScanPending) {
                mScanPending = true;
                root.postDelayed(() -> {
                    mScanPending = false;
                    if (root.isAttachedToWindow()) onLayout(root);
                }, Math.max(mNextComposeScan - now, SCAN_INTERVAL_MS));
            }
            return;
        }
        try {
            mBars.add(new LiquidTabBar(found));
            if (mFloat) floatOverContent(found);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not restyle " + found.getClass().getName(), e);
        }
    }

    public void onTouch(MotionEvent ev) {
        for (int i = 0; i < mBars.size(); i++) {
            LiquidTabBar bar = mBars.get(i);
            View view = bar.mBar;
            if (!view.isAttachedToWindow() || !view.isShown()) continue;
            view.getLocationInWindow(mLocation);
            MotionEvent local = MotionEvent.obtain(ev);
            local.offsetLocation(-mLocation[0], -mLocation[1]);
            float sx = view.getScaleX();
            float sy = view.getScaleY();
            if (sx != 1f || sy != 1f) {
                local.setLocation(local.getX() / sx, local.getY() / sy);
            }
            bar.onObserveTouch(local);
            local.recycle();
        }
    }

    private final int[] mLocation = new int[2];

    private void findCompose(ViewGroup group, int rootHeight) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
            child.getLocationInWindow(mLocation);
            if (mLocation[1] + child.getHeight() < rootHeight - 4) continue;
            if (child.getAccessibilityNodeProvider() != null) {
                try {
                    mComposeBar = ComposeTabBar.find(child);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not look for a Compose tab bar", e);
                }
                if (mComposeBar != null) {
                    return;
                }
            }
            if (child instanceof ViewGroup g) {
                findCompose(g, rootHeight);
                if (mComposeBar != null) return;
            }
        }
    }

    private ViewGroup find(ViewGroup group, int rootWidth, int rootHeight) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !(child instanceof ViewGroup g)) continue;
            if (isBar(g, rootWidth, rootHeight)) return g;
            ViewGroup found = find(g, rootWidth, rootHeight);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isBar(ViewGroup v, int rootWidth, int rootHeight) {
        int h = v.getHeight();
        if (v.getWidth() < rootWidth * 0.9f || h < 40 * mDensity || h > 160 * mDensity) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        if (loc[1] + h < rootHeight - 4) return false;
        return matches(v.getClass(), BAR_CLASSES);
    }

    private static boolean matches(Class<?> cls, String[] names) {
        for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            for (String n : names) {
                if (name.contains(n)) return true;
            }
        }
        return false;
    }

    private void floatOverContent(ViewGroup bar) {
        ViewParent p = bar.getParent();
        if (!(p instanceof ViewGroup parent)) return;
        int barHeight = bar.getHeight();
        View content = null;
        if (parent instanceof LinearLayout ll && ll.getOrientation() == LinearLayout.VERTICAL) {
            int index = parent.indexOfChild(bar);
            if (index > 0 && bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
                lp.topMargin -= barHeight;
                bar.setLayoutParams(lp);
                content = parent.getChildAt(index - 1);
            }
        } else {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child == bar || Math.abs(child.getBottom() - bar.getTop()) > 2) continue;
                if (extendToBottom(child, bar, barHeight)) {
                    content = child;
                    break;
                }
            }
        }
        if (content != null) padScrollers(content, barHeight);
    }

    private static boolean extendToBottom(View child, View bar, int barHeight) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp instanceof RelativeLayout.LayoutParams rl
                && rl.getRule(RelativeLayout.ABOVE) == bar.getId()) {
            rl.removeRule(RelativeLayout.ABOVE);
            rl.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            child.setLayoutParams(rl);
            return true;
        }
        if (setConstraint(lp, bar.getId())) {
            child.setLayoutParams(lp);
            return true;
        }
        if (lp instanceof ViewGroup.MarginLayoutParams m && m.bottomMargin >= barHeight - 2) {
            m.bottomMargin -= barHeight;
            child.setLayoutParams(m);
            return true;
        }
        return false;
    }

    private static boolean setConstraint(ViewGroup.LayoutParams lp, int barId) {
        try {
            Class<?> cls = lp.getClass();
            Field toTop = cls.getField("bottomToTop");
            if (barId == View.NO_ID || toTop.getInt(lp) != barId) return false;
            toTop.setInt(lp, -1);
            cls.getField("bottomToBottom").setInt(lp, 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private void padScrollers(View view, int barHeight) {
        if (view instanceof ViewGroup group) {
            if (matches(view.getClass(), SCROLLER_CLASSES) && !mPadded.containsKey(view)) {
                mPadded.put(view, true);
                group.setClipToPadding(false);
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), view.getPaddingBottom() + barHeight);
                return;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                padScrollers(group.getChildAt(i), barHeight);
            }
        }
    }
}
