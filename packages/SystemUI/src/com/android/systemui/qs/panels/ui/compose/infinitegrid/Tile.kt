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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.mechanics.effects.VerticalTactileSurfaceRevealEffect
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.gradient.diagonal
import com.android.systemui.gradient.rememberGradientColors
import com.android.systemui.gradient.rememberGradientEnabled
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.Tooltip
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ToggleTargetSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.tiles.impl.ringer.QSTileRingerSlider
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

private val TileViewModel.traceName
    get() = spec.toString().takeLast(Trace.MAX_SECTION_NAME_LEN)

/** Values of [Settings.System.QS_PANEL_STYLE]; 0 is the default card style. */
private const val QS_PANEL_STYLE_CLASSIC = 1
private const val QS_PANEL_STYLE_COLOROS = 2
private const val QS_PANEL_STYLE_NOTHING = 3

/** Nothing-style Quick Settings (monochrome pills, circles and 2x2 quad tiles on a 4 column grid). */
internal val LocalNothingQsStyle = compositionLocalOf { false }

/** Tiles that the Nothing style draws as a 2x2 quad tile. */
/** Page-relative (row, column) of each tile as placed by the paginated Nothing grid, if any. */
internal val LocalNothingPagePlacements = compositionLocalOf<Map<TileSpec, Pair<Int, Int>>?> { null }
internal val LocalQSQuadTiles = compositionLocalOf { emptySet<TileSpec>() }

/** Draws a Nothing style slider inside its grid cell. Null where the host cannot draw them. */
internal val LocalNothingSliderContent =
    compositionLocalOf<(@Composable (NothingSliderKind) -> Unit)?> { null }

/** Sliders of the page being drawn, with rows relative to the page, if the pager set them. */
internal val LocalNothingPageSliders = compositionLocalOf<List<NothingSliderItem>?> { null }

/** ColorOS-style Quick Settings (glass tiles, round icon tiles with labels below). */
internal val LocalColorOSQsStyle = compositionLocalOf { false }

private val ColorOSBlue = Color(0xFF0068FF)
private val ColorOSGreen = Color(0xFF16A34A)
private val ColorOSRed = Color(0xFFF2453D)
private val ColorOSInk = Color(0xFF1C1C1E)
private val COLOROS_DATA_TILES = setOf("cell", "mobile_data", "mobiledata")
private val COLOROS_WIFI_TILES = setOf("wifi")

/** How much of a round tile that cannot be used is left, the whole tile fades, glass included. */
private const val ColorOSUnavailableAlpha = 0.45f

/** Tiles whose icon is dark ink on the white bubble of a dark panel, instead of blue. */
private val COLOROS_INK_TILES = setOf("dnd", "dark", "night")

/** Tiles that ColorOS marks with a small down chevron after the label, as they open more. */
private val COLOROS_DROPDOWN_TILES =
    COLOROS_DATA_TILES + setOf("dnd", "screenrecord", "hotspot", "screenshot")

/**
 * The label under a round ColorOS tile. The mobile data tile is named after the SIM there, the
 * network name its state carries, and is only called by its own name while there is none.
 */
private fun colorOSRoundTileLabel(spec: String, uiState: TileUiState): String =
    if (spec in COLOROS_DATA_TILES && uiState.secondaryLabel.isNotBlank()) {
        uiState.secondaryLabel
    } else {
        uiState.label
    }

/**
 * This composable function is responsible for rendering a tile based on the provided
 * [TileViewModel]. It handles different states of the tile (e.g., available, unavailable),
 * interactions (click, long click), and visual styles (icon only or large tile).
 *
 * @param tile The [TileViewModel] containing the data and logic for the tile.
 * @param iconOnly A boolean indicating whether to display only the icon of the tile or the full
 *   tile content (false for large tiles).
 * @param squishiness The float value representing the current squishiness factor of the tile, used
 *   for animations.
 * @param coroutineScope The [CoroutineScope] to launch coroutines for animations.
 * @param tileHapticsViewModelFactory A factory for creating a [TileHapticsViewModel] instance, used
 *   for haptic feedback.
 * @param modifier An optional [Modifier] to be applied to the root composable of the tile.
 * @param isVisible Whether the tile is currently visible. Defaults to true.
 * @param requestToggleTextFeedback A lambda function that is invoked when a toggleable icon only
 *   tile is clicked, used to request the feedback text.
 * @param detailsViewModel An optional [DetailsViewModel] used to handle navigation to a detailed
 *   view when a tile is clicked, if applicable.
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 * @param hideLabel If `true`, the label of a ColorOS style icon tile is not drawn (QQS shows only
 *   the round icons), whatever the tile label setting says. Ignored for the other panel styles,
 *   which follow the tile label setting.
 */
