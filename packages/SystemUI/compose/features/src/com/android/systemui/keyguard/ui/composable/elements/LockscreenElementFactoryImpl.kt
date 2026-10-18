/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.keyguard.ui.composable.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.android.compose.animation.scene.Key
import com.android.compose.modifiers.onUnplaced
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.plugins.keyguard.VRectF
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementFactory
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.MovableLockscreenElement
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

@Immutable
class LockscreenElementFactoryImpl
@AssistedInject
constructor(
    @Assisted private val elements: Map<Key, BaseLockscreenElement>,
    @KeyguardBlueprintLog private val blueprintLog: LogBuffer,
) : LockscreenElementFactory {
    private val logger = Logger(blueprintLog, LockscreenElementFactoryImpl::class.simpleName!!)

    @Composable
    override fun LockscreenElement(scope: LockscreenScope<*>, key: Key, modifier: Modifier) {
        val element = elements[key]
        if (element == null) {
            logger.e({ "No lockscreen element available at key: $str1" }) { str1 = "$key" }
            return
        }

        with(scope) {
            CompositionLocalProvider(LocalContext provides element.context) {
                val elementModifier =
                    modifier
                        .onGloballyPositioned { coordinates ->
                            context.onElementPositioned(
                                element.key,
                                VRectF(coordinates.boundsInWindow()),
                            )
                        }
                        .onUnplaced { context.onElementUnpositioned(element.key) }
                when (element) {
                    is MovableLockscreenElement -> {
                        MovableElement(element.key, elementModifier) {
                            content { with(element) { LockscreenElement() } }
                        }
                    }
                    is LockscreenElement -> {
                        ElementWithValues(element.key, elementModifier) {
                            content { with(element) { LockscreenElement() } }
                        }
                    }
                    else -> {
                        logger.wtf({ "Bad Lockscreen Element Type: $str1" }) { str1 = "$element" }
                    }
                }
            }
        }
    }

    @AssistedFactory
    interface Builder {
        fun create(elements: Map<Key, BaseLockscreenElement>): LockscreenElementFactoryImpl
    }
}
