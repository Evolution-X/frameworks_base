/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AnrController;
import android.app.Dialog;
import android.content.Context;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A controller to generate error dialogs in {@link ProcessRecord}.
 */
final class ErrorDialogController {
    private final ProcessRecord mApp;
    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private UserManagerInternal mUserManagerInternal;

    /**
     * Dialogs being displayed due to crash.
     */
    @GuardedBy("mProcLock")
    private List<AppErrorDialog> mCrashDialogs;

    /**
     * Dialogs being displayed due to app not responding.
     */
    @GuardedBy("mProcLock")
    private List<AppNotRespondingDialog> mAnrDialogs;

    /**
     * Dialogs displayed due to strict mode violation.
     */
    @GuardedBy("mProcLock")
    private List<StrictModeViolationDialog> mViolationDialogs;

    /**
     * Current wait for debugger dialog.
     */
    @GuardedBy("mProcLock")
    private AppWaitingForDebuggerDialog mWaitDialog;

    /**
     * ANR dialog controller
     */
    @GuardedBy("mProcLock")
    @Nullable
    private AnrController mAnrController;

    @GuardedBy("mProcLock")
    boolean hasCrashDialogs() {
        return mCrashDialogs != null;
    }

    @GuardedBy("mProcLock")
    List<AppErrorDialog> getCrashDialogs() {
        return mCrashDialogs;
    }

    @GuardedBy("mProcLock")
    boolean hasAnrDialogs() {
        return mAnrDialogs != null;
    }

    @GuardedBy("mProcLock")
    List<AppNotRespondingDialog> getAnrDialogs() {
        return mAnrDialogs;
    }

    @GuardedBy("mProcLock")
    boolean hasViolationDialogs() {
        return mViolationDialogs != null;
    }

    @GuardedBy("mProcLock")
    boolean hasDebugWaitingDialog() {
        return mWaitDialog != null;
    }

    @GuardedBy("mProcLock")
    void clearAllErrorDialogs() {
        clearCrashDialogs(false);
        clearAnrDialogs();
        clearViolationDialogs();
        clearWaitingDialog();
    }

    /**
     * If there are any error dialogs on the display, close them and show a corresponding new one on
     * the default display if one doesn't already exist there.
     */
    @GuardedBy("mProcLock")
    void moveAllErrorDialogsToDefaultDisplay(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return;
        }

