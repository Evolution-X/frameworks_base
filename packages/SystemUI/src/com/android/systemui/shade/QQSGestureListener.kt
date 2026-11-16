/*
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.systemui.shade

import android.content.Context
import android.database.ContentObserver
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.CentralSurfaces
import dagger.Lazy
import lineageos.providers.LineageSettings
import javax.inject.Inject

@SysUISingleton
class QQSGestureListener @Inject constructor(
        private val context: Context,
        private val falsingManager: FalsingManager,
        private val powerManager: PowerManager,
        private val statusBarStateController: StatusBarStateController,
        private val centralSurfaces: CentralSurfaces,
        private val shadeInteractor: Lazy<ShadeInteractor>,
) : GestureDetector.SimpleOnGestureListener() {

    private var doubleTapToSleepEnabled = false
    private val quickQsOffsetHeight: Int
    private var tapStartedOnSleepSurface = false
    private var doubleTapStartedOnSleepSurface = false

    init {
        val contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                doubleTapToSleepEnabled = LineageSettings.System.getInt(
                        context.contentResolver, LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE,
                        if (context.resources.getBoolean(org.lineageos.platform.internal.
                                R.bool.config_dt2sGestureEnabledByDefault)) 1 else 0) != 0
            }
        }
        context.contentResolver.registerContentObserver(
                LineageSettings.System.getUriFor(LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE),
                false, contentObserver)
        contentObserver.onChange(true)

        quickQsOffsetHeight = context.resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height)
    }

    override fun onDown(e: MotionEvent): Boolean {
        tapStartedOnSleepSurface = isSleepSurface(e)
        return false
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        tapStartedOnSleepSurface = tapStartedOnSleepSurface && isSleepSurface(e)
        return false
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        // GestureDetector reports this before onDown for the second tap.
        doubleTapStartedOnSleepSurface = tapStartedOnSleepSurface && isSleepSurface(e)
        return false
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        doubleTapStartedOnSleepSurface =
            doubleTapStartedOnSleepSurface && isSleepSurface(e)
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            doubleTapStartedOnSleepSurface = false
            tapStartedOnSleepSurface = false
            return false
        }
        if (e.actionMasked != MotionEvent.ACTION_UP) {
            return false
        }

        val shouldSleep = doubleTapStartedOnSleepSurface && tapStartedOnSleepSurface &&
            !falsingManager.isFalseDoubleTap
        doubleTapStartedOnSleepSurface = false
        if (shouldSleep) {
            powerManager.goToSleep(e.eventTime)
        }
        return shouldSleep
    }

    private fun isSleepSurface(e: MotionEvent): Boolean {
        val shade = shadeInteractor.get()
        // The legacy keyguard panel itself is expanded, so use normalized expansion rather
        // than isAnyExpanded. QS/shade contacts, including header taps, belong to their UI.
        return !statusBarStateController.isDozing && doubleTapToSleepEnabled &&
            shade.shadeExpansion.value <= 0f && shade.qsExpansion.value <= 0f &&
            (e.y < quickQsOffsetHeight ||
                statusBarStateController.state == StatusBarState.KEYGUARD &&
                    !centralSurfaces.isBouncerShowing())
    }

}