@Composable
fun ContentScope.Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
    hideLabel: Boolean = false,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        /*
         * Use produce state because [QSTile.State] doesn't have well defined equals (due to
         * inheritance). This way, even if tile.state changes, uiState may not change and lead to
         * recomposition.
         */
        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.handlesMainClick

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val colorOSStyle = LocalColorOSQsStyle.current
        val nothingStyle = LocalNothingQsStyle.current
        val isQuadTile = nothingStyle && LocalQSQuadTiles.current.contains(tile.spec)
        val tileGradient = rememberQSTileGradient()
        val colors =
            if (nothingStyle) {
                TileDefaults.nothingColorsForState(uiState, isQuadTile)
            } else if (colorOSStyle) {
                TileDefaults.colorOSColorsForState(uiState, iconOnly, tile.spec.spec)
            } else {
                TileDefaults.getColorForState(uiState, iconOnly, tileGradient)
            }
        val hapticsViewModel: TileHapticsViewModel? =
            if (rememberTileHaptic()) {
                rememberViewModel(traceName = "TileHapticsViewModel") {
                    tileHapticsViewModelFactory.create(tile)
                }
            } else {
                null
            }

        // The ColorOS style draws its icon tiles with the classic circle and label below, and
        // keeps the regular layout for the wide tiles.
        val userClassicStyle = rememberQSPanelStyle()
        val classicStyle = if (nothingStyle) false else if (colorOSStyle) iconOnly else userClassicStyle
        val userIconShapeKey = rememberQSTileIconShapeKey()
        val iconShapeKey =
            if (colorOSStyle || nothingStyle) QSTileIconShapes.DEFAULT_KEY else userIconShapeKey
        // The label below an icon tile follows the user setting in every panel style, and the
        // ColorOS style also drops it where the caller asks for it (QQS).
        val userLabelHide = rememberQSTileLabelHide()
        val labelHide = classicStyle && (userLabelHide || (colorOSStyle && hideLabel))
        val tileHeight = if (!classicStyle || labelHide) {
            CommonTileDefaults.TileHeight
        } else {
            CommonTileDefaults.TileHeight + CommonTileDefaults.ClassicLabelHeight
        }

        val userShapeMode = rememberTileShapeMode()
        val shapeMode = if (colorOSStyle || nothingStyle) 0 else userShapeMode
        val animationStyle = rememberQSTileAnimationStyle()
        val wantCircle = shapeMode == 4 && iconOnly
        val tileShape =
            if (nothingStyle) {
                when {
                    isQuadTile -> RoundedCornerShape(28.dp)
                    iconOnly -> CircleShape
                    else -> RoundedCornerShape(50)
                }
            } else if (wantCircle && !classicStyle) {
                CircleShape
            } else {
                TileDefaults.animateTileShapeAsState(uiState, shapeMode).value
            }
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")
        val isDualTarget = uiState.handlesToggleClick
        val interactionSource = remember { MutableInteractionSource() }

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.VerticalPadding.toPx() }

            val animatedCornerRadius by animateDpAsState(TileDefaults.tileRadius(uiState))

            val inactiveCornerRadius = InactiveIconCornerRadius
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(
                    deltaY = marginBottom,
                    effectSpec =
                        remember(inactiveCornerRadius) {
                            VerticalTactileSurfaceRevealEffect(
                                maxCornerSize = { animatedCornerRadius },
                                phase1MarginX = inactiveCornerRadius,
                            )
                        },
                    label = tile.traceName,
                )

            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.traceName)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        // The Nothing style swaps the wide sound tile for the ringer slider.
        if (nothingStyle && tile.spec.spec == "sound" && !iconOnly) {
            QSTileRingerSlider(
                modifier = modifier.then(surfaceRevealModifier).fillMaxSize().verticalSquish(squishiness),
                isNothing = true,
            )
            return@trace
        }

        // Where SurfaceFlinger can blur behind the shade, the glass of the wide ColorOS tiles and
        // of the round ones that are off is a blur region and not a flat fill.
        val glassBlur = LocalColorOSGlassBlur.current
        val glassPill = colorOSStyle && !classicStyle && glassBlur != null
        // The glass is drawn by SurfaceFlinger and does not follow the alpha of a Compose layer,
        // so the round tile that cannot be used scales the alpha of its own region as well.
        val colorOSDimmed = colorOSStyle && iconOnly && uiState.visualState == STATE_UNAVAILABLE
        val tileGlassBlur =
            remember(glassBlur, colorOSDimmed) {
                if (glassBlur != null && colorOSDimmed) {
                    ColorOSGlassBlur { glassBlur.alpha() * ColorOSUnavailableAlpha }
                } else {
                    glassBlur
                }
            }
        val outerShape = if (wantCircle && !classicStyle) RoundedCornerShape(0.dp) else tileShape
        val outerColor: () -> Color =
            if (wantCircle || classicStyle || glassPill) { { Color.Transparent } }
            else { { animatedColor } }
        val focusBorderColor = MaterialTheme.colorScheme.secondary

        val expandable =
            if (dynamicTargetResolutionEnabled()) tile.expandable
            else remember { Expandable(mutableSetOf()) }
        Tooltip(
            text = uiState.label,
            modifier = modifier,
            enabled = Flags.enableQsTileTooltips(),
        ) { modifier ->
            TileExpandable(
                expandable = expandable,
                color = outerColor,
                shape = outerShape,
                squishiness = if (nothingStyle && iconOnly) { { 1f } } else squishiness,
                hapticsViewModel = hapticsViewModel,
                classicStyle = classicStyle,
                modifier =
                    modifier
                        .then(
                            if (colorOSStyle && !classicStyle) {
                                Modifier.colorOSPress(interactionSource)
                            } else {
                                Modifier
                            }
                        )
                        .then(surfaceRevealModifier)
                        .then(
                            if (glassPill) {
                                Modifier.colorOSGlassBlur(tileShape, colorOSGlassTileMaterial())
                            } else if (colorOSStyle && !classicStyle) {
                                Modifier.border(
                                    width = 1.dp,
                                    brush = colorOSTileRim(),
                                    shape = tileShape,
                                )
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (colorOSStyle && !classicStyle) {
                                Modifier.colorOSSpotLight(tileShape, ColorOSSpotLightMedium)
                            } else {
                                Modifier
                            }
                        )
                        .thenIf(!wantCircle) {
                            modifier.borderOnFocus(
                                color = focusBorderColor,
                                outerShape.topEnd,
                            )
                        }
                        .sysuiResTag("tile_expandable")
                        .then(
                            if (nothingStyle) {
                                // The Nothing grid hands each tile a fixed cell to fill.
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.fillMaxWidth().height(tileHeight)
                            }
                        )
                        .tileToggleAnimation(animationStyle, uiState.visualState)
                        .bounceable(
                            currentBounceableInfo.bounceable,
                            currentBounceableInfo.previousTile,
                            currentBounceableInfo.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo.bounceEnd,
                            // ColorOS presses with colorOSPress instead of the width bounce.
                            interactionSource = interactionSource.takeIf { !colorOSStyle },
                        ),
            ) { expandable ->
                // Use main click on long press for small, available dual target tiles.
                // Open settings otherwise.
                val useLongClickToSettings = !(iconOnly && isDualTarget && isClickable)
                val longClick: (() -> Unit)? =
                    {
                            hapticsViewModel?.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                            )

                            if (useLongClickToSettings) {
                                tile.settingsClick(expandable)
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (!hasDetails) {
                                    tile.mainClick(expandable)
                                }
                            }
                        }
                        .takeIf { !useLongClickToSettings || uiState.handlesSettingsClick }

                // Bounce the tile's container if it is toggleable and is not a large
                // dual target tile. These don't toggle on main click.
                val bounceContainer = uiState.isToggleable && (iconOnly || !isDualTarget)
                val click: (() -> Unit)? =
                    onClick@{
                            if (!isClickable) return@onClick

                            if (iconOnly && isDualTarget) {
                                tile.toggleClick()
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (hasDetails) return@onClick

                                // For those tile's who doesn't have a detailed view, process with
                                // their `onClick` behavior.
                                tile.mainClick(expandable)
                            }

                            // Side effects of the click
                            hapticsViewModel?.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.CLICKED
                            )

                            coroutineScope.launch {
                                // Bounce the content of the tile if we're not animating the
                                // container.
                                if (!bounceContainer) {
                                    currentBounceableInfo.bounceable.animateContentBounce(iconOnly)
                                }
                            }
                            if (uiState.isToggleable && iconOnly) {
                                // And show footer text feedback for icons
                                requestToggleTextFeedback(tile.spec)
                            }
                    }
                if (wantCircle || classicStyle) {
                    val interaction = remember { MutableInteractionSource() }

                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .thenIf(classicStyle) { 
                                    Modifier
                                        .fillMaxSize() 
                                        .align(Alignment.TopCenter)
                                }
                                .thenIf(!classicStyle) {
                                    Modifier
                                        .size(tileHeight)
                                        .align(Alignment.Center)
                                        .clip(CircleShape)
                                        .then(
                                            colors.backgroundGradient?.let {
                                                Modifier.background(it)
                                            } ?: Modifier.background(animatedColor)
                                        )
                                }
                                .indication(interaction, LocalIndication.current)
                                .tileCombinedClickable(
                                    onClick = { click?.invoke() ?: Unit },
                                    onLongClick = { longClick?.invoke() },
                                    accessibilityUiState = uiState.accessibilityUiState,
                                    interactionSource =
                                        interactionSource.takeIf { bounceContainer || colorOSStyle },
                                    iconOnly = true,
                                    isDualTarget = isDualTarget,
                                )
                                .tileTestTag(iconOnly),
                        ) {
                            val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                            if (!classicStyle) {
                                 SmallTileContent(
                                    iconProvider = iconProvider,
                                    color = colors.icon,
                                    modifier =
                                        Modifier.align(Alignment.Center).bounceScale {
                                            currentBounceableInfo.bounceable.iconBounceScale
                                        },
                                )
                            } else {
                                CompositionLocalProvider(
                                    LocalColorOSGlassBlur provides tileGlassBlur
                                ) {
                                    ClassicTileContent(
                                        label =
                                            if (colorOSStyle) {
                                                colorOSRoundTileLabel(tile.spec.spec, uiState)
                                            } else {
                                                uiState.label
                                            },
                                        secondaryLabel = uiState.secondaryLabel,
                                        iconProvider = iconProvider,
                                        iconShapeKey = iconShapeKey,
                                        colors = colors,
                                        labelHide = labelHide,
                                        rim = if (colorOSStyle) colorOSTileRim() else null,
                                        glass = colorOSStyle && glassBlur != null,
                                        chevron =
                                            colorOSStyle &&
                                                tile.spec.spec in COLOROS_DROPDOWN_TILES,
                                        pressSource = interactionSource.takeIf { colorOSStyle },
                                        lottieSpec = tile.spec.spec.takeIf { colorOSStyle },
                                        active = uiState.visualState == STATE_ACTIVE,
                                        modifier =
                                            Modifier.align(Alignment.TopCenter)
                                                .bounceScale {
                                                    currentBounceableInfo.bounceable
                                                        .iconBounceScale
                                                }
                                                .then(
                                                    if (colorOSDimmed) {
                                                        Modifier.alpha(ColorOSUnavailableAlpha)
                                                    } else {
                                                        Modifier
                                                    }
                                                ),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    TileContainer(
                        interactionSource =
                            interactionSource.takeIf { bounceContainer || colorOSStyle },
                        onClick = click,
                        onLongClick = longClick,
                        accessibilityUiState = uiState.accessibilityUiState,
                        iconOnly = iconOnly,
                        isDualTarget = isDualTarget,
                        modifier = contentRevealModifier,
                        gradient = colors.backgroundGradient,
                        fillCell = nothingStyle,
                    ) {
                        val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                        if (nothingStyle) {
                            if (isQuadTile) {
                                NothingQuadTileContent(
                                    tileSpec = tile.spec.spec,
                                    label = uiState.label,
                                    secondaryLabel = uiState.secondaryLabel,
                                    iconProvider = iconProvider,
                                    visualState = uiState.visualState,
                                    onClick = { tile.toggleClick() },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (!iconOnly) {
                                NothingPillTileContent(
                                    label = uiState.label,
                                    secondaryLabel = uiState.secondaryLabel,
                                    iconProvider = iconProvider,
                                    visualState = uiState.visualState,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                NothingCircleTileContent(
                                    iconProvider = iconProvider,
                                    visualState = uiState.visualState,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        } else if (iconOnly) {
                            SmallTileContent(
                                iconProvider = iconProvider,
                                color = colors.icon,
                                modifier =
                                    Modifier.align(Alignment.Center).bounceScale {
                                        currentBounceableInfo.bounceable.iconBounceScale
                                    },
                            )
                        } else {
                            val iconShape by TileDefaults.animateIconShapeAsState(uiState, shapeMode)
                            val secondaryClick: (() -> Unit)? =
                                {
                                        hapticsViewModel?.setTileInteractionState(
                                            TileHapticsViewModel.TileInteractionState.CLICKED
                                        )
                                        tile.toggleClick()
                                    }
                                    .takeIf { isDualTarget }
                            // ColorOS names the network in the Wi-Fi pill, with the status under
                            // it. While Wi-Fi is only scanning there is no network to name.
                            val scanningLabel =
                                stringResource(R.string.quick_settings_scanning_for_wifi)
                            val connectedLabel = stringResource(R.string.quick_settings_connected)
                            val networkAsLabel =
                                colorOSStyle &&
                                    tile.spec.spec in COLOROS_WIFI_TILES &&
                                    uiState.visualState == STATE_ACTIVE &&
                                    !uiState.secondaryLabel.isNullOrBlank() &&
                                    uiState.secondaryLabel != scanningLabel
                            LargeTileContent(
                                label =
                                    if (networkAsLabel) uiState.secondaryLabel else uiState.label,
                                secondaryLabel =
                                    if (networkAsLabel) connectedLabel else uiState.secondaryLabel,
                                labelMaxLines = if (networkAsLabel) 2 else 1,
                                iconProvider = iconProvider,
                                sideDrawable = uiState.sideDrawable,
                                colors = colors,
                                iconShape = iconShape,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                                lottieSpec = tile.spec.spec.takeIf { colorOSStyle },
                                active = uiState.visualState == STATE_ACTIVE,
                                accessibilityUiState = uiState.accessibilityUiState,
                                squishiness = squishiness,
                                isVisible = isVisible,
                                textScale = { currentBounceableInfo.bounceable.textBounceScale },
                                modifier =
                                    Modifier.largeTilePadding(
                                        isDualTarget = uiState.handlesSettingsClick
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileExpandable(
    expandable: Expandable,
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    classicStyle: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = expandable,
        controller = rememberExpandableController(color = color, shape = shape),
        modifier =
            modifier
                .motionTestValues { squishiness() exportAs TileMotionTestKeys.Squishness }
                .verticalSquish(squishiness)
                .thenIf(!classicStyle) {
                    Modifier
                        .clip(shape)
                },
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    gradient: Brush? = null,
    fillCell: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .then(if (fillCell) Modifier.fillMaxSize() else Modifier.height(TileHeight).fillMaxWidth())
                .thenIf(gradient != null && (!isDualTarget || iconOnly)) {
                    Modifier.drawBehind { drawRect(brush = gradient!!) }
                }
                .tileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                    interactionSource = interactionSource,
                )
                .tileTestTag(iconOnly),
        content = content,
    )
}

@Composable
fun SmallStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val shapeMode = rememberTileShapeMode()

    val colors =
        TileDefaults.getColorForState(
            uiState = uiState,
            iconOnly = true,
            gradient = rememberQSTileGradient(),
        )

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState, shapeMode).value)
            .then(
                colors.backgroundGradient?.let { Modifier.background(it) }
                    ?: Modifier.background(colors.background)
            )
            .size(TileHeight)
            .clickable(onClick = onClick)
    ) {
        SmallTileContent(
            iconProvider = { getTileIcon(icon = iconProvider) },
            color = colors.icon,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
fun LargeStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val shapeMode = rememberTileShapeMode()

    val colors =
        TileDefaults.getColorForState(
            uiState = uiState,
            iconOnly = false,
            gradient = rememberQSTileGradient(),
        )

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState, shapeMode).value)
            .then(
                colors.backgroundGradient?.let { Modifier.background(it) }
                    ?: Modifier.background(colors.background)
            )
            .height(TileHeight)
            .clickable(onClick = onClick)
            .largeTilePadding()
    ) {
        LargeTileContent(
            label = uiState.label,
            secondaryLabel = "",
            iconProvider = { getTileIcon(icon = iconProvider) },
            sideDrawable = null,
            colors = colors,
            squishiness = { 1f },
        )
    }
}

private fun Context.getTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = false, // Haptics handled separately
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

@Composable
fun rememberTileShapeMode(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readShapeMode(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_TILE_SHAPE, 0,
                UserHandle.USER_CURRENT
            )
        } catch (_: Throwable) {
            0
        }
    }

    var shapeMode by remember { mutableIntStateOf(readShapeMode()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    shapeMode = readShapeMode()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_SHAPE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return shapeMode
}

@Composable
fun rememberTileHaptic(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readHapticEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_TILE_HAPTIC, 1,
                UserHandle.USER_CURRENT
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var hapticEnabled by remember { mutableStateOf(readHapticEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    hapticEnabled = readHapticEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_HAPTIC),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return hapticEnabled
}

@Composable
fun rememberQSTileGradient(): Brush? {
    val enabled = rememberGradientEnabled(Settings.System.QS_TILE_GRADIENT)
    val gradientColors = rememberGradientColors()
    return remember(enabled, gradientColors) {
        if (enabled) gradientColors.diagonal() else null
    }
}

@Composable
fun rememberQSPanelStyle(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readPanelStyleEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_PANEL_STYLE, 0,
                UserHandle.USER_CURRENT
            ) == QS_PANEL_STYLE_CLASSIC
        } catch (_: Throwable) {
            false
        }
    }

    var classicStyleEnabled by remember { mutableStateOf(readPanelStyleEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    classicStyleEnabled = readPanelStyleEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_PANEL_STYLE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return classicStyleEnabled
}

@Composable
fun rememberQSNothingStyle(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readNothingStyleEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_PANEL_STYLE, 0,
                UserHandle.USER_CURRENT
            ) == QS_PANEL_STYLE_NOTHING
        } catch (_: Throwable) {
            false
        }
    }

    var nothingStyleEnabled by remember { mutableStateOf(readNothingStyleEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    nothingStyleEnabled = readNothingStyleEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_PANEL_STYLE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return nothingStyleEnabled
}

/** Sliders the Nothing style can place in its grid. They are layout entries, not tiles. */
enum class NothingSliderKind(val spec: String) {
    Brightness("nothing_brightness"),
    Volume("nothing_volume");

    companion object {
        fun fromSpec(spec: String): NothingSliderKind? = entries.firstOrNull { it.spec == spec }
    }
}

/** A slider at [row], [col] spanning [span] (2 or 4) columns of the Nothing grid. */
data class NothingSliderItem(
    val kind: NothingSliderKind,
    val row: Int,
    val col: Int,
    val span: Int,
)

/**
 * Specs the Nothing style draws as a 2x2 quad tile, the saved position of every tile and the
 * sliders placed in the grid.
 */
data class NothingLayout(
    val quadTiles: Set<TileSpec>,
    val tileLayout: Map<TileSpec, Pair<Int, Int>>,
    val sliders: List<NothingSliderItem> = emptyList(),
) {
    companion object {
        val None = NothingLayout(emptySet(), emptyMap())
    }
}

/** Nothing OS only lets the Bluetooth tile grow to 2x2. */
fun TileSpec.supportsNothingQuad(): Boolean = spec == "bt"

/**
 * Reads the Nothing style quad tiles (Bluetooth by default) and tile layout with a single
 * [ContentObserver] for both settings.
 */
@Composable
fun rememberNothingLayout(): NothingLayout {
    val context = LocalContext.current

    fun readRaw(): Pair<String, String> {
        val resolver = context.contentResolver
        return Pair(
            Settings.Secure.getStringForUser(resolver, QS_TILE_QUAD_SPECS, UserHandle.USER_CURRENT)
                ?: "bt",
            Settings.Secure.getStringForUser(resolver, QS_TILE_LAYOUT, UserHandle.USER_CURRENT)
                ?: "",
        )
    }

    val raw by produceState(initialValue = readRaw()) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = readRaw()
            }
        }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_TILE_QUAD_SPECS), false, observer, UserHandle.USER_ALL
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_TILE_LAYOUT), false, observer, UserHandle.USER_ALL
        )
        try {
            awaitCancellation()
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    val quadTiles = remember(raw.first) {
        if (raw.first.isBlank()) emptySet()
        else
            raw.first
                .split(",")
                .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(TileSpec::create) }
                .filter { it.supportsNothingQuad() }
                .toSet()
    }
    val tileLayout = remember(raw.second) {
        if (raw.second.isBlank()) {
            emptyMap()
        } else {
            raw.second.split(",")
                .mapNotNull { item ->
                    val parts = item.trim().split(":")
                    if (parts.size == 3 && NothingSliderKind.fromSpec(parts[0]) == null) {
                        val row = parts[1].toIntOrNull()
                        val col = parts[2].toIntOrNull()
                        if (row != null && col != null) TileSpec.create(parts[0]) to (row to col)
                        else null
                    } else {
                        null
                    }
                }
                .toMap()
        }
    }
    val sliders = remember(raw.second) { parseNothingSliders(raw.second) }
    return remember(quadTiles, tileLayout, sliders) {
        NothingLayout(quadTiles, tileLayout, sliders)
    }
}

/**
 * Reads the slider entries of the layout string, `nothing_brightness:row:col:span`. The span is 2
 * or 4 columns (4 when missing or invalid) and each slider appears at most once.
 */
private fun parseNothingSliders(raw: String): List<NothingSliderItem> {
    if (raw.isBlank()) return emptyList()
    return raw.split(",")
        .mapNotNull { item ->
            val parts = item.trim().split(":")
            val kind = NothingSliderKind.fromSpec(parts[0])
            val row = parts.getOrNull(1)?.toIntOrNull()
            val col = parts.getOrNull(2)?.toIntOrNull()
            if (kind == null || row == null || col == null || row < 0) {
                null
            } else {
                val span = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it == 2 || it == 4 } ?: 4
                NothingSliderItem(kind, row, col.coerceIn(0, 4 - span), span)
            }
        }
        .distinctBy { it.kind }
}

