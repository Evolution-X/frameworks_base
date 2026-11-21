/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-FileCopyrightText: The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.drawInOverlay
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.mechanics.effects.VerticalTactileSurfaceRevealEffect
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.gradient.brush
import com.android.systemui.gradient.rememberGradient
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.shared.model.LocalQsGlass
import com.android.systemui.qs.panels.shared.model.LocalQsGlassTune
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsOffFill
import com.android.systemui.qs.panels.shared.model.QsOnFill
import com.android.systemui.qs.panels.shared.model.QsPanelStyle
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTileDepth
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
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.RemixBrightnessTile
import com.android.systemui.qs.tiles.RemixSoundTile
import com.android.systemui.qs.tiles.RemixVolumeTile
import com.android.systemui.qs.shared.style.glassPress
import com.android.systemui.qs.shared.style.glassRim
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.impl.ringer.QSTileRingerSlider
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

/** Whether the QS panel is in the Remix style. The QS fragment provides it. */
internal val LocalRemixQsStyle = compositionLocalOf { false }

/**
 * A tile of the Remix QS panel style, ported from ResurrectedRemix. The look of the tile (shape,
 * colors, layout, glow, outline) comes from the [QsStyle] in [LocalQsStyle]; clicks, haptics, the
 * bounce and the reveal effect work as in the other styles.
 */
