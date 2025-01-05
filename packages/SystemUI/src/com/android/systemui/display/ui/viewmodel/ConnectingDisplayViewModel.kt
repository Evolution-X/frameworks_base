/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.display.ui.viewmodel

import android.app.Dialog
import android.content.Context
import android.os.SystemProperties
import android.provider.Settings.Secure.MIRROR_BUILT_IN_DISPLAY
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowInsets.Type.displayCutout
import android.view.WindowInsets.Type.navigationBars
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.window.DesktopExperienceFlags
import com.android.app.displaylib.ExternalDisplayConnectionType
import com.android.app.displaylib.ExternalDisplayConnectionType.DESKTOP
import com.android.app.displaylib.ExternalDisplayConnectionType.MIRROR
import com.android.app.displaylib.ExternalDisplayConnectionType.NOT_SPECIFIED
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.server.policy.feature.flags.Flags
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.Utils.getInsetsOf
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.KioskModeRepository
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.ui.view.ExternalDisplayConnectionDialogDelegate
import com.android.systemui.display.ui.view.MirroringConfirmationDialogDelegate
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIBottomSheetDialog
import com.android.systemui.statusbar.phone.SystemUIBottomSheetDialog.WindowLayout
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.settings.SecureSettings
import com.android.wm.shell.shared.desktopmode.DesktopState
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext

/**
 * Shows/hides a dialog to allow the user to decide whether to use the external display for
 * mirroring.
 */
