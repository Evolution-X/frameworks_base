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
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

final class ComposeTabBar {
    private static final float SIDE_MARGIN_DP = 20f;
    private static final float VERTICAL_INSET_DP = 2f;
    private static final int MAX_NODES = 400;
    private static final String KEY_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 ref;\n"
            + "uniform float3 indicator;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  half4 k = content.eval(float2(ref.x, p.y));\n"
            + "  float d = distance(float3(c.rgb), float3(k.rgb));\n"
            + "  if (indicator.z > 0.0 && abs(p.y - indicator.y) < indicator.z) {\n"
            + "    float3 i = float3(content.eval(indicator.xy).rgb);\n"
            + "    float3 b = float3(k.rgb);\n"
            + "    float3 e = i - b;\n"
            + "    float t = clamp(dot(float3(c.rgb) - b, e) / max(dot(e, e), 1e-4), 0.0, 1.0);\n"
            + "    d = min(d, distance(float3(c.rgb), b + e * t));\n"
            + "  }\n"
            + "  return c * half(smoothstep(0.05, 0.16, d));\n"
            + "}\n";

    final View mView;
    private final int mBarId;
    private final float mDensity;
    private final Mask mMask = new Mask();
    private final Rect mBar = new Rect();
    private final ViewGroup mHost;
    private final int[] mTabIds;
    private final boolean mFloat;
    private int mShift;
    private int mOriginalHeight;
    private boolean mOwned;
    private int mSelected = -1;
    private final LiquidMotion mMotion = new LiquidMotion(78f / 56f, () -> mMask.invalidateSelf());

    private ComposeTabBar(View view, int barId, Rect bar, int[] tabIds, boolean floatOver) {
        mView = view;
        mBarId = barId;
        mTabIds = tabIds;
        mDensity = view.getResources().getDisplayMetrics().density;
        mBar.set(bar);
        mHost = view.getParent() instanceof ViewGroup g ? g : null;
        if (mHost != null) {
            mMask.setBounds(0, 0, mHost.getWidth(), mHost.getHeight());
            mHost.getOverlay().add(mMask);
        }
        mMotion.setMax(Math.max(tabIds.length - 1, 0));
        mFloat = floatOver && mHost != null && mHost.getLayoutParams() != null
                && mHost.getParent() instanceof ViewGroup;
        if (mFloat) {
            mShift = mHost.getHeight() - (view.getTop() + bar.top);
            mOriginalHeight = mHost.getLayoutParams().height;
            setHeight(mHost.getHeight() + mShift);
        }
    }

    private void setHeight(int height) {
        ViewGroup.LayoutParams lp = mHost.getLayoutParams();
        if (lp == null || lp.height == height) return;
        lp.height = height;
        mHost.setLayoutParams(lp);
    }

    void remove() {
        mMotion.cancel();
        if (mHost != null) mHost.getOverlay().remove(mMask);
        if (mFloat) setHeight(mOriginalHeight);
    }

