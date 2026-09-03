/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel

import android.content.Context
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel.Starting.Companion.toCountdownSeconds
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** ViewModel backing the screen-recording page inside the dynamic island. */
class ScreenRecordPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val interactor: ScreenRecordChipInteractor,
    private val systemClock: SystemClock,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    private val popupModel: Flow<ScreenRecordPopupModel?> =
        interactor.screenRecordState
            .map { state ->
                when (state) {
                    is ScreenRecordChipModel.DoingNothing -> null
                    is ScreenRecordChipModel.Starting ->
                        ScreenRecordPopupModel.Starting(
                            secondsUntilStarted = state.millisUntilStarted.toCountdownSeconds()
                        )
                    is ScreenRecordChipModel.Recording ->
                        ScreenRecordPopupModel.Recording(
                            startElapsedRealtimeMs = systemClock.elapsedRealtime(),
                            stopRecording = interactor::stopRecording,
                        )
                }
            }
            .pairwise(initialValue = null)
            .map { (old, new) ->
                if (
                    old is ScreenRecordPopupModel.Recording &&
                        new is ScreenRecordPopupModel.Recording
                ) {
                    new.copy(startElapsedRealtimeMs = old.startElapsedRealtimeMs)
                } else {
                    new
                }
            }

    override val chip: QuickActionChipModel by
        popupModel
            .map { model -> toPopupChipModel(model) }
            .combine(
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.SCREEN_RECORDING,
                )
            ) { model, enabled ->
                if (enabled) model else QuickActionChipModel.Hidden(QuickActionChipId.ScreenRecord)
            }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.ScreenRecord),
            )

    private fun toPopupChipModel(model: ScreenRecordPopupModel?): QuickActionChipModel {
        if (model == null) {
            return QuickActionChipModel.Hidden(QuickActionChipId.ScreenRecord)
        }

        val recordingDescription =
            ContentDescription.Resource(R.string.screenrecord_ongoing_screen_only)

        val chipText =
            when (model) {
                is ScreenRecordPopupModel.Starting -> model.secondsUntilStarted.toString()
                is ScreenRecordPopupModel.Recording ->
                    context.getString(R.string.dynamic_island_screen_record_short)
            }

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.ScreenRecord,
            icons =
                listOf(
                    ChipIcon(
                        Icon.Resource(
                            resId = R.drawable.ic_screenrecord,
                            contentDescription = recordingDescription,
                        )
                    )
                ),
            chipContent = ChipContent.Text(chipText),
            colors = ChipColors.DynamicIslandAlert,
            contentDescription = recordingDescription,
            popupContent = PopupContentModel.ScreenRecord(model),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): ScreenRecordPopupChipViewModel
    }
}