        moveCrashDialogToDefaultDisplay(displayId);
        moveAnrDialogToDefaultDisplay(displayId);
        moveViolationDialogToDefaultDisplay(displayId);
        moveWaitingDialogToDefaultDisplay(displayId);
    }

    @GuardedBy("mProcLock")
    void clearCrashDialogs() {
        clearCrashDialogs(true /* needDismiss */);
    }

    @GuardedBy("mProcLock")
    void clearCrashDialogs(boolean needDismiss) {
        if (mCrashDialogs == null) {
            return;
        }
        if (needDismiss) {
            scheduleForAllDialogs(mCrashDialogs, Dialog::dismiss);
        }
        mCrashDialogs = null;
    }

    @GuardedBy("mProcLock")
    void clearAnrDialogs() {
        if (mAnrDialogs == null) {
            return;
        }
        scheduleForAllDialogs(mAnrDialogs, Dialog::dismiss);
        mAnrDialogs = null;
        mAnrController = null;
    }

    @GuardedBy("mProcLock")
    void clearViolationDialogs() {
        if (mViolationDialogs == null) {
            return;
        }
        scheduleForAllDialogs(mViolationDialogs, Dialog::dismiss);
        mViolationDialogs = null;
    }

    @GuardedBy("mProcLock")
    void clearWaitingDialog() {
        if (mWaitDialog == null) {
            return;
        }
        final BaseErrorDialog dialog = mWaitDialog;
        mService.mUiHandler.post(dialog::dismiss);
        mWaitDialog = null;
    }

    @GuardedBy("mProcLock")
    void scheduleForAllDialogs(List<? extends BaseErrorDialog> dialogs,
            Consumer<BaseErrorDialog> c) {
        mService.mUiHandler.post(() -> {
            if (dialogs != null) {
                forAllDialogs(dialogs, c);
            }
        });
    }

    void forAllDialogs(List<? extends BaseErrorDialog> dialogs, Consumer<BaseErrorDialog> c) {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            c.accept(dialogs.get(i));
        }
    }

    @GuardedBy("mProcLock")
    void showCrashDialogs(AppErrorDialog.Data data) {
        List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
        mCrashDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mCrashDialogs.add(new AppErrorDialog(c, mService, data));
        }
        mService.mUiHandler.post(() -> {
            List<AppErrorDialog> dialogs;
            synchronized (mProcLock) {
                dialogs = mCrashDialogs;
            }
            if (dialogs != null) {
                forAllDialogs(dialogs, Dialog::show);
            }
        });
    }

    @GuardedBy("mProcLock")
    void showAnrDialogs(AppNotRespondingDialog.Data data) {
        List<Context> contexts = getDisplayContexts(
                mApp.mErrorState.isSilentAnr() /* lastUsedOnly */);
        mAnrDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mAnrDialogs.add(new AppNotRespondingDialog(mService, c, data));
        }
        scheduleForAllDialogs(mAnrDialogs, Dialog::show);
    }

    @GuardedBy("mProcLock")
    void showViolationDialogs(AppErrorResult res) {
        List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
        mViolationDialogs = new ArrayList<>();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            final Context c = contexts.get(i);
            mViolationDialogs.add(
                    new StrictModeViolationDialog(c, mService, res, mApp));
        }
        scheduleForAllDialogs(mViolationDialogs, Dialog::show);
    }

    @GuardedBy("mProcLock")
    void showDebugWaitingDialogs() {
        List<Context> contexts = getDisplayContexts(true /* lastUsedOnly */);
        final Context c = contexts.get(0);
        mWaitDialog = new AppWaitingForDebuggerDialog(mService, c, mApp);

        mService.mUiHandler.post(() -> {
            Dialog dialog;
            synchronized (mProcLock) {
                dialog = mWaitDialog;
            }
            if (dialog != null) {
                dialog.show();
            }
        });
    }

    @GuardedBy("mProcLock")
    @Nullable
    AnrController getAnrController() {
        return mAnrController;
    }

    @GuardedBy("mProcLock")
    void setAnrController(AnrController controller) {
        mAnrController = controller;
    }

    @GuardedBy("mProcLock")
    @Nullable
    private <T extends BaseErrorDialog> T findDialogOnDisplay(@NonNull List<T> dialogs,
            int displayId) {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            T dialog = dialogs.get(i);
            if (dialog == null) {
                continue;
            }

            if (dialog.getContext().getDisplayId() == displayId && dialog.isShowing()) {
                return dialog;
            }
        }
        return null;
    }

    /**
     * If a crash dialog is showing on the display, close it and show a new one on the default
     * display if one doesn't already exist there.
     *
     * @param displayId The display id of the display where the crash dialog is showing and needs to
     *                  be moved.
     */
    @GuardedBy("mProcLock")
    private void  moveCrashDialogToDefaultDisplay(int displayId) {
        if (!hasCrashDialogs()) {
            return;
        }

        AppErrorDialog dialogToRemoved = findDialogOnDisplay(mCrashDialogs, displayId);
        if (dialogToRemoved == null) {
            return;
        }
        AppErrorDialog.Data data = dialogToRemoved.getData();
        mCrashDialogs.remove(dialogToRemoved);
        mService.mUiHandler.post(dialogToRemoved::remove);

        boolean showingInDefaultDisplay =
                (findDialogOnDisplay(mCrashDialogs, Display.DEFAULT_DISPLAY) != null);
        if (showingInDefaultDisplay || data == null) {
            return;
        }

        // If there is no crash dialog showing in the default display, show a new one.
        // TODO(b/412589019): Use WindowContext instead.
        Context c = mService.mWmInternal.getDisplayUiContext(Display.DEFAULT_DISPLAY);
        AppErrorDialog newDialog = new AppErrorDialog(c, mService, data);
        mCrashDialogs.add(newDialog);
        mService.mUiHandler.post(newDialog::show);
    }

    /**
     * If a ANR dialog is showing on the display, close it and show a new one on the default display
     * if one doesn't already exist there.
     *
     * @param displayId The display id of the display where the ANR dialog is showing and needs to
     *                  be moved.
     */
    @GuardedBy("mProcLock")
    private void moveAnrDialogToDefaultDisplay(int displayId) {
        if (!hasAnrDialogs()) {
            return;
        }

        AppNotRespondingDialog dialogToRemoved = findDialogOnDisplay(mAnrDialogs, displayId);
        if (dialogToRemoved == null) {
            return;
        }
        AppNotRespondingDialog.Data data = dialogToRemoved.getData();
        mAnrDialogs.remove(dialogToRemoved);
        mService.mUiHandler.post(dialogToRemoved::dismiss);

        boolean showingInDefaultDisplay =
                (findDialogOnDisplay(mAnrDialogs, Display.DEFAULT_DISPLAY) != null);
        if (showingInDefaultDisplay || data == null) {
            return;
        }

        // If there is no ANR dialog showing in the default display, show a new one.
        // TODO(b/412589019): Use WindowContext instead.
        Context c = mService.mWmInternal.getDisplayUiContext(Display.DEFAULT_DISPLAY);
        AppNotRespondingDialog newDialog = new AppNotRespondingDialog(mService, c, data);
        mAnrDialogs.add(newDialog);
        mService.mUiHandler.post(newDialog::show);
    }

    /**
     * If a strict mode violation dialog is showing on the display, close it and show a new one on
     * the default display if one doesn't already exist there.
     *
     * @param displayId The display id of the display where the violation dialog is showing and
     *                  needs to be moved.
     */
    @GuardedBy("mProcLock")
    private void moveViolationDialogToDefaultDisplay(int displayId) {
        if (!hasViolationDialogs()) {
            return;
        }

        StrictModeViolationDialog dialogToRemoved =
                findDialogOnDisplay(mViolationDialogs, displayId);
        if (dialogToRemoved == null) {
            return;
        }
        AppErrorResult result = dialogToRemoved.getResult();
        mViolationDialogs.remove(dialogToRemoved);
        mService.mUiHandler.post(dialogToRemoved::dismiss);

        boolean showingInDefaultDisplay =
                (findDialogOnDisplay(mViolationDialogs, Display.DEFAULT_DISPLAY) != null);
        if (showingInDefaultDisplay || result == null) {
            return;
        }

        // If there is no violation dialog showing in the default display, show a new one.
        // TODO(b/412589019): Use WindowContext instead.
        Context c = mService.mWmInternal.getDisplayUiContext(Display.DEFAULT_DISPLAY);
        StrictModeViolationDialog newDialog =
                new StrictModeViolationDialog(c, mService, result, mApp);
        mViolationDialogs.add(newDialog);
        mService.mUiHandler.post(newDialog::show);
    }

    /**
     * If a debug waiting dialog is showing on the display, close it and show a new one on the
     * default display if one doesn't already exist there.
     *
     * @param displayId The display id of the display where the waiting dialog is showing and needs
     *                  to be moved.
     */
    @GuardedBy("mProcLock")
    private void moveWaitingDialogToDefaultDisplay(int displayId) {
        if (mWaitDialog == null || mWaitDialog.getContext().getDisplayId() != displayId) {
            return;
        }

        // The debug waiting dialog is showing on the display, remove this and show a new one on the
        // default display.
        mWaitDialog.dismiss();
        Context c = mService.mWmInternal.getDisplayUiContext(Display.DEFAULT_DISPLAY);
        mWaitDialog = new AppWaitingForDebuggerDialog(mService, c, mApp);
        mService.mUiHandler.post(mWaitDialog::show);
    }


    /**
     * Helper function to collect contexts from crashed app located displays.
     *
     * @param lastUsedOnly Sets to {@code true} to indicate to only get last used context.
     *                     Sets to {@code false} to collect contexts from crashed app located
     *                     displays.
     *
     * @return display context list.
     */
    private List<Context> getDisplayContexts(boolean lastUsedOnly) {
        List<Context> displayContexts = new ArrayList<>();
        if (!lastUsedOnly) {
            mApp.getWindowProcessController().getDisplayContextsWithErrorDialogs(displayContexts);
        }
        // If there is no foreground window display, fallback to last used display.
        if (displayContexts.isEmpty() || lastUsedOnly) {
            Context uiContext;
            final UserManagerInternal umi = getUserManagerInternal();
            if (Flags.errorDialogForVisibleBackgroundUsers()
                    && umi.isVisibleBackgroundFullUser(mApp.userId)) {
                // For a visible background full user, only use its assigned display.
                int displayId = umi.getMainDisplayAssignedToUser(mApp.userId);
                uiContext = mService.mWmInternal != null
                        ? mService.mWmInternal.getDisplayUiContext(displayId)
                        : mService.mUiContext;
            } else {
                uiContext = mService.mWmInternal != null
                        ? mService.mWmInternal.getTopFocusedDisplayUiContext()
                        : mService.mUiContext;
            }
            displayContexts.add(uiContext);
        }
        return displayContexts;
    }

    private UserManagerInternal getUserManagerInternal() {
        if (mUserManagerInternal == null) {
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        }
        return mUserManagerInternal;
    }

    ErrorDialogController(ProcessRecord app) {
        mApp = app;
        mService = app.mService;
        mProcLock = mService.mProcLock;
    }
}
