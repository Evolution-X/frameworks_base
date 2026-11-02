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

package com.android.systemui.statusbar.quickactions.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandCutoutEdge
import com.android.systemui.statusbar.quickactions.ui.compose.DynamicIslandSideEdgeLayout

/**
 * Hosts the Dynamic Island in its own window when the camera cutout is on the left or right edge
 * (landscape). The status bar window is a thin horizontal strip along the top, so it cannot hold
 * a pill that has to stay on a camera sitting mid-height on a side edge.
 *
 * The window covers the display but only the pill is touchable: the touchable region follows the
 * bounds the island reports, everything else falls through to the windows below. The window is
 * [View.GONE] (so it has no surface) whenever the island has nothing to show.
 *
 * Must be used from the main thread.
 */
class DynamicIslandSideEdgeHost(
    context: Context,
    private val content:
        @Composable
        (
            edge: DynamicIslandCutoutEdge,
            onBoundsChanged: (Rect) -> Unit,
            onActiveChanged: (Boolean) -> Unit,
        ) -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val edgeState = mutableStateOf<DynamicIslandCutoutEdge?>(null)
    private val offsetState = mutableStateOf(IntOffset.Zero)
    private val touchableBounds = Rect()
    private var view: ComposeView? = null
    private var lifecycleOwner: HostLifecycleOwner? = null

    private val insetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(touchableBounds)
        }

    /**
     * Docks the island to [edge], adding the window if it is not there yet. [offset] moves it from
     * the camera, in screen px.
     */
    fun show(edge: DynamicIslandCutoutEdge, offset: IntOffset = IntOffset.Zero) {
        edgeState.value = edge
        offsetState.value = offset
        if (view != null) return

        val owner =
            HostLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        val composeView =
            ComposeView(appContext).apply {
                visibility = View.GONE
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    PlatformTheme {
                        val current = edgeState.value
                        if (current != null) {
                            DynamicIslandSideEdgeLayout(current, offset = offsetState.value) {
                                content(
                                    current,
                                    this@DynamicIslandSideEdgeHost::onBoundsChanged,
                                    this@DynamicIslandSideEdgeHost::onActiveChanged,
                                )
                            }
                        }
                    }
                }
                viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
            }
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)
        composeView.setViewTreeOnBackPressedDispatcherOwner(owner)

        windowManager.addView(composeView, layoutParams())
        view = composeView
        lifecycleOwner = owner
    }

    /** Removes the window; the island goes back to the status bar. */
    fun hide() {
        val composeView = view ?: return
        lifecycleOwner?.apply {
            handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        composeView.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        windowManager.removeViewImmediate(composeView)
        view = null
        lifecycleOwner = null
        touchableBounds.setEmpty()
        edgeState.value = null
    }

    private fun onBoundsChanged(bounds: Rect) {
        touchableBounds.set(bounds)
    }

    private fun onActiveChanged(active: Boolean) {
        view?.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) touchableBounds.setEmpty()
    }

    private fun layoutParams() =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                title = "DynamicIslandSideEdge"
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }

    private class HostLifecycleOwner :
        LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val backDispatcher = OnBackPressedDispatcher()

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val onBackPressedDispatcher: OnBackPressedDispatcher
            get() = backDispatcher

        init {
            savedStateRegistryController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
