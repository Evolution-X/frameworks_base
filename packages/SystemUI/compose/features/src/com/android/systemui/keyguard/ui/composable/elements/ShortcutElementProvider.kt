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

package com.android.systemui.keyguard.ui.composable.elements

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.annotation.IdRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.modifiers.padding
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardQuickAffordanceButton
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Shortcuts
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import javax.inject.Inject
import kotlin.collections.List
import kotlinx.coroutines.flow.Flow

class ShortcutElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: KeyguardQuickAffordancesCombinedViewModel,
    private val keyguardQuickAffordanceViewBinder: KeyguardQuickAffordanceViewBinder,
    private val indicationController: KeyguardIndicationController,
) : LockscreenElementProvider {
    override val elements: List<LockscreenElement> by lazy {
        listOf(
            ShortcutElement(Shortcuts.Start, isStart = true),
            ShortcutElement(Shortcuts.End, isStart = false),
        )
    }

    private inner class ShortcutElement(
        override val key: ElementKey,
        private val isStart: Boolean,
    ) : LockscreenElement {
        override val context = this@ShortcutElementProvider.context

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            Shortcut(isStart, applyPadding = false)
        }
    }

    /**
     * Renders a single lockscreen shortcut.
     *
     * @param isStart Whether the shortcut goes on the left (in left-to-right locales).
     * @param applyPadding Whether to apply padding around the shortcut, this is needed if the
     *   shortcut is placed along the edges of the display.
     */
    @Composable
    private fun Shortcut(
        isStart: Boolean,
        applyPadding: Boolean,
        onTopChanged: ((Float) -> Unit)? = null,
        modifier: Modifier = Modifier,
    ) {
        Shortcut(
            viewId = if (isStart) R.id.start_button else R.id.end_button,
            viewModel = if (isStart) viewModel.startButton else viewModel.endButton,
            transitionAlpha = viewModel.transitionAlpha,
            indicationController = indicationController,
            binder = keyguardQuickAffordanceViewBinder,
            modifier = if (applyPadding) modifier.shortcutPadding() else modifier,
        )
    }

    @Composable
    private fun Shortcut(
        @IdRes viewId: Int,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        transitionAlpha: Flow<Float>,
        indicationController: KeyguardIndicationController,
        binder: KeyguardQuickAffordanceViewBinder,
        modifier: Modifier = Modifier,
    ) {
        val (binding, setBinding) = mutableStateOf<KeyguardQuickAffordanceViewBinder.Binding?>(null)

        AndroidView(
            factory = { context ->
                val padding =
                    context.resources.getDimensionPixelSize(
                        R.dimen.keyguard_affordance_fixed_padding
                    )
                val view =
                    KeyguardQuickAffordanceButton(context, null).apply {
                        id = viewId
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background =
                            ResourcesCompat.getDrawable(
                                context.resources,
                                R.drawable.keyguard_bottom_affordance_bg,
                                context.theme,
                            )
                        foreground =
                            ResourcesCompat.getDrawable(
                                context.resources,
                                R.drawable.keyguard_bottom_affordance_selected_border,
                                context.theme,
                            )
                        visibility = View.INVISIBLE
                        setPadding(padding, padding, padding, padding)
                    }

                setBinding(
                    binder.bind(view, viewModel, transitionAlpha) {
                        indicationController.showTransientIndication(it)
                    }
                )

                view
            },
            onRelease = { binding?.destroy() },
            modifier =
                modifier.size(
                    width = dimensionResource(R.dimen.keyguard_affordance_fixed_width),
                    height = dimensionResource(R.dimen.keyguard_affordance_fixed_height),
                ),
        )
    }

    @Composable
    private fun Modifier.shortcutPadding(): Modifier {
        return this.padding(
                horizontal = dimensionResource(R.dimen.keyguard_affordance_horizontal_offset)
            )
            .padding(bottom = dimensionResource(R.dimen.keyguard_affordance_vertical_offset))
    }
}
