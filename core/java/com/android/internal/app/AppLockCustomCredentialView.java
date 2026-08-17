/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.UserHandle;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen Monet-themed authentication view for App Lock supporting PIN, Password, and Pattern verification.
 *
 * @hide
 */
public class AppLockCustomCredentialView extends FrameLayout {

    public interface OnUnlockListener {
        void onUnlocked();
        void onCancelled();
    }

    private int mUserId = UserHandle.myUserId();
    private OnUnlockListener mListener;
    private int mCredentialType = AppLockCredentialUtils.CREDENTIAL_TYPE_PIN;

    private ImageView mIconView;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mErrorView;

    // PIN UI
    private LinearLayout mPinDotsLayout;
    private final List<View> mPinDots = new ArrayList<>();
    private final StringBuilder mEnteredPin = new StringBuilder();

    // Password UI
    private EditText mPasswordInput;

    // Pattern UI
    private LockPatternView mPatternView;

    private int mBgColor;
    private int mKeyColor;
    private int mKeyPressedColor;
    private int mAccentColor;
    private int mTextColor;
    private int mTextSecondaryColor;
    private int mDotUnfilledColor;
    private int mDotUnfilledStroke;

    public AppLockCustomCredentialView(Context context) {
        super(context);
        init(context);
    }

    public AppLockCustomCredentialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public void setUserId(int userId) {
        mUserId = userId;
        mCredentialType = AppLockCredentialUtils.getCredentialType(getContext(), mUserId);
        buildUi(getContext());
    }

    public void setOnUnlockListener(OnUnlockListener listener) {
        mListener = listener;
    }

    public void setAppDetails(CharSequence label, Bitmap logo) {
        if (mTitleView != null && label != null) {
            mTitleView.setText(label);
        }
        if (mIconView != null && logo != null) {
            mIconView.setImageBitmap(logo);
        }
    }

    private void init(Context context) {
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        boolean isDark = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        if (isDark) {
            mBgColor = 0xB30F0F14;
            mKeyColor = getMonetColor(context, android.R.color.system_neutral2_800, 0xFF21232A);
            mKeyPressedColor = getMonetColor(context, android.R.color.system_accent1_200, 0xFF424756);
            mAccentColor = getMonetColor(context, android.R.color.system_accent1_300, 0xFFD0BCFF);
            mTextColor = getMonetColor(context, android.R.color.system_neutral1_50, 0xFFFFFFFF);
            mTextSecondaryColor = getMonetColor(context, android.R.color.system_neutral2_200, 0xCCFFFFFF);
            mDotUnfilledColor = 0x22FFFFFF;
            mDotUnfilledStroke = 0x80FFFFFF;
        } else {
            mBgColor = 0xB3F9F9FF;
            mKeyColor = getMonetColor(context, android.R.color.system_neutral2_100, 0xFFF3EDF7);
            mKeyPressedColor = getMonetColor(context, android.R.color.system_accent1_200, 0xFFE8DEF8);
            mAccentColor = getMonetColor(context, android.R.color.system_accent1_600, 0xFF6750A4);
            mTextColor = getMonetColor(context, android.R.color.system_neutral1_900, 0xFF1C1B1F);
            mTextSecondaryColor = getMonetColor(context, android.R.color.system_neutral2_700, 0x99000000);
            mDotUnfilledColor = 0x11000000;
            mDotUnfilledStroke = 0x66000000;
        }

        setBackgroundColor(mBgColor);
        setClickable(true);
        setFocusable(true);

        mCredentialType = AppLockCredentialUtils.getCredentialType(context, mUserId);
        buildUi(context);
    }

