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

package com.android.systemui.screencapture.record.largescreen.ui.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ui.compose.RadioButtonGroup
import com.android.systemui.screencapture.common.ui.compose.RadioButtonGroupItem
import com.android.systemui.screencapture.common.ui.compose.Toolbar
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureRegion
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.record.largescreen.ui.viewmodel.PreCaptureToolbarViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreCaptureToolbar(
    viewModel: PreCaptureToolbarViewModel,
    selectedCaptureType: ScreenCaptureType,
    selectedCaptureRegion: ScreenCaptureRegion,
    onCaptureTypeSelected: (ScreenCaptureType) -> Unit,
    onCaptureRegionSelected: (ScreenCaptureRegion) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recordButtonLabel = stringResource(R.string.screen_capture_toolbar_record_button)
    val screenshotButtonLabel = stringResource(R.string.screen_capture_toolbar_screenshot_button)

    val recordIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screenrecord,
            contentDescription = null,
        )
    val screenshotSelectedIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screen_capture_camera,
            contentDescription = null,
        )
    val screenshotUnselectedIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screen_capture_camera_outline,
            contentDescription = null,
        )

    val captureTypeButtonItems =
        listOf(
            RadioButtonGroupItem(
                icon = recordIcon,
                label = recordButtonLabel,
                isSelected = selectedCaptureType == ScreenCaptureType.RECORDING,
                onClick = { onCaptureTypeSelected(ScreenCaptureType.RECORDING) },
                hasTooltip = false,
            ),
            RadioButtonGroupItem(
                selectedIcon = screenshotSelectedIcon,
                unselectedIcon = screenshotUnselectedIcon,
                label = screenshotButtonLabel,
                isSelected = selectedCaptureType == ScreenCaptureType.SCREENSHOT,
                onClick = { onCaptureTypeSelected(ScreenCaptureType.SCREENSHOT) },
                hasTooltip = false,
            ),
        )

    val appWindowButtonContentDescription =
        stringResource(
            when (selectedCaptureType) {
                ScreenCaptureType.SCREENSHOT ->
                    R.string.screen_capture_toolbar_app_window_button_screenshot_a11y
                ScreenCaptureType.RECORDING ->
                    R.string.screen_capture_toolbar_app_window_button_record_a11y
            }
        )
    val partialButtonContentDescription =
        stringResource(
            when (selectedCaptureType) {
                ScreenCaptureType.SCREENSHOT ->
                    R.string.screen_capture_toolbar_region_button_screenshot_a11y
                ScreenCaptureType.RECORDING ->
                    R.string.screen_capture_toolbar_region_button_record_a11y
            }
        )
    val fullscreenButtonContentDescription =
        stringResource(
            when (selectedCaptureType) {
                ScreenCaptureType.SCREENSHOT ->
                    R.string.screen_capture_toolbar_fullscreen_button_screenshot_a11y
                ScreenCaptureType.RECORDING ->
                    R.string.screen_capture_toolbar_fullscreen_button_record_a11y
            }
        )

    val fullscreenIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screen_capture_fullscreen,
            contentDescription = null,
        )
    val partialRegionIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screen_capture_region,
            contentDescription = null,
        )
    val appWindowIcon by
        loadIcon(
            viewModel = viewModel,
            resId = R.drawable.ic_screen_capture_window,
            contentDescription = null,
        )

    val captureRegionButtonItems = buildList {
        if (viewModel.appWindowRegionSupported) {
            add(
                RadioButtonGroupItem(
                    icon = appWindowIcon,
                    isSelected = (selectedCaptureRegion == ScreenCaptureRegion.APP_WINDOW),
                    onClick = { onCaptureRegionSelected(ScreenCaptureRegion.APP_WINDOW) },
                    contentDescription = appWindowButtonContentDescription,
                    hasTooltip = true,
                )
            )
        }

        if (
            selectedCaptureType == ScreenCaptureType.SCREENSHOT ||
                viewModel.regionRecordingSupported
        ) {
            add(
                RadioButtonGroupItem(
                    icon = partialRegionIcon,
                    isSelected = (selectedCaptureRegion == ScreenCaptureRegion.PARTIAL),
                    onClick = { onCaptureRegionSelected(ScreenCaptureRegion.PARTIAL) },
                    contentDescription = partialButtonContentDescription,
                    hasTooltip = true,
                )
            )
        }

        add(
            RadioButtonGroupItem(
                icon = fullscreenIcon,
                isSelected = (selectedCaptureRegion == ScreenCaptureRegion.FULLSCREEN),
                onClick = { onCaptureRegionSelected(ScreenCaptureRegion.FULLSCREEN) },
                contentDescription = fullscreenButtonContentDescription,
                hasTooltip = true,
            )
        )
    }

    Toolbar(
        expanded = true,
        onCloseClick = {
            viewModel.recordClose()
            onCloseClick()
        },
        modifier =
            modifier
                .onGloballyPositioned {
                    val boundsInWindow = it.boundsInWindow().roundToIntRect().toAndroidRect()
                    viewModel.setToolbarBounds(boundsInWindow)
                }
                .graphicsLayer { alpha = viewModel.toolbarOpacity },
    ) {
        Row {
            if (viewModel.screenRecordingSupported) {
                CaptureSettingsMenu(
                    viewModel,
                    screenRecordingSelected = selectedCaptureType == ScreenCaptureType.RECORDING,
                    frontCameraAllowed = selectedCaptureRegion == ScreenCaptureRegion.FULLSCREEN,
                )
            }

            Spacer(Modifier.size(8.dp))

            RadioButtonGroup(items = captureRegionButtonItems)

            if (viewModel.screenRecordingSupported) {
                Spacer(Modifier.size(16.dp))

                RadioButtonGroup(items = captureTypeButtonItems)
            }
        }
    }
}
