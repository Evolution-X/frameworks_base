package com.android.systemui.tuner;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;

import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService.Tunable;

public class TunerSwitch extends SwitchPreferenceCompat implements Tunable {

    private final boolean mDefault;
    private final int mAction;

    public TunerSwitch(Context context, AttributeSet attrs) {
        super(context, attrs, androidx.preference.R.attr.switchPreferenceCompatStyle);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TunerSwitch);
        mDefault = a.getBoolean(R.styleable.TunerSwitch_defValue, false);
        mAction = a.getInt(R.styleable.TunerSwitch_metricsAction, -1);
        a.recycle();

        setupTheme();
    }

    private void setupTheme() {
        Drawable icon = getIcon();
        if (icon != null) {
            TypedArray a = getContext().obtainStyledAttributes(
                    new int[]{android.R.attr.textColorPrimary});
            int color = a.getColor(0, 0);
            a.recycle();
            Drawable wrappedIcon = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(wrappedIcon, color);
            setIcon(wrappedIcon);
        }
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, getKey().split(","));
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        setChecked(TunerService.parseIntegerSwitch(newValue, mDefault));
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (mAction != -1) {
            MetricsLogger.action(getContext(), mAction, isChecked());
        }
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        TunerService tunerService = Dependency.get(TunerService.class);
        for (String key : getKey().split(",")) {
            tunerService.setValue(key, value ? "1" : "0");
        }
        return true;
    }

}
