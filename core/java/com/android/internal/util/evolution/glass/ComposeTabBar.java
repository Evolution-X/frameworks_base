/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution.glass;

import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

final class ComposeTabBar {
    private static final float SIDE_MARGIN_DP = 20f;
    private static final float VERTICAL_INSET_DP = 2f;
    private static final int MAX_NODES = 400;

    final View mView;
    private final int mBarId;
    private final float mDensity;
    private final Mask mMask = new Mask();
    private final Rect mBar = new Rect();
    private final ViewGroup mHost;

    private ComposeTabBar(View view, int barId, Rect bar) {
        mView = view;
        mBarId = barId;
        mDensity = view.getResources().getDisplayMetrics().density;
        mBar.set(bar);
        mHost = view.getParent() instanceof ViewGroup g ? g : null;
        if (mHost != null) {
            mMask.setBounds(0, 0, mHost.getWidth(), mHost.getHeight());
            mHost.getOverlay().add(mMask);
        }
    }

    void remove() {
        if (mHost != null) mHost.getOverlay().remove(mMask);
    }

    boolean update() {
        if (mHost == null || mView.getParent() != mHost) return false;
        AccessibilityNodeProvider provider = mView.getAccessibilityNodeProvider();
        if (provider == null) return false;
        AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(mBarId);
        if (node == null || !node.isVisibleToUser()) return false;
        Rect bar = boundsInView(mView, node);
        if (!bar.equals(mBar)) {
            mBar.set(bar);
            mMask.invalidateSelf();
        }
        if (mMask.getBounds().width() != mHost.getWidth()
                || mMask.getBounds().height() != mHost.getHeight()) {
            mMask.setBounds(0, 0, mHost.getWidth(), mHost.getHeight());
        }
        return true;
    }

