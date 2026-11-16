/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose.toolbar

import com.android.systemui.qs.shared.style.liquidGlassOn
import com.android.systemui.qs.shared.style.liquidGlassCosStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.systemui.common.ui.icons.Edit
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButtonDefaults.SpacingBetweenTooltipAndAnchor
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButtonDefaults.TooltipMaxWidth
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditModeButton(
    viewModel: EditModeButtonViewModel,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
) {
    if (!viewModel.isEditButtonVisible) {
        return
    }
    // The ColorOS glass panel is dark in both themes.
    val cosGlass = liquidGlassOn && liquidGlassCosStyle()
    CompositionLocalProvider(
        value =
            LocalContentColor provides
                if (cosGlass) Color.White else MaterialTheme.colorScheme.onSurface
    ) {
        val tooltipState = rememberTooltipState(isPersistent = true)
        val showTooltip = isVisible && viewModel.showTooltip
        LaunchedEffect(showTooltip) { if (showTooltip) tooltipState.show() }

        // Make sure to dismiss the tooltip if it's still visible when it shouldn't be due to always
        // composing QS.
        LaunchedEffect(isVisible) {
            if (!isVisible && tooltipState.isVisible) tooltipState.dismiss()
        }

        val density = LocalDensity.current
        val windowContainerSizePx =
            with(density) {
                IntSize(
                    LocalConfiguration.current.screenWidthDp.dp.roundToPx(),
                    LocalConfiguration.current.screenHeightDp.dp.roundToPx(),
                )
            }
        val tertiaryColor = MaterialTheme.colorScheme.tertiary
        val caretPath = remember { mutableStateOf(Path()) }
        val coroutineScope = rememberCoroutineScope()

        DisposableEffectWithLifecycle(Unit) { onDispose(viewModel::onTooltipDisposed) }

        TooltipBox(
            modifier = modifier,
            positionProvider = rememberTooltipPositionProvider(),
            state = tooltipState,
            focusable = false,
            onDismissRequest = {
                if (tooltipState.isVisible) {
                    coroutineScope.launch { tooltipState.dismiss() }
                }
                viewModel.onTooltipDisposed()
            },
            tooltip = {
                PlainTooltip(
                    shape = RoundedCornerShape(16.dp),
                    containerColor = tertiaryColor,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    shadowElevation = EditModeButtonDefaults.TooltipShadowElevation,
                    maxWidth = TooltipMaxWidth,
                    modifier =
                        Modifier.layoutCaret(
                                caretPath,
                                density,
                                windowContainerSizePx,
                                DpSize(12.dp, 8.dp),
                            ) {
                                obtainAnchorBounds()
                            }
                            .drawWithContent {
                                drawContent()
                                drawPath(caretPath.value, color = tertiaryColor)
                            },
                ) {
                    Text(
                        stringResource(R.string.qs_edit_mode_tooltip),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            },
        ) {
            IconButton(
                onClick = viewModel::onButtonClick,
                shape = RoundedCornerShape(CornerSize(28.dp)),
                modifier =
                    Modifier.sysuiResTag("qs_edit_mode_button")
                        .borderOnFocus(
                            color = MaterialTheme.colorScheme.secondary,
                            cornerSize = CornerSize(24.dp),
                        ),
            ) {
                Icon(
                    imageVector = Edit,
                    contentDescription =
                        stringResource(id = R.string.accessibility_quick_settings_edit),
                )
            }
        }
    }
}

/**
 * Variant of [TooltipDefaults.rememberTooltipPositionProvider] that favors placing the tooltip
 * below the anchor if there's enough space.
 *
 * @param spacingBetweenTooltipAndAnchor the padding between the tooltip and its target.
 */
@Composable
private fun rememberTooltipPositionProvider(
    spacingBetweenTooltipAndAnchor: Dp = SpacingBetweenTooltipAndAnchor
): PopupPositionProvider {
    val density = LocalDensity.current
    val tooltipAnchorSpacing = with(density) { spacingBetweenTooltipAndAnchor.roundToPx() }
    return remember(tooltipAnchorSpacing, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // Horizontal alignment preference: middle -> start -> end
                // Vertical preference: below -> above

                // Tooltip prefers to be center aligned horizontally.
                var x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2

                if (x < 0) {
                    // Make tooltip start aligned if colliding with the
                    // left side of the screen
                    x = anchorBounds.left
                } else if (x + popupContentSize.width > windowSize.width) {
                    // Make tooltip end aligned if colliding with the
                    // right side of the screen
                    x = anchorBounds.right - popupContentSize.width
                }

                // Tooltip prefers to be below the anchor,
                // but if this causes the tooltip to be outside the window
                // then we place it above the anchor
                var y = anchorBounds.bottom + tooltipAnchorSpacing
                if (y + popupContentSize.height > windowSize.height) {
                    y = anchorBounds.top - popupContentSize.height - tooltipAnchorSpacing
                }
                return IntOffset(x, y)
            }
        }
    }
}

