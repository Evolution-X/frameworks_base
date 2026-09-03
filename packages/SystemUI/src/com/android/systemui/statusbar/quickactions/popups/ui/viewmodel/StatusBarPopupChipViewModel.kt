/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.popups.ui.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.StatusBarVisibilityInteractor
import com.android.systemui.statusbar.quickactions.alarm.ui.viewmodel.AlarmPopupChipViewModel
import com.android.systemui.statusbar.quickactions.assistant.StatusBarAssistantIcon
import com.android.systemui.statusbar.quickactions.assistant.ui.viewmodel.AssistantIconViewModel
import com.android.systemui.statusbar.quickactions.av.ui.viewmodel.AvControlsChipViewModel
import com.android.systemui.statusbar.quickactions.domain.interactor.QuickActionsInteractor
import com.android.systemui.statusbar.quickactions.flashlight.ui.viewmodel.FlashlightPopupChipViewModel
import com.android.systemui.statusbar.quickactions.ime.ui.viewmodel.ImeIndicatorChipViewModel
import com.android.systemui.statusbar.quickactions.livescore.ui.viewmodel.LiveScorePopupChipViewModel
import com.android.systemui.statusbar.quickactions.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.quickactions.popups.StatusBarPopupChips
import com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.LargeScreenRecordingChipViewModel
import com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.ScreenRecordPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionPanelModel
import com.android.systemui.statusbar.quickactions.sharescreen.ui.viewmodel.ShareScreenPrivacyIndicatorViewModel
import com.android.systemui.statusbar.quickactions.stopwatch.ui.viewmodel.StopwatchPopupChipViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * View model deciding which system process chips to show in the status bar. Emits a list of
 * [QuickActionChipModel]s.
 */
