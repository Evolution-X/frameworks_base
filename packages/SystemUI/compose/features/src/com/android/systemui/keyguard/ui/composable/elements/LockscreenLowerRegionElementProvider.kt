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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.Key
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModelModule.Companion.LOCKSCREEN_INSTANCE
import com.android.systemui.keyguard.ui.viewmodel.LockscreenLowerRegionViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.IndicationArea
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Shortcuts
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.LockscreenElement
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import javax.inject.Named

@SysUISingleton
/** Provides a combined element for lockscreen ui elements at the bottom of the screen. */
class LockscreenLowerRegionElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModelFactory: LockscreenLowerRegionViewModel.Factory,
    @Named(LOCKSCREEN_INSTANCE)
    private val quickAffordancesCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel,
    private val axDynamicBarChipViewModel: AxDynamicBarChipViewModel,
) : LockscreenElementProvider {
    override val elements: List<LockscreenElement> by lazy { listOf(LowerRegionElement()) }

    private inner class LowerRegionElement : LockscreenElement {
        override val key = LockscreenElementKeys.Region.Lower
        override val context = this@LockscreenLowerRegionElementProvider.context
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            val viewModel = rememberViewModel("LockscreenLowerRegion") { viewModelFactory.create() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(),
            ) {
                ShortcutElement(Shortcuts.Start, viewModel)

                Box(
                    Modifier.weight(1f)
                        .wrapContentHeight(Alignment.CenterVertically, unbounded = true),
                    contentAlignment = Alignment.Center,
                ) {
                    LockscreenElement(IndicationArea)
                    val isLowUdfps by
                        axDynamicBarChipViewModel.isLowUdfps.collectAsStateWithLifecycle()
                    if (!isLowUdfps) {
                        PlatformTheme {
                            AxDynamicBarKeyguardChip(
                                viewModel = axDynamicBarChipViewModel,
                                modifier = Modifier.widthIn(max = 260.dp),
                            )
                        }
                    }
                }

                ShortcutElement(Shortcuts.End, viewModel)
            }
        }

        @Composable
        private fun Modifier.padding(): Modifier {
            val horizontalPadding = dimensionResource(R.dimen.keyguard_affordance_horizontal_offset)
            return padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = dimensionResource(R.dimen.keyguard_affordance_vertical_offset),
                )
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom))
        }

        @Composable
        private fun LockscreenScope<ElementContentScope>.ShortcutElement(
            key: Key,
            viewModel: LockscreenLowerRegionViewModel,
        ) {

            val endButton by
                quickAffordancesCombinedViewModel.endButton.collectAsStateWithLifecycle(
                    initialValue = KeyguardQuickAffordanceViewModel(slotId = "")
                )

            val startButton by
                quickAffordancesCombinedViewModel.startButton.collectAsStateWithLifecycle(
                    initialValue = KeyguardQuickAffordanceViewModel(slotId = "")
                )

            // If neither shortcut is visible, do not display anything to allow indication area and
            // other features to take the full width of the device.
            if (!startButton.isVisible && !endButton.isVisible) {
                return
            }

            LockscreenElement(
                key,
                Modifier.graphicsLayer {
                        translationX =
                            // unfoldTranslations may be updated every frame, so only read value in
                            // the draw phase.
                            when (key) {
                                Shortcuts.Start -> {
                                    viewModel.unfoldTranslations.start
                                }

                                Shortcuts.End -> {
                                    viewModel.unfoldTranslations.end
                                }
                                else -> {
                                    throw IllegalArgumentException(
                                        "Invalid keyguard shortcut key: $key"
                                    )
                                }
                            }
                    }
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .size(
                        height = dimensionResource(R.dimen.keyguard_affordance_fixed_height),
                        width = dimensionResource(R.dimen.keyguard_affordance_fixed_width),
                    ),
            )
        }
    }
}