private const val QS_TILE_QUAD_SPECS = "qs_tile_quad_specs"
private const val QS_TILE_LAYOUT = "qs_tile_layout"

fun saveQSTileLayout(
    resolver: android.content.ContentResolver,
    layout: Map<TileSpec, Pair<Int, Int>>,
    sliders: List<NothingSliderItem>? = null,
) {
    val tiles = layout.entries.map { "${it.key.spec}:${it.value.first}:${it.value.second}" }
    // Without a slider list the slider entries already stored are kept as they are.
    val sliderEntries =
        sliders?.map { "${it.kind.spec}:${it.row}:${it.col}:${it.span}" }
            ?: (Settings.Secure.getStringForUser(resolver, QS_TILE_LAYOUT, UserHandle.USER_CURRENT)
                    ?: "")
                .split(",")
                .map { it.trim() }
                .filter { NothingSliderKind.fromSpec(it.substringBefore(":")) != null }
    putSecureStringIfChanged(resolver, QS_TILE_LAYOUT, (tiles + sliderEntries).joinToString(","))
}

/** Writes a [Settings.Secure] string for the current user only when it differs from the stored one. */
fun putSecureStringIfChanged(resolver: android.content.ContentResolver, key: String, value: String) {
    if (Settings.Secure.getStringForUser(resolver, key, UserHandle.USER_CURRENT) == value) return
    Settings.Secure.putStringForUser(resolver, key, value, UserHandle.USER_CURRENT)
}

