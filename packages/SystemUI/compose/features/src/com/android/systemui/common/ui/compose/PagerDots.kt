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

package com.android.systemui.common.ui.compose

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.compose.modifiers.width
import com.android.systemui.common.ui.compose.Icons.ChevronRight
import com.android.systemui.common.ui.compose.PagerDotsDefaults.SPRING_STIFFNESS
import com.android.systemui.res.R
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Pager dots to be used with a Pager (displays horizontally, but can be rotated for vertical). */
@Composable
fun PagerDots(
    pagerState: PagerState,
    /** Color for the dot corresponding to the current page. */
    activeColor: Color,
    /** Color for the dots corresponding to the not current pages. */
    nonActiveColor: Color,
    modifier: Modifier = Modifier,
    /** Size of each inactive dot. The active dot width is double this, unless [roundDots]. */
    dotSize: Dp = 6.dp,
    /** Space between each dot. */
    spaceSize: Dp = 4.dp,
    /** Whether to show arrows for moving to previous/next page on click */
    showArrows: Boolean = false,
    /** Whether to cycle through pages on click on the pager dots */
    clickToCyclePages: Boolean = false,
    /** Whether the active dot is a round dot like the others, and not a wider pill. */
    roundDots: Boolean = false,
) {
    if (pagerState.pageCount < 2) return

    val activeDotWidth = if (roundDots) dotSize else dotSize * 2
    // Active dot + inactive dots + spacing
    val totalWidth =
        activeDotWidth +
            (dotSize * (pagerState.pageCount - 1)) +
            (spaceSize * (pagerState.pageCount - 1))
    val coroutineScope = rememberCoroutineScope()

    // List of animated colors, one per page
    val colors =
        remember(pagerState.pageCount, activeColor, nonActiveColor) {
            List(pagerState.pageCount) { page ->
                Animatable(if (page == pagerState.currentPage) activeColor else nonActiveColor)
            }
        }
    LaunchedEffect(pagerState.currentPage, colors) {
        colors.forEachIndexed { index, animatable ->
            val targetColor =
                if (index == pagerState.currentPage) {
                    activeColor
                } else {
                    nonActiveColor
                }
            if (animatable.targetValue != targetColor) {
                launch {
                    animatable.animateTo(
                        targetColor,
                        animationSpec = spring(stiffness = SPRING_STIFFNESS),
                    )
                }
            }
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArrows) {
            val enabled = pagerState.canScrollBackward
            Icon(
                imageVector = ChevronRight,
                contentDescription =
                    stringResource(R.string.pager_dots_previous_content_description),
                tint = activeColor.copy(enabled.alpha),
                modifier =
                    Modifier.size(24.dp).scale(scaleX = -1f, scaleY = 1f).clickable(
                        enabled = enabled
                    ) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
            )
        }
        val nextPageActionLabel =
            stringResource(R.string.accessibility_pager_dots_navigate_to_next_page_action)
        Canvas(
            Modifier.width { totalWidth.roundToPx() }
                .height(dotSize)
                .pagerDotsSemantics(pagerState, coroutineScope)
                .thenIf(clickToCyclePages) {
                    Modifier.clickable(onClickLabel = nextPageActionLabel) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.nextPage())
                        }
                    }
                }
        ) {
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                // The impacted index is the neighbor of the active index, in the direction dictated
                // from the page offset. The impacted dot will have its width modified
                val impactedIndex =
                    if (pagerState.currentPageOffsetFraction >= 0) {
                        pagerState.currentPage + 1
                    } else {
                        pagerState.currentPage - 1
                    }
                val dotSizePx = dotSize.toPx()
                val activeDotWidthPx = activeDotWidth.toPx()
                val spacingPx = spaceSize.toPx()
                val offsetFraction = abs(pagerState.currentPageOffsetFraction)
                val cornerRadius = CornerRadius(size.height / 2)

                var x = 0f
                repeat(pagerState.pageCount) { page ->
                    val width =
                        when {
                            roundDots -> dotSizePx
                            page == impactedIndex -> dotSizePx + dotSizePx * offsetFraction
                            page == pagerState.currentPage ->
                                activeDotWidthPx - dotSizePx * offsetFraction
                            else -> dotSizePx
                        }

                    drawRoundRect(
                        color = colors[page].value,
                        cornerRadius = cornerRadius,
                        topLeft = Offset(x, 0f),
                        size = Size(width, size.height),
                    )
                    x += width + spacingPx
                }
            }
        }
        if (showArrows) {
            val enabled = pagerState.currentPage != pagerState.pageCount - 1
            Icon(
                imageVector = ChevronRight,
                contentDescription = stringResource(R.string.pager_dots_next_content_description),
                tint = activeColor.copy(alpha = enabled.alpha),
                modifier =
                    Modifier.size(24.dp).clickable(enabled = enabled) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
            )
        }
    }
}

/** Returns the next page, cycling back to 0 if we're at the last page. */
private fun PagerState.nextPage(): Int {
    if (pageCount == 0) return 0
    return (currentPage + 1) % pageCount
}

private val Boolean.alpha
    get() = if (this) 1f else 0.38f

private fun Modifier.pagerDotsSemantics(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
): Modifier {
    return then(
        Modifier.semantics {
            pageLeft {
                if (pagerState.canScrollBackward) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                    true
                } else {
                    false
                }
            }
            pageRight {
                if (pagerState.canScrollForward) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                    true
                } else {
                    false
                }
            }
            stateDescription = "Page ${pagerState.settledPage + 1} of ${pagerState.pageCount}"
        }
    )
}

private object PagerDotsDefaults {
    const val SPRING_STIFFNESS = 1600f
}

private object Icons {
    val ChevronRight: ImageVector
        get() {
            if (_ChevronRight != null) {
                return _ChevronRight!!
            }
            _ChevronRight =
                ImageVector.Builder(
                        name = "ChevronRight",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 960f,
                        viewportHeight = 960f,
                        autoMirror = true,
                    )
                    .apply {
                        path(fill = SolidColor(Color.Black)) {
                            moveTo(472f, 480f)
                            lineTo(332f, 340f)
                            quadToRelative(-18f, -18f, -18f, -44f)
                            reflectiveQuadToRelative(18f, -44f)
                            quadToRelative(18f, -18f, 44f, -18f)
                            reflectiveQuadToRelative(44f, 18f)
                            lineToRelative(183f, 183f)
                            quadToRelative(9f, 9f, 14f, 21f)
                            reflectiveQuadToRelative(5f, 24f)
                            quadToRelative(0f, 12f, -5f, 24f)
                            reflectiveQuadToRelative(-14f, 21f)
                            lineTo(420f, 708f)
                            quadToRelative(-18f, 18f, -44f, 18f)
                            reflectiveQuadToRelative(-44f, -18f)
                            quadToRelative(-18f, -18f, -18f, -44f)
                            reflectiveQuadToRelative(18f, -44f)
                            lineToRelative(140f, -140f)
                            close()
                        }
                    }
                    .build()

            return _ChevronRight!!
        }

    @Suppress("ObjectPropertyName") private var _ChevronRight: ImageVector? = null
}