@Composable
fun ContentScope.RemixTile(
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
    rows: Int = 1,
    height: Dp? = null,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.handlesMainClick

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val tweaks = LocalQsTweaks.current
        val tileTweak = tweaks.tiles[tile.spec.spec]
        val volume =
            when (tile.spec.spec) {
                RemixVolumeTile.TILE_SPEC -> rememberVolumeTile(tweaks)
                RemixBrightnessTile.TILE_SPEC -> rememberBrightnessTile(tweaks)
                else -> null
            }
        val sound =
            if (tile.spec.spec == RemixSoundTile.TILE_SPEC && !iconOnly) rememberSoundMode()
            else null
        // A slider tile is drawn as an off tile, and its fill is the level.
        val paintState =
            if (volume != null || sound != null) uiState.copy(visualState = STATE_INACTIVE)
            else uiState
        val shape = tileShapeStyle()
        val paint = tilePaintStyle()
        val glass = LocalQsGlass.current
        val tune = LocalQsGlassTune.current
        val look = tileLook(shape)
        val active = paintState.visualState == STATE_ACTIVE
        val ownColor = tileTweak?.color?.let { Color(it) }
        val tone = ownColor ?: tweaks.accent?.let { Color(it) }
        val layout =
            tileLayout(shape, tweaks.label, tileTweak?.hideLabel == true || hideLabel, iconOnly, rows)
        val chipFills =
            layout == TileLayout.Under && (shape == QsStyle.CIRCLES || shape == QsStyle.SHAPES)
        val chipShape =
            tweaks.iconBacking.chipShape(uiState.label)
                ?: CircleShape.takeIf {
                    active &&
                        !chipFills &&
                        tweaks.iconBacking == null &&
                        tweaks.onFill == QsOnFill.ICON
                }
        val colors =
            RemixTileDefaults.getColorForState(paintState, iconOnly, paint, tone)
                .let { if (volume != null || sound != null) it.withLevelTrack(paint) else it }
                .withFills(tweaks, paintState.visualState, tone, ownColor != null, chipFills)
                .withBacking(tweaks.iconBacking, active, tone)
                .glassy(glass, active, tune)
        val hapticsViewModel: TileHapticsViewModel? =
            if (rememberTileHaptic()) {
                rememberViewModel(traceName = "TileHapticsViewModel") {
                    tileHapticsViewModelFactory.create(tile)
                }
            } else {
                null
            }

        val tileShape by RemixTileDefaults.animateTileShapeAsState(paintState, look)
        // A picked fill replaces the theme's outline and glow.
        val repainted =
            if (active) tweaks.onFill != null || ownColor != null else tweaks.offFill != null
        val styleModifier =
            when {
                tweaks.glow != null ->
                    Modifier.tweakGlow(active, look.activeRadius, tweaks.glow, tone)
                repainted -> Modifier
                paint == QsStyle.GLASS -> Modifier.glassGlow(active, look.activeRadius, tone)
                paint == QsStyle.NEON -> Modifier.neonGlow(active, look.activeRadius, tone)
                else -> Modifier
            }.then(
                when {
                    tweaks.depth == QsTileDepth.FLAT -> Modifier
                    tweaks.depth == QsTileDepth.RAISED -> Modifier.softRaise(active, tileShape)
                    repainted -> Modifier
                    paint == QsStyle.SOFT -> Modifier.softRaise(active, tileShape)
                    else -> Modifier
                }
            )
        val see = glassAlphas(glass, tune)?.first ?: 1f
        val fillModifier =
            when {
                active && tweaks.onFill == QsOnFill.GRADIENT && !chipFills ->
                    Modifier.remixFill(true, see, tone)
                active && tweaks.onFill == QsOnFill.OUTLINE ->
                    Modifier.styleOutline(true, tileShape, Color.Transparent, 1.5.dp, tone)
                !active && tweaks.offFill == QsOffFill.OUTLINE ->
                    Modifier.styleOutline(false, tileShape, Color.White.copy(alpha = .18f), 1.dp)
                repainted -> Modifier
                paint == QsStyle.REMIX -> Modifier.remixFill(active, see, tone)
                // Liquid Glass draws its own rim.
                paint == QsStyle.GLASS && glass != QsGlass.LIQUID ->
                    Modifier.glassBorder(active, tileShape, tone)
                paint == QsStyle.NEON ->
                    Modifier.styleOutline(
                        active,
                        tileShape,
                        Color.White.copy(alpha = .12f),
                        if (active) 1.5.dp else 1.dp,
                        tone,
                    )
                paint == QsStyle.BLUEPRINT ->
                    Modifier.styleOutline(active, tileShape, Color.White.copy(alpha = .18f), 1.dp, tone)
                paint == QsStyle.SOFT && tweaks.depth != QsTileDepth.FLAT ->
                    Modifier.softInset(active, tileShape)
                else -> Modifier
            }
        val levelModifier =
            if (volume != null) volumeLevelModifier(volume, tweaks, iconOnly, rows) else Modifier
        // Dragging brightness pops the tile out over the faded shade, on the stock bar's backing.
        val mirrored = volume?.mirrored == true
        val animatedColor by
            animateColorAsState(
                if (mirrored) ContainerColors.defaultContainerColor else colors.background,
                label = "QSTileBackgroundColor",
            )
        val isDualTarget = uiState.handlesToggleClick
        // A tile that only shows its icon toggles on tap, whatever its size.
        val tapToggles = iconOnly || layout == TileLayout.Icon
        val interactionSource = remember { MutableInteractionSource() }
        // Bounce the tile's container if it is toggleable and is not a large
        // dual target tile. These don't toggle on main click.
        val bounceContainer = uiState.isToggleable && (tapToggles || !isDualTarget)

        // Evolution X Liquid Glass: the blur region and lit rim of the glass, and its press.
        val glassModifier =
            if (glass == QsGlass.LIQUID) {
                (if (tune.sheen) Modifier.liquidSheen(tileShape) else Modifier)
                    .glassRim(tileShape)
                    .glassPress()
            } else {
                Modifier
            }

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.VerticalPadding.toPx() }

            val animatedCornerRadius by
                animateDpAsState(RemixTileDefaults.tileRadius(uiState, look))

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

        val expandable =
            if (dynamicTargetResolutionEnabled()) tile.expandable
            else remember { Expandable(mutableSetOf()) }
        Tooltip(
            text = uiState.label,
            modifier = modifier,
            enabled = Flags.enableQsTileTooltips(),
        ) { modifier ->
            val squishGaps =
                with(LocalDensity.current) {
                    ((height ?: look.height) - look.height * rows).roundToPx()
                }
            RemixTileExpandable(
                expandable = expandable,
                color = { animatedColor },
                shape = tileShape,
                squishiness = squishiness,
                squishGaps = { squishGaps },
                hapticsViewModel = hapticsViewModel,
                glassModifier = glassModifier,
                modifier =
                    modifier
                        .then(if (mirrored) Modifier.drawInOverlay() else Modifier)
                        .then(surfaceRevealModifier)
                        .then(styleModifier)
                        .borderOnFocus(
                            color = MaterialTheme.colorScheme.secondary,
                            tileShape.topEnd,
                        )
                        .sysuiResTag("tile_expandable")
                        .fillMaxWidth()
                        .bounceable(
                            currentBounceableInfo.bounceable,
                            currentBounceableInfo.previousTile,
                            currentBounceableInfo.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo.bounceEnd,
                            interactionSource = interactionSource,
                        ),
            ) { expandable ->
                // Use main click on long press for small, available dual target tiles.
                // Open settings otherwise.
                val useLongClickToSettings = !(tapToggles && isDualTarget && isClickable)
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

                TileContainer(
                    interactionSource = interactionSource.takeIf { volume == null },
                    onClick = onClick@{
                            if (volume != null) {
                                volume.onTap()
                                hapticsViewModel?.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                                return@onClick
                            }
                            if (!isClickable) return@onClick

                            if (tapToggles && isDualTarget) {
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
                                    currentBounceableInfo.bounceable.animateContentBounce(tapToggles)
                                }
                            }
                            if (uiState.isToggleable && tapToggles) {
                                // And show footer text feedback for icons
                                requestToggleTextFeedback(tile.spec)
                            }
                        },
                    onLongClick = longClick,
                    accessibilityUiState = uiState.accessibilityUiState,
                    iconOnly = tapToggles,
                    isDualTarget = isDualTarget,
                    height = height ?: look.height,
                    modifier = fillModifier.then(levelModifier).then(contentRevealModifier),
                ) {
                    val iconProvider: Context.() -> Icon = {
                        tileTweak?.icon?.let { name ->
                            QsTileIcons[name]?.let { Icon.Resource(it, null) }
                        } ?: getTileIcon(icon = icon)
                    }
                    val iconBounce =
                        Modifier.bounceScale { currentBounceableInfo.bounceable.iconBounceScale }
                    val label = if (look.caps) uiState.label.uppercase() else uiState.label
                    val secondaryLabel =
                        when {
                            !tweaks.secondLine -> ""
                            look.caps -> uiState.secondaryLabel.uppercase()
                            else -> uiState.secondaryLabel
                        }
                    val secondaryClick: (() -> Unit)? =
                        {
                                hapticsViewModel?.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                                tile.toggleClick()
                            }
                            .takeIf { isDualTarget && !tapToggles }
                    if (volume != null) {
                        VolumeTileContent(volume, colors, look, paint, iconOnly, rows, longClick)
                    } else if (sound != null) {
                        SoundModeContent(sound, colors, look)
                    } else when (layout) {
                        TileLayout.Under ->
                            when {
                                chipShape != null ->
                                    BackedUnderTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        shape = chipShape,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                shape == QsStyle.SHAPES && tweaks.iconBacking == null ->
                                    ShapeTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        active = active,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                shape == QsStyle.CIRCLES && tweaks.iconBacking == null ->
                                    CircleTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                                else ->
                                    RemixClassicTileContent(
                                        label = label,
                                        iconProvider = iconProvider,
                                        colors = colors,
                                        iconSize = look.smallIconSize,
                                        iconModifier = iconBounce,
                                    )
                            }
                        TileLayout.Stack ->
                            CardTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                colors = colors,
                                look = look,
                                iconModifier = iconBounce,
                                chipShape = chipShape ?: CircleShape,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                            )
                        TileLayout.Duo ->
                            DuoTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                colors = colors,
                                look = look,
                                accessibilityUiState = uiState.accessibilityUiState,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                                iconModifier = iconBounce,
                            )
                        TileLayout.Icon ->
                            if (chipShape != null) {
                                BackedIcon(
                                    iconProvider = iconProvider,
                                    colors = colors,
                                    shape = chipShape,
                                    chipSize = minOf(look.smallIconSize + 22.dp, look.height - 8.dp),
                                    iconSize = look.smallIconSize,
                                    modifier = Modifier.align(Alignment.Center).then(iconBounce),
                                )
                            } else {
                                SmallTileContent(
                                    iconProvider = iconProvider,
                                    color = colors.icon,
                                    size = { look.smallIconSize },
                                    modifier = Modifier.align(Alignment.Center).then(iconBounce),
                                )
                            }
                        TileLayout.Row -> {
                            val stockIconShape by
                                RemixTileDefaults.animateIconShapeAsState(uiState)
                            val iconShape =
                                if (shape == QsStyle.STOCK) stockIconShape else CircleShape
                            LargeTileContent(
                                label = label,
                                secondaryLabel = secondaryLabel,
                                iconProvider = iconProvider,
                                sideDrawable = uiState.sideDrawable,
                                colors = colors,
                                iconShape = iconShape,
                                iconClip = chipShape ?: iconShape,
                                alwaysBacked = chipShape != null,
                                toggleClick = secondaryClick,
                                toggleInteractionSource = interactionSource,
                                onLongClick = longClick,
                                accessibilityUiState = uiState.accessibilityUiState,
                                squishiness = squishiness,
                                isVisible = isVisible,
                                textScale = { currentBounceableInfo.bounceable.textBounceScale },
                                toggleSize = look.toggleSize,
                                iconSize = look.largeIconSize,
                                labelStyle = look.labelStyle,
                                secondaryLabelStyle = look.secondaryLabelStyle,
                                showChevron = shape == QsStyle.CIRCLES && isDualTarget,
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
private fun RemixTileExpandable(
    expandable: Expandable,
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    modifier: Modifier = Modifier,
    glassModifier: Modifier = Modifier,
    squishGaps: () -> Int = { 0 },
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = expandable,
        controller = rememberExpandableController(color = color, shape = shape),
        modifier =
            modifier
                .clip(shape)
                .motionTestValues { squishiness() exportAs TileMotionTestKeys.Squishness }
                .verticalSquish(squishGaps, squishiness)
                .then(glassModifier),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

/** The colors and corner radii of the Remix tiles. */
internal object RemixTileDefaults {
    @Composable
    @ReadOnlyComposable
    fun activeTileColors(): TileColors =
        RemixColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    @ReadOnlyComposable
    fun activeDualTargetTileColors(): TileColors =
        RemixColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        RemixColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        RemixColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        return RemixColors(
            background = surfaceColor,
            iconBackground = surfaceColor,
            label = onSurfaceVariantColor,
            secondaryLabel = onSurfaceVariantColor,
            icon = onSurfaceVariantColor,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun getColorForState(
        uiState: TileUiState,
        iconOnly: Boolean,
        style: QsStyle = QsStyle.STOCK,
        tone: Color? = null,
    ): TileColors {
        styledTileColors(style, uiState.visualState, iconOnly, tone)?.let {
            return it
        }
        return when (uiState.visualState) {
            STATE_ACTIVE ->
                if (uiState.handlesToggleClick && !iconOnly) activeDualTargetTileColors()
                else activeTileColors()
            STATE_INACTIVE ->
                if (uiState.handlesToggleClick && !iconOnly) inactiveDualTargetTileColors()
                else inactiveTileColors()
            else -> unavailableTileColors()
        }
    }

    @Composable
    fun iconRadius(uiState: TileUiState): Dp =
        when (uiState.visualState) {
            STATE_ACTIVE -> CommonTileDefaults.ActiveIconCornerRadius
            else -> CommonTileDefaults.InactiveIconCornerRadius
        }

    @Composable
    fun tileRadius(uiState: TileUiState, look: TileLook? = null): Dp =
        when (uiState.visualState) {
            STATE_ACTIVE -> look?.activeRadius ?: CommonTileDefaults.ActiveTileCornerRadius
            else -> look?.inactiveRadius ?: CommonTileDefaults.InactiveTileCornerRadius
        }

    @Composable
    fun animateIconShapeAsState(uiState: TileUiState): State<RoundedCornerShape> =
        animateShapeAsState(iconRadius(uiState), "QSTileIconCornerRadius")

    @Composable
    fun animateTileShapeAsState(
        uiState: TileUiState,
        look: TileLook? = null,
    ): State<RoundedCornerShape> =
        animateShapeAsState(tileRadius(uiState, look), "QSTileCornerRadius")

    @Composable
    fun animateShapeAsState(targetValue: Dp, label: String): State<RoundedCornerShape> {
        val animatedCornerRadius by animateDpAsState(targetValue = targetValue, label = label)

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
