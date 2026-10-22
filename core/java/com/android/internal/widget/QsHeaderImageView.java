/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.widget;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.internal.util.android.QsHeaderImageGeometry;

public class QsHeaderImageView extends ImageView {
    private final Matrix mImageMatrix = new Matrix();
    private final float[] mGeometry = new float[3];
    private boolean mSettingsObserverEnabled = true;
    private boolean mObserving;
    private float mZoom = 1f;
    private float mPositionX = 0.5f;
    private float mPositionY = 0.5f;
    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            loadFraming();
        }
    };

    public QsHeaderImageView(Context context) {
        this(context, null);
    }

    public QsHeaderImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        loadFraming();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startObservingSettings();
    }

    public void setSettingsObserverEnabled(boolean enabled) {
        mSettingsObserverEnabled = enabled;
        if (enabled && isAttachedToWindow()) {
            startObservingSettings();
        } else if (!enabled) {
            stopObservingSettings();
        }
    }

    private void startObservingSettings() {
        if (!mSettingsObserverEnabled || mObserving) return;
        mObserving = true;
        for (String key : new String[] {Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        loadFraming();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopObservingSettings();
        super.onDetachedFromWindow();
    }

    private void stopObservingSettings() {
        if (mObserving) {
            getContext().getContentResolver().unregisterContentObserver(mObserver);
            mObserving = false;
        }
    }

    private void loadFraming() {
        setImageFraming(Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM, 100,
                        UserHandle.USER_CURRENT) / 100f,
                Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X, 500,
                        UserHandle.USER_CURRENT) / 1000f,
                Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y, 500,
                        UserHandle.USER_CURRENT) / 1000f);
    }

    public void setImageFraming(float zoom, float positionX, float positionY) {
        mZoom = QsHeaderImageGeometry.clamp(zoom, 1f, 4f, 1f);
        mPositionX = QsHeaderImageGeometry.clamp(positionX, 0f, 1f, 0.5f);
        mPositionY = QsHeaderImageGeometry.clamp(positionY, 0f, 1f, 0.5f);
        updateImageMatrix();
    }

    public float getImageZoom() { return mZoom; }
    public float getImagePositionX() { return mPositionX; }
    public float getImagePositionY() { return mPositionY; }

    public void panImage(float dx, float dy) {
        Drawable image = getDrawable();
        if (image == null) return;
        float overflowX = image.getIntrinsicWidth() * mGeometry[0] - contentWidth();
        float overflowY = image.getIntrinsicHeight() * mGeometry[0] - contentHeight();
        setImageFraming(mZoom,
                overflowX > 0.5f ? mPositionX - dx / overflowX : mPositionX,
                overflowY > 0.5f ? mPositionY - dy / overflowY : mPositionY);
    }

    public void zoomImage(float scaleFactor, float focusX, float focusY) {
        Drawable image = getDrawable();
        if (image == null || mGeometry[0] <= 0f) return;
        float zoom = QsHeaderImageGeometry.clamp(mZoom * scaleFactor, 1f, 4f, mZoom);
        float factor = zoom / mZoom;
        float overflowX = image.getIntrinsicWidth() * mGeometry[0] * factor - contentWidth();
        float overflowY = image.getIntrinsicHeight() * mGeometry[0] * factor - contentHeight();
        float x = focusX - getPaddingLeft();
        float y = focusY - getPaddingTop();
        setImageFraming(zoom,
                overflowX > 0.5f ? ((x - mGeometry[1]) * factor - x) / overflowX : mPositionX,
                overflowY > 0.5f ? ((y - mGeometry[2]) * factor - y) / overflowY : mPositionY);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (mImageMatrix != null) updateImageMatrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateImageMatrix();
    }

    private int contentWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int contentHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    private void updateImageMatrix() {
        Drawable image = getDrawable();
        if (image == null || image.getIntrinsicWidth() <= 0 || image.getIntrinsicHeight() <= 0
                || contentWidth() <= 0 || contentHeight() <= 0) return;
        QsHeaderImageGeometry.calculate(image.getIntrinsicWidth(), image.getIntrinsicHeight(),
                contentWidth(), contentHeight(), mZoom, mPositionX, mPositionY, mGeometry);
        mImageMatrix.setScale(mGeometry[0], mGeometry[0]);
        mImageMatrix.postTranslate(mGeometry[1], mGeometry[2]);
        setImageMatrix(mImageMatrix);
    }
}
