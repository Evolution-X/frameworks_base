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

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.StatusBarVisibilityInteractor
import com.android.systemui.statusbar.quickactions.assistant.StatusBarAssistantIcon
import com.android.systemui.statusbar.quickactions.assistant.ui.viewmodel.AssistantIconViewModel
import com.android.systemui.statusbar.quickactions.av.ui.viewmodel.AvControlsChipViewModel
import com.android.systemui.statusbar.quickactions.domain.interactor.QuickActionsInteractor
import com.android.systemui.statusbar.quickactions.ime.ui.viewmodel.ImeIndicatorChipViewModel
import com.android.systemui.statusbar.quickactions.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.quickactions.popups.StatusBarPopupChips
import com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.LargeScreenRecordingChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionPanelModel
import com.android.systemui.statusbar.quickactions.sharescreen.ui.viewmodel.ShareScreenPrivacyIndicatorViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
    private val quickActionsInteractor: QuickActionsInteractor,
    private val statusBarVisibilityInteractor: StatusBarVisibilityInteractor,
    mediaControlChipFactory: MediaControlChipViewModel.Factory,
    avControlsChipFactory: AvControlsChipViewModel.Factory,
    shareScreenPrivacyIndicatorFactory: ShareScreenPrivacyIndicatorViewModel.Factory,
    assistantIconFactory: AssistantIconViewModel.Factory,
    imeIndicatorChipFactory: ImeIndicatorChipViewModel.Factory,
    largeScreenRecordingChipViewModelFactory: LargeScreenRecordingChipViewModel.Factory,
) : HydratedActivatable() {

    private val mediaControlChip by lazy { mediaControlChipFactory.create() }
    private val avControlsChip by lazy { avControlsChipFactory.create() }
    private val shareScreenPrivacyIndicator by lazy { shareScreenPrivacyIndicatorFactory.create() }
    private val assistantIcon by lazy { assistantIconFactory.create() }
    private val imeIndicatorChip by lazy { imeIndicatorChipFactory.create(displayId) }
    private val largeScreenRecordingChip by lazy {
        largeScreenRecordingChipViewModelFactory.create()
    }

    /** The ID of the current chip that is currently active, or `null` if no chip is active. */
    private val currentActiveQuickActionId: QuickActionChipId?
        get() = quickActionsInteractor.activePanel?.chipId.takeIf { isShadeWindowOnThisDisplay }

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
        )
    }

    val shownQuickActionChips: List<QuickActionChipModel> by derivedStateOf {
        val bundle = incomingQuickActionChipBundle
        val candidateChips =
            if (StatusBarPopupChips.isEnabled) {
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
                chip.copy(
                    isPopupShown = chip.chipId == currentActiveQuickActionId,
                    togglePopup = { _, anchorBounds ->
                        chip.popupViewModelFactory?.let { factory ->
                            quickActionsInteractor.toggle(
                                QuickActionPanelModel(
                                    chipId = chip.chipId,
                                    anchorBounds = anchorBounds,
                                    panelContentViewModelFactory = factory,
                                )
                            )
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
            launch { avControlsChip.activate() }
            launch { mediaControlChip.activate() }
            launch { shareScreenPrivacyIndicator.activate() }
            launch { largeScreenRecordingChip.activate() }
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
    ) {
        val asList: List<QuickActionChipModel>
            get() = listOf(media, privacy, shareScreen, assistant, ime, largeScreenRecording)
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): StatusBarPopupChipsViewModel
    }
}
