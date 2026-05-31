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

package com.android.systemui.notifications.ui.composable.row

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxOfOrDefault
import androidx.compose.ui.util.fastSumBy
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.row.ui.viewmodel.BundleHeaderViewModel

object BundleHeader {
    object Scenes {
        val Collapsed = SceneKey("Collapsed")
        val Expanded = SceneKey("Expanded")
    }

    object Elements {
        // The right most PreviewIcon
        val PreviewIcon1 = ElementKey("PreviewIcon1")
        // The middle PreviewIcon
        val PreviewIcon2 = ElementKey("PreviewIcon2")
        // The left most PreviewIcon
        val PreviewIcon3 = ElementKey("PreviewIcon3")
        val TitleText = ElementKey("TitleText")
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BundleHeader(viewModel: BundleHeaderViewModel, modifier: Modifier = Modifier) {
    val state =
        rememberMutableSceneTransitionLayoutState(
            initialScene = BundleHeader.Scenes.Collapsed,
            transitions =
                transitions {
                    from(BundleHeader.Scenes.Collapsed, to = BundleHeader.Scenes.Expanded) {
                        spec = tween(350, easing = FastOutSlowInEasing)
                        val scale = 0.6f
                        timestampRange(endMillis = 250, easing = FastOutSlowInEasing) {
                            scaleDraw(BundleHeader.Elements.PreviewIcon1, scale, scale)
                        }
                        timestampRange(startMillis = 150, endMillis = 250, easing = LinearEasing) {
                            fade(BundleHeader.Elements.PreviewIcon1)
                        }
                        timestampRange(startMillis = 150, endMillis = 250, easing = LinearEasing) {
                            fade(NotificationRowPrimitives.Elements.ExpandedNumber)
                        }
                        timestampRange(
                            startMillis = 50,
                            endMillis = 300,
                            easing = FastOutSlowInEasing,
                        ) {
                            translate(BundleHeader.Elements.PreviewIcon2, x = 16.dp)
                            scaleDraw(BundleHeader.Elements.PreviewIcon2, scale, scale)
                        }
                        timestampRange(startMillis = 150, endMillis = 300, easing = LinearEasing) {
                            fade(BundleHeader.Elements.PreviewIcon2)
                        }
                        timestampRange(startMillis = 100, easing = FastOutSlowInEasing) {
                            translate(BundleHeader.Elements.PreviewIcon3, x = 32.dp)
                            scaleDraw(BundleHeader.Elements.PreviewIcon3, scale, scale)
                        }
                        timestampRange(startMillis = 200, endMillis = 350, easing = LinearEasing) {
                            fade(BundleHeader.Elements.PreviewIcon3)
                        }
                    }
                },
        )

    DisposableEffect(viewModel, state) {
        viewModel.state = state
        onDispose { viewModel.state = null }
    }

    val scope = rememberCoroutineScope()
    DisposableEffect(viewModel, state) {
        viewModel.composeScope = scope
        onDispose { viewModel.composeScope = null }
    }
    
    val useBlur = viewModel.useBlurBackground
    val onKeyguard = viewModel.isOnKeyguard

    // In most cases the height is expected to be equal to the header height dimension's value, but
    // it is set as the minimum here so that the header can resize if necessary for larger font
    // or display sizes.
    val bgModifier = if (!useBlur && onKeyguard) {
        Modifier.background(MaterialTheme.colorScheme.surfaceBright)
    } else {
        Modifier
    }
    Box(
        modifier =
            modifier
                .then(bgModifier)
                .heightIn(min = dimensionResource(R.dimen.notification_bundle_header_height))
    ) {
        Background(
            background = viewModel.backgroundDrawable,
            useBlurBackground = useBlur && onKeyguard,
            modifier = Modifier.matchParentSize(),
        )
        SceneTransitionLayout(
            state = state,
            // The BundleHeader is clickable, but clicks are handled at the level of the
            // ExpandableNotificationRow. We clear all semantics here so that accessibility focus
            // remains on the same element as handles the clicks and actions.
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            scene(BundleHeader.Scenes.Collapsed) {
                BundleHeaderContent(viewModel, collapsed = true)
            }
            scene(BundleHeader.Scenes.Expanded) {
                BundleHeaderContent(viewModel, collapsed = false)
            }
        }
    }
}

@Composable
private fun Background(
    background: Drawable?,
    useBlurBackground: Boolean,
    modifier: Modifier = Modifier,
) {
    if (background != null && !useBlurBackground) {
        val painter = rememberDrawablePainter(drawable = background)
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentScope.BundleHeaderContent(
    viewModel: BundleHeaderViewModel,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 12.dp),
    ) {
        BundleIcon(
            viewModel.bundleIcon,
            large = false, // BundleHeader is always small
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp)
                    .align(Alignment.CenterVertically)
                    .width(40.dp)
                    // Has to be a shared element because we may have a semi-transparent background
                    .element(NotificationRowPrimitives.Elements.NotificationIconBackground),
        )

        // Set FontWeight.ExtraBold if bold text adjustment is enabled
        // because titleMediumEmphasized is already bold
        val config = LocalConfiguration.current
        val isBoldTextEnabled = config.fontWeightAdjustment > 0
        Text(
            text = stringResource(viewModel.titleText),
            style =
                MaterialTheme.typography.titleMediumEmphasized.copy(
                    fontWeight = if (isBoldTextEnabled) FontWeight.ExtraBold else FontWeight.Bold
                ),
            color = MaterialTheme.colorScheme.primary,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            modifier = Modifier.element(BundleHeader.Elements.TitleText).weight(1f),
        )

        if (collapsed) {
            if (viewModel.previewIcons.isNotEmpty()) {
                BundlePreviewIcons(
                    previewDrawables = viewModel.previewIcons,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        ExpansionControl(
            collapsed = collapsed,
            numberToShow = if (collapsed) viewModel.numberOfChildren else null,
            modifier =
                Modifier.padding(start = 8.dp, end = 16.dp).semantics(mergeDescendants = false) {
                    contentDescription = viewModel.numberOfChildrenContentDescription
                },
        )
    }
}

@Composable
private fun ContentScope.BundlePreviewIcons(
    previewDrawables: List<Drawable>,
    modifier: Modifier = Modifier,
) {
    check(previewDrawables.isNotEmpty())
    val iconSize = 24.dp

    // The design stroke width is 2.5dp but there is a ~4% padding inside app icons; ~1.25dp here.
    val borderWidth = 1.25.dp
    HalfOverlappingReversedRow(
        modifier =
            modifier.graphicsLayer {
                // This is needed for rendering transparent PreviewIcon border
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        // We need to lay out icons from the end (icon1) to the start (icon3) so that we can define
        // STL animations statically per element, rather than making the movement of each element's
        // animation dynamic based on the number of visible siblings. This take/reversed does that,
        // while preserving the user's expected ordering of start-to-end == top-to-bottom contents.
        val reversedIcons = previewDrawables.take(3).reversed()
        PreviewIcon(
            drawable = reversedIcons[0],
            modifier = Modifier.element(BundleHeader.Elements.PreviewIcon1).size(iconSize),
            borderWidth = borderWidth,
        )
        if (reversedIcons.size < 2) return@HalfOverlappingReversedRow
        PreviewIcon(
            drawable = reversedIcons[1],
            modifier = Modifier.element(BundleHeader.Elements.PreviewIcon2).size(iconSize),
            borderWidth = borderWidth,
        )
        if (reversedIcons.size < 3) return@HalfOverlappingReversedRow
        PreviewIcon(
            drawable = reversedIcons[2],
            modifier = Modifier.element(BundleHeader.Elements.PreviewIcon3).size(iconSize),
            borderWidth = borderWidth,
        )
    }
}

/** The Icon used to display a preview of contained child notifications in a Bundle. */
@Composable
private fun PreviewIcon(drawable: Drawable, modifier: Modifier = Modifier, borderWidth: Dp) {
    val strokeWidthPx = with(LocalDensity.current) { borderWidth.toPx() }
    Box(
        modifier =
            modifier.drawWithContent {
                // Draw a circle with BlendMode.Clear to 'erase' pixels for the "border".
                // This will punch a hole in *this* icon's local offscreen buffer, allowing the
                // background of the containing Composable (which needs to have a global
                // offscreen layer) to show through.
                drawCircle(
                    color = Color.Black, // Color doesn't matter for BlendMode.Clear
                    radius = (size.minDimension / 2f) + strokeWidthPx,
                    center = center,
                    blendMode = BlendMode.Clear,
                )

                // Draw the original content of the inner Box
                drawContent()
            }
    ) {
        Image(
            painter = rememberDrawablePainter(drawable),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun HalfOverlappingReversedRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val placeables = measurables.fastMap { measurable -> measurable.measure(constraints) }

        if (placeables.isEmpty())
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val width = placeables.fastSumBy { it.width / 2 } + placeables.first().width / 2
        val childHeight = placeables.fastMaxOfOrDefault(0) { it.height }

        layout(constraints.constrainWidth(width), constraints.constrainHeight(childHeight)) {
            // Start in the middle of the right-most placeable
            var currentXPosition = placeables.fastSumBy { it.width / 2 }
            placeables.fastForEach { placeable ->
                currentXPosition -= placeable.width / 2
                placeable.placeRelative(x = currentXPosition, y = 0)
            }
        }
    }
}
