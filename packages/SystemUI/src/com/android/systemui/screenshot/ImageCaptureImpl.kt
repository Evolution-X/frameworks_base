/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.IActivityTaskManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.IWindowManager
import android.window.ScreenCaptureInternal
import android.window.ScreenCaptureInternal.CaptureArgs
import android.window.TaskSnapshotManager
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@SysUISingleton
open class ImageCaptureImpl
@Inject
constructor(
    private val context: Context,
    private val windowManager: IWindowManager,
    private val atmService: IActivityTaskManager,
    @Background private val bgContext: CoroutineDispatcher,
) : ImageCapture {

    override fun captureDisplay(displayId: Int, crop: Rect?): Bitmap? {
        val captureArgs =
            CaptureArgs.Builder().setSourceCrop(getSourceCrop(displayId, crop)).build()
        val syncScreenCapture = ScreenCaptureInternal.createSyncCaptureListener()
        windowManager.captureDisplay(displayId, captureArgs, syncScreenCapture)
        val buffer = syncScreenCapture.getBuffer()
        return buffer?.asBitmap()
    }

    override suspend fun captureTask(taskId: Int): Bitmap? {
        val snapshot =
            withContext(bgContext) {
                TaskSnapshotManager.getInstance()
                    .takeTaskSnapshot(
                        taskId,
                        false /* updateCache */,
                        false /* lowResolution */,
                        true, /* includeDecors */
                    )
            } ?: return null
        return snapshot.wrapToBitmap()
    }

    private fun getSourceCrop(displayId: Int, crop: Rect?): Rect? {
        if (crop != null || displayId != Display.DEFAULT_DISPLAY || !shouldHideStatusBar()) {
            return crop
        }

        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return null
        val displaySize = Point()
        display.getRealSize(displaySize)
        if (displaySize.x <= 0 || displaySize.y <= 0) {
            return null
        }

        val statusBarHeight = getStatusBarHeight(display)
        if (statusBarHeight <= 0 || statusBarHeight >= displaySize.y) {
            return null
        }
        return Rect(0, statusBarHeight, displaySize.x, displaySize.y)
    }

    private fun shouldHideStatusBar(): Boolean {
        return Settings.System.getInt(
            context.contentResolver,
            Settings.System.HIDE_STATUS_BAR_IN_SCREENSHOT,
            0,
        ) == 1
    }

    private fun getStatusBarHeight(display: Display): Int {
        return try {
            val displayContext = context.createDisplayContext(display)
            SystemBarUtils.getStatusBarHeight(displayContext)
        } catch (_: RuntimeException) {
            0
        }
    }
}
