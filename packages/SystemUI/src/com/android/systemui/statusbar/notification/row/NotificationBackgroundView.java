/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Flags.lockscreenBlurForNotifications;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dumpable;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.qs.shared.style.LiquidGlass;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.Assert;
import com.android.systemui.util.DrawableDumpKt;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mTopOverlap;
    private int mBottomOverlap;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;
    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    private boolean mBlurBackgroundEnabled;
    // When true, the blur region is forced to alpha 0 (dropped from the aggregator) but kept around.
    private boolean mBlurRegionSuppressed;
    private boolean mBlurHiddenInShelf;
    private int mBlurViewAlpha = 255;
    private final Rect mBlurBounds = new Rect();

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;
    private boolean mOnKeyguard = true;

    private boolean mIsBlurSupported = false;

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);
        if (mIsBlurSupported) {
            mNormalColor = SurfaceEffectColors.surfaceEffect1(getContext());
        } else {
            mNormalColor = mContext.getColor(
                    com.android.internal.R.color.materialColorSurfaceContainerHigh);
        }
        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);
    }

    /** Sets whether blur/translucency is supported for notification rows. */
    public void setIsBlurSupported(boolean isBlurSupported) {
        if (mIsBlurSupported != isBlurSupported) {
            mIsBlurSupported = isBlurSupported;
            setStatefulColors();
            invalidate();
        }
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float clipTop = Math.max(mClipTopAmount, mTopOverlap);
        int clipBottomAmount = Math.max(mClipBottomAmount, mBottomOverlap);
        if (clipTop + clipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, clipTop, getWidth(),
                        getActualHeight() - clipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                if (mBackgroundBlurDrawable != null) {
                    drawBlur(canvas, null);
                } else {
                    draw(canvas, mBackground);
                    drawGlassRim(canvas, mBackground != null ? mBackground.getBounds() : null);
                }
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mBackgroundBlurDrawable != null
                    || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }
            if (mBackgroundBlurDrawable != null) {
                drawBlur(canvas, backgroundBounds);
                drawGlassRim(canvas, mBlurBounds);
            } else if (mBackground != null) {
                mBackground.setBounds(backgroundBounds);
                mBackground.draw(canvas);
                drawGlassRim(canvas, backgroundBounds);
            }

            canvas.restore();
        }
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private final RectF mGlassBounds = new RectF();

    /** Strokes the Liquid Glass rim around a card. Not drawn on a card with a ColorOS material. */
    private void drawGlassRim(Canvas canvas, @Nullable Rect bounds) {
        if (bounds == null || bounds.isEmpty() || !LiquidGlass.isOnShade(getContext())
                || (mCosMaterial != 0 && mBackgroundBlurDrawable != null)) {
            return;
        }
        mGlassBounds.set(bounds);
        LiquidGlass.drawRim(canvas, mGlassBounds, mCornerRadii,
                getResources().getDisplayMetrics().density);
    }

    /**
     * The fill of a card on Liquid Glass: a faint frost instead of the card color. Only the
     * plain card color is swapped. Tinted cards (a conversation, a call) keep their own, and the
     * shade colors are never touched, those stay with ShadeColors and shade_color_style.
     */
    private int surfaceFor(int tintColor) {
        if (tintColor != mNormalColor || !LiquidGlass.isOnShade(getContext())) return tintColor;
        // The ColorOS material frosts the card itself, so the fill stays clear.
        if (mCosMaterial != 0) return Color.TRANSPARENT;
        // A pinned heads-up floats over the app, so it gets a lighter glass of its own.
        if (mHeadsUpGlass) return LiquidGlass.headsUpSurface();
        return LiquidGlass.viewSurface();
    }

    private boolean mHeadsUpGlass;

    private int mCosMaterial;

    /** With Liquid Glass, a heavier frost than the stock card, as iOS blurs notifications. */
    private static final float COS_CARD_BLUR_DP = 36f;

    /** A heads-up sits over the app, so its blur is lighter than that of a card in the shade. */
    private static final float COS_HEADS_UP_BLUR_DP = 14f;

    /**
     * Draws the card as a heads-up floating over the app, which has no shade glass behind it:
     * its own fill on Liquid Glass, and the ColorOS heads-up material under the ColorOS style.
     */
    public void setHeadsUpGlass(boolean headsUp) {
        if (mHeadsUpGlass == headsUp) return;
        mHeadsUpGlass = headsUp;
        if (mBackground != null) {
            setTint(mTintColor != 0 ? mTintColor : mNormalColor);
        }
    }

    /**
     * Picks the ColorOS material for the blur region, or none: the card material (dark 7, light 8),
     * or the heads-up material (dark 9, light 10) while the card is a pinned heads-up.
     */
    private void updateCosMaterial() {
        mCosMaterial = 0;
        if (mBackgroundBlurDrawable == null) return;
        if (LiquidGlass.isCosStyle(getContext())) {
            final boolean dark = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            if (mHeadsUpGlass) {
                mCosMaterial = dark ? BackgroundBlurDrawable.MATERIAL_HEADS_UP_DARK
                        : BackgroundBlurDrawable.MATERIAL_HEADS_UP_LIGHT;
            } else {
                mCosMaterial = dark ? BackgroundBlurDrawable.MATERIAL_CARD_DARK
                        : BackgroundBlurDrawable.MATERIAL_CARD_LIGHT;
            }
        }
        mBackgroundBlurDrawable.setMaterial(mCosMaterial);
        final float density = getResources().getDisplayMetrics().density;
        final int blurRadius;
        if (mCosMaterial != 0 && mHeadsUpGlass) {
            blurRadius = Math.round(COS_HEADS_UP_BLUR_DP * density);
        } else if (mCosMaterial != 0 && LiquidGlass.isOnShade(getContext())) {
            blurRadius = Math.round(COS_CARD_BLUR_DP * density);
        } else {
            blurRadius = getResources().getDimensionPixelSize(
                    R.dimen.notification_background_blur_radius);
        }
        mBackgroundBlurDrawable.setBlurRadius(blurRadius);
    }

    private void drawBlur(Canvas canvas, @Nullable Rect backgroundBounds) {
        final Rect bounds = mBlurBounds;
        if (backgroundBounds != null) {
            bounds.set(backgroundBounds);
        } else {
            computeBackgroundBounds(bounds);
        }
        if (!mExpandAnimationRunning) {
            bounds.top = Math.max(bounds.top, Math.max(mClipTopAmount, mTopOverlap));
            bounds.bottom = Math.min(bounds.bottom,
                    getActualHeight() - Math.max(mClipBottomAmount, mBottomOverlap));
        }
        if (bounds.isEmpty()) {
            return;
        }
        mBackgroundBlurDrawable.setBounds(bounds);
        mBackgroundBlurDrawable.draw(canvas);
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        final Rect bounds = new Rect();
        computeBackgroundBounds(bounds);
        return bounds;
    }

    private void computeBackgroundBounds(Rect out) {
        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
        }
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        out.set(left, top, right, bottom);
    }

    /**
     * @return Whether the background view should be right-aligned. This only matters if the
     * actualWidth is different than the full (measured) width. In other words, this is used to
     * define the short-shelf alignment.
     */
    protected boolean isAlignedToRight() {
        return isLayoutRtl();
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
            }

            final boolean alignedToRight = isAlignedToRight();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = alignedToRight ? width - actualWidth : 0;
            int right = alignedToRight ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            drawable.setBounds(left, top, right, bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Update the view on whether the device is currently on the keyguard.
     */
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard == mOnKeyguard) {
            return;
        }

        // TODO: (b/445495701) - Determine why this value is opposite of expected, then use in #draw
        // to restrict the blurred background to the keyguard only.
        mOnKeyguard = onKeyguard;
    }

    /**
     * Stateful colors are colors that will overlay on the notification original color when one of
     * hover states, pressed states or other similar states is activated.
     */
    private void setStatefulColors() {
        Drawable statefulLayer = getStatefulBackgroundLayer();
        if (statefulLayer == null) {
            return;
        }
        if (mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) statefulLayer.mutate()).setColor(newColor);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    /**
     * Update whether this view should allow a blurred background or not.
     *
     * @param enabled - If true, queues creation of a {BackgroundBlurDrawable}. If false, removes
     *     any reference to a blurred drawable.
     */
    @UiThread
    public void setBlurBackgroundEnabled(boolean enabled) {
        if (!lockscreenBlurForNotifications()) {
            return;
        }
        Assert.isMainThread();

        if (mBlurBackgroundEnabled == enabled) {
            return;
        }
        mBlurBackgroundEnabled = enabled;
        if (enabled) {
            if (isAttachedToWindow()) {
                createBlurDrawable();
            }
            // Otherwise created in onAttachedToWindow.
        } else {
            releaseBlurDrawable();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mBlurBackgroundEnabled) {
            createBlurDrawable();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseBlurDrawable();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible() != isVisible) {
            mBackgroundBlurDrawable.setVisible(isVisible, false);
        }
    }

    private void createBlurDrawable() {
        if (mBackgroundBlurDrawable != null || getViewRootImpl() == null) {
            return;
        }
        mBackgroundBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
        mBackgroundBlurDrawable.setBlurRadius(
                getResources().getDimensionPixelSize(R.dimen.notification_background_blur_radius));
        mBackgroundBlurDrawable.setXfermode(null);
        mBackgroundBlurDrawable.setCallback(this);
        updateCosMaterial();
        mBackgroundBlurDrawable.setColor(
                surfaceFor(mTintColor != 0 ? mTintColor : mNormalColor));
        if (!isAggregatedVisible()) {
            mBackgroundBlurDrawable.setVisible(false, false);
        }
        updateBlurAlpha();
        updateBackgroundRadii();
        invalidate();
    }

    private void releaseBlurDrawable() {
        if (mBackgroundBlurDrawable == null) {
            return;
        }
        mBackgroundBlurDrawable.setVisible(false, false);
        mBackgroundBlurDrawable.setCallback(null);
        mBackgroundBlurDrawable = null;
        // The COS material is drawn by the window blur: a card that has lost its blur (moved
        // from the lock screen into the shade) needs its own fill again.
        if (mBackground != null) {
            setTint(mTintColor != 0 ? mTintColor : mNormalColor);
        }
        invalidate();
    }

    private void updateBlurAlpha() {
        if (mBackgroundBlurDrawable == null) {
            return;
        }
        final int alpha = mBlurRegionSuppressed || mBlurHiddenInShelf
                ? 0 : mDrawableAlpha * mBlurViewAlpha / 255;
        mBackgroundBlurDrawable.setAlpha(alpha);
    }

    public void setBlurHiddenInShelf(boolean hidden) {
        if (mBlurHiddenInShelf == hidden) {
            return;
        }
        mBlurHiddenInShelf = hidden;
        if (mBackgroundBlurDrawable != null) {
            updateBlurAlpha();
            invalidate();
        }
    }

    public void setBlurViewAlpha(float alpha) {
        final int blurViewAlpha = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        if (mBlurViewAlpha == blurViewAlpha) {
            return;
        }
        mBlurViewAlpha = blurViewAlpha;
        if (mBackgroundBlurDrawable != null && !mExpandAnimationRunning) {
            updateBlurAlpha();
            invalidate();
        }
    }

    public Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        updateCosMaterial();
        Drawable baseLayer = getBaseBackgroundLayer();
        final int surface = surfaceFor(tintColor);
        if (mIsBlurSupported) {
            ((GradientDrawable) baseLayer.mutate()).setColor(surface);
        } else {
            baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
            baseLayer.setTint(surface);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setColor(surface);
        }
        mTintColor = tintColor;
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    /**
     * Sets the overlap on the top of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipTopAmount, however it is a separate way to clip which is usually
     * then combined with the clipTopAmount to take the maximum.
     */
    public void setTopOverlap(int topOverlap) {
        mTopOverlap = topOverlap;
        invalidate();
    }

    /**
     * Sets the overlap on the bottom of the view with other views. As a result we should clip the
     * background and content such that no overlap is visible anymore.
     * This is related to setClipBottomAmount, however it is a separate way to clip which is usually
     * then combined with the clipBottomAmount to take the maximum.
     */
    public void setBottomOverlap(int bottomOverlap) {
        mBottomOverlap = bottomOverlap;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
        updateBlurAlpha();
    }

    /** Drops the blur region without tearing down the drawable; blur regions ignore ancestor alpha. */
    public void setBlurRegionSuppressed(boolean suppressed) {
        if (mBlurRegionSuppressed == suppressed) {
            return;
        }
        mBlurRegionSuppressed = suppressed;
        if (mBackgroundBlurDrawable != null) {
            updateBlurAlpha();
            invalidate();
        }
    }

    /**
     * Sets the current top and bottom radius for this background.
     */
    public void setRadius(float topRoundness, float bottomRoundness) {
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    @VisibleForTesting
    protected boolean isBlurEnabled() {
        return mBackgroundBlurDrawable != null;
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setCornerRadius(mCornerRadii[0], mCornerRadii[1],
                    mCornerRadii[2], mCornerRadii[3], mCornerRadii[4], mCornerRadii[5],
                    mCornerRadii[6], mCornerRadii[7]);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        if (overlay == null) {
            return;
        }
        for (int i = 0; i < mCornerRadii.length; i++) {
            // in theory subtracting mFocusOverlayStroke/2 should be enough but notification
            // background is still peeking a bit from below - probably due to antialiasing or
            // overlay uneven scaling. So let's subtract full mFocusOverlayStroke to make sure the
            // radius is a bit smaller and covers background corners fully
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mTopOverlap: " + mTopOverlap);
        pw.println("mBottomOverlap: " + mBottomOverlap);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
        pw.println("mBackgroundBlurDrawable: "
                + DrawableDumpKt.dumpToString(mBackgroundBlurDrawable));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + " blurEnabled=" + (mBackgroundBlurDrawable != null)
                + ">";

    }
}
