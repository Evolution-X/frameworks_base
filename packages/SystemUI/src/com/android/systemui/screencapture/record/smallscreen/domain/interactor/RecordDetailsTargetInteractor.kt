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

package com.android.systemui.screencapture.record.smallscreen.domain.interactor

import android.view.Display
import com.android.app.tracing.coroutines.flow.stateInTraced
import com.android.systemui.common.shared.model.Text
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.mediaprojection.permission.MediaProjectionPermissionUtils
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ScreenCaptureUi
import com.android.systemui.screencapture.common.ScreenCaptureUiScope
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureLabelInteractor
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureMarkupInteractor
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureRecentTaskInteractor
import com.android.systemui.screencapture.common.domain.model.ScreenCaptureRecentTask
import com.android.systemui.screencapture.record.camera.domain.interactor.ScreenRecordCameraSurfaceInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordParametersInteractor
import com.android.systemui.screencapture.record.smallscreen.data.repository.RecordDetailsTargetRepository
import com.android.systemui.screencapture.record.smallscreen.shared.model.RecordDetailsTargetModel
import com.android.systemui.screencapture.record.smallscreen.shared.model.SmallScreenRecordTargetsModel
import com.android.systemui.screencapture.record.smallscreen.shared.model.currentTargetModel
import com.android.systemui.screencapture.record.smallscreen.ui.SmallScreenPostRecordingActivity
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@ScreenCaptureUiScope
class RecordDetailsTargetInteractor
@Inject
constructor(
    @ScreenCaptureUi private val display: Display,
    @ScreenCaptureUi private val coroutineScope: CoroutineScope,
    private val recordDetailsTargetRepository: RecordDetailsTargetRepository,
    recordingServiceInteractor: ScreenRecordingServiceInteractor,
    recentTaskInteractor: ScreenCaptureRecentTaskInteractor,
    private val screenCaptureLabelInteractor: ScreenCaptureLabelInteractor,
    private val parametersInteractor: ScreenCaptureRecordParametersInteractor,
    private val cameraInteractor: ScreenRecordCameraSurfaceInteractor,
    private val markupInteractor: ScreenCaptureMarkupInteractor,
    displayRepository: DisplayRepository,
) {

    val recentTasks: StateFlow<List<ScreenCaptureRecentTask>> =
        recentTaskInteractor.recentTasks
            .map { it.withoutPostRecordingActivity() }
            .stateInTraced(
                "RecordDetailsTargetInteractor#recentTasks",
                coroutineScope,
                SharingStarted.Eagerly,
                emptyList(),
            )
    private val items: StateFlow<List<RecordDetailsTargetModel>> =
        combine(
                displayRepository.displays.map { displays ->
                    MediaProjectionPermissionUtils.filterProjectableConnectedDisplays(displays)
                        .filter { it.displayId != display.displayId }
                        .sortedBy { it.name }
                },
                recentTasks,
                recordDetailsTargetRepository.currentlySelectedTask,
            ) { displays, recentTasks, currentlySelectedTask ->
                buildList(capacity = displays.size + 2) {
                    add(
                        RecordDetailsTargetModel.EntireScreen(
                            display = display,
                            label = Text.Resource(R.string.screen_record_entire_screen),
                        )
                    )
                    add(
                        if (recentTasks.isEmpty()) {
                            RecordDetailsTargetModel.SingleAppNoRecents
                        } else {
                            val task = currentlySelectedTask ?: recentTasks.first()
                            RecordDetailsTargetModel.SingleApp(
                                task = task,
                                appLabel = screenCaptureLabelInteractor.loadLabel(task).getOrNull(),
                            )
                        }
                    )
                    for (display in displays) {
                        add(
                            RecordDetailsTargetModel.EntireScreen(
                                display = display,
                                label = Text.Loaded(display.name),
                            )
                        )
                    }
                }
            }
            .stateInTraced(
                "RecordDetailsTargetInteractor#items",
                coroutineScope,
                SharingStarted.Eagerly,
                listOf(
                    RecordDetailsTargetModel.EntireScreen(
                        display = display,
                        label = Text.Resource(R.string.screen_record_entire_screen),
                    )
                ),
            )

    val canChangeTarget: StateFlow<Boolean> =
        recordingServiceInteractor.status
            .map { it.canChangeTarget() }
            .stateInTraced(
                "RecordDetailsTargetInteractor#canChangeTarget",
                coroutineScope,
                SharingStarted.Eagerly,
                recordingServiceInteractor.status.value.canChangeTarget(),
            )

    val model: StateFlow<SmallScreenRecordTargetsModel> =
        combine(items, recordDetailsTargetRepository.selectedIndex) { items, selectedIndex ->
                SmallScreenRecordTargetsModel(
                    items = items,
                    selectedIndex =
                        selectedIndex.coerceIn(items.indices).takeIf { items[it].isSelectable }
                            ?: items.indexOfFirst { it is RecordDetailsTargetModel.EntireScreen },
                )
            }
            .stateInTraced(
                "RecordDetailsTargetInteractor#model",
                coroutineScope,
                SharingStarted.Eagerly,
                SmallScreenRecordTargetsModel(items = items.value, selectedIndex = 0),
            )

    init {
        model
            .onEach {
                val selectedTask = it.currentTargetModel
                if (!selectedTask.canUseCamera) {
                    // Don't write the preference. Switching back to the full screen restores it.
                    parametersInteractor.suspendFrontCamera()
                    cameraInteractor.stopStream()
                } else {
                    parametersInteractor.restoreFrontCamera()
                }
                if (!selectedTask.canUseMarkup) {
                    markupInteractor.setEnabled(false)
                }
            }
            .launchIn(coroutineScope)
    }

    fun selectItem(index: Int) {
        recordDetailsTargetRepository.selectIndex(index)
    }

    fun selectTask(task: ScreenCaptureRecentTask?) {
        recordDetailsTargetRepository.selectTask(task)
    }
}

private fun ScreenRecordingStatus.canChangeTarget(): Boolean = this is ScreenRecordingStatus.Stopped

private fun List<ScreenCaptureRecentTask>.withoutPostRecordingActivity():
    List<ScreenCaptureRecentTask> = filter { task ->
    SmallScreenPostRecordingActivity::class.qualifiedName != task.component?.className
}
