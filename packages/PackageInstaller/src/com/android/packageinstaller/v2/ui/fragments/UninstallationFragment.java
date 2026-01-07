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

package com.android.packageinstaller.v2.ui.fragments;

import static android.text.format.Formatter.formatFileSize;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.UninstallAborted;
import com.android.packageinstaller.v2.model.UninstallStage;
import com.android.packageinstaller.v2.model.UninstallUserActionRequired;
import com.android.packageinstaller.v2.ui.UiUtil;
import com.android.packageinstaller.v2.ui.UninstallActionListener;
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel;

/**
 * Dialog to show while requesting uninstalling an app.
 */
public class UninstallationFragment extends DialogFragment {

    private static final String LOG_TAG = UninstallationFragment.class.getSimpleName();
    private UninstallActionListener mUninstallActionListener;
    private Dialog mDialog;

    private ImageView mAppIcon = null;
    private TextView mAppLabelTextView = null;
    private View mAppSnippet = null;
    private CheckBox mKeepDataCheckbox = null;
    private View mKeepDataLayout = null;
    private TextView mCustomMessageTextView = null;
    private TextView mKeepDataBytesTextView = null;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mUninstallActionListener = (UninstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final UninstallStage uninstallStage = getCurrentUninstallStage();
        Log.i(LOG_TAG, "Creating " + LOG_TAG + "\n" + uninstallStage);

        // There is no root view here. Ok to pass null view root
        @SuppressWarnings("InflateParams")
        View dialogView = getLayoutInflater().inflate(R.layout.uninstall_fragment_layout, null);
        mAppSnippet = dialogView.requireViewById(R.id.app_snippet);
        mAppIcon = dialogView.requireViewById(R.id.app_icon);
        mAppLabelTextView = dialogView.requireViewById(R.id.app_label);
        mCustomMessageTextView = dialogView.requireViewById(R.id.custom_message);
        mKeepDataLayout = dialogView.requireViewById(R.id.keep_data_layout);
        mKeepDataBytesTextView = mKeepDataLayout.requireViewById(R.id.keep_data_bytes);
        mKeepDataCheckbox = mKeepDataLayout.requireViewById(R.id.keep_data_checkbox);

        mDialog = UiUtil.getAlertDialog(requireContext(), getString(R.string.title_uninstall),
                dialogView, R.string.button_uninstall, R.string.button_cancel,
                /* positiveBtnListener= */ null, (dialogInt, which) ->
                        mUninstallActionListener.onNegativeResponse());

        return mDialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mUninstallActionListener.onNegativeResponse();
    }

    @Override
    public void onStart() {
        super.onStart();
        updateUI();
    }

    private UninstallStage getCurrentUninstallStage() {
        return new ViewModelProvider(requireActivity()).get(UninstallViewModel.class)
                .getCurrentUninstallStage().getValue();
    }

    /**
     * Update the UI based on the current uninstall stage
     */
    public void updateUI() {
        if (!isAdded()) {
            return;
        }

        // Get the current uninstall stage
        final UninstallStage uninstallStage = getCurrentUninstallStage();

        switch (uninstallStage.getStageCode()) {
            case UninstallStage.STAGE_ABORTED -> {
                updateUninstallAbortedUI(mDialog, (UninstallAborted) uninstallStage);
            }
            case UninstallStage.STAGE_USER_ACTION_REQUIRED -> {
                updateUserActionRequiredUI(mDialog, (UninstallUserActionRequired) uninstallStage);
            }
        }

        UiUtil.updateButtonBarLayoutIfNeeded(requireContext(), mDialog);
    }

    private void updateUninstallAbortedUI(Dialog dialog, UninstallAborted uninstallStage) {
        mKeepDataLayout.setVisibility(View.GONE);
        mCustomMessageTextView.setVisibility(View.VISIBLE);

        if (uninstallStage.getAppIcon() != null) {
            mAppSnippet.setVisibility(View.VISIBLE);
            mAppIcon.setImageDrawable(uninstallStage.getAppIcon());
            mAppLabelTextView.setText(uninstallStage.getAppLabel());
        } else {
            mAppSnippet.setVisibility(View.GONE);
        }

        // Set the title and the message
        dialog.setTitle(uninstallStage.getDialogTitleResource());
        mCustomMessageTextView.setText(uninstallStage.getDialogTextResource());

        // Hide the positive button
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.GONE);
        }

        // Set the negative button and the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setText(R.string.button_close);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mUninstallActionListener.onNegativeResponse();
            });
        }
    }

    private void updateUserActionRequiredUI(Dialog dialog,
            UninstallUserActionRequired uninstallStage) {
        mAppSnippet.setVisibility(View.VISIBLE);

        mAppSnippet.setOnClickListener(view -> {
            mUninstallActionListener.onAppSnippetClick();
        });

        // Set app icon and label
        mAppIcon.setImageDrawable(uninstallStage.getAppIcon());
        mAppLabelTextView.setText(uninstallStage.getAppLabel(requireContext()));

        // Set title
        dialog.setTitle(uninstallStage.getTitle(requireContext()));

        // Set custom message
        final String message = uninstallStage.getMessage(requireContext());
        if (message != null) {
            mCustomMessageTextView.setVisibility(View.VISIBLE);
            mCustomMessageTextView.setText(message);
        } else {
            mCustomMessageTextView.setVisibility(View.GONE);
        }

        // Set keep data information
        long appDataSize = uninstallStage.getAppDataSize();
        if (appDataSize != 0) {
            mKeepDataLayout.setVisibility(View.VISIBLE);

            mKeepDataBytesTextView.setText(formatFileSize(getContext(), appDataSize));
            mKeepDataLayout.setOnClickListener(v -> mKeepDataCheckbox.toggle());
        } else {
            mKeepDataLayout.setVisibility(View.GONE);
        }

        // Set the positive button and the listener
        Button positiveButton = UiUtil.getAlertDialogPositiveButton(dialog);
        if (positiveButton != null) {
            positiveButton.setVisibility(View.VISIBLE);
            positiveButton.setText(uninstallStage.getPositiveButtonResId());
            positiveButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mUninstallActionListener.onPositiveResponse(mKeepDataCheckbox.isChecked());
            });
        }

        // Set the negative button and the listener
        Button negativeButton = UiUtil.getAlertDialogNegativeButton(dialog);
        if (negativeButton != null) {
            negativeButton.setText(R.string.button_cancel);
            negativeButton.setOnClickListener(view -> {
                // Set clickable of the button to false to avoid the user clicks it
                // more than once quickly
                view.setClickable(false);
                mUninstallActionListener.onNegativeResponse();
            });
        }
    }
}
