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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.PathParser
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable as ComposeAnimatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.modifiers.size
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.flags.DesktopSizing
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconWidth
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TILE_INITIAL_DELAY_MILLIS
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TILE_MARQUEE_ITERATIONS
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileEndPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileLabelBlurWidth
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlin.math.abs
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

private const val TEST_TAG_TILE_ICON = "qs_tile_icon"
private const val TEST_TAG_TOGGLE = "qs_tile_toggle_target"
private const val TEST_TAG_SMALL = "qs_tile_small"
private const val TEST_TAG_LARGE = "qs_tile_large"

/** White of an active ColorOS bubble on a dark panel, a touch short of opaque like ColorOS. */
internal val ColorOSActiveFill = Color(0xF2FFFFFF)

/** White fill of the ColorOS sliders, which lets a little of the glass show through. */
internal val ColorOSSliderFill = Color(0xF7FFFFFF)

/**
 * Glass rim of ColorOS tiles, lit from the top left and the bottom right with the sides fading out
 * in between. Created once instead of on every frame of the QS animation.
 */
internal val ColorOSTileRim =
    Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.30f),
        0.3f to Color.White.copy(alpha = 0.05f),
        0.7f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.22f),
    )

/**
 * Diameter of the ColorOS round tiles, which is also the height of the pills and the width of the
 * sliders. The expanded ColorOS panel sets it to the width of one of its four columns so the
 * circles fill their column like in ColorOS. Everywhere else (QQS, edit mode) it keeps the fixed
 * default.
 */
internal val LocalColorOSTileSize = compositionLocalOf { CommonTileDefaults.ColorOSTileHeight }