    boolean onTouch(MotionEvent ev) {
        if (!mFloat) return false;
        RectF capsule = mMask.mCapsuleInWindow;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mOwned = !capsule.isEmpty() && capsule.contains(ev.getX(), ev.getY());
                if (mOwned && mSelected >= 0) mMotion.pressDown();
                return mOwned;
            case MotionEvent.ACTION_UP:
                if (!mOwned) return false;
                mOwned = false;
                int count = mTabIds.length;
                if (count > 0 && capsule.contains(ev.getX(), ev.getY())) {
                    int index = (int) ((ev.getX() - capsule.left) / capsule.width() * count);
                    index = Math.max(0, Math.min(count - 1, index));
                    if (mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        index = count - 1 - index;
                    }
                    AccessibilityNodeProvider provider = mView.getAccessibilityNodeProvider();
                    if (provider != null) {
                        provider.performAction(mTabIds[index],
                                AccessibilityNodeInfo.ACTION_CLICK, null);
                    }
                    if (mSelected >= 0 && index != mSelected) {
                        mSelected = index;
                        mMotion.animateToValue(index);
                    }
                }
                mMotion.release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                boolean owned = mOwned;
                mOwned = false;
                if (owned) mMotion.release();
                return owned;
            default:
                return mOwned;
        }
    }

    boolean update() {
        if (mHost == null || mView.getParent() != mHost) return false;
        AccessibilityNodeProvider provider = mView.getAccessibilityNodeProvider();
        if (provider == null) return false;
        AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(mBarId);
        if (node == null || (!mFloat && !node.isVisibleToUser())) return false;
        if (mFloat) {
            int base = ((ViewGroup) mHost.getParent()).getHeight() - mHost.getTop();
            if (base > 0) setHeight(base + mShift);
        }
        if (mFloat) updateSelection(provider);
        Rect bar = mFloat ? mBar : boundsInView(mView, node);
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

    private void updateSelection(AccessibilityNodeProvider provider) {
        int selected = -1;
        for (int i = 0; i < mTabIds.length; i++) {
            AccessibilityNodeInfo tab = provider.createAccessibilityNodeInfo(mTabIds[i]);
            if (tab != null && tab.isSelected()) {
                selected = i;
                break;
            }
        }
        if (selected < 0 || selected == mSelected) return;
        if (mSelected < 0) mMotion.snapTo(selected);
        else if (!mOwned) mMotion.animateToValue(selected);
        mSelected = selected;
        mMask.invalidateSelf();
    }

    static ComposeTabBar find(View view, boolean floatOver) {
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider == null || view.getWidth() == 0) return null;
        AccessibilityNodeInfo root =
                provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
        if (root == null) return null;
        float density = view.getResources().getDisplayMetrics().density;
        int[] budget = {MAX_NODES};
        int[] found = {AccessibilityNodeProvider.HOST_VIEW_ID};
        Rect bar = new Rect();
        java.util.ArrayList<Integer> tabs = new java.util.ArrayList<>();
        if (!search(view, provider, root, density, budget, found, bar, tabs)) return null;
        int[] tabIds = new int[tabs.size()];
        for (int i = 0; i < tabIds.length; i++) tabIds[i] = tabs.get(i);
        return new ComposeTabBar(view, found[0], bar, tabIds, floatOver);
    }

    private static boolean search(View view, AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node, float density, int[] budget, int[] found, Rect bar,
            java.util.ArrayList<Integer> tabs) {
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            int id = AccessibilityNodeInfo.getVirtualDescendantId(node.getChildId(i));
            AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(id);
            budget[0]--;
            if (child == null || !child.isVisibleToUser()) continue;
            Rect r = boundsInView(view, child);
            if (r.bottom < view.getHeight() - 56 * density) continue;
            if (isBar(view, provider, child, r, density, tabs)) {
                found[0] = id;
                bar.set(r);
                return true;
            }
            if (search(view, provider, child, density, budget, found, bar, tabs)) return true;
        }
        return false;
    }

    private static boolean isBar(View view, AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node, Rect r, float density, java.util.ArrayList<Integer> tabs) {
        tabs.clear();
        int w = view.getWidth();
        if (r.width() < w * 0.9f || r.height() < 40 * density || r.height() > 160 * density) {
            return false;
        }
        if (r.bottom < view.getHeight() - 120 * density) return false;
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
            tabs.add(id);
            if (child.isSelected()) selected++;
        }
        return tabs.size() >= 2 && tabs.size() <= 6 && selected == 1;
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

        final RectF mCapsuleInWindow = new RectF();
        private final int[] mHostLocation = new int[2];

        @Override
        public void draw(Canvas canvas) {
            if (mBar.isEmpty() || !canvas.isHardwareAccelerated()) return;
            if (mFloat) {
                drawFloating(canvas);
                return;
            }
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

            drawRim(canvas, r, dark);
        }

        private final RectF mBarF = new RectF();
        private float mRimTop = Float.NaN;
        private boolean mRimDark;

        private final RenderNode mGlassNode = new RenderNode("ComposeTabBarGlass");
        private final RenderNode mTabsNode = new RenderNode("ComposeTabBarTabs");
        private final LiquidGlass mGlass = new LiquidGlass();
        private final Paint mDot = new Paint();
        private final RectF mSource = new RectF();
        private RuntimeShader mKey;
        private int mGlassKey;

        private void drawFloating(Canvas canvas) {
            float ox = mView.getLeft();
            float oy = mView.getTop();
            float sourceTop = oy + mView.getHeight() - mShift;
            mSource.set(mBar.left + ox, sourceTop, mBar.right + ox, sourceTop + mBar.height());
            float barTop = sourceTop - mShift;
            mBarF.set(mSource.left, barTop, mSource.right, barTop + mSource.height());
            float side = dp(SIDE_MARGIN_DP);
            float inset = dp(VERTICAL_INSET_DP);
            mCapsule.set(mBarF.left + side, mBarF.top + inset, mBarF.right - side,
                    mBarF.bottom - inset);
            if (mCapsule.isEmpty()) return;
            mHost.getLocationInWindow(mHostLocation);
            mCapsuleInWindow.set(mCapsule);
            mCapsuleInWindow.offset(mHostLocation[0], mHostLocation[1]);
            float r = mCapsule.height() / 2f;
            boolean dark = isDark(pageColor());

            mPath.reset();
            mPath.addRoundRect(mCapsule, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(mPath);
            drawShadow(canvas, dark);
            canvas.restore();

            int left = Math.round(mCapsule.left);
            int top = Math.round(mCapsule.top);
            int w = Math.max(1, Math.round(mCapsule.width()));
            int h = Math.max(1, Math.round(mCapsule.height()));
            int key = (w * 31 + h) * 2 + (dark ? 1 : 0);
            if (key != mGlassKey) {
                mGlassKey = key;
                mGlass.setShape(0f, 0f, w, h, h / 2f)
                        .setLens(dp(24f), dp(24f), 0f)
                        .setLook(dp(20f), 1.5f, dark ? 0.35f : 0.5f,
                                dark ? LiquidGlass.TINT_DARK : LiquidGlass.TINT_LIGHT);
                mGlassNode.setBackdropRenderEffect(mGlass.build());
            }
            mGlassNode.setPosition(left, top, left + w, top + h);
            Canvas g = mGlassNode.beginRecording(w, h);
            mDot.setColor(0x01000000);
            g.drawRect(0f, 0f, 1f, 1f, mDot);
            mGlassNode.endRecording();
            canvas.drawRenderNode(mGlassNode);

            float scale = mCapsule.width() / mSource.width();
            mTabsNode.setPosition(left, top, left + w, top + h);
            Canvas c = mTabsNode.beginRecording(w, h);
            c.translate(mCapsule.centerX() - left, mCapsule.centerY() - top);
            c.scale(scale, scale);
            c.translate(-mSource.centerX(), -mSource.centerY());
            c.clipRect(mSource);
            c.drawRenderNode(mView.updateDisplayListIfDirty());
            mTabsNode.endRecording();
            if (mKey == null) mKey = new RuntimeShader(KEY_SHADER);
            mKey.setFloatUniform("ref", dp(3f), h / 2f);
            if (mSelected >= 0 && mTabIds.length > 0) {
                float tab = mSource.width() / mTabIds.length;
                float iconX = mSource.left + (mSelected + 0.5f) * tab;
                float x = (iconX - dp(14f) - mSource.centerX()) * scale + mCapsule.centerX() - left;
                float y = (mSource.top + mSource.height() * 0.35f - mSource.centerY()) * scale
                        + mCapsule.centerY() - top;
                mKey.setFloatUniform("indicator", x, y, dp(24f) * scale);
            } else {
                mKey.setFloatUniform("indicator", 0f, 0f, 0f);
            }
            mTabsNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(mKey, "content"));
            canvas.save();
            canvas.clipPath(mPath);
            drawPill(canvas, dark);
            canvas.drawRenderNode(mTabsNode);
            canvas.restore();

            drawRim(canvas, r, dark);
        }

        private final RectF mPill = new RectF();
        private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private void drawPill(Canvas canvas, boolean dark) {
            float v = mMotion.value();
            if (mSelected < 0 || Float.isNaN(v) || mTabIds.length == 0) return;
            if (mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                v = mTabIds.length - 1 - v;
            }
            float inner = dp(4f);
            float tab = (mCapsule.width() - 2f * inner) / mTabIds.length;
            float speed = Math.max(-0.2f, Math.min(0.2f, mMotion.velocity() / 10f));
            float w = tab * mMotion.scaleX() / (1f - speed * 0.75f);
            float h = (mCapsule.height() - 2f * inner) * mMotion.scaleY() * (1f - speed * 0.25f);
            float cx = mCapsule.left + inner + (v + 0.5f) * tab;
            mPill.set(cx - w / 2f, mCapsule.centerY() - h / 2f, cx + w / 2f,
                    mCapsule.centerY() + h / 2f);
            float press = mMotion.press();
            int alpha = Math.round(255 * (0.10f + 0.06f * press));
            mPillPaint.setColor((alpha << 24) | (dark ? 0xFFFFFF : 0x000000));
            canvas.drawRoundRect(mPill, h / 2f, h / 2f, mPillPaint);
        }

        private void drawRim(Canvas canvas, float r, boolean dark) {
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
