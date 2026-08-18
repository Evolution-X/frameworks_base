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

package com.android.systemui.screenshot

import android.app.assist.AssistContent
import android.util.Log
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.UUID
import java.util.function.Consumer

/**
 * Responsible for obtaining the actions for each screenshot and sending them to the view model.
 * Ensures that only actions from screenshots that are currently being shown are added to the view
 * model.
 */
class ScreenshotActionsController
@AssistedInject
constructor(
    private val viewModel: ScreenshotViewModel,
    private val actionsProviderFactory: ScreenshotActionsProvider.Factory,
    @Assisted val actionExecutor: ActionExecutor,
    @Assisted private val onSaveToStorageRequested: (Consumer<ScreenshotSavedResult?>) -> Unit,
) {
    private val actionProviders: MutableMap<UUID, ScreenshotActionsProvider> = mutableMapOf()
    private var currentScreenshotId: UUID? = null

    fun setCurrentScreenshot(screenshot: ScreenshotData): UUID {
        val screenshotId = UUID.randomUUID()
        currentScreenshotId = screenshotId
        actionProviders[screenshotId] =
            actionsProviderFactory.create(
                screenshotId,
                screenshot,
                actionExecutor,
                ActionsCallback(screenshotId),
            )
        return screenshotId
    }

    fun endScreenshotSession() {
        currentScreenshotId = null
    }

    fun onAssistContent(screenshotId: UUID, assistContent: AssistContent?) {
        actionProviders[screenshotId]?.onAssistContent(assistContent)
    }

    fun onScrollChipReady(screenshotId: UUID, onClick: ScrollClickCallback) {
        if (screenshotId == currentScreenshotId) {
            actionProviders[screenshotId]?.onScrollChipReady(onClick)
        }
    }

    fun onScrollChipInvalidated() {
        for (provider in actionProviders.values) {
            provider.onScrollChipInvalidated()
        }
    }

    fun setCompletedScreenshot(screenshotId: UUID, result: ScreenshotSavedResult) {
        if (screenshotId == currentScreenshotId) {
            actionProviders[screenshotId]?.setCompletedScreenshot(result)
        }
    }

    @AssistedFactory
    interface Factory {
        fun getController(
            actionExecutor: ActionExecutor,
            onSaveToStorageRequested: (Consumer<ScreenshotSavedResult?>) -> Unit,
        ): ScreenshotActionsController
    }

    inner class ActionsCallback(private val screenshotId: UUID) {
        fun providePreviewAction(previewAction: PreviewAction) {
            if (screenshotId == currentScreenshotId) {
                viewModel.setPreviewAction(previewAction)
            }
        }

        fun provideActionButton(
            appearance: ActionButtonAppearance,
            showDuringEntrance: Boolean,
            onClick: () -> Unit,
        ): Int {
            if (screenshotId == currentScreenshotId) {
                return viewModel.addAction(appearance, showDuringEntrance, onClick)
            }
            return 0
        }

        fun updateActionButtonAppearance(buttonId: Int, appearance: ActionButtonAppearance) {
            if (screenshotId == currentScreenshotId) {
                viewModel.updateActionAppearance(buttonId, appearance)
            }
        }

        fun updateActionButtonVisibility(buttonId: Int, visible: Boolean) {
            if (screenshotId == currentScreenshotId) {
                viewModel.setActionVisibility(buttonId, visible)
            }
        }

        fun saveToStorage() {
            if (screenshotId != currentScreenshotId) {
                return
            }
            onSaveToStorageRequested(
                Consumer { result ->
                    if (result != null) {
                        setCompletedScreenshot(screenshotId, result)
                    } else {
                        Log.e(TAG, "Failed to save screenshot to storage on demand")
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "ScreenshotActionsCtrl"
    }
}