    private int getMonetColor(Context context, int resId, int fallback) {
        try {
            return context.getColor(resId);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void buildUi(Context context) {
        removeAllViews();

        LinearLayout rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        rootContainer.setPadding(dpToPx(24), dpToPx(48), dpToPx(24), dpToPx(24));

        LayoutParams rootParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        rootContainer.setLayoutParams(rootParams);

        // Header Section
        mIconView = new ImageView(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(64), dpToPx(64));
        iconParams.bottomMargin = dpToPx(14);
        mIconView.setLayoutParams(iconParams);
        rootContainer.addView(mIconView);

        mTitleView = new TextView(context);
        mTitleView.setTextColor(mTextColor);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mTitleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mTitleView.setGravity(Gravity.CENTER);
        rootContainer.addView(mTitleView);

        mSubtitleView = new TextView(context);
        mSubtitleView.setText("Touch ID or Enter Passcode");
        mSubtitleView.setTextColor(mTextSecondaryColor);
        mSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mSubtitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dpToPx(4);
        subParams.bottomMargin = dpToPx(12);
        mSubtitleView.setLayoutParams(subParams);
        rootContainer.addView(mSubtitleView);

        mErrorView = new TextView(context);
        mErrorView.setTextColor(0xFFFF5252);
        mErrorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mErrorView.setTypeface(null, Typeface.BOLD);
        mErrorView.setGravity(Gravity.CENTER);
        mErrorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        errParams.bottomMargin = dpToPx(12);
        mErrorView.setLayoutParams(errParams);
        rootContainer.addView(mErrorView);

        // Spacer to push keypad/input down
        View spacerTop = new View(context);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1.0f);
        spacerTop.setLayoutParams(spacerParams);
        rootContainer.addView(spacerTop);

        // Credential-specific UI
        if (mCredentialType == AppLockCredentialUtils.CREDENTIAL_TYPE_PIN) {
            buildPinUi(context, rootContainer);
        } else if (mCredentialType == AppLockCredentialUtils.CREDENTIAL_TYPE_PASSWORD) {
            buildPasswordUi(context, rootContainer);
        } else if (mCredentialType == AppLockCredentialUtils.CREDENTIAL_TYPE_PATTERN) {
            buildPatternUi(context, rootContainer);
        }

        // Bottom Spacer & Action Bar (Cancel)
        View spacerBottom = new View(context);
        LinearLayout.LayoutParams spacerBtmParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1.0f);
        spacerBottom.setLayoutParams(spacerBtmParams);
        rootContainer.addView(spacerBottom);

        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        barParams.bottomMargin = dpToPx(12);
        bottomBar.setLayoutParams(barParams);

        TextView cancelBtn = new TextView(context);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(mTextColor);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancelBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancelBtn.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        cancelBtn.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onCancelled();
            }
        });

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cancelParams.weight = 1.0f;
        cancelParams.gravity = Gravity.END;
        cancelBtn.setLayoutParams(cancelParams);
        cancelBtn.setGravity(Gravity.END);

        bottomBar.addView(cancelBtn);
        rootContainer.addView(bottomBar);

        addView(rootContainer);
    }

    private void buildPinUi(Context context, LinearLayout parent) {
        // PIN Dots Indicator Layout
        mPinDotsLayout = new LinearLayout(context);
        mPinDotsLayout.setOrientation(LinearLayout.HORIZONTAL);
        mPinDotsLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dotsParams.bottomMargin = dpToPx(32);
        mPinDotsLayout.setLayoutParams(dotsParams);

        mPinDots.clear();
        for (int i = 0; i < 4; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams dotParam = new LinearLayout.LayoutParams(dpToPx(14), dpToPx(14));
            dotParam.setMargins(dpToPx(10), 0, dpToPx(10), 0);
            dot.setLayoutParams(dotParam);
            updateDotState(dot, false);
            mPinDots.add(dot);
            mPinDotsLayout.addView(dot);
        }
        parent.addView(mPinDotsLayout);

        // Numeric Keypad Grid
        GridLayout keypad = new GridLayout(context);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        LinearLayout.LayoutParams keypadParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        keypadParams.gravity = Gravity.CENTER_HORIZONTAL;
        keypad.setLayoutParams(keypadParams);

        KeyDef[] keys = new KeyDef[]{
                new KeyDef("1", ""), new KeyDef("2", "A B C"), new KeyDef("3", "D E F"),
                new KeyDef("4", "G H I"), new KeyDef("5", "J K L"), new KeyDef("6", "M N O"),
                new KeyDef("7", "P Q R S"), new KeyDef("8", "T U V"), new KeyDef("9", "W X Y Z"),
                new KeyDef("Clear", null), new KeyDef("0", ""), new KeyDef("⌫", null)
        };

        for (KeyDef k : keys) {
            FrameLayout keyFrame = createCircularKeyView(context, k);
            keyFrame.setOnClickListener(v -> {
                if ("Clear".equals(k.digit)) {
                    mEnteredPin.setLength(0);
                    refreshDots();
                    mErrorView.setVisibility(View.GONE);
                } else if ("⌫".equals(k.digit)) {
                    if (mEnteredPin.length() > 0) {
                        mEnteredPin.deleteCharAt(mEnteredPin.length() - 1);
                        refreshDots();
                    }
                } else if (k.digit.length() == 1 && Character.isDigit(k.digit.charAt(0))) {
                    if (mEnteredPin.length() < 4) {
                        mEnteredPin.append(k.digit);
                        refreshDots();
                        if (mEnteredPin.length() == 4) {
                            verifyPin();
                        }
                    }
                }
            });
            keypad.addView(keyFrame);
        }
        parent.addView(keypad);
    }

    private static class KeyDef {
        String digit;
        String letters;
        KeyDef(String digit, String letters) {
            this.digit = digit;
            this.letters = letters;
        }
    }

    private FrameLayout createCircularKeyView(Context context, KeyDef keyDef) {
        FrameLayout frame = new FrameLayout(context);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dpToPx(76);
        params.height = dpToPx(76);
        params.setMargins(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        frame.setLayoutParams(params);

        if ("Clear".equals(keyDef.digit) || "⌫".equals(keyDef.digit)) {
            TextView text = new TextView(context);
            text.setText(keyDef.digit);
            text.setTextColor(mTextColor);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, "Clear".equals(keyDef.digit) ? 14 : 22);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            text.setLayoutParams(textParams);
            frame.addView(text);
            return frame;
        }

        // Circular background drawable with press state
        StateListDrawable stateBg = new StateListDrawable();

        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setShape(GradientDrawable.OVAL);
        pressedBg.setColor(mKeyPressedColor);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setShape(GradientDrawable.OVAL);
        normalBg.setColor(mKeyColor);

        stateBg.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        stateBg.addState(new int[]{}, normalBg);

        frame.setBackground(stateBg);

        LinearLayout keyContent = new LinearLayout(context);
        keyContent.setOrientation(LinearLayout.VERTICAL);
        keyContent.setGravity(Gravity.CENTER);

        TextView digitTv = new TextView(context);
        digitTv.setText(keyDef.digit);
        digitTv.setTextColor(mTextColor);
        digitTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        digitTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        digitTv.setGravity(Gravity.CENTER);
        keyContent.addView(digitTv);

        if (keyDef.letters != null && !keyDef.letters.isEmpty()) {
            TextView lettersTv = new TextView(context);
            lettersTv.setText(keyDef.letters);
            lettersTv.setTextColor(mTextSecondaryColor);
            lettersTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            lettersTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lettersTv.setGravity(Gravity.CENTER);
            keyContent.addView(lettersTv);
        }

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        keyContent.setLayoutParams(contentParams);
        frame.addView(keyContent);

        return frame;
    }

    private void updateDotState(View dot, boolean filled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        if (filled) {
            drawable.setColor(mAccentColor);
        } else {
            drawable.setColor(mDotUnfilledColor);
            drawable.setStroke(dpToPx(1), mDotUnfilledStroke);
        }
        dot.setBackground(drawable);
    }

    private void refreshDots() {
        int len = mEnteredPin.length();
        for (int i = 0; i < mPinDots.size(); i++) {
            updateDotState(mPinDots.get(i), i < len);
        }
    }

    private void verifyPin() {
        boolean verified = AppLockCredentialUtils.verifyCredential(getContext(), mUserId, mEnteredPin.toString());
        if (verified) {
            if (mListener != null) {
                mListener.onUnlocked();
            }
        } else {
            mErrorView.setText("Incorrect Passcode. Try again.");
            mErrorView.setVisibility(View.VISIBLE);
            shakeView(mPinDotsLayout);
            mEnteredPin.setLength(0);
            refreshDots();
        }
    }

    private void buildPasswordUi(Context context, LinearLayout parent) {
        mPasswordInput = new EditText(context);
        mPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mPasswordInput.setHint("Enter Password");
        mPasswordInput.setHintTextColor(mTextSecondaryColor);
        mPasswordInput.setTextColor(mTextColor);
        mPasswordInput.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(mKeyColor);
        inputBg.setCornerRadius(dpToPx(20));
        mPasswordInput.setBackground(inputBg);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        inputParams.bottomMargin = dpToPx(20);
        mPasswordInput.setLayoutParams(inputParams);
        parent.addView(mPasswordInput);

        Button unlockBtn = new Button(context);
        unlockBtn.setText("Unlock");
        unlockBtn.setTextColor(mBgColor);
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        unlockBtn.setTypeface(null, Typeface.BOLD);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(mAccentColor);
        btnBg.setCornerRadius(dpToPx(20));
        unlockBtn.setBackground(btnBg);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dpToPx(52));
        unlockBtn.setLayoutParams(btnParams);

        unlockBtn.setOnClickListener(v -> {
            String pass = mPasswordInput.getText().toString();
            boolean verified = AppLockCredentialUtils.verifyCredential(getContext(), mUserId, pass);
            if (verified) {
                if (mListener != null) {
                    mListener.onUnlocked();
                }
            } else {
                mErrorView.setText("Incorrect Password.");
                mErrorView.setVisibility(View.VISIBLE);
                shakeView(mPasswordInput);
                mPasswordInput.setText("");
            }
        });
        parent.addView(unlockBtn);
    }

    private void buildPatternUi(Context context, LinearLayout parent) {
        mPatternView = new LockPatternView(context);
        LinearLayout.LayoutParams patternParams = new LinearLayout.LayoutParams(
                dpToPx(300), dpToPx(300));
        patternParams.gravity = Gravity.CENTER_HORIZONTAL;
        mPatternView.setLayoutParams(patternParams);

        mPatternView.setOnPatternListener(new LockPatternView.OnPatternListener() {
            @Override
            public void onPatternStart() {
                mErrorView.setVisibility(View.GONE);
            }

            @Override
            public void onPatternDetected(List<LockPatternView.Cell> pattern, LockPatternView.InputMode inputMode, byte patternSize) {
                if (pattern != null) {
                    String patternStr = pattern.stream()
                            .map(cell -> cell.getRow() + "," + cell.getColumn())
                            .reduce((a, b) -> a + "-" + b)
                            .orElse("");
                    boolean verified = AppLockCredentialUtils.verifyCredential(getContext(), mUserId, patternStr);
                    if (verified) {
                        mPatternView.setDisplayMode(LockPatternView.DisplayMode.Correct);
                        if (mListener != null) {
                            mListener.onUnlocked();
                        }
                    } else {
                        mPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                        mErrorView.setText("Incorrect Pattern.");
                        mErrorView.setVisibility(View.VISIBLE);
                        shakeView(mPatternView);
                    }
                }
            }
        });
        parent.addView(mPatternView);
    }

    private void shakeView(View view) {
        Animation shake = new TranslateAnimation(0, dpToPx(12), 0, 0);
        shake.setDuration(50);
        shake.setRepeatCount(4);
        shake.setRepeatMode(Animation.REVERSE);
        view.startAnimation(shake);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
