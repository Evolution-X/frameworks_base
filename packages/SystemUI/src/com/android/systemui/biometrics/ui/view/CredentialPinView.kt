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

package com.android.systemui.biometrics.ui.view

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.android.systemui.bouncer.ui.viewmodel.ActionButtonAppearance
import kotlinx.coroutines.launch
import lineageos.providers.LineageSettings

@Composable
fun CredentialPinView(
    onVerify: suspend (CharSequence) -> ByteArray?,
    onSuccess: (ByteArray) -> Unit,
    onPinPress: () -> Unit,
    isVisible: Boolean,
    error: String = "",
    userId: Int,
) {
    var pinText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val view = LocalView.current
    val context = LocalContext.current
    val accessibilityManager = remember(context) { AccessibilityManager.getInstance(context) }

    var scrambledDigits by remember(userId) { mutableStateOf(scrambledPinDigits(context, userId)) }
    var hasAppeared by remember(userId) { mutableStateOf(false) }

    LaunchedEffect(isVisible, userId) {
        if (isVisible) {
            if (hasAppeared) {
                scrambledDigits = scrambledPinDigits(context, userId)
            }
            hasAppeared = true
            focusRequester.requestFocus()
        } else {
            pinText = ""
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("cred_pin_pad")
                .onPreviewKeyEvent { event ->
                    val digit = event.getDigitClicked()
                    if (digit != null) {
                        if (pinText.length < 16) {
                            view.notifyPinTextChanged(
                                accessibilityManager = accessibilityManager,
                                previousLength = pinText.length,
                                addedDigit = digit,
                            )
                            pinText += digit
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (event.isBackspace()) {
                        if (pinText.isNotEmpty()) {
                            view.notifyPinTextChanged(
                                accessibilityManager = accessibilityManager,
                                previousLength = pinText.length,
                            )
                            pinText = pinText.dropLast(1)
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (event.isEnter()) {
                        // TODO: Pull this out
                        if (pinText.isNotEmpty()) {
                            scope.launch {
                                val attestation = onVerify(pinText)
                                if (attestation != null) {
                                    onSuccess(attestation)
                                } else {
                                    pinText = ""
                                }
                            }
                        }
                        return@onPreviewKeyEvent true
                    }

                    false
                },
    ) {
        PromptErrorText(error = error)

        PinDisplay(pinText = pinText, modifier = Modifier.padding(bottom = 16.dp))

        CredentialPinPad(
            onDigitClick = { digit ->
                if (pinText.length < 16) {
                    view.notifyPinTextChanged(
                        accessibilityManager = accessibilityManager,
                        previousLength = pinText.length,
                        addedDigit = digit,
                    )
                    pinText += digit
                    onPinPress()
                }
            },
            onDeleteClick = {
                if (pinText.isNotEmpty()) {
                    view.notifyPinTextChanged(
                        accessibilityManager = accessibilityManager,
                        previousLength = pinText.length,
                    )
                    pinText = pinText.dropLast(1)
                    onPinPress()
                }
            },
            onEnterClick = {
                if (pinText.isNotEmpty()) {
                    onPinPress()
                    scope.launch {
                        val attestation = onVerify(pinText)
                        if (attestation != null) {
                            onSuccess(attestation)
                        } else {
                            pinText = ""
                        }
                    }
                }
            },
            isInputEnabled = isVisible,
            deleteButtonAppearance = ActionButtonAppearance.Shown,
            scrambledDigits = scrambledDigits,
        )
    }
}

// Helper to extract digit string from a key event
// TODO: Is there a better way?
private fun KeyEvent.getDigitClicked(): String? {
    if (this.type != KeyEventType.KeyDown) return null
    val char = utf16CodePoint.toChar()
    return if (char.isDigit()) char.toString() else null
}

private fun KeyEvent.isEnter(): Boolean {
    if (type != KeyEventType.KeyDown) return false
    return key == Key.Enter || key == Key.NumPadEnter
}

private fun KeyEvent.isBackspace(): Boolean {
    if (type != KeyEventType.KeyDown) return false
    return key == Key.Backspace || key == Key.Delete
}

private fun scrambledPinDigits(context: Context, userId: Int): List<Int> {
    val digits = mutableListOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
    val isEnabled =
        LineageSettings.System.getIntForUser(
            context.contentResolver,
            LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT,
            0,
            userId,
        ) == 1
    if (isEnabled) digits.shuffle()
    return digits
}

private const val PIN_BULLET = "\u2022"

private fun View.notifyPinTextChanged(
    accessibilityManager: AccessibilityManager,
    previousLength: Int,
    addedDigit: String? = null,
) {
    val isDeletion = addedDigit == null
    if (!accessibilityManager.isEnabled || (isDeletion && previousLength <= 0)) return

    val bulletString = PIN_BULLET.repeat(previousLength)

    val event =
        AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
            isEnabled = true
            isPassword = true
            beforeText = bulletString

            if (addedDigit != null) {
                text.add(bulletString + addedDigit)
                addedCount = 1
                removedCount = 0
                fromIndex = previousLength
            } else {
                text.add(PIN_BULLET.repeat(previousLength - 1))
                addedCount = 0
                removedCount = 1
                fromIndex = previousLength - 1
            }
        }

    sendAccessibilityEventUnchecked(event)
}
