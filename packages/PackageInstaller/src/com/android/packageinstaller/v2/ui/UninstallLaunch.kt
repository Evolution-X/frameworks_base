/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider

import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.PackageUtil
import com.android.packageinstaller.v2.model.PackageUtil.localLogv
import com.android.packageinstaller.v2.model.UninstallAborted
import com.android.packageinstaller.v2.model.UninstallFailed
import com.android.packageinstaller.v2.model.UninstallRepository
import com.android.packageinstaller.v2.model.UninstallStage
import com.android.packageinstaller.v2.model.UninstallSuccess
import com.android.packageinstaller.v2.ui.fragments.UninstallationFragment
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel
import com.android.packageinstaller.v2.viewmodel.UninstallViewModelFactory

class UninstallLaunch : FragmentActivity(), UninstallActionListener {

    companion object {
        @JvmField val EXTRA_CALLING_PKG_UID =
            UninstallLaunch::class.java.packageName + ".callingPkgUid"
        @JvmField val EXTRA_CALLING_ACTIVITY_NAME =
            UninstallLaunch::class.java.packageName + ".callingActivityName"
        private val LOG_TAG = UninstallLaunch::class.java.simpleName
        private const val TAG_DIALOG = "dialog"
        private const val TAG_UNINSTALLATION_DIALOG = "uninstallation-dialog"
        private const val ARGS_SAVED_INTENT = "saved_intent"
    }

    private var uninstallViewModel: UninstallViewModel? = null
    private var uninstallRepository: UninstallRepository? = null
    private var fragmentManager: FragmentManager? = null
    private var notificationManager: NotificationManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)

        // Never restore any state, esp. never create any fragments. The data in the fragment might
        // be stale, if e.g. the app was uninstalled while the activity was destroyed.
        super.onCreate(null)

        // The base theme inherits a deviceDefault theme. Applying a material style on the base
        // theme to support the material design.
        if (PackageUtil.isMaterialDesignEnabled(this)) {
            Log.d(LOG_TAG, "Apply material design")
            theme.applyStyle(R.style.Theme_AlertDialogActivity_Material, /* force= */ true)
        }

        fragmentManager = supportFragmentManager
        notificationManager = getSystemService(NotificationManager::class.java)

        uninstallRepository = UninstallRepository(applicationContext)
        uninstallViewModel = ViewModelProvider(
            this, UninstallViewModelFactory(this.application, uninstallRepository!!)
        ).get(UninstallViewModel::class.java)

        val intent = intent
        val callerInfo = UninstallRepository.CallerInfo(
            intent.getStringExtra(EXTRA_CALLING_ACTIVITY_NAME),
            intent.getIntExtra(EXTRA_CALLING_PKG_UID, Process.INVALID_UID)
        )

        var savedIntent: Intent? = null
        if (savedInstanceState != null) {
            savedIntent = savedInstanceState.getParcelable(ARGS_SAVED_INTENT, Intent::class.java)
        }
        if (!intent.filterEquals(savedIntent)) {
            uninstallViewModel!!.preprocessIntent(intent, callerInfo)
        }

        uninstallViewModel!!.currentUninstallStage.observe(this) { uninstallStage: UninstallStage ->
            onUninstallStageChange(uninstallStage)
        }
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs / fragments based on the
     * uninstall stage
     */
    private fun onUninstallStageChange(uninstallStage: UninstallStage) {
        when (uninstallStage.stageCode) {
            UninstallStage.STAGE_ABORTED -> {
                val aborted = uninstallStage as UninstallAborted
                when (aborted.abortReason) {
                    UninstallAborted.ABORT_REASON_APP_UNAVAILABLE,
                    UninstallAborted.ABORT_REASON_UNKNOWN,
                    UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED -> {
                        showUninstallationDialog()
                    }

                    else -> {
                        setResult(aborted.activityResultCode, null, true)
                    }
                }
            }

            UninstallStage.STAGE_USER_ACTION_REQUIRED -> {
                showUninstallationDialog()
            }

            UninstallStage.STAGE_FAILED -> {
                val failed = uninstallStage as UninstallFailed
                if (!failed.returnResult) {
                    notificationManager!!.notify(
                        failed.uninstallNotificationId!!, failed.uninstallNotification
                    )
                }
                setResult(failed.activityResultCode, failed.resultIntent, true)
            }

            UninstallStage.STAGE_SUCCESS -> {
                val success = uninstallStage as UninstallSuccess
                val message = success.getMessage(this)
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                setResult(success.activityResultCode, success.resultIntent, true)
            }

            else -> {
                Log.e(LOG_TAG, "Invalid stage: " + uninstallStage.stageCode)
                showDialogInner(null)
            }
        }
    }

    private fun showUninstallationDialog() {
        val fragment = getUninstallationFragment() ?: UninstallationFragment()
        fragment.updateUI()
        showDialogInner(fragment, TAG_UNINSTALLATION_DIALOG)
    }

    /**
     * Replace any visible dialog by the dialog returned by UninstallRepository with the tag
     * TAG_DIALOG.
     *
     * @param newDialog The new dialog to display
     */
    private fun showDialogInner(newDialog: DialogFragment?) {
        showDialogInner(newDialog, TAG_DIALOG)
    }
    private fun showDialogInner(newDialog: DialogFragment?, tag: String) {
        var currentTag: String? = null
        if (tag == TAG_UNINSTALLATION_DIALOG) {
            if (getUninstallationFragment() != null) {
                return
            }
            currentTag = TAG_DIALOG
        } else {
            currentTag = TAG_UNINSTALLATION_DIALOG
        }

        val currentDialog = fragmentManager!!.findFragmentByTag(currentTag)
        if (currentDialog is DialogFragment) {
            currentDialog.dismissAllowingStateLoss()
        }
        newDialog?.show(fragmentManager!!, tag)
    }

    private fun getUninstallationFragment(): UninstallationFragment? {
        return (fragmentManager!!.findFragmentByTag(TAG_UNINSTALLATION_DIALOG)
            ?: return null) as UninstallationFragment?
    }

    fun setResult(resultCode: Int, data: Intent?, shouldFinish: Boolean) {
        super.setResult(resultCode, data)
        if (shouldFinish) {
            finish()
        }
    }

    override fun onPositiveResponse(keepData: Boolean) {
        if (localLogv) {
            Log.d(LOG_TAG, "Staring uninstall")
        }
        uninstallViewModel!!.initiateUninstall(keepData)
    }

    override fun onAppSnippetClick() {
        if (localLogv) {
            Log.d(LOG_TAG, "onAppSnippetClick")
        }
        val repository = uninstallViewModel!!.repository
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            setData(Uri.fromParts("package", repository.targetPackageName!!, null))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAsUser(intent, repository.uninstalledUser!!)
    }

    override fun onNegativeResponse() {
        if (localLogv) {
            Log.d(LOG_TAG, "Cancelling uninstall")
        }
        uninstallViewModel!!.cancelUninstall()
        setResult(RESULT_FIRST_USER, null, true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(ARGS_SAVED_INTENT, intent)
        super.onSaveInstanceState(outState)
    }
}