@Composable
fun ClassicTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    iconShapeKey: String,
    colors: TileColors,
    labelHide: Boolean,
    modifier: Modifier = Modifier,
    rim: Brush? = null,
    glass: Boolean = false,
    chevron: Boolean = false,
    pressSource: InteractionSource? = null,
    lottieSpec: String? = null,
    active: Boolean = false,
) {
    val isNoBackground = iconShapeKey in QSTileIconShapes.NO_BACKGROUND_KEYS
    // The circle is a blur region of ColorOS glass, instead of the flat fill and the rim.
    val onGlass = glass && !isNoBackground
    val iconShape = remember(iconShapeKey) {
        QSTileIconShapes.shapeForKey(iconShapeKey)
    }

    val overlayPathData = remember(iconShapeKey) {
        QSTileIconShapes.OVERLAY_BY_KEY[iconShapeKey]
    }
    val overlayPath = remember(overlayPathData) {
        overlayPathData?.let { pathData ->
            try {
                PathParser.createPathFromPathData(pathData)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    // The ColorOS style (the only one that passes a rim) eases the toggle over 200ms and pops the
    // icon, instead of using the default springs.
    val colorOS = rim != null
    val animatedColor by animateToggleColor(colors.background, colorOS, "QSTileCircleBgColor")
    val animatedOutlineColor by animateColorAsState(colors.outline, label = "QSTileOutlineColor")
    // A tile that is on keeps the glass under its bubble. The bubble fades in over it instead of
    // replacing it, so the lit rim of the glass still shows around the edge.
    val glassBubbleAlpha by
        animateToggleFloat(if (onGlass && active) 1f else 0f, colorOS, "QSTileGlassBubble")
    // The icon pops from 0.8 to 1 when the tile toggles. A Lottie clip animates itself instead.
    val pop = remember { ComposeAnimatable(1f) }
    var popActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (!colorOS || popActive == active) return@LaunchedEffect
        popActive = active
        pop.snapTo(ColorOSPopFrom)
        pop.animateTo(1f, spring(dampingRatio = ColorOSPopDamping, stiffness = ColorOSPopStiffness))
    }

    val iconBoxSize = CommonTileDefaults.TileHeight
    val iconSize = CommonTileDefaults.LargeTileIconSize

    Column(
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(iconBoxSize)
                .colorOSPress(pressSource)
                .then(
                    if (onGlass) {
                        Modifier.clip(iconShape)
                            .colorOSGlassBlur(
                                iconShape,
                                if (active) colorOSGlassActiveTileMaterial()
                                else colorOSGlassTileMaterial(),
                            )
                            .drawBehind {
                                if (glassBubbleAlpha > 0f) {
                                    drawRect(color = animatedColor, alpha = glassBubbleAlpha)
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .thenIf(!isNoBackground && !onGlass) {
                    Modifier
                        .clip(iconShape)
                        .drawBehind {
                            val brush: Brush? = colors.backgroundGradient
                            if (brush != null) {
                                drawRect(brush = brush)
                            } else {
                                drawRect(color = animatedColor)
                            }
                        }
                        .thenIf(rim != null) {
                            Modifier.border(width = 1.dp, brush = rim!!, shape = iconShape)
                        }
                }
                .then(
                    if (rim != null && !isNoBackground) {
                        Modifier.colorOSSpotLight(iconShape, ColorOSSpotLightSmall)
                    } else {
                        Modifier
                    }
                )
                .thenIf(overlayPath != null) {
                    Modifier.drawWithContent {
                        drawContent()
                        overlayPath?.let { path ->
                            val scaledPath = Path(path)
                            val matrix = Matrix()
                            matrix.setScale(size.width / 100f, size.height / 100f)
                            scaledPath.transform(matrix)
                            drawPath(
                                path = scaledPath.asComposePath(),
                                color = animatedOutlineColor,
                            )
                        }
                    }
                },
        ) {
            val animated =
                !isNoBackground &&
                    colorOSTileLottie(
                        spec = lottieSpec,
                        active = active,
                        offColor = MaterialTheme.colorScheme.onSurface,
                        onColor = colors.icon,
                        size = iconSize,
                        modifier = Modifier.align(Alignment.Center),
                    )
            if (!animated) {
                SmallTileContent(
                    iconProvider = iconProvider,
                    color = if (!isNoBackground) colors.icon else animatedOutlineColor,
                    size = { iconSize },
                    modifier =
                        Modifier.align(Alignment.Center).thenIf(colorOS) {
                            Modifier.graphicsLayer {
                                scaleX = pop.value
                                scaleY = pop.value
                            }
                        },
                    eased = colorOS,
                )
            }
        }

        if (!labelHide) {
            val animatedLabelColor by animateColorAsState(colors.classicLabel, label = "QSTileLabelColor")
            val animatedSecondaryLabelColor by
                animateColorAsState(colors.classicSecondaryLabel, label = "QSTileSecondaryLabelColor")
            // ColorOS wraps the label over two lines and has no state line under it.
            val colorOSLabels = LocalColorOSQsStyle.current
            val labels: @Composable () -> Unit = {
                TileLabel(
                    text = label,
                    maxLines = if (colorOSLabels) 2 else 1,
                    color = { animatedLabelColor },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        hyphens = Hyphens.Auto,
                        lineHeight =
                            if (colorOSLabels) CommonTileDefaults.ColorOSLabelLineHeight
                            else MaterialTheme.typography.labelMedium.lineHeight,
                    ),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                )
                if (!colorOSLabels && !TextUtils.isEmpty(secondaryLabel)) {
                    TileLabel(
                        text = secondaryLabel ?: "",
                        color = { animatedSecondaryLabelColor },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            hyphens = Hyphens.Auto,
                        ),
                        modifier = Modifier
                            .fillMaxWidth(),
                    )
                }
            }
            if (chevron) {
                // The chevron sits right after the widest label line, and the two are centred
                // under the tile as one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false).width(IntrinsicSize.Max)) { labels() }
                    Image(
                        painter = painterResource(R.drawable.ic_coloros_tile_chevron),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(animatedSecondaryLabelColor),
                        modifier = Modifier.padding(start = 1.dp).size(10.dp),
                    )
                }
            } else {
                labels()
            }
        }
    }
}

@Composable
fun LargeTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    sideDrawable: Drawable?,
    colors: TileColors,
    squishiness: () -> Float,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(CommonTileDefaults.InactiveIconCornerRadius),
    textScale: () -> Float = { 1f },
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    lottieSpec: String? = null,
    active: Boolean = false,
    labelMaxLines: Int = 1,
) {
    val isDualTarget = toggleClick != null
    val colorOSStyle = LocalColorOSQsStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (colorOSStyle) {
                Arrangement.spacedBy(space = CommonTileDefaults.ColorOSPillArrangementPadding)
            } else {
                tileHorizontalArrangement()
            },
        modifier = modifier,
    ) {
        // Icon
        val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
        val animatedBackgroundColor by
            animateColorAsState(colors.iconBackground, label = "QSTileDualTargetBackgroundColor")
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        // The icon circle of a ColorOS pill is glass, like the round tiles: a blur region of its
        // own that a white bubble fades in over while the tile is on, so the lit rim of the glass
        // still shows around the bubble. Without blur it is the flat fill with the glass rim.
        val onGlass = colorOSStyle && LocalColorOSGlassBlur.current != null
        val glassMaterial =
            if (active) colorOSGlassActiveTileMaterial() else colorOSGlassTileMaterial()
        val glassBubbleAlpha by
            animateFloatAsState(if (onGlass && active) 1f else 0f, label = "QSTileGlassBubble")
        // colorOSGlassBlur is @Composable, so it is built here and not inside the thenIf lambda.
        val glassBlurModifier =
            if (onGlass) Modifier.colorOSGlassBlur(iconShape, glassMaterial) else Modifier
        Box(
            modifier =
                Modifier.size(CommonTileDefaults.ToggleTargetSize).thenIf(isDualTarget) {
                    Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                        .clip(iconShape)
                        .then(glassBlurModifier)
                        .drawBehind {
                            if (onGlass) {
                                if (glassBubbleAlpha > 0f) {
                                    drawRect(
                                        color = animatedBackgroundColor,
                                        alpha = glassBubbleAlpha,
                                    )
                                }
                            } else {
                                val brush: Brush? = colors.backgroundGradient
                                if (brush != null) {
                                    drawRect(brush = brush)
                                } else {
                                    drawRect(animatedBackgroundColor)
                                }
                            }
                        }
                        .thenIf(colorOSStyle && !onGlass) {
                            Modifier.border(width = 1.dp, brush = ColorOSTileRim, shape = iconShape)
                        }
                        // apply the squish effect after the bg is drawn
                        .verticalSquish(squishiness)
                        .combinedClickable(
                            onClick = toggleClick!!,
                            onLongClick = onLongClick,
                            onLongClickLabel = longPressLabel,
                            hapticFeedbackEnabled = false, // Haptics handled separately
                        )
                        .thenIf(accessibilityUiState != null) {
                            Modifier.semantics {
                                    accessibilityUiState as AccessibilityUiState
                                    contentDescription = accessibilityUiState.contentDescription
                                    stateDescription = accessibilityUiState.stateDescription
                                    accessibilityUiState.toggleableState?.let {
                                        toggleableState = it
                                    }
                                    role = Role.Switch
                                }
                                .sysuiResTag(TEST_TAG_TOGGLE)
                        }
                }
        ) {
            val animated =
                colorOSTileLottie(
                    spec = lottieSpec,
                    active = active,
                    offColor = MaterialTheme.colorScheme.onSurface,
                    onColor = colors.icon,
                    size = CommonTileDefaults.LargeTileIconSize,
                    modifier = Modifier.align(Alignment.Center),
                )
            if (!animated) {
                SmallTileContent(
                    iconProvider = iconProvider,
                    color = colors.icon,
                    size = { CommonTileDefaults.LargeTileIconSize },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // Labels
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            isVisible = isVisible,
            labelMaxLines = labelMaxLines,
            modifier = Modifier.weight(1f).bounceScale(TransformOrigin(0f, .5f), textScale),
        )

        if (sideDrawable != null) {
            Image(
                painter = rememberDrawablePainter(sideDrawable),
                contentDescription = null,
                modifier = Modifier.width(SideIconWidth).height(SideIconHeight),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LargeTileLabels(
    label: String,
    secondaryLabel: String?,
    colors: TileColors,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    labelMaxLines: Int = 1,
) {
    val animatedLabelColor by animateColorAsState(colors.label, label = "QSTileLabelColor")
    val animatedSecondaryLabelColor by
        animateColorAsState(colors.secondaryLabel, label = "QSTileSecondaryLabelColor")
    Column(verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxHeight()) {
        TileLabel(
            text = label,
            style = MaterialTheme.typography.titleSmallEmphasized.copy(
                fontWeight =
                    if (LocalColorOSQsStyle.current) FontWeight.Medium else FontWeight.SemiBold,
            ),
            color = { animatedLabelColor },
            isVisible = isVisible,
            maxLines = labelMaxLines,
        )
        if (!TextUtils.isEmpty(secondaryLabel)) {
            TileLabel(
                secondaryLabel ?: "",
                color = { animatedSecondaryLabelColor },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Normal,
                ),
                isVisible = isVisible,
                modifier =
                    Modifier.thenIf(
                        accessibilityUiState?.stateDescription?.contains(secondaryLabel ?: "") ==
                            true
                    ) {
                        Modifier.clearAndSetSemantics {}
                    },
            )
        }
    }
}

@Composable
fun SmallTileContent(
    iconProvider: Context.() -> Icon,
    color: Color,
    modifier: Modifier = Modifier,
    size: @Composable () -> Dp = { CommonTileDefaults.SmallTileIconSize },
    animateToEnd: Boolean = false,
    eased: Boolean = false,
) {
    val context = LocalContext.current
    val icon = iconProvider(context)
    val animatedColor by animateToggleColor(color, eased, "QSTileIconColor")
    val sizeValue = size()
    val iconModifier =
        modifier
            .size({ sizeValue.roundToPx() }, { sizeValue.roundToPx() })
            .sysuiResTag(TEST_TAG_TILE_ICON)

    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> context.getDrawable(icon.resId)
            }
        }

    // Skip initial animation, icons should animate only as the state change
    // and not when first composed
    var shouldSkipInitialAnimation by remember { mutableStateOf(true) }
    if (loadedDrawable is Animatable) {
        LaunchedEffect(Unit) { shouldSkipInitialAnimation = animateToEnd }

        val painter =
            when (icon) {
                is Icon.Resource -> {
                    val image = AnimatedImageVector.animatedVectorResource(id = icon.resId)
                    key(icon) {
                        var atEnd by remember(icon) { mutableStateOf(shouldSkipInitialAnimation) }
                        LaunchedEffect(key1 = icon.resId) { atEnd = true }

                        rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
                    }
                }

                is Icon.Loaded -> {
                    val painter = rememberDrawablePainter(loadedDrawable)

                    // rememberDrawablePainter automatically starts the animation. Using
                    // SideEffect here to immediately stop it if needed
                    DisposableEffect(painter) {
                        if (loadedDrawable is AnimatedVectorDrawable) {
                            loadedDrawable.forceAnimationOnUI()
                        }
                        if (shouldSkipInitialAnimation) {
                            loadedDrawable.stop()
                        }
                        onDispose {}
                    }

                    painter
                }
            }

        NonClippedImage(
            painter = painter,
            contentDescription = icon.contentDescription?.load(),
            colorFilter = ColorFilter.tint(color = animatedColor),
            modifier = iconModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(icon = icon, tint = animatedColor, modifier = iconModifier)
    }
}

@Composable
private fun TileLabel(
    text: String,
    color: ColorProducer,
    style: TextStyle,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    maxLines: Int = 1,
) {
    var textSize by remember { mutableIntStateOf(0) }

    // A label that wraps has nothing to scroll or fade, the marquee would give it unbounded width
    // and keep it on one line.
    val wraps = maxLines > 1
    val iterations = if (isVisible()) TILE_MARQUEE_ITERATIONS else 0

    BasicText(
        text = text,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = if (wraps) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { textSize = it.size.width },
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    if (!wraps && textSize > size.width) {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (!wraps && textSize > size.width) {
                        // Draw a blur over the end of the text
                        val edgeWidthPx = TileLabelBlurWidth.toPx()
                        if (layoutDirection == LayoutDirection.Rtl) {
                            drawFadedEdge(
                                startX = 0f,
                                endX = edgeWidthPx,
                                colors = listOf(Color.Transparent, Color.Black),
                            )
                        } else {
                            drawFadedEdge(
                                startX = size.width - edgeWidthPx,
                                endX = size.width,
                                colors = listOf(Color.Black, Color.Transparent),
                            )
                        }
                    }
                }
                .thenIf(!wraps) {
                    Modifier.basicMarquee(
                        iterations = iterations,
                        initialDelayMillis = TILE_INITIAL_DELAY_MILLIS,
                    )
                },
    )
}

fun Modifier.tileTestTag(iconOnly: Boolean): Modifier {
    return sysuiResTag(if (iconOnly) TEST_TAG_SMALL else TEST_TAG_LARGE)
}

/**
 * Apply the correct padding for large tiles
 *
 * Large tiles have a different end padding based on the content, such as if it's a dual target tile
 * or if it has a side drawable.
 */
@Composable
fun Modifier.largeTilePadding(isDualTarget: Boolean = false): Modifier {
    return padding(
        start =
            if (LocalColorOSQsStyle.current) {
                CommonTileDefaults.ColorOSPillStartPadding
            } else {
                CommonTileDefaults.StartPadding
            },
        end = if (isDualTarget) CommonTileDefaults.DualTargetEndPadding else TileEndPadding,
    )
}

private fun DrawScope.drawFadedEdge(startX: Float, endX: Float, colors: List<Color>) {
    drawRect(
        topLeft = Offset(startX, 0f),
        size = Size(abs(endX - startX), size.height),
        brush = Brush.horizontalGradient(colors = colors, startX = startX, endX = endX),
        blendMode = BlendMode.DstIn,
    )
}

fun Modifier.bounceScale(
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    scale: () -> Float,
): Modifier {
    return motionTestValues { scale() exportAs TileBounceMotionTestKeys.BounceScale }
        .graphicsLayer {
            scale().let {
                scaleY = it
                scaleX = it
                this.transformOrigin = transformOrigin
            }
        }
}

@VisibleForTesting
object TileBounceMotionTestKeys {
    val BounceScale = MotionTestValueKey<Float>("bounceScale")
}

object CommonTileDefaults {
    val ActiveIconCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_active_icon_corner_radius)

    val ActiveTileCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_active_tile_corner_radius)

    val DualTargetEndPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_dual_target_end_padding)

    val InactiveIconCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_inactive_icon_corner_radius)

    val InactiveTileCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_inactive_tile_corner_radius)

    /** Dimensions for focus rings with wide corners */
    val TileDetailsEntryWideCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.focus_ring_wide_corner_radius)

    /** Dimensions for focus rings with tight corners */
    val TileDetailsEntryTightCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.focus_ring_tight_corner_radius)

    // The size of the icon in the tile with an icon and a label.
    val LargeTileIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (DesktopSizing.isEnabled) {
                smallIconSize
            } else {
                largeIconSize
            }

    // The size of the icon in the tile with an icon only.
    val SmallTileIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (DesktopSizing.isEnabled) {
                largeIconSize
            } else {
                smallIconSize
            }

    val StartPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_start_padding)

    /** ColorOS style sizes, measured from ColorOS 17. The default style is unchanged. */
    val ColorOSTileHeight = 66.dp
    val ColorOSToggleTargetSize = 44.dp

    /** Room under a classic round icon tile for its label, on top of [TileHeight]. */
    val ClassicLabelHeight = 32.dp

    /**
     * Line height of a ColorOS round tile label. Two lines have to fit in [ClassicLabelHeight]
     * less the 4dp above the label, or the ellipsis cuts the label to one line.
     */
    val ColorOSLabelLineHeight = 13.sp

    val TileHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (LocalColorOSQsStyle.current) LocalColorOSTileSize.current
            else dimensionResource(id = R.dimen.common_tile_default_tile_height)

    val ToggleTargetSize: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (LocalColorOSQsStyle.current) {
                // Keeps the proportions of the 46dp toggle inside a 66dp tile.
                LocalColorOSTileSize.current * (ColorOSToggleTargetSize / ColorOSTileHeight)
            } else {
                dimensionResource(id = R.dimen.common_tile_default_toggle_target_size)
            }

    val SideIconWidth = 32.dp
    val SideIconHeight = 20.dp
    val ChevronSize = 14.dp
    val TileEndPadding = 12.dp
    val TileArrangementPadding = 6.dp

    /**
     * ColorOS pills, measured from ColorOS 17: the icon circle sits as far from the left edge as
     * from the top and bottom, and the text is a little further from the circle.
     */
    val ColorOSPillStartPadding = 10.5.dp
    val ColorOSPillArrangementPadding = 8.dp
    val TileLabelBlurWidth = 32.dp
    const val TILE_MARQUEE_ITERATIONS = 1
    const val TILE_INITIAL_DELAY_MILLIS = 2000

    @Composable
    fun longPressLabelSettings() = stringResource(id = R.string.accessibility_long_click_tile)

    @Composable
    fun longPressLabelMoreDetails() =
        stringResource(id = R.string.accessibility_long_click_tile_details)

    private val largeIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_large_tile_icon_size)

    private val smallIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_icon_size)
}

