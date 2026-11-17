/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.notification.stack;

import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

final class NotificationGlassPress {
    private static final float LIFT = 0.03f;

    private final float mTouchSlop;
    private final FloatValueHolder mLift = new FloatValueHolder();
    private final SpringAnimation mSpring = new SpringAnimation(mLift);
    private final Matrix mMatrix = new Matrix();
    private View mPressed;
    private float mDownX;
    private float mDownY;
    private boolean mHeld;

    NotificationGlassPress(View host) {
        mTouchSlop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        mSpring.setMinimumVisibleChange(0.002f);
        mSpring.addUpdateListener((anim, value, velocity) -> apply());
        mSpring.addEndListener((anim, canceled, value, velocity) -> {
            if (!mHeld && mPressed != null) {
                mPressed.setAnimationMatrix(null);
                mPressed = null;
            }
        });
    }

    void onTouch(NotificationStackScrollLayout stack, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                settle();
                ExpandableView child = stack.getChildAtRawPosition(ev.getRawX(), ev.getRawY());
                if (!(child instanceof ExpandableNotificationRow)) return;
                mPressed = child;
                mHeld = true;
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                animateTo(1f, 0.62f, 520f);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (mHeld && Math.hypot(ev.getRawX() - mDownX, ev.getRawY() - mDownY)
                        > mTouchSlop) {
                    release();
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release();
        }
    }

    private void release() {
        if (!mHeld) return;
        mHeld = false;
        animateTo(0f, 0.38f, SpringForce.STIFFNESS_LOW);
    }

    private void settle() {
        mSpring.cancel();
        mLift.setValue(0f);
        if (mPressed != null) mPressed.setAnimationMatrix(null);
        mPressed = null;
        mHeld = false;
    }

    private void animateTo(float target, float damping, float stiffness) {
        mSpring.setSpring(new SpringForce(target).setDampingRatio(damping).setStiffness(stiffness));
        mSpring.start();
    }

    private void apply() {
        if (mPressed == null) return;
        float scale = 1f + LIFT * mLift.getValue();
        float height = mPressed instanceof ExpandableView row
                ? row.getActualHeight() : mPressed.getHeight();
        mMatrix.setScale(scale, scale, mPressed.getWidth() / 2f, height / 2f);
        mPressed.setAnimationMatrix(mMatrix);
    }
}
