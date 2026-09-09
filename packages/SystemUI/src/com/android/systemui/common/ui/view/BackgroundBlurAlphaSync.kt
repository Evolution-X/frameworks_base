/*
 * Copyright (C) 2026 FundamentalOS
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

package com.android.systemui.common.ui.view

import android.view.View
import android.view.ViewTreeObserver
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import kotlin.math.roundToInt
import kotlinx.coroutines.DisposableHandle

/**
 * Keeps a [BackgroundBlurDrawable]'s alpha in step with the alpha its view is really drawn with.
 *
 * Blur regions are composited by SurfaceFlinger from the drawable's own alpha alone; the alpha of
 * the view's ancestors never reaches them. When the keyguard root view fades out (unlock, a shade
 * drag, the bouncer) a blurred lock icon or shortcut therefore keeps its full blur until the view
 * stops drawing, well after the icon itself has gone. Before every frame, fold the alphas from the
 * view up to the window root into the drawable instead.
 */
class BackgroundBlurAlphaSync(
    private val view: View,
    private val drawable: () -> BackgroundBlurDrawable?,
) : ViewTreeObserver.OnPreDrawListener {

    override fun onPreDraw(): Boolean {
        sync()
        return true
    }

    /** Applies the current effective alpha of [view] to the drawable. */
    fun sync() {
        val blur = drawable() ?: return
        var alpha = 1f
        var v: View? = view
        while (v != null && alpha > 0f) {
            alpha *= v.alpha * v.transitionAlpha
            v = v.parent as? View
        }
        val blurAlpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        if (blur.alpha != blurAlpha) {
            blur.alpha = blurAlpha
        }
    }

    /**
     * Starts syncing before every frame. Call while [view] is attached; dispose the handle before
     * it detaches (the observer is reset on detach).
     */
    fun start(): DisposableHandle {
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(this)
        sync()
        return DisposableHandle {
            (if (observer.isAlive) observer else view.viewTreeObserver).removeOnPreDrawListener(this)
        }
    }
}