/** Same as Image, but it doesn't clip its content. */
@Composable
private fun NonClippedImage(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter,
) {
    val semantics =
        if (contentDescription != null) {
            Modifier.semantics {
                this.contentDescription = contentDescription
                this.role = Role.Image
            }
        } else {
            Modifier
        }

    // Explicitly use a simple Layout implementation here as Spacer squashes any non fixed
    // constraint with zero
    Layout(
        modifier
            .then(semantics)
            .paint(
                painter,
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                colorFilter = colorFilter,
            )
    ) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}

private const val ColorOSToggleMillis = 200
private const val ColorOSPopFrom = 0.8f
private const val ColorOSPopDamping = 0.55f
private const val ColorOSPopStiffness = 500f
private val ColorOSToggleEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

/** [animateColorAsState] with the ColorOS 200ms ease when [eased], the default spring otherwise. */
@Composable
private fun animateToggleColor(target: Color, eased: Boolean, label: String): State<Color> =
    if (eased) {
        animateColorAsState(
            target,
            animationSpec = tween(ColorOSToggleMillis, easing = ColorOSToggleEase),
            label = label,
        )
    } else {
        animateColorAsState(target, label = label)
    }

/** [animateFloatAsState] with the ColorOS 200ms ease when [eased], the default spring otherwise. */
@Composable
private fun animateToggleFloat(target: Float, eased: Boolean, label: String): State<Float> =
    if (eased) {
        animateFloatAsState(
            target,
            animationSpec = tween(ColorOSToggleMillis, easing = ColorOSToggleEase),
            label = label,
        )
    } else {
        animateFloatAsState(target, label = label)
    }