@SysUISingleton
class ConnectingDisplayViewModel
@Inject
constructor(
    private val context: Context,
    private val desktopState: DesktopState,
    private val secureSettings: SecureSettings,
    private val kioskModeRepository: KioskModeRepository,
    private val connectedDisplayInteractor: ConnectedDisplayInteractor,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val bottomSheetFactoryDeprecated: MirroringConfirmationDialogDelegate.Factory,
    private val delegateFactory: ExternalDisplayConnectionDialogDelegate.Factory,
    private val dialogFactory: SystemUIBottomSheetDialog.Factory,
    private val externalDisplayDialogWindowLayout: WindowLayout.ExternalDisplayDialogWindowLayout,
) : CoreStartable {

    private var dialog: Dialog? = null

    /** Starts listening for pending displays. */
    @OptIn(FlowPreview::class)
    override fun start() {
        val pendingDisplayFlow = connectedDisplayInteractor.pendingDisplay
        val kioskModeFlow = kioskModeRepository.isInKioskMode
        val concurrentDisplaysInProgressFlow =
            if (Flags.enableDualDisplayBlocking()) {
                connectedDisplayInteractor.concurrentDisplaysInProgress
            } else {
                flow { emit(false) }
            }

        // Let's debounce for 2 reasons:
        // - prevent fast dialog flashes where pending displays are available for just a few millis
        // - prevent jumps related to inset changes: when in 3 buttons navigation, device unlock
        //   triggers a change in insets that might result in a jump of the dialog (if a display was
        //   connected while on the lockscreen).
        val debouncedPendingDisplayFlow = pendingDisplayFlow.debounce(200.milliseconds)

        combine(debouncedPendingDisplayFlow, kioskModeFlow, concurrentDisplaysInProgressFlow) {
                pendingDisplay,
                isInKioskMode,
                concurrentDisplaysInProgress ->
                if (pendingDisplay == null) {
                    dismissDialog()
                } else {
                    handleNewPendingDisplay(
                        pendingDisplay,
                        isInKioskMode,
                        concurrentDisplaysInProgress,
                    )
                }
            }
            .launchIn(scope)
    }

    @Deprecated("Use showNewDialog instead")
    private fun showMirroringDialog(
        pendingDisplay: PendingDisplay,
        concurrentDisplaysInProgress: Boolean,
    ) {
        dismissDialog()

        if (SystemProperties.getBoolean(DISABLE_MIRRORING_CONFIRMATION_DIALOG, false)) {
            scope.launch(context = bgDispatcher) { pendingDisplay.enable() }
            return
        }

        dialog =
            bottomSheetFactoryDeprecated
                .createDialog(
                    onStartMirroringClickListener = {
                        scope.launch(context = bgDispatcher) { pendingDisplay.enable() }
                        dismissDialog()
                    },
                    onCancelMirroring = {
                        scope.launch(context = bgDispatcher) { pendingDisplay.ignore() }
                        dismissDialog()
                    },
                    navbarBottomInsetsProvider = { Utils.getNavbarInsets(context).bottom },
                    showConcurrentDisplayInfo = concurrentDisplaysInProgress,
                )
                .apply { show() }
    }

    private fun PendingDisplay.showNewDialog(
        showConcurrentDisplayInfo: Boolean,
        isInKioskMode: Boolean,
    ) {
        var saveChoice = false
        dismissDialog()

        val delegate =
            delegateFactory.create(
                rememberChoiceCheckBoxListener = { _, isChecked -> saveChoice = isChecked },
                onStartDesktopClickListener = { enableFor(DESKTOP, saveChoice = saveChoice) },
                onStartMirroringClickListener = { enableFor(MIRROR, saveChoice = saveChoice) },
                onCancelClickListener = {
                    scope.launch(context = bgDispatcher) { ignore() }
                    dismissDialog()
                },
                insetsProvider = { getInsetsOf(context, displayCutout() or navigationBars()) },
                showConcurrentDisplayInfo = showConcurrentDisplayInfo,
                isInKioskMode = isInKioskMode,
            )

        dialog =
            dialogFactory.create(delegate, externalDisplayDialogWindowLayout).also {
                SystemUIDialog.registerDismissListener(it)
                it.show()
            }
    }

    private suspend fun handleNewPendingDisplay(
        pendingDisplay: PendingDisplay,
        isInKioskMode: Boolean,
        concurrentDisplaysInProgress: Boolean,
    ) {
        val useNewDialog =
            DesktopExperienceFlags.ENABLE_UPDATED_DISPLAY_CONNECTION_DIALOG.isTrue &&
                DesktopExperienceFlags.ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT.isTrue
        if (!useNewDialog) {
            showMirroringDialog(pendingDisplay, concurrentDisplaysInProgress)
            return
        }

        val isInExtendedMode = desktopState.isDesktopModeSupportedOnDisplay(DEFAULT_DISPLAY)

        when {
            isInKioskMode && isInExtendedMode -> {
                pendingDisplay.enableForMirroring()
            }
            isInKioskMode -> {
                dismissDialog()
                pendingDisplay.showNewDialog(concurrentDisplaysInProgress, isInKioskMode = true)
            }
            isInExtendedMode -> {
                pendingDisplay.enableForDesktop()
                showExtendedDisplayConnectionToast()
            }
            else -> {
                when (pendingDisplay.connectionType) {
                    DESKTOP -> pendingDisplay.enableForDesktop()
                    MIRROR -> pendingDisplay.enableForMirroring()
                    NOT_SPECIFIED ->
                        pendingDisplay.showNewDialog(
                            concurrentDisplaysInProgress,
                            isInKioskMode = false,
                        )
                }
            }
        }
    }

    private suspend fun PendingDisplay.enableForDesktop() =
        withContext(bgDispatcher) { applyConnectionChoice(enableMirroring = false) }

    private suspend fun PendingDisplay.enableForMirroring() =
        withContext(bgDispatcher) { applyConnectionChoice(enableMirroring = true) }

    private fun PendingDisplay.enableFor(
        connectionType: ExternalDisplayConnectionType,
        saveChoice: Boolean,
    ) {
        scope.launch(context = bgDispatcher) {
            if (saveChoice) updateConnectionPreference(connectionType)
            when (connectionType) {
                DESKTOP -> applyConnectionChoice(enableMirroring = false)
                MIRROR -> applyConnectionChoice(enableMirroring = true)
                else -> Log.wtf(TAG, "Tried to enable display for unknown mode: $connectionType")
            }
        }

        dismissDialog()
    }

    private suspend fun PendingDisplay.applyConnectionChoice(enableMirroring: Boolean) {
        if (setDisplayMirrorSetting(enableMirroring)) {
            // regardless of mirror setting, display should be enabled on successful update
            enable()
        } else {
            Log.w(TAG, "Failed to update display mirroring, so ignore display $id")
            ignore()
        }
    }

    private suspend fun setDisplayMirrorSetting(enable: Boolean): Boolean =
        withContext(bgDispatcher) {
            val newVal = if (enable) 1 else 0
            val currentVal = secureSettings.getInt(MIRROR_BUILT_IN_DISPLAY, 0)

            if (currentVal == newVal) return@withContext true
            return@withContext secureSettings.putInt(MIRROR_BUILT_IN_DISPLAY, newVal)
        }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun showExtendedDisplayConnectionToast() =
        Toast.makeText(context, R.string.connected_display_extended_mode_text, LENGTH_LONG).show()

    @Module
    interface StartableModule {
        @Binds
        @IntoMap
        @ClassKey(ConnectingDisplayViewModel::class)
        fun bindsConnectingDisplayViewModel(impl: ConnectingDisplayViewModel): CoreStartable
    }

    private companion object {
        const val TAG: String = "ConnectingDisplayViewModel"
        private const val DISABLE_MIRRORING_CONFIRMATION_DIALOG =
            "persist.sys.disable_mirroring_confirmation_dialog"
    }
}
