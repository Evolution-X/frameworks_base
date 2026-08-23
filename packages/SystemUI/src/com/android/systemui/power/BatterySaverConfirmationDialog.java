/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.power;

import static com.android.settingslib.fuelgauge.BatterySaverLogging.SAVER_ENABLED_CONFIRMATION;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;

import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;

import java.lang.ref.WeakReference;

import javax.inject.Inject;

/**
 * First-time Battery Saver confirmation dialog, shown when the user enables Battery Saver for the
 * first time (e.g. from the QS tile). Lets the user choose between Standard Battery Saver and
 * Extreme Battery Saver (Flipendo) before turning it on.
 */
public class BatterySaverConfirmationDialog {

    private static final String TAG = "BatterySaverConfirmationDialog";
    private static final String INTERACTION_JANK_TAG = "start_power_saver";

    private static final String ACTION_FLIPENDO_ONBOARDING =
            "android.settings.batterysaver.flipendo.onboarding";

    private final Context mApplicationContext;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final ActivityStarter mActivityStarter;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final BatteryController mBatteryController;

    @VisibleForTesting
    boolean mIsStandardMode = true;
    @VisibleForTesting
    SystemUIDialog mConfirmationDialog;

    @Inject
    public BatterySaverConfirmationDialog(
            Context context,
            ActivityStarter activityStarter,
            DialogTransitionAnimator dialogTransitionAnimator,
            BatteryController batteryController,
            SystemUIDialog.Factory systemUIDialogFactory) {
        mApplicationContext = context.getApplicationContext();
        mActivityStarter = activityStarter;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mBatteryController = batteryController;
        mSystemUIDialogFactory = systemUIDialogFactory;
    }

    /** Builds and shows the confirmation dialog. */
    public void show() {
        if (mConfirmationDialog != null) {
            if (!mConfirmationDialog.isShowing()) {
                mConfirmationDialog.show();
            }
            return;
        }
        final SystemUIDialog d = mSystemUIDialogFactory.create();
        d.setTitle(R.string.saver_confirmation_dialog_title);
        d.setMessage(R.string.saver_confirmation_dialog_subtitle);

        final View content = LayoutInflater.from(d.getContext()).inflate(
                R.layout.battery_saver_confirmation_content, null);
        final RadioButton standardButton = content.findViewById(R.id.standard_button);
        final RadioButton extremeButton = content.findViewById(R.id.extreme_button);
        content.findViewById(R.id.standard_option_layout).setOnClickListener(v -> {
            mIsStandardMode = true;
            standardButton.setChecked(true);
            extremeButton.setChecked(false);
        });
        content.findViewById(R.id.extreme_option_layout).setOnClickListener(v -> {
            mIsStandardMode = false;
            standardButton.setChecked(false);
            extremeButton.setChecked(true);
        });

        // Offer Flipendo onboarding when it is available; hide the entry point otherwise.
        final Button setupButton = content.findViewById(R.id.setup_button);
        if (mApplicationContext.getPackageManager()
                .resolveActivity(new Intent(ACTION_FLIPENDO_ONBOARDING), 0) != null) {
            setupButton.setOnClickListener(v -> {
                d.dismiss();
                mActivityStarter.startActivity(new Intent(ACTION_FLIPENDO_ONBOARDING),
                        true /* dismissShade */);
            });
        } else {
            setupButton.setVisibility(View.GONE);
        }

        d.setView(content);
        d.setShowForAllUsers(true);
        d.setCanceledOnTouchOutside(true);
        d.setPositiveButton(R.string.battery_saver_confirmation_ok, (dialog, which) -> {
            dialog.dismiss();
            ThreadUtils.postOnBackgroundThread(this::applySelection);
        });
        d.setNegativeButton(android.R.string.cancel, null);
        d.setOnDismissListener(dialog -> mConfirmationDialog = null);
        mConfirmationDialog = d;

        // Launch the dialog with a transition from the tile that triggered it, if available.
        WeakReference<Expandable> ref = mBatteryController.getLastPowerSaverStartExpandable();
        Expandable expandable = ref != null ? ref.get() : null;
        DialogTransitionAnimator.Controller controller =
                expandable != null
                        ? expandable.dialogTransitionController(
                                new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                        INTERACTION_JANK_TAG))
                        : null;
        if (controller != null) {
            mDialogTransitionAnimator.show(d, controller);
        } else {
            d.show();
        }
    }

    @VisibleForTesting
    void applySelection() {
        if (!mIsStandardMode) {
            FlipendoUtils.applyExtremeSaverMode(mApplicationContext);
        }
        // needFirstTimeWarning=false marks the warning as acknowledged, so this dialog is not
        // shown again.
        BatterySaverUtils.setPowerSaveMode(mApplicationContext, true,
                false /* needFirstTimeWarning */, SAVER_ENABLED_CONFIRMATION);
    }
}
