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

package com.android.systemui.biometrics.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.bouncer.ui.composable.ActionButton
import com.android.systemui.bouncer.ui.composable.DigitButton
import com.android.systemui.bouncer.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import java.lang.Float.min

@Composable
fun CredentialPinPad(
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onEnterClick: () -> Unit,
    isInputEnabled: Boolean,
    deleteButtonAppearance: ActionButtonAppearance,
    verticalSpacing: Dp = 8.dp,
    horizontalSpacing: Dp = 14.dp,
    scrambledDigits: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0),
) {
    val pinPadContent =
        @Composable {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                ) {
                    // Rows 1-3 (Digits 1-9)
                    for (i in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
                            for (j in 1..3) {
                                val digit = scrambledDigits[i * 3 + j - 1]
                                DigitButton(
                                    digit = digit,
                                    isInputEnabled = isInputEnabled,
                                    onClicked = { onDigitClick(digit.toString()) },
                                    onPointerDown = {}, // Hook up haptics here if needed
                                    scaling = { 1f },
                                    isAnimationEnabled = true,
                                    backgroundColor =
                                        MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            }
                        }
                    }

                    // Row 4 (Delete, 0, Enter)
                    Row(horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
                        ActionButton(
                            icon =
                                Icon.Resource(
                                    resId = R.drawable.pin_bouncer_delete_outline,
                                    contentDescription =
                                        ContentDescription.Resource(
                                            R.string.keyboardview_keycode_delete
                                        ),
                                ),
                            isInputEnabled = isInputEnabled,
                            onClicked = onDeleteClick,
                            appearance = deleteButtonAppearance,
                            scaling = { 1f },
                            elementId = "delete_button",
                            onPointerDown = {},
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                        )

                        DigitButton(
                            digit = scrambledDigits[9],
                            isInputEnabled = isInputEnabled,
                            onClicked = { onDigitClick(scrambledDigits[9].toString()) },
                            onPointerDown = {},
                            scaling = { 1f },
                            isAnimationEnabled = true,
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )

                        ActionButton(
                            icon =
                                Icon.Resource(
                                    resId = R.drawable.pin_bouncer_confirm,
                                    contentDescription =
                                        ContentDescription.Resource(
                                            R.string.keyboardview_keycode_enter
                                        ),
                                ),
                            isInputEnabled = isInputEnabled,
                            onClicked = onEnterClick,
                            appearance = ActionButtonAppearance.Shown,
                            scaling = { 1f },
                            elementId = "key_enter",
                            onPointerDown = {},
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
            }
        }

    Layout(content = pinPadContent) { measurables, constraints ->
        val placeable =
            measurables
                .first()
                .measure(
                    Constraints(
                        minWidth = 0,
                        maxWidth = Constraints.Infinity,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    )
                )

        val scaleWidth =
            if (placeable.width > constraints.maxWidth) {
                constraints.maxWidth.toFloat() / placeable.width.toFloat()
            } else 1f

        val scaleHeight =
            if (placeable.height > constraints.maxHeight) {
                constraints.maxHeight.toFloat() / placeable.height.toFloat()
            } else 1f

        val scale = min(scaleWidth, scaleHeight)

        val width = (placeable.width * scale).toInt()
        val height = (placeable.height * scale).toInt()

        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
fun PinDisplay(pinText: String, isError: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(30.dp),
    ) {
        repeat(pinText.length) {
            Box(
                modifier =
                    Modifier.size(16.dp)
                        .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
        }
    }
}
