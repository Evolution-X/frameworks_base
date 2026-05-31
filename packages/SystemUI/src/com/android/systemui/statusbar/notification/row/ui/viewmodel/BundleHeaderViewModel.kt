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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.notifications.ui.composable.row.BundleHeader
import com.android.systemui.statusbar.notification.row.dagger.BundleRowScope
import com.android.systemui.statusbar.notification.row.domain.interactor.BundleInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope

class BundleHeaderViewModel @AssistedInject constructor(private val interactor: BundleInteractor) :
    HydratedActivatable() {

    val titleText: Int
        get() = interactor.titleText

    val numberOfChildren: Int?
        get() = interactor.numberOfChildren

    val bundleIcon: Int
        get() = interactor.bundleIcon

    val previewIcons: List<Drawable> by
        interactor.previewIcons.hydratedStateOf(
            traceName = "previewIcons",
            initialValue = emptyList(),
        )

    var state: MutableSceneTransitionLayoutState? by interactor::state

    var composeScope: CoroutineScope? by interactor::composeScope

    var backgroundDrawable by mutableStateOf<Drawable?>(null)

    var useBlurBackground by mutableStateOf(false)
    
    var isOnKeyguard by mutableStateOf(false)

    val numberOfChildrenContentDescription: String
        get() = interactor.numberOfChildrenContentDescription

    val headerContentDescription: String
        get() = interactor.headerContentDescription

    fun onHeaderClicked() {
        val targetScene =
            when (state?.currentScene) {
                BundleHeader.Scenes.Collapsed -> BundleHeader.Scenes.Expanded
                BundleHeader.Scenes.Expanded -> BundleHeader.Scenes.Collapsed
                null -> {
                    Log.e(TAG, "Unexpected scene: ${state?.currentScene}")
                    return
                }
                else -> error("Unknown Scene.")
            }
        interactor.setTargetScene(targetScene)
    }

    fun setExpansionState(isExpanded: Boolean) = interactor.setExpansionState(isExpanded)
    
    fun setIsOnKeyguard(isOnKeyguard: Boolean) {
        this.isOnKeyguard = isOnKeyguard
    }

    @AssistedFactory
    @BundleRowScope
    interface Factory {
        fun create(): BundleHeaderViewModel
    }

    companion object {
        const val TAG = "BundleHeaderViewModel"
    }
}