    static ComposeTabBar find(View view) {
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider == null || view.getWidth() == 0) return null;
        AccessibilityNodeInfo root =
                provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
        if (root == null) return null;
        float density = view.getResources().getDisplayMetrics().density;
        int[] budget = {MAX_NODES};
        int[] found = {AccessibilityNodeProvider.HOST_VIEW_ID};
        Rect bar = new Rect();
        if (!search(view, provider, root, density, budget, found, bar)) return null;
        return new ComposeTabBar(view, found[0], bar);
    }

    private static boolean search(View view, AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node, float density, int[] budget, int[] found, Rect bar) {
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            int id = AccessibilityNodeInfo.getVirtualDescendantId(node.getChildId(i));
            AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(id);
            budget[0]--;
            if (child == null || !child.isVisibleToUser()) continue;
            Rect r = boundsInView(view, child);
            if (r.bottom < view.getHeight() - 56 * density) continue;
            if (isBar(view, provider, child, r, density)) {
                found[0] = id;
                bar.set(r);
                return true;
            }
            if (search(view, provider, child, density, budget, found, bar)) return true;
        }
        return false;
    }

    private static boolean isBar(View view, AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node, Rect r, float density) {
        int w = view.getWidth();
        if (r.width() < w * 0.9f || r.height() < 40 * density || r.height() > 160 * density) {
            return false;
        }
        if (r.bottom < view.getHeight() - 120 * density) return false;
        int tabs = 0;
        int selected = 0;
        int lastRight = Integer.MIN_VALUE / 2;
        for (int i = 0; i < node.getChildCount(); i++) {
            int id = AccessibilityNodeInfo.getVirtualDescendantId(node.getChildId(i));
            AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(id);
            if (child == null || !child.isVisibleToUser()) continue;
            Rect c = boundsInView(view, child);
            if (!(child.isClickable() || child.isSelected()) || c.width() < w * 0.12f
                    || c.height() < r.height() * 0.6f) {
                continue;
            }
            if (c.left < lastRight - 2) return false;
            lastRight = c.right;
            tabs++;
            if (child.isSelected()) selected++;
        }
        return tabs >= 2 && tabs <= 6 && selected == 1;
    }

    private static final int[] sLocation = new int[2];

    private static Rect boundsInView(View view, AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        view.getLocationOnScreen(sLocation);
        r.offset(-sLocation[0], -sLocation[1]);
        return r;
    }

    private final class Mask extends Drawable {
        private final Paint mPage = new Paint();
        private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mCapsule = new RectF();
        private final Path mPath = new Path();
        private Bitmap mShadowBitmap;
        private float mShadowW;
        private float mShadowH;

        Mask() {
            mRim.setStyle(Paint.Style.STROKE);
        }

        private float dp(float v) {
            return v * mDensity;
        }

        @Override
        public void draw(Canvas canvas) {
            if (mBar.isEmpty() || !canvas.isHardwareAccelerated()) return;
            float ox = mView.getLeft();
            float oy = mView.getTop();
            mBarF.set(mBar);
            mBarF.offset(ox, oy);
            float side = dp(SIDE_MARGIN_DP);
            float inset = dp(VERTICAL_INSET_DP);
            mCapsule.set(mBarF.left + side, mBarF.top + inset, mBarF.right - side,
                    mBarF.bottom - inset);
            float r = mCapsule.height() / 2f;
            int page = pageColor();
            boolean dark = isDark(page);

            mPage.setColor(page);
            canvas.drawRect(mBarF.left, mBarF.top, mBarF.right, oy + mView.getHeight(), mPage);
            mPath.reset();
            mPath.addRoundRect(mCapsule, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(mPath);
            drawShadow(canvas, dark);
            canvas.restore();

            float scale = mCapsule.width() / mBarF.width();
            canvas.save();
            canvas.clipPath(mPath);
            canvas.translate(mCapsule.centerX(), mCapsule.centerY());
            canvas.scale(scale, scale);
            canvas.translate(-mBarF.centerX(), -mBarF.centerY());
            canvas.clipRect(mBarF);
            canvas.drawRenderNode(mView.updateDisplayListIfDirty());
            canvas.restore();

            float width = dp(1f);
            mRim.setStrokeWidth(width);
            if (mRimTop != mCapsule.top || mRimDark != dark) {
                mRimTop = mCapsule.top;
                mRimDark = dark;
                mRim.setShader(new LinearGradient(0f, mCapsule.top, 0f, mCapsule.bottom,
                        dark ? 0x59FFFFFF : 0x80FFFFFF, dark ? 0x14FFFFFF : 0x1A000000,
                        Shader.TileMode.CLAMP));
            }
            float half = width / 2f;
            canvas.drawRoundRect(mCapsule.left + half, mCapsule.top + half,
                    mCapsule.right - half, mCapsule.bottom - half, r - half, r - half, mRim);
        }

        private final RectF mBarF = new RectF();
        private float mRimTop = Float.NaN;
        private boolean mRimDark;

        private void drawShadow(Canvas canvas, boolean dark) {
            float pad = dp(10f) * 2f;
            if (mShadowBitmap == null || mShadowW != mCapsule.width()
                    || mShadowH != mCapsule.height()) {
                mShadowW = mCapsule.width();
                mShadowH = mCapsule.height();
                int bw = Math.max(1, Math.round(mShadowW + 2 * pad));
                int bh = Math.max(1, Math.round(mShadowH + 2 * pad));
                mShadowBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
                Canvas c = new Canvas(mShadowBitmap);
                mShadow.setMaskFilter(new BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.OUTER));
                mShadow.setColor(0xFF000000);
                float r = mShadowH / 2f;
                c.drawRoundRect(pad, pad, pad + mShadowW, pad + mShadowH, r, r, mShadow);
                mShadow.setMaskFilter(null);
            }
            mShadow.setColor(dark ? 0x40000000 : 0x1F000000);
            canvas.drawBitmap(mShadowBitmap, mCapsule.left - pad, mCapsule.top - pad, mShadow);
        }

        private int mPageColor;
        private boolean mPageResolved;

        private int pageColor() {
            if (!mPageResolved) {
                mPageResolved = true;
                TypedArray a = mView.getContext().obtainStyledAttributes(
                        new int[] {android.R.attr.colorBackground});
                mPageColor = a.getColor(0, 0xFFFFFFFF) | 0xFF000000;
                a.recycle();
            }
            return mPageColor;
        }

        private boolean isDark(int color) {
            return android.graphics.Color.luminance(color) < 0.4f;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
