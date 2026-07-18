/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.composable

import android.security.Flags.lockscreenTimeoutDeactivatePinPad
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Easings
import com.android.compose.grid.VerticalGrid
import com.android.compose.modifiers.thenIf
import com.android.systemui.bouncer.shared.constants.PinBouncerConstants
import com.android.systemui.bouncer.ui.BouncerColors.pinActionBg
import com.android.systemui.bouncer.ui.BouncerColors.pinDigitBg
import com.android.systemui.bouncer.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.kairos.internal.util.fastForEach
import com.android.systemui.res.R
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Renders the PIN button pad. */
@Composable
fun PinPad(viewModel: PinBouncerViewModel, verticalSpacing: Dp, modifier: Modifier = Modifier) {
    val isInputEnabled: Boolean by viewModel.isInputEnabled.collectAsStateWithLifecycle()
    val confirmButtonAppearance by viewModel.confirmButtonAppearance.collectAsStateWithLifecycle()
    val animateFailure: Boolean by viewModel.animateFailure.collectAsStateWithLifecycle()
    val isDigitButtonAnimationEnabled: Boolean by
        viewModel.isDigitButtonAnimationEnabled.collectAsStateWithLifecycle()
    val digitOrder: List<Int> by viewModel.digitOrder.collectAsStateWithLifecycle()

    val buttonScaleAnimatables = remember { List(12) { Animatable(1f) } }
    LaunchedEffect(animateFailure) {
        // Show the failure animation if the user entered the wrong input.
        if (animateFailure) {
            showFailureAnimation(buttonScaleAnimatables)
            viewModel.onFailureAnimationShown()
        }
    }

    // Set the focus, so adb can send the key events for testing and the user can type the pin using
    // a phyiscal keyboard.
    val focusRequester = remember { FocusRequester() }
    RequestFocus(focusRequester = focusRequester, viewModel = viewModel)

    val view = LocalView.current
    val context = LocalContext.current
    val accessibilityManager = remember(context) { AccessibilityManager.getInstance(context) }

    VerticalGrid(
        columns = columns,
        verticalSpacing = verticalSpacing,
        horizontalSpacing = calculateHorizontalSpacingBetweenColumns(gridWidth = 300.dp),
        placeRelative = false,
        modifier =
            modifier
                .onFocusChanged { viewModel.onFocusChanged(it.isFocused) }
                .focusRequester(focusRequester)
                .sysuiResTag("pin_pad_grid")
                .semantics { isTraversalGroup = true },
    ) {
        repeat(9) { index ->
            DigitButton(
                digit = digitOrder[index],
                isInputEnabled = isInputEnabled,
                onClicked = { digit ->
                    sendAccessibilityEvent(
                        view = view,
                        accessibilityManager = accessibilityManager,
                    ) {
                        PinAccessibilityEvent.DigitAdded(
                            pinLengthBeforeChange = viewModel.enteredPinLength,
                            digitAdded = digit,
                        )
                    }
                    viewModel.onPinButtonClicked(digit)
                },
                scaling = buttonScaleAnimatables[index]::value,
                isAnimationEnabled = isDigitButtonAnimationEnabled,
                onPointerDown = viewModel::onDigitButtonDown,
                backgroundColor = Color(context.pinDigitBg()),
            )
        }

        ActionButton(
            icon =
                Icon.Resource(
                    resId =
                        if (viewModel.backspaceButtonAppearance == ActionButtonAppearance.Shown) {
                            R.drawable.pin_bouncer_delete_outline
                        } else {
                            R.drawable.pin_bouncer_delete_filled
                        },
                    contentDescription =
                        ContentDescription.Resource(R.string.keyboardview_keycode_delete),
                ),
            isInputEnabled = isInputEnabled,
            onClicked = {
                sendAccessibilityEvent(view = view, accessibilityManager = accessibilityManager) {
                    PinAccessibilityEvent.CharacterDeleted(
                        pinLengthBeforeChange = viewModel.enteredPinLength
                    )
                }
                viewModel.onBackspaceButtonClicked()
            },
            onPointerDown = viewModel::onBackspaceButtonPressed,
            onLongPressed = {
                sendAccessibilityEvent(view = view, accessibilityManager = accessibilityManager) {
                    PinAccessibilityEvent.TextCleared(
                        pinLengthBeforeChange = viewModel.enteredPinLength
                    )
                }
                viewModel.onBackspaceButtonLongPressed()
            },
            onLongClickLabel =
                stringResource(R.string.keyguard_accessibility_pin_delete_long_click),
            appearance = viewModel.backspaceButtonAppearance,
            scaling = buttonScaleAnimatables[9]::value,
            elementId = "delete_button",
            backgroundColor = Color(context.pinActionBg()),
        )

        DigitButton(
            digit = digitOrder[9],
            isInputEnabled = isInputEnabled,
            onClicked = {
                sendAccessibilityEvent(view = view, accessibilityManager = accessibilityManager) {
                    PinAccessibilityEvent.DigitAdded(
                        pinLengthBeforeChange = viewModel.enteredPinLength,
                        digitAdded = digitOrder[9],
                    )
                }
                viewModel.onPinButtonClicked(digitOrder[9])
            },
            scaling = buttonScaleAnimatables[10]::value,
            isAnimationEnabled = isDigitButtonAnimationEnabled,
            onPointerDown = viewModel::onDigitButtonDown,
            backgroundColor = Color(context.pinDigitBg()),
        )

        ActionButton(
            icon =
                Icon.Resource(
                    resId = R.drawable.pin_bouncer_confirm,
                    contentDescription =
                        ContentDescription.Resource(R.string.keyboardview_keycode_enter),
                ),
            isInputEnabled = isInputEnabled,
            onClicked = viewModel::onAuthenticateButtonClicked,
            onPointerDown = { viewModel.onDown() },
            appearance = confirmButtonAppearance,
            scaling = buttonScaleAnimatables[11]::value,
            elementId = "key_enter",
            backgroundColor = Color(context.pinActionBg()),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun DigitButton(
    digit: Int,
    isInputEnabled: Boolean,
    onClicked: (Int) -> Unit,
    onPointerDown: () -> Unit,
    scaling: () -> Float,
    isAnimationEnabled: Boolean,
    backgroundColor: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val textScaleX by
        animateFloatAsState(
            targetValue =
                if (isPressed) {
                    PinBouncerConstants.Animation.pressedTextScaleX
                } else {
                    PinBouncerConstants.Animation.normalTextScaleX
                }
        )
    PinPadButton(
        interactionSource = interactionSource,
        onClicked = { onClicked(digit) },
        isEnabled = isInputEnabled,
        backgroundColor = backgroundColor,
        foregroundColor = colorResource(PinBouncerConstants.Color.digit),
        isAnimationEnabled = isAnimationEnabled,
        onPointerDown = onPointerDown,
        modifier =
            Modifier.graphicsLayer {
                val scale = if (isAnimationEnabled) scaling() else 1f
                scaleX = scale
                scaleY = scale
            },
    ) { contentColor ->
        BasicText(
            text = digit.toString(),
            style = MaterialTheme.typography.labelSmallEmphasized.merge(fontSize = 32.sp),
            color = { contentColor() },
            modifier = Modifier.graphicsLayer { scaleX = textScaleX },
        )
    }
}

@Composable
fun ActionButton(
    icon: Icon,
    isInputEnabled: Boolean,
    onClicked: () -> Unit,
    elementId: String,
    onPointerDown: () -> Unit,
    onLongPressed: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    appearance: ActionButtonAppearance,
    scaling: () -> Float,
    backgroundColor: Color,
) {
    val isHidden = appearance == ActionButtonAppearance.Hidden
    val hiddenAlpha by animateFloatAsState(if (isHidden) 0f else 1f, label = "Action button alpha")

    val foregroundColor = colorResource(PinBouncerConstants.Color.action)

    val backgroundColorOrTransparentWhenHidden =
        when (appearance) {
            ActionButtonAppearance.Shown -> backgroundColor
            else -> Color.Transparent
        }

    PinPadButton(
        onClicked = onClicked,
        onLongPressed = onLongPressed,
        isEnabled = isInputEnabled && !isHidden,
        backgroundColor = backgroundColorOrTransparentWhenHidden,
        foregroundColor = foregroundColor,
        isAnimationEnabled = true,
        elementId = elementId,
        onPointerDown = onPointerDown,
        onLongClickLabel = onLongClickLabel,
        modifier =
            Modifier.graphicsLayer {
                alpha = hiddenAlpha
                val scale = scaling()
                scaleX = scale
                scaleY = scale
            },
    ) { contentColor ->
        Icon(icon = icon, tint = contentColor())
    }
}

@Composable
private fun PinPadButton(
    onClicked: () -> Unit,
    isEnabled: Boolean,
    backgroundColor: Color,
    foregroundColor: Color,
    isAnimationEnabled: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onPointerDown: () -> Unit,
    elementId: String? = null,
    onLongPressed: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable (contentColor: () -> Color) -> Unit,
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val indication = LocalIndication.current.takeUnless { isPressed }

    // Pin button animation specification is asymmetric: fast animation to the pressed state, and a
    // slow animation upon release. Note that isPressed is guaranteed to be true for at least the
    // press animation duration (see below in detectTapGestures).
    val animEasing = if (isPressed) pinButtonPressedEasing else pinButtonReleasedEasing
    val animDurationMillis =
        (if (isPressed) pinButtonPressedDuration else pinButtonReleasedDuration).toInt(
            DurationUnit.MILLISECONDS
        )

    // Fraction of the total height of the button that the corner radius should be. Note that a
    // value of 0.5 will make the button a perfect circle while any value below that will make it
    // into a square with rounded corners.
    val cornerRadiusFraction: Float by
        animateFloatAsState(
            if (isAnimationEnabled && isPressed) 0.25f else 0.5f,
            label = "PinButton round corners",
            animationSpec = tween(animDurationMillis, easing = animEasing),
        )
    val colorAnimationSpec: AnimationSpec<Color> = tween(animDurationMillis, easing = animEasing)
    val containerColor: Color by
        animateColorAsState(
            when {
                isAnimationEnabled && isPressed ->
                    colorResource(PinBouncerConstants.Color.bgPressed)
                lockscreenTimeoutDeactivatePinPad() && !isEnabled ->
                    backgroundColor.copy(alpha = 0.18f)
                else -> backgroundColor
            },
            label = "Pin button container color",
            animationSpec = colorAnimationSpec,
        )
    val contentColor =
        animateColorAsState(
            when {
                isAnimationEnabled && isPressed ->
                    colorResource(PinBouncerConstants.Color.digitPressed)
                lockscreenTimeoutDeactivatePinPad() && !isEnabled ->
                    foregroundColor.copy(alpha = 0.38f)
                else -> foregroundColor
            },
            label = "Pin button container color",
            animationSpec = colorAnimationSpec,
        )

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .thenIf(!lockscreenTimeoutDeactivatePinPad() || isEnabled) {
                    Modifier.focusRequester(FocusRequester.Default).focusable()
                }
                .sizeIn(maxWidth = pinButtonMaxSize, maxHeight = pinButtonMaxSize)
                .aspectRatio(1f)
                .drawBehind {
                    drawRoundRect(
                        color = containerColor,
                        cornerRadius = CornerRadius(cornerRadiusFraction * size.height),
                    )
                }
                .clip(CircleShape)
                .pinPadButtonInput(
                    isEnabled = isEnabled,
                    onPointerDown = onPointerDown,
                    onClicked = onClicked,
                    onLongPressed = onLongPressed,
                    onLongClickLabel = onLongClickLabel,
                    interactionSource = interactionSource,
                    indication = indication,
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    if (!isEnabled) {
                        disabled()
                    }
                }
                .thenIf(elementId != null) { Modifier.sysuiResTag(elementId!!) },
    ) {
        content(contentColor::value)
    }
}

private fun Modifier.pinPadButtonInput(
    isEnabled: Boolean,
    onPointerDown: () -> Unit,
    onClicked: () -> Unit,
    onLongPressed: (() -> Unit)?,
    onLongClickLabel: String?,
    interactionSource: MutableInteractionSource,
    indication: Indication?,
): Modifier {
    return this.thenIf(isEnabled) {
        Modifier.semantics {
                this.onClick(
                    action = {
                        onClicked()
                        true
                    },
                    label = onLongClickLabel,
                )
                onLongPressed?.let {
                    this.onLongClick(
                        action = {
                            it()
                            true
                        },
                        label = onLongClickLabel,
                    )
                }
            }
            .indication(interactionSource, indication)
            .pointerInput(
                onPointerDown,
                onClicked,
                onLongPressed,
                onLongClickLabel,
                interactionSource,
            ) {
                coroutineScope {
                    awaitPointerEventScope {
                        // This becomes true when the pointer was moved so far that the
                        // gesture can no longer be a click or a long press.
                        var movedTooFar = false
                        // The position of the down event from the current gesture.
                        var downPosition = Offset.Zero
                        // Becomes true when the button has already been long-clicked. This
                        // is read in the "up" case to prevent reporting a normal click if
                        // there was already a long click.
                        var longClicked = false
                        // The Job housing the coroutine that will trigger a long press if
                        // the pointer was held down long enough.
                        //
                        // This Job will get canceled if the pointer is released before the
                        // long click duration elapses.
                        var longClickJob: Job? = null

                        var downInteraction: PressInteraction.Press? = null
                        var enterInteraction: HoverInteraction.Enter? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.fastForEach { change ->
                                if (change.changedToDown()) {
                                    downPosition = change.position

                                    // Animate a ripple to show the user that their press
                                    // has been acknowledged.
                                    downInteraction =
                                        PressInteraction.Press(downPosition).also {
                                            launch { interactionSource.emit(it) }
                                        }

                                    movedTooFar = false
                                    longClicked = false

                                    onLongPressed?.let {
                                        // Start a long click "timer" such that if the user
                                        // keeps holding down the pointer, it eventually
                                        // triggers a long click.
                                        longClickJob = launch {
                                            delay(viewConfiguration.longPressTimeoutMillis)
                                            longClicked = true
                                            downInteraction.let {
                                                launch {
                                                    interactionSource.emit(
                                                        PressInteraction.Release(it)
                                                    )
                                                }
                                            }
                                            onLongPressed()
                                        }
                                    }
                                    onPointerDown()
                                }

                                if (change.positionChanged()) {
                                    if (!movedTooFar) {
                                        val distanceMoved =
                                            (change.position - downPosition).getDistance()
                                        if (distanceMoved >= viewConfiguration.touchSlop * 4f) {
                                            // The held pointer has been moved enough
                                            // such that it shouldn't become a click or
                                            // a long click any longer.
                                            movedTooFar = true
                                            longClickJob?.cancel()
                                            downInteraction?.let {
                                                launch {
                                                    interactionSource.emit(
                                                        PressInteraction.Cancel(it)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (change.changedToUp()) {
                                    if (!movedTooFar && !longClicked) {
                                        // The held pointer was released before the long click
                                        // occurred and wasn't moved too far. This is an actual
                                        // click.
                                        longClickJob?.cancel()
                                        downInteraction?.let {
                                            launch {
                                                interactionSource.emit(PressInteraction.Release(it))
                                            }
                                        }
                                        onClicked()
                                    }
                                }

                                change.consume()
                            }

                            if (event.type == PointerEventType.Enter) {
                                enterInteraction =
                                    HoverInteraction.Enter().also {
                                        launch { interactionSource.emit(it) }
                                    }
                            }

                            if (event.type == PointerEventType.Exit) {
                                enterInteraction?.let {
                                    launch { interactionSource.emit(HoverInteraction.Exit(it)) }
                                }
                            }
                        }
                    }
                }
            }
    }
}

/**
 * (Re)requests focus as needed. Done as a separate `@Composable` function to make sure that the
 * caller doesn't need to recompose every time the state in the view-model is changed.
 */
@Composable
private fun RequestFocus(focusRequester: FocusRequester, viewModel: PinBouncerViewModel) {
    val isFocusRequested by viewModel.isFocusRequested.collectAsStateWithLifecycle()
    LaunchedEffect(isFocusRequested) {
        if (isFocusRequested) {
            focusRequester.requestFocus()
        }
    }
}

private suspend fun showFailureAnimation(
    buttonScaleAnimatables: List<Animatable<Float, AnimationVector1D>>
) {
    coroutineScope {
        buttonScaleAnimatables.forEachIndexed { index, animatable ->
            launch {
                animatable.animateTo(
                    targetValue = pinButtonErrorShrinkFactor,
                    animationSpec =
                        tween(
                            durationMillis = pinButtonErrorShrinkMs,
                            delayMillis = index * pinButtonErrorStaggerDelayMs,
                            easing = Easings.Linear,
                        ),
                )

                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(durationMillis = pinButtonErrorRevertMs, easing = Easings.Legacy),
                )
            }
        }
    }
}

private sealed interface PinAccessibilityEvent {
    val pinLengthBeforeChange: Int

    data class DigitAdded(override val pinLengthBeforeChange: Int, val digitAdded: Int) :
        PinAccessibilityEvent

    data class CharacterDeleted(override val pinLengthBeforeChange: Int) : PinAccessibilityEvent

    data class TextCleared(override val pinLengthBeforeChange: Int) : PinAccessibilityEvent
}

private fun sendAccessibilityEvent(
    view: View,
    accessibilityManager: AccessibilityManager,
    eventFactory: () -> PinAccessibilityEvent,
) {
    if (!accessibilityManager.isEnabled) {
        return
    }

    val event = eventFactory()

    view.sendAccessibilityEventUnchecked(
        AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
            isEnabled = true
            isPassword = true
            beforeText = PinInputBullet.repeat(event.pinLengthBeforeChange)
            when (event) {
                is PinAccessibilityEvent.DigitAdded -> {
                    text.add(
                        PinInputBullet.repeat(event.pinLengthBeforeChange) + "${event.digitAdded}"
                    )
                    addedCount = 1
                    removedCount = 0
                    fromIndex = event.pinLengthBeforeChange
                }
                is PinAccessibilityEvent.CharacterDeleted -> {
                    text.add(PinInputBullet.repeat(event.pinLengthBeforeChange - 1))
                    addedCount = 0
                    removedCount = 1
                    fromIndex = event.pinLengthBeforeChange - 1
                }
                is PinAccessibilityEvent.TextCleared -> {
                    addedCount = 0
                    removedCount = event.pinLengthBeforeChange
                    fromIndex = 0
                }
            }
        }
    )
}

/** Returns the amount of horizontal spacing between columns, in dips. */
private fun calculateHorizontalSpacingBetweenColumns(gridWidth: Dp): Dp {
    return (gridWidth - (pinButtonMaxSize * columns)) / (columns - 1)
}

/** Number of columns in the PIN pad grid. */
private const val columns = 3
/** Maximum size (width and height) of each PIN pad button. */
private val pinButtonMaxSize = 84.dp
/** Scale factor to apply to buttons when animating the "error" animation on them. */
private val pinButtonErrorShrinkFactor = 67.dp / pinButtonMaxSize
/** Animation duration of the "shrink" phase of the error animation, on each PIN pad button. */
private const val pinButtonErrorShrinkMs = 50
/** Amount of time to wait between application of the "error" animation to each row of buttons. */
private const val pinButtonErrorStaggerDelayMs = 33
/** Animation duration of the "revert" phase of the error animation, on each PIN pad button. */
private const val pinButtonErrorRevertMs = 617

// Pin button motion spec: http://shortn/_9TTIG6SoEa
private val pinButtonPressedDuration = 100.milliseconds
private val pinButtonPressedEasing = Easings.Linear
private val pinButtonReleasedDuration = 420.milliseconds
private val pinButtonReleasedEasing = Easings.Standard

/** This is `•`, the bullet character. */
val PinInputBullet = "\u2022"