@Composable
fun rememberQSColorOSStyle(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readColorOSStyleEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_PANEL_STYLE, 0,
                UserHandle.USER_CURRENT
            ) == QS_PANEL_STYLE_COLOROS
        } catch (_: Throwable) {
            false
        }
    }

    var colorOSStyleEnabled by remember { mutableStateOf(readColorOSStyleEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    colorOSStyleEnabled = readColorOSStyleEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_PANEL_STYLE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return colorOSStyleEnabled
}

/** Largest cell of the Nothing style grid on this screen, or unspecified for no limit. */
@Composable
fun rememberNothingQSMaxCellSize(): Dp {
    val maxCellSize = dimensionResource(R.dimen.qs_nothing_max_cell_size)
    return if (maxCellSize > 0.dp) maxCellSize else Dp.Unspecified
}

/** Width of the four capped columns and their gaps in the Nothing style grid. */
@Composable
fun rememberNothingQSMaxContentWidth(): Dp {
    val maxCellSize = rememberNothingQSMaxCellSize()
    return if (maxCellSize == Dp.Unspecified) Dp.Unspecified else maxCellSize * 4 + 16.dp * 3
}

@Composable
fun rememberQSTileLabelHide(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readLabelHideEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_TILE_LABEL_HIDE, 0,
                UserHandle.USER_CURRENT
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var labelHideEnabled by remember { mutableStateOf(readLabelHideEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    labelHideEnabled = readLabelHideEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_LABEL_HIDE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return labelHideEnabled
}

/** Whether the media card of the ColorOS panel style fills with the album art. */
@Composable
fun rememberQSColorOSMediaArtFill(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readArtFillEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_COLOROS_MEDIA_ART_FILL, 0,
                UserHandle.USER_CURRENT
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var artFillEnabled by remember { mutableStateOf(readArtFillEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    artFillEnabled = readArtFillEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_COLOROS_MEDIA_ART_FILL),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return artFillEnabled
}

@Composable
fun rememberQSTileIconShapeKey(): String {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readValue(): String {
        return try {
            Settings.System.getStringForUser(
                contentResolver, Settings.System.QS_TILE_ICON_SHAPE,
                UserHandle.USER_CURRENT
            ) ?: "circle"
        } catch (_: Throwable) {
            "circle"
        }
    }

    var value by remember { mutableStateOf(readValue()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    value = readValue()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_ICON_SHAPE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return value
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
    val outline: Color,
    val classicLabel: Color,
    val classicSecondaryLabel: Color,
    val backgroundGradient: Brush? = null,
)

@VisibleForTesting
object TileMotionTestKeys {
    val Squishness = MotionTestValueKey<Float>("tile_squishiness")
}

private object TileDefaults {
    /** An active tile uses the active color as background */
    @Composable
    @ReadOnlyComposable
    fun activeTileColors(gradient: Brush?): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
            outline = MaterialTheme.colorScheme.primary,
            classicLabel = MaterialTheme.colorScheme.onSurface,
            classicSecondaryLabel = MaterialTheme.colorScheme.onSurface,
            backgroundGradient = gradient,
        )

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    @ReadOnlyComposable
    fun activeDualTargetTileColors(gradient: Brush?): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onPrimary,
            outline = MaterialTheme.colorScheme.primary,
            classicLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .9f),
            classicSecondaryLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f),
            backgroundGradient = gradient,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
            classicLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .9f),
            classicSecondaryLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f),
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
            classicLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .9f),
            classicSecondaryLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f),
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        return TileColors(
            background = surfaceColor,
            iconBackground = surfaceColor,
            label = onSurfaceVariantColor,
            secondaryLabel = onSurfaceVariantColor,
            icon = onSurfaceVariantColor,
            outline = onSurfaceVariantColor,
            classicLabel = onSurfaceVariantColor,
            classicSecondaryLabel = onSurfaceVariantColor,
        )
    }

    /**
     * ColorOS style: translucent "glass" tiles that let the shade blur show through. On a dark
     * panel an active tile shows a white bubble with a coloured icon; on a light panel the bubble
     * is filled with the colour and the icon is white. Mobile data uses green, auto rotate red,
     * and everything else blue, except do not disturb and dark mode, whose icon is dark ink on the
     * white bubble of a dark panel.
     */
    @Composable
    @ReadOnlyComposable
    fun colorOSColorsForState(uiState: TileUiState, iconOnly: Boolean, spec: String): TileColors {
        val onSurface = MaterialTheme.colorScheme.onSurface
        val darkPanel = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val accent =
            when {
                spec in COLOROS_DATA_TILES -> ColorOSGreen
                spec == "rotation" -> ColorOSRed
                darkPanel && spec in COLOROS_INK_TILES -> ColorOSInk
                else -> ColorOSBlue
            }
        val glass =
            if (darkPanel) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f)
        // The icon circle of a pill that is off is a flat, clearly lighter disc than the pill, as in
        // ColorOS. The pill and the round tiles keep the glass.
        val bubbleOff =
            if (darkPanel) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.06f)
        val bubbleOn = if (darkPanel) ColorOSActiveFill else accent
        val iconOn = if (darkPanel) accent else Color.White
        // The labels of a dark panel are softer than full white, like in ColorOS.
        val primary = if (darkPanel) onSurface.copy(alpha = 0.90f) else onSurface
        val secondary = onSurface.copy(alpha = if (darkPanel) 0.60f else 0.70f)
        val inactive =
            TileColors(
                background = glass,
                iconBackground = bubbleOff,
                label = primary,
                secondaryLabel = secondary,
                icon = onSurface,
                outline = onSurface,
                classicLabel = primary,
                classicSecondaryLabel = secondary,
            )
        return when (uiState.visualState) {
            STATE_ACTIVE ->
                TileColors(
                    background = if (iconOnly) bubbleOn else glass,
                    iconBackground = bubbleOn,
                    label = primary,
                    secondaryLabel = secondary,
                    icon = iconOn,
                    outline = iconOn,
                    classicLabel = primary,
                    classicSecondaryLabel = secondary,
                )
            STATE_INACTIVE -> inactive
            // A round tile that cannot be used keeps the colours of one that is off and is faded
            // as a whole by the caller. The wide tiles keep the greyed out colours, with the icon
            // circle of a pill that is off.
            else ->
                if (iconOnly) inactive
                else unavailableTileColors().copy(iconBackground = bubbleOff)
        }
    }

    /** Monochrome colors of the Nothing style: white when on, a dark grey surface otherwise. */
    @Composable
    @ReadOnlyComposable
    fun nothingColorsForState(uiState: TileUiState, isQuad: Boolean): TileColors {
        val active = uiState.visualState == STATE_ACTIVE && !isQuad
        val background =
            colorResource(if (active) R.color.nothing_qs_active else R.color.nothing_qs_surface)
        val content = if (active) Color.Black else Color.White
        return TileColors(
            background = background,
            iconBackground = background,
            label = content,
            secondaryLabel = content.copy(alpha = 0.7f),
            icon = content,
            outline = content,
            classicLabel = content,
            classicSecondaryLabel = content.copy(alpha = 0.7f),
        )
    }

    @Composable
    @ReadOnlyComposable
    fun getColorForState(
        uiState: TileUiState,
        iconOnly: Boolean,
        gradient: Brush? = null,
    ): TileColors {
        return when (uiState.visualState) {
            STATE_ACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    activeDualTargetTileColors(gradient)
                } else {
                    activeTileColors(gradient)
                }
            }

            STATE_INACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            }

            else -> unavailableTileColors()
        }
    }

    @Composable
    fun iconRadius(uiState: TileUiState): Dp {
        if (LocalColorOSQsStyle.current) return ToggleTargetSize / 2
        return when (uiState.visualState) {
            STATE_ACTIVE -> ActiveIconCornerRadius
            STATE_INACTIVE -> InactiveIconCornerRadius
            else -> InactiveIconCornerRadius
        }
    }

    @Composable
    fun tileRadius(uiState: TileUiState): Dp {
        if (LocalColorOSQsStyle.current) return TileHeight / 2
        return when (uiState.visualState) {
            STATE_ACTIVE -> ActiveTileCornerRadius
            STATE_INACTIVE -> InactiveTileCornerRadius
            else -> InactiveTileCornerRadius
        }
    }

    @Composable
    fun animateIconShapeAsState(uiState: TileUiState, shapeMode: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = iconRadius(uiState),
            label = "QSTileIconCornerRadius",
            shapeMode = shapeMode,
        )
    }

    @Composable
    fun animateTileShapeAsState(uiState: TileUiState, shapeMode: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = tileRadius(uiState),
            label = "QSTileCornerRadius",
            shapeMode = shapeMode,
        )
    }

    @Composable
    fun animateShapeAsState(targetValue: Dp, label: String, shapeMode: Int): State<RoundedCornerShape> {
        val animatedCornerRadius by animateDpAsState(
            targetValue = when (shapeMode) {
                    1 -> InactiveTileCornerRadius /* Circle-ish */
                    2 -> ActiveTileCornerRadius /* Rounded Square */
                    3 -> 0.dp /* Square */
                    4 -> InactiveTileCornerRadius /* Circle */
                    else -> targetValue
                 },
            label = label
        )

        return remember {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadius.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

/**
 * A composable function that returns the [Resources]. It will be recomposed when [Configuration]
 * gets updated.
 */
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