class StatusBarPopupChipsViewModel
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Application private val context: Context,
    private val quickActionsInteractor: QuickActionsInteractor,
    private val statusBarVisibilityInteractor: StatusBarVisibilityInteractor,
    mediaControlChipFactory: MediaControlChipViewModel.Factory,
    avControlsChipFactory: AvControlsChipViewModel.Factory,
    shareScreenPrivacyIndicatorFactory: ShareScreenPrivacyIndicatorViewModel.Factory,
    assistantIconFactory: AssistantIconViewModel.Factory,
    imeIndicatorChipFactory: ImeIndicatorChipViewModel.Factory,
    largeScreenRecordingChipViewModelFactory: LargeScreenRecordingChipViewModel.Factory,
    screenRecordChipFactory: ScreenRecordPopupChipViewModel.Factory,
    liveScoreChipFactory: LiveScorePopupChipViewModel.Factory,
    flashlightChipFactory: FlashlightPopupChipViewModel.Factory,
    stopwatchChipFactory: StopwatchPopupChipViewModel.Factory,
    alarmChipFactory: AlarmPopupChipViewModel.Factory,
) : HydratedActivatable() {

    private val mediaControlChip by lazy { mediaControlChipFactory.create() }
    private val avControlsChip by lazy { avControlsChipFactory.create() }
    private val shareScreenPrivacyIndicator by lazy { shareScreenPrivacyIndicatorFactory.create() }
    private val assistantIcon by lazy { assistantIconFactory.create() }
    private val imeIndicatorChip by lazy { imeIndicatorChipFactory.create(displayId) }
    private val largeScreenRecordingChip by lazy {
        largeScreenRecordingChipViewModelFactory.create()
    }
    private val screenRecordChip by lazy { screenRecordChipFactory.create() }
    private val liveScoreChip by lazy { liveScoreChipFactory.create() }
    private val flashlightChip by lazy { flashlightChipFactory.create() }
    private val stopwatchChip by lazy { stopwatchChipFactory.create() }
    private val alarmChip by lazy { alarmChipFactory.create() }

    private var isDynamicIslandEnabled by mutableStateOf(readDynamicIslandEnabled())
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isDynamicIslandEnabled = readDynamicIslandEnabled()
                if (!isDynamicIslandEnabled) {
                    currentShownPopupChipId = null
                }
            }
        }

    /** The ID of the current chip that is currently active, or `null` if no chip is active. */
    private val currentActiveQuickActionId: QuickActionChipId?
        get() = quickActionsInteractor.activePanel?.chipId.takeIf { isShadeWindowOnThisDisplay }

    private var currentShownPopupChipId by mutableStateOf<QuickActionChipId?>(null)

    private val isShadeWindowOnThisDisplay by
        statusBarVisibilityInteractor.isShadeWindowOnThisDisplay.hydratedStateOf(
            traceName = "isShadeWindowOnThisDisplay"
        )

    private val incomingQuickActionChipBundle: QuickActionChipBundle by derivedStateOf {
        QuickActionChipBundle(
            media = mediaControlChip.chip,
            privacy = avControlsChip.chip,
            shareScreen = shareScreenPrivacyIndicator.chip,
            assistant = assistantIcon.chip,
            ime = imeIndicatorChip.chip,
            largeScreenRecording = largeScreenRecordingChip.chip,
            screenRecord = screenRecordChip.chip,
            liveScore = liveScoreChip.chip,
            flashlight = flashlightChip.chip,
            stopwatch = stopwatchChip.chip,
            alarm = alarmChip.chip,
        )
    }

    val shownQuickActionChips: List<QuickActionChipModel> by derivedStateOf {
        val bundle = incomingQuickActionChipBundle
        val candidateChips =
            if (isDynamicIslandEnabled) {
                listOfNotNull(
                    bundle.screenRecord,
                    bundle.liveScore,
                    bundle.flashlight,
                    bundle.stopwatch,
                    bundle.alarm,
                    bundle.media,
                    bundle.privacy.takeIf { StatusBarPopupChips.isEnabled },
                    bundle.shareScreen.takeIf { StatusBarPopupChips.isEnabled },
                )
            } else if (StatusBarPopupChips.isEnabled) {
                listOfNotNull(
                    bundle.media,
                    bundle.privacy,
                    bundle.shareScreen,
                    bundle.largeScreenRecording,
                )
            } else {
                // Keep media ticker available even when popup chips modernization is disabled.
                listOfNotNull(bundle.media)
            }

        candidateChips
            .filterIsInstance<QuickActionChipModel.PopupChip>()
            .map { chip ->
                val isShown =
                    (chip.chipId == currentActiveQuickActionId) ||
                        (chip.chipId == currentShownPopupChipId)
                chip.copy(
                    isPopupShown = isShown,
                    showPopup = { currentShownPopupChipId = chip.chipId },
                    hidePopup = {
                        if (currentShownPopupChipId == chip.chipId) {
                            currentShownPopupChipId = null
                        }
                        quickActionsInteractor.close()
                    },
                    togglePopup = { _, anchorBounds ->
                        chip.popupViewModelFactory?.let { factory ->
                            quickActionsInteractor.toggle(
                                QuickActionPanelModel(
                                    chipId = chip.chipId,
                                    anchorBounds = anchorBounds,
                                    panelContentViewModelFactory = factory,
                                )
                            )
                        } ?: run {
                            if (currentShownPopupChipId == chip.chipId) {
                                currentShownPopupChipId = null
                            } else {
                                currentShownPopupChipId = chip.chipId
                            }
                        }
                    },
                )
            } +
            (if (StatusBarPopupChips.isEnabled) {
                listOfNotNull(bundle.assistant, bundle.ime).filter {
                    it !is QuickActionChipModel.Hidden
                }
            } else {
                emptyList()
            })
    }

    override suspend fun onActivated() {
        coroutineScope {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND
                ),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            dynamicIslandObserver.onChange(false)

            launch { avControlsChip.activate() }
            launch { mediaControlChip.activate() }
            launch { shareScreenPrivacyIndicator.activate() }
            launch { largeScreenRecordingChip.activate() }
            launch { screenRecordChip.activate() }
            launch { liveScoreChip.activate() }
            launch { flashlightChip.activate() }
            launch { stopwatchChip.activate() }
            launch { alarmChip.activate() }
            if (StatusBarAssistantIcon.isEnabled) {
                launch { assistantIcon.activate() }
            }
            launch { imeIndicatorChip.activate() }

            launch {
                snapshotFlow {
                        val activeId = currentActiveQuickActionId ?: return@snapshotFlow false
                        val bundle = incomingQuickActionChipBundle

                        bundle.asList.find { it.chipId == activeId } is QuickActionChipModel.Hidden
                    }
                    .filter { isHidden -> isHidden }
                    .collect { quickActionsInteractor.close() }
            }

            launch {
                snapshotFlow {
                        val activeId = currentShownPopupChipId ?: return@snapshotFlow false
                        val bundle = incomingQuickActionChipBundle

                        bundle.asList.find { it.chipId == activeId } is QuickActionChipModel.Hidden
                    }
                    .filter { isHidden -> isHidden }
                    .collect { currentShownPopupChipId = null }
            }

            try {
                awaitCancellation()
            } finally {
                context.contentResolver.unregisterContentObserver(dynamicIslandObserver)
            }
        }
    }

    private data class QuickActionChipBundle(
        val media: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.MediaControl),
        val privacy: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.AvControlsIndicator),
        val shareScreen: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ShareScreenPrivacyIndicator),
        val assistant: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.AssistantIcon),
        val ime: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ImeIndicator),
        val largeScreenRecording: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ScreenRecording),
        val screenRecord: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.ScreenRecord),
        val liveScore: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.LiveScore),
        val flashlight: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.Flashlight),
        val stopwatch: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.Stopwatch),
        val alarm: QuickActionChipModel =
            QuickActionChipModel.Hidden(chipId = QuickActionChipId.Alarm),
    ) {
        val asList: List<QuickActionChipModel>
            get() =
                listOf(
                    media,
                    privacy,
                    shareScreen,
                    assistant,
                    ime,
                    largeScreenRecording,
                    screenRecord,
                    liveScore,
                    flashlight,
                    stopwatch,
                    alarm,
                )
    }

    private fun readDynamicIslandEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): StatusBarPopupChipsViewModel
    }
}