/**
 * Variant of [androidx.compose.material3.layoutCaret] that favors placing the tooltip below the
 * anchor if there's enough space.
 */
private fun Modifier.layoutCaret(
    caretPath: MutableState<Path>,
    density: Density,
    windowContainerSizePx: IntSize,
    caretSize: DpSize,
    getAnchorLayoutCoordinates: MeasureScope.() -> LayoutCoordinates?,
): Modifier =
    this.layout { measurables, constraints ->
        val placeable = measurables.measure(constraints)
        val width = placeable.width
        val height = placeable.height
        val tooltipWidth = width.toFloat()
        val tooltipHeight = height.toFloat()
        val anchorLayoutCoordinates = getAnchorLayoutCoordinates()

        val path = Path()

        if (anchorLayoutCoordinates != null) {
            val caretHeightPx: Int
            val caretWidthPx: Int
            val tooltipAnchorSpacing: Int
            with(density) {
                caretHeightPx = caretSize.height.roundToPx()
                caretWidthPx = caretSize.width.roundToPx()
                tooltipAnchorSpacing = SpacingBetweenTooltipAndAnchor.roundToPx()
            }
            val screenWidthPx = windowContainerSizePx.width
            val anchorBounds = anchorLayoutCoordinates.boundsInWindow()
            val anchorLeft = anchorBounds.left
            val anchorRight = anchorBounds.right
            val anchorBottom = anchorBounds.bottom
            val anchorMid = (anchorRight + anchorLeft) / 2
            val anchorWidth = anchorRight - anchorLeft
            val isCaretTop =
                anchorBottom + tooltipAnchorSpacing + tooltipHeight <= windowContainerSizePx.height
            val caretY =
                if (isCaretTop) {
                    0f
                } else {
                    tooltipHeight
                }

            // Default the caret to be in the middle
            // caret might need to be offset depending on where
            // the tooltip is placed relative to the anchor
            var position: Offset =
                if (anchorLeft - tooltipWidth / 2 + anchorWidth / 2 <= 0) {
                    Offset(x = anchorMid, y = caretY)
                } else if (anchorRight + tooltipWidth / 2 - anchorWidth / 2 >= screenWidthPx) {
                    val anchorMidFromRightScreenEdge = screenWidthPx - anchorMid
                    val caretX = tooltipWidth - anchorMidFromRightScreenEdge
                    Offset(x = caretX, y = caretY)
                } else {
                    Offset(x = tooltipWidth / 2, y = caretY)
                }
            if (anchorMid - tooltipWidth / 2 < 0) {
                // The tooltip needs to be start aligned if it would collide with the left side of
                // screen.
                position = Offset(x = anchorMid - anchorLeft, y = caretY)
            } else if (anchorMid + tooltipWidth / 2 > screenWidthPx) {
                // The tooltip needs to be end aligned if it would collide with the right side of
                // the
                // screen.
                position = Offset(x = anchorMid - (anchorRight - tooltipWidth), y = caretY)
            }

            if (isCaretTop) {
                path.apply {
                    moveTo(x = position.x, y = position.y)
                    lineTo(x = position.x + caretWidthPx / 2, y = position.y)
                    lineTo(x = position.x, y = position.y - caretHeightPx)
                    lineTo(x = position.x - caretWidthPx / 2, y = position.y)
                    close()
                }
            } else {
                path.apply {
                    moveTo(x = position.x, y = position.y)
                    lineTo(x = position.x + caretWidthPx / 2, y = position.y)
                    lineTo(x = position.x, y = position.y + caretHeightPx.toFloat())
                    lineTo(x = position.x - caretWidthPx / 2, y = position.y)
                    close()
                }
            }

            caretPath.value = path
        }
        layout(width, height) { placeable.place(0, 0) }
    }

private object EditModeButtonDefaults {
    val SpacingBetweenTooltipAndAnchor = 4.dp
    val TooltipMaxWidth = 360.dp
    val TooltipShadowElevation = 2.dp
}
