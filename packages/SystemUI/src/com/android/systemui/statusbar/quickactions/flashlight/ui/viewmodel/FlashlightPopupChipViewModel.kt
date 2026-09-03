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

package com.android.systemui.statusbar.quickactions.flashlight.ui.viewmodel

import android.content.Context
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.quickactions.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** ViewModel backing the flashlight page inside the dynamic island. */
class FlashlightPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val flashlightController: FlashlightController,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    override val chip: QuickActionChipModel by
        callbackFlow {
                val callback =
                    object : FlashlightController.FlashlightListener {
                        override fun onFlashlightChanged(enabled: Boolean) {
                            trySend(readFlashlightState())
                        }

                        override fun onFlashlightError() {
                            trySend(readFlashlightState())
                        }

                        override fun onFlashlightAvailabilityChanged(available: Boolean) {
                            trySend(readFlashlightState())
                        }
                    }

                flashlightController.addCallback(callback)
                trySend(readFlashlightState())
                awaitClose { flashlightController.removeCallback(callback) }
            }
            .map(::toPopupChipModel)
            .combine(
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.FLASHLIGHT,
                )
            ) { model, enabled ->
                if (enabled) model else QuickActionChipModel.Hidden(QuickActionChipId.Flashlight)
            }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.Flashlight),
            )

    private fun toPopupChipModel(state: FlashlightState): QuickActionChipModel {
        if (!state.hasFlashlight || !state.isAvailable || !state.isEnabled) {
            return QuickActionChipModel.Hidden(QuickActionChipId.Flashlight)
        }

        val model =
            FlashlightPopupModel(
                levelPercent = null,
                turnOff = { flashlightController.setFlashlight(false) },
            )

        val contentDescription =
            ContentDescription.Resource(R.string.quick_settings_flashlight_label)

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.Flashlight,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_flashlight,
                                contentDescription = contentDescription,
                            ),
                    )
                ),
            chipContent = ChipContent.Text(context.getString(R.string.dynamic_island_flashlight_short)),
            colors = ChipColors.DynamicIsland,
            contentDescription = contentDescription,
            popupContent = PopupContentModel.Flashlight(model),
        )
    }

    private fun readFlashlightState(): FlashlightState {
        return FlashlightState(
            hasFlashlight = flashlightController.hasFlashlight(),
            isAvailable = flashlightController.isAvailable(),
            isEnabled = flashlightController.isEnabled(),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): FlashlightPopupChipViewModel
    }
}

private data class FlashlightState(
    val hasFlashlight: Boolean,
    val isAvailable: Boolean,
    val isEnabled: Boolean,
)
