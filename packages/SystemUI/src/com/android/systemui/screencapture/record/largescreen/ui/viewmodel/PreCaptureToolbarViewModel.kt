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

package com.android.systemui.screencapture.record.largescreen.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.screencapture.ScreenCaptureEvent
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.LargeScreenCaptureFeaturesInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.LargeScreenCaptureParametersInteractor
import com.android.systemui.screencapture.record.largescreen.ui.DirectoryPickerActivity
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.settings.UserTracker
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.text.split
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Models the UI for the large-screen pre-capture toolbar. */
class PreCaptureToolbarViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val uiEventLogger: UiEventLogger,
    private val userTracker: UserTracker,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val largeScreenCaptureParametersInteractor: LargeScreenCaptureParametersInteractor,
    private val drawableLoaderViewModel: DrawableLoaderViewModel,
    featuresInteractor: LargeScreenCaptureFeaturesInteractor,
    recordParametersViewModelFactory: ScreenCaptureRecordParametersViewModel.Factory,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModel {
    private val toolbarBoundsSource = MutableStateFlow(Rect())
    private val toolbarOpacitySource = MutableStateFlow(1f)

    val recordParametersViewModel = recordParametersViewModelFactory.create()

    val appWindowRegionSupported = featuresInteractor.appWindowRegionSupported

    val screenRecordingSupported = featuresInteractor.screenRecordingSupported

    val customSaveLocationSupported = featuresInteractor.customSaveLocationSupported

    val regionRecordingSupported = featuresInteractor.regionRecordingSupported

    val showClicksAndKeysSupported = featuresInteractor.showClicksAndKeysSupported

    val frontCameraSupported =
        (featuresInteractor.frontCameraSupported || Flags.newScreenRecordToolbarSelfie()) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

    val customSaveLocationUriString: String by
        largeScreenCaptureParametersInteractor.customSaveLocationUriString.hydratedStateOf(
            initialValue = ""
        )

    val isCustomSaveLocationActive: Boolean by
        largeScreenCaptureParametersInteractor.isCustomSaveLocationActive.hydratedStateOf(
            initialValue = false
        )

    val customSaveLocationDisplayName: String? by derivedStateOf {
        if (customSaveLocationUriString.isEmpty()) {
            null
        } else {
            customSaveLocationUriString
                .toUri()
                .lastPathSegment
                ?.split(":")
                ?.last()
                ?.split("/")
                ?.last()
        }
    }

    val currentSaveLocationString: String by derivedStateOf {
        if (!isCustomSaveLocationActive) {
            Environment.DIRECTORY_SCREENSHOTS
        } else {
            customSaveLocationDisplayName ?: Environment.DIRECTORY_SCREENSHOTS
        }
    }

    val currentSaveLocationUri: Uri? by derivedStateOf {
        if (isCustomSaveLocationActive && customSaveLocationUriString.isNotEmpty()) {
            customSaveLocationUriString.toUri()
        } else {
            null
        }
    }

    val toolbarOpacity: Float by toolbarOpacitySource.hydratedStateOf()

    fun setToolbarBounds(bounds: Rect) {
        toolbarBoundsSource.value = bounds
    }

    /**
     * Updates the toolbar opacity based on whether the region box selection intersects with the
     * toolbar, and whether the region box is resized or moved.
     *
     * @param isInteracting Whether the region box is currently resized or moved.
     * @param regionBoxBounds The current bounds of the region box.
     */
    fun updateOpacityForRegionBox(isInteracting: Boolean, regionBoxBounds: Rect?) {
        if (isInteracting) {
            toolbarOpacitySource.value = 0f
            return
        }

        // When interaction stops, or a region is selected, calculate the final opacity.
        if (regionBoxBounds == null) {
            toolbarOpacitySource.value = 1f
            return
        }

        val toolbarBounds = toolbarBoundsSource.value
        if (!toolbarBounds.isEmpty && Rect.intersects(regionBoxBounds, toolbarBounds)) {
            toolbarOpacitySource.value = 0.15f
        } else {
            toolbarOpacitySource.value = 1f
        }
    }

    fun requestLaunchDirectoryPicker() {
        screenCaptureUiInteractor.hide(ScreenCaptureType.RECORD)

        val intent = Intent(context, DirectoryPickerActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivityAsUser(intent, userTracker.userHandle)
    }

    fun setCustomSaveLocationActiveStatus(activeStatus: Boolean) {
        backgroundScope.launch {
            if (isCustomSaveLocationActive != activeStatus) {
                largeScreenCaptureParametersInteractor.setIsCustomSaveLocationActive(activeStatus)
            }
        }
    }

    fun recordClose() {
        uiEventLogger.log(ScreenCaptureEvent.SCREEN_CAPTURE_LARGE_SCREEN_CLOSE_UI_WITHOUT_CAPTURE)
    }

    override suspend fun onActivated() {
        coroutineScope { launch { recordParametersViewModel.activate() } }
    }

    @AssistedFactory
    interface Factory {
        fun create(): PreCaptureToolbarViewModel
    }
}
