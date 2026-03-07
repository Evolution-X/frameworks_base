/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.volume.dialog.percentage.ui.viewmodel

import com.android.systemui.volume.dialog.percentage.domain.VolumeDialogVolumePercentageInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

class VolumeDialogVolumePercentageViewModel
@Inject
constructor(private val interactor: VolumeDialogVolumePercentageInteractor) {
    val percentage: StateFlow<String> = interactor.percentage
    val isVisible: StateFlow<Boolean> = interactor.isVisible
}
