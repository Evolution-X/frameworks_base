/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.text.TextUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.LocalQsStyle
import com.android.systemui.qs.panels.shared.model.LocalQsTweaks
import com.android.systemui.qs.shared.style.liquidGlassRim
import com.android.systemui.qs.panels.shared.model.QsGlass
import com.android.systemui.qs.panels.shared.model.QsGlassTune
import com.android.systemui.qs.panels.shared.model.QsIconBacking
import com.android.systemui.qs.panels.shared.model.QsOffFill
import com.android.systemui.qs.panels.shared.model.QsOnFill
import com.android.systemui.qs.panels.shared.model.QsStyle
import com.android.systemui.qs.panels.shared.model.QsTileFont
import com.android.systemui.qs.panels.shared.model.QsTileLabel
import com.android.systemui.qs.panels.shared.model.QsTweaks
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.res.R
import kotlin.math.roundToInt
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RR's five tile colors; Evolution X's [TileColors] also wants an outline and classic labels. */
internal fun RemixColors(
    background: Color,
    iconBackground: Color,
    label: Color,
    secondaryLabel: Color,
    icon: Color,
): TileColors =
    TileColors(
        background = background,
        iconBackground = iconBackground,
        label = label,
        secondaryLabel = secondaryLabel,
        icon = icon,
        outline = background,
        classicLabel = label,
        classicSecondaryLabel = secondaryLabel,
    )

/** Sizes of one tile in a [QsStyle]. */
internal data class TileLook(
    val height: Dp,
    val activeRadius: Dp,
    val inactiveRadius: Dp,
    val toggleSize: Dp,
    val largeIconSize: Dp,
    val smallIconSize: Dp,
    val labelStyle: TextStyle?,
    val secondaryLabelStyle: TextStyle?,
    val caps: Boolean = false,
)

/** The style a tile takes its shape and layout from. */
@Composable
@ReadOnlyComposable
internal fun tileShapeStyle(): QsStyle = LocalQsTweaks.current.shapeFrom ?: LocalQsStyle.current

/** The style a tile takes its colors from. */
@Composable
@ReadOnlyComposable
internal fun tilePaintStyle(): QsStyle = LocalQsTweaks.current.paintFrom ?: LocalQsStyle.current

/** Draws [content] in the look one tile picked for itself, on the panel's row height. */
@Composable
internal fun PerTileLook(spec: TileSpec, content: @Composable () -> Unit) {
    val tweaks = LocalQsTweaks.current
    val look = tweaks.tiles[spec.spec]?.look
    if (look == null) {
        content()
        return
    }
    val height = tileLook(tileShapeStyle()).height
    CompositionLocalProvider(
        LocalQsTweaks provides
            tweaks.copy(shapeFrom = look, paintFrom = look, tileHeight = height.value.roundToInt())
    ) {
        content()
    }
}

/** The look of [style] with the current changes on top. */
@Composable
@ReadOnlyComposable
internal fun tileLook(style: QsStyle): TileLook {
    val base = themeTileLook(style)
    val tweaks = LocalQsTweaks.current
    val type = MaterialTheme.typography
    val radius = tweaks.radius?.dp
    val icon = tweaks.iconSize?.dp
    return base.copy(
        height = tweaks.tileHeight?.dp ?: base.height,
        activeRadius = radius ?: base.activeRadius,
        inactiveRadius = radius ?: base.inactiveRadius,
        largeIconSize = icon ?: base.largeIconSize,
        smallIconSize = icon ?: base.smallIconSize,
        labelStyle = base.labelStyle.withFont(tweaks.font, type.titleSmall),
        secondaryLabelStyle = base.secondaryLabelStyle.withFont(tweaks.font, type.labelMedium),
        caps = if (tweaks.font == null) style == QsStyle.BLUEPRINT else tweaks.font == QsTileFont.CAPS,
    )
}

private fun TextStyle?.withFont(font: QsTileFont?, plain: TextStyle): TextStyle? =
    when (font) {
        null -> this
        QsTileFont.MONO -> (this ?: plain).copy(fontFamily = MonoFont)
        QsTileFont.SANS,
        QsTileFont.CAPS -> (this ?: plain).copy(fontFamily = plain.fontFamily)
    }

@Composable
@ReadOnlyComposable
internal fun themeTileLook(style: QsStyle): TileLook {
    val type = MaterialTheme.typography
    fun look(h: Int, r: Int, toggle: Int, large: Int, small: Int, label: TextStyle? = null,
        secondary: TextStyle? = null) =
        TileLook(h.dp, r.dp, r.dp, toggle.dp, large.dp, small.dp, label, secondary)
    return when (style) {
        QsStyle.STOCK ->
            TileLook(
                CommonTileDefaults.TileHeight,
                CommonTileDefaults.ActiveTileCornerRadius,
                CommonTileDefaults.InactiveTileCornerRadius,
                CommonTileDefaults.ToggleTargetSize,
                CommonTileDefaults.LargeTileIconSize,
                CommonTileDefaults.SmallTileIconSize,
                null,
                null,
            )
        QsStyle.REMIX -> look(60, 20, 40, 20, 24, type.titleSmall, type.labelMedium)
        QsStyle.COMPACT ->
            look(48, 24, 36, 18, 20, type.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                type.labelSmall)
        QsStyle.GLASS -> look(64, 22, 44, 22, 24, type.titleSmall, type.labelMedium)
        QsStyle.CIRCLES -> look(80, 26, 48, 22, 24, type.titleSmall, type.labelMedium)
        QsStyle.CLASSIC -> look(78, 16, 0, 0, 26)
        QsStyle.CARDS -> look(104, 26, 38, 20, 20)
        QsStyle.NEON -> look(64, 20, 40, 22, 22, type.titleSmall, type.labelMedium)
        QsStyle.BENTO ->
            look(80, 26, 46, 24, 22, type.titleSmall.copy(fontWeight = FontWeight.Bold),
                type.labelMedium)
        QsStyle.SHAPES -> look(88, 20, 0, 0, 24)
        QsStyle.BLUEPRINT -> look(56, 10, 36, 20, 20, BlueprintLabel, BlueprintSecondary)
        // The toggle is Duo's whole left segment, as tall as the tile.
        QsStyle.DUO -> look(60, 20, 60, 22, 22, type.titleSmall, type.labelMedium)
        QsStyle.SOFT -> look(64, 22, 40, 22, 22, type.titleSmall, type.labelMedium)
    }
}

private val MonoFont =
    FontFamily(Typeface(android.graphics.Typeface.getSystemDefaultTypeface("monospace")))

private val BlueprintLabel =
    TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = .8.sp,
    )
private val BlueprintSecondary = BlueprintLabel.copy(fontWeight = FontWeight.Normal, fontSize = 10.5.sp)

/** Soft: tiles share the panel's color and stand out by their shadows. */
internal val SoftSurface = Color(0xFF1C1716)
private val SoftDark = Color(0xFF0B0908)
private val SoftLight = Color(0xFF2E2725)
private val DuoSegment = Color.White.copy(alpha = .07f)

private val RemixOff = Color(0xFF1E2024)
private val RemixOffChip = Color(0xFF2B2E33)
private val RemixOffText = Color(0xFFE9EAEC)
private val RemixOffMuted = Color(0xFF9DA1A7)

/** A dark version of a light color, or white on a dark one, to draw on top of it. */
fun Color.inkOn(): Color =
    if (luminance() > .3f) lerpColor(this, Color.Black, .68f) else Color.White

/**
 * Tile colors for [style], or null to use the stock ones. [tone] replaces the accent, for a tile
 * or a panel with its own color.
 */
@Composable
@ReadOnlyComposable
internal fun styledTileColors(
    style: QsStyle,
    visualState: Int,
    iconOnly: Boolean,
    tone: Color? = null,
): TileColors? {
    if (style == QsStyle.STOCK && tone == null) return null
    val theme = MaterialTheme.colorScheme
    val scheme = if (tone == null) theme else theme.copy(primary = tone, onPrimary = tone.inkOn())
    val surface = LocalAndroidColorScheme.current.surfaceEffect1
    val active = visualState == STATE_ACTIVE
    val vivid = tone ?: colorResource(android.R.color.system_primary_dark)
    val vividInk = tone?.inkOn() ?: colorResource(android.R.color.system_on_primary_dark)
    val colors =
        when (style) {
            QsStyle.REMIX ->
                if (active) {
                    RemixColors(
                        background = tone ?: colorResource(android.R.color.system_accent1_600),
                        iconBackground = Color.White.copy(alpha = .22f),
                        label = Color.White,
                        secondaryLabel = Color.White.copy(alpha = .8f),
                        icon = Color.White,
                    )
                } else {
                    RemixColors(RemixOff, RemixOffChip, RemixOffText, RemixOffMuted, RemixOffText)
                }
            QsStyle.GLASS ->
                if (active) {
                    RemixColors(
                        background = scheme.primary.copy(alpha = .2f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.primary,
                        icon = scheme.primary,
                    )
                } else {
                    RemixColors(
                        background = Color.White.copy(alpha = .035f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = scheme.onSurface,
                    )
                }
            QsStyle.CLASSIC ->
                RemixColors(
                    background = Color.Transparent,
                    iconBackground = Color.Transparent,
                    label = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    secondaryLabel = scheme.onSurfaceVariant,
                    icon = if (active) scheme.primary else scheme.onSurface.copy(alpha = .55f),
                )
            QsStyle.CIRCLES ->
                if (iconOnly) {
                    RemixColors(
                        background = Color.Transparent,
                        iconBackground = if (active) scheme.primary else surface,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = if (active) scheme.onPrimary else scheme.onSurface,
                    )
                } else {
                    filled(active, surface, tone)
                }
            QsStyle.NEON ->
                if (active) {
                    RemixColors(
                        background = vivid.copy(alpha = .1f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = vivid,
                        icon = vivid,
                    )
                } else {
                    RemixColors(
                        background = Color.White.copy(alpha = .03f),
                        iconBackground = Color.Transparent,
                        label = scheme.onSurface,
                        secondaryLabel = scheme.onSurfaceVariant,
                        icon = scheme.onSurface.copy(alpha = .7f),
                    )
                }
            QsStyle.BENTO ->
                if (active) {
                    RemixColors(vivid, vividInk.copy(alpha = .14f), vividInk, vividInk.copy(alpha = .8f),
                        vividInk)
                } else {
                    RemixColors(surface, Color.White.copy(alpha = .08f), scheme.onSurface,
                        scheme.onSurfaceVariant, scheme.onSurface)
                }
            QsStyle.SHAPES ->
                RemixColors(
                    background = Color.Transparent,
                    iconBackground = if (active) vivid else surface,
                    label = scheme.onSurface,
                    secondaryLabel = scheme.onSurfaceVariant,
                    icon = if (active) vividInk else scheme.onSurface,
                )
            QsStyle.BLUEPRINT ->
                RemixColors(
                    background = if (active) vivid.copy(alpha = .08f) else Color.Transparent,
                    iconBackground = Color.Transparent,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vivid else scheme.onSurface.copy(alpha = .7f),
                )
            QsStyle.DUO -> {
                val segment = if (active) vivid else Color.White.copy(alpha = .1f)
                RemixColors(
                    // A large tile is two segments over nothing; a small one is one segment.
                    background = if (iconOnly) segment else Color.Transparent,
                    iconBackground = if (iconOnly) Color.Transparent else segment,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vividInk else scheme.onSurface,
                )
            }
            QsStyle.SOFT ->
                RemixColors(
                    background = SoftSurface,
                    iconBackground = Color.Transparent,
                    label = scheme.onSurface,
                    secondaryLabel = if (active) vivid else scheme.onSurfaceVariant,
                    icon = if (active) vivid else scheme.onSurface.copy(alpha = .75f),
                )
            // Compact and Cards: stock colors, but dual target tiles fill too.
            else -> filled(active, surface, tone)
        }
    return when (visualState) {
        STATE_ACTIVE,
        STATE_INACTIVE -> colors
        else ->
            colors.copy(
                background =
                    if (colors.background.alpha == 0f) colors.background
                    else colors.background.copy(alpha = colors.background.alpha * .5f),
                label = colors.label.copy(alpha = .38f),
                secondaryLabel = colors.secondaryLabel.copy(alpha = .38f),
                icon = colors.icon.copy(alpha = .38f),
            )
    }
}

@Composable
@ReadOnlyComposable
private fun filled(active: Boolean, surface: Color, tone: Color?): TileColors {
    val scheme = MaterialTheme.colorScheme
    return if (active) {
        val fill = tone ?: scheme.primary
        val ink = tone?.inkOn() ?: scheme.onPrimary
        RemixColors(
            background = fill,
            iconBackground = ink.copy(alpha = .14f),
            label = ink,
            secondaryLabel = ink.copy(alpha = .8f),
            icon = ink,
        )
    } else {
        RemixColors(
            background = surface,
            iconBackground = Color.White.copy(alpha = .1f),
            label = scheme.onSurface,
            secondaryLabel = scheme.onSurfaceVariant,
            icon = scheme.onSurface,
        )
    }
}

/**
 * The fills picked for on and off tiles. A tile with its own [ownColor] fills with it. On Circles
 * and Shapes, where the label sits under a chip ([chipFills]), the chip takes the fill instead.
 */
@Composable
@ReadOnlyComposable
internal fun TileColors.withFills(
    tweaks: QsTweaks,
    visualState: Int,
    tone: Color?,
    ownColor: Boolean,
    chipFills: Boolean,
): TileColors {
    val scheme = MaterialTheme.colorScheme
    val surface = LocalAndroidColorScheme.current.surfaceEffect1
    val vivid = tone ?: colorResource(android.R.color.system_primary_dark)
    val ink = tone?.inkOn() ?: colorResource(android.R.color.system_on_primary_dark)
    return when (visualState) {
        STATE_ACTIVE -> {
            fun fill(color: Color, on: Color, chip: Color) =
                if (chipFills) {
                    RemixColors(Color.Transparent, color, scheme.onSurface, scheme.onSurfaceVariant, on)
                } else {
                    RemixColors(color, chip, on, on.copy(alpha = .8f), on)
                }
            when (tweaks.onFill ?: if (ownColor) QsOnFill.SOLID else null) {
                QsOnFill.SOLID -> fill(vivid, ink, ink.copy(alpha = .14f))
                QsOnFill.GRADIENT ->
                    fill(
                        if (chipFills) vivid else Color.Transparent,
                        if (chipFills) ink else Color.White,
                        Color.White.copy(alpha = .22f),
                    )
                QsOnFill.TINT ->
                    RemixColors(
                        vivid.copy(alpha = .18f),
                        Color.Transparent,
                        scheme.onSurface,
                        vivid,
                        vivid,
                    )
                QsOnFill.OUTLINE ->
                    RemixColors(Color.Transparent, Color.Transparent, scheme.onSurface, vivid, vivid)
                QsOnFill.ICON ->
                    RemixColors(
                        surface,
                        vivid,
                        scheme.onSurface,
                        scheme.onSurfaceVariant,
                        if (tweaks.iconBacking == QsIconBacking.NONE) vivid else ink,
                    )
                null -> this
            }
        }
        STATE_INACTIVE ->
            when (tweaks.offFill) {
                QsOffFill.SURFACE -> copy(background = surface)
                QsOffFill.CLEAR,
                QsOffFill.OUTLINE -> copy(background = Color.Transparent)
                QsOffFill.DARK -> copy(background = TweakOffDark)
                null -> this
            }
        else -> this
    }
}

private val TweakOffDark = Color(0xFF141012)

/** The icon backing picked in the customizer, and its fill. */
@Composable
@ReadOnlyComposable
internal fun TileColors.withBacking(backing: QsIconBacking?, active: Boolean, tone: Color?): TileColors =
    when {
        backing == null -> this
        backing == QsIconBacking.NONE -> copy(iconBackground = Color.Transparent)
        iconBackground.alpha > 0f -> this
        else ->
            copy(
                iconBackground =
                    if (active) {
                        (tone ?: colorResource(android.R.color.system_primary_dark)).copy(alpha = .24f)
                    } else {
                        Color.White.copy(alpha = .08f)
                    }
            )
    }

/** The clip for an icon backing, or null to leave it to the theme. */
internal fun QsIconBacking?.chipShape(label: String): Shape? =
    when (this) {
        QsIconBacking.CIRCLE -> CircleShape
        QsIconBacking.ROUNDED -> RoundedCornerShape(30)
        QsIconBacking.BLOB -> {
            val (lobes, depth) = ShapeLobes[Math.floorMod(label.hashCode(), ShapeLobes.size)]
            BlobShape(lobes, depth)
        }
        else -> null
    }

/** Icons a tile can take instead of its own, by the name saved in its tweak. */
val QsTileIcons: Map<String, Int> =
    linkedMapOf(
        "star" to R.drawable.ic_star,
        "sparkle" to R.drawable.ic_star_shine,
        "note" to R.drawable.ic_music_note,
        "coffee" to R.drawable.ic_qs_caffeine,
        "person" to R.drawable.ic_person,
        "gear" to R.drawable.ic_settings,
        "camera" to R.drawable.ic_camera,
        "alarm" to R.drawable.ic_alarm,
        "sun" to R.drawable.ic_brightness_full,
        "pin" to R.drawable.ic_location,
        "sync" to R.drawable.ic_qs_sync,
        "notes" to R.drawable.ic_qs_notes,
    )

/** How a tile lays out its icon and labels. */
internal enum class TileLayout {
    Icon,
    Under,
    Row,
    Stack,
    Duo,
}

internal fun tileLayout(
    shape: QsStyle,
    label: QsTileLabel?,
    hideLabel: Boolean,
    small: Boolean,
    rows: Int,
): TileLayout =
    when {
        hideLabel || label == QsTileLabel.NONE -> TileLayout.Icon
        label == QsTileLabel.UNDER -> TileLayout.Under
        label == QsTileLabel.INSIDE ->
            when {
                small -> TileLayout.Icon
                rows >= 2 -> TileLayout.Stack
                else -> TileLayout.Row
            }
        shape == QsStyle.CARDS -> TileLayout.Stack
        shape == QsStyle.CLASSIC || shape == QsStyle.SHAPES -> TileLayout.Under
        small ->
            if (shape == QsStyle.CIRCLES || shape == QsStyle.BENTO) TileLayout.Under
            else TileLayout.Icon
        rows >= 2 -> TileLayout.Stack
        shape == QsStyle.DUO -> TileLayout.Duo
        else -> TileLayout.Row
    }

/** A glow of [strength] percent around the tile while on. */
@Composable
internal fun Modifier.tweakGlow(active: Boolean, radius: Dp, strength: Int, tone: Color?): Modifier {
    val glow = animateFloatAsState(if (active) 1f else 0f, label = "QSTileTweakGlow")
    val glowColor = tone ?: colorResource(android.R.color.system_primary_dark)
    if (strength <= 0) return this
    return glowBehind(glow, glowColor, .7f * strength / 100f, (strength * .34f).coerceAtLeast(1f).dp, radius)
}

/** A blurred round rect behind the tile, [alpha] at full [glow]; the paint is built once. */
private fun Modifier.glowBehind(
    glow: State<Float>,
    color: Color,
    alpha: Float,
    blur: Dp,
    radius: Dp,
): Modifier = drawWithCache {
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
    val r = radius.toPx()
    onDrawBehind {
        val strength = glow.value
        if (strength > 0f) {
            paint.color = color.copy(alpha = alpha * strength).toArgb()
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            }
        }
    }
}

/** An icon with a backing of any shape. */
@Composable
internal fun BackedIcon(
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    shape: Shape,
    chipSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val chip by animateColorAsState(colors.iconBackground, label = "QSTileBackedIconColor")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(chipSize).clip(shape).drawBehind { drawRect(chip) },
    ) {
        SmallTileContent(iconProvider = iconProvider, color = colors.icon, size = { iconSize })
    }
}

/** An icon with its backing, its label under it. */
@Composable
internal fun BackedUnderTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    shape: Shape,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize(),
    ) {
        BackedIcon(iconProvider, colors, shape, iconSize + 22.dp, iconSize, iconModifier)
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** Glass: a soft accent glow around the tile while on. */
@Composable
internal fun Modifier.glassGlow(active: Boolean, radius: Dp, tone: Color? = null): Modifier {
    val glow = animateFloatAsState(if (active) 1f else 0f, label = "QSTileGlassGlow")
    val glowColor = tone ?: colorResource(android.R.color.system_accent1_500)
    return glowBehind(glow, glowColor, .45f, 14.dp, radius)
}

/** Glass: the tile outline, accent while on. */
@Composable
internal fun Modifier.glassBorder(active: Boolean, shape: Shape, tone: Color? = null): Modifier {
    val color by
        animateColorAsState(
            if (active) (tone ?: MaterialTheme.colorScheme.primary).copy(alpha = .95f)
            else Color.White.copy(alpha = .13f),
            label = "QSTileGlassBorder",
        )
    return border(1.5.dp, color, shape)
}

/** Liquid Glass: lets the blurred shade show through the tile. */
internal fun TileColors.glassy(glass: QsGlass, active: Boolean, tune: QsGlassTune): TileColors {
    val (on, off) = glassAlphas(glass, tune) ?: return this
    val alpha = if (active) on else off
    return copy(
        background = background.copy(alpha = background.alpha * alpha),
        iconBackground = iconBackground.copy(alpha = iconBackground.alpha * alpha),
    )
}

internal fun glassAlphas(glass: QsGlass, tune: QsGlassTune): Pair<Float, Float>? =
    when (glass) {
        QsGlass.SOLID -> null
        QsGlass.FROSTED -> lerp(.86f, .97f, tune.tint) to lerp(.55f, .85f, tune.tint)
        QsGlass.LIQUID -> lerp(.72f, .94f, tune.tint) to lerp(.24f, .7f, tune.tint)
    }

/**
 * Liquid Glass: Evolution X's lit glass rim, over RR's sheen with [sheen].
 */
internal fun Modifier.liquidRim(shape: Shape, sheen: Boolean = false): Modifier =
    (if (sheen) liquidSheen(shape) else this).liquidGlassRim(shape)

/** RR's glass: a soft sheen from the top left, where light catches it, under what's on it. */
internal fun Modifier.liquidSheen(shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val sheen =
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = .14f), Color.Transparent),
            center = Offset(size.width * .2f, 0f),
            radius = maxOf(size.width, size.height) * .8f,
        )
    onDrawWithContent {
        drawOutline(outline, sheen)
        drawContent()
    }
}

/** Remix: on tiles fill with an accent gradient. */
@Composable
internal fun Modifier.remixFill(active: Boolean, opacity: Float = 1f, tone: Color? = null): Modifier {
    val fill by animateFloatAsState(if (active) 1f else 0f, label = "QSTileRemixFill")
    val start = tone ?: colorResource(android.R.color.system_accent1_500)
    val end =
        tone?.let { lerpColor(it, Color.Black, .35f) }
            ?: colorResource(android.R.color.system_accent1_700)
    return drawBehind {
        if (fill > 0f) {
            drawRect(
                Brush.linearGradient(listOf(start, end), Offset.Zero, Offset(size.width, size.height)),
                alpha = fill * opacity,
            )
        }
    }
}

@Composable
private fun StyledLabel(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val animated by animateColorAsState(color, label = "QSTileStyledLabelColor")
    val font = LocalQsTweaks.current.font
    BasicText(
        text = if (font == QsTileFont.CAPS) text.uppercase() else text,
        style = if (font == QsTileFont.MONO) style.copy(fontFamily = MonoFont) else style,
        color = { animated },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** Classic: a bare icon over its label. */
@Composable
internal fun RemixClassicTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().padding(horizontal = 2.dp),
    ) {
        SmallTileContent(
            iconProvider = iconProvider,
            color = colors.icon,
            size = { iconSize },
            modifier = iconModifier,
        )
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** Circles: a round toggle with its label under it. */
@Composable
internal fun CircleTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val circle by animateColorAsState(colors.iconBackground, label = "QSTileCircleColor")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = iconModifier.size(56.dp).clip(CircleShape).drawBehind { drawRect(circle) },
        ) {
            SmallTileContent(iconProvider = iconProvider, color = colors.icon, size = { iconSize })
        }
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** Cards: an icon chip on top, the label and state at the bottom. */
@Composable
internal fun CardTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    look: TileLook,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    chipShape: Shape = CircleShape,
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val chip by animateColorAsState(colors.iconBackground, label = "QSTileCardChipColor")
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxSize().padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                iconModifier
                    .size(look.toggleSize)
                    .clip(chipShape)
                    .drawBehind { drawRect(chip) }
                    .then(
                        if (toggleClick == null) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                onClick = toggleClick,
                                onLongClick = onLongClick,
                                hapticFeedbackEnabled = false,
                            )
                        }
                    ),
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { look.largeIconSize },
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            StyledLabel(
                text = label,
                color = colors.label,
                style = MaterialTheme.typography.titleSmall,
            )
            if (!TextUtils.isEmpty(secondaryLabel)) {
                StyledLabel(
                    text = secondaryLabel ?: "",
                    color = colors.secondaryLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Neon: a tight accent glow around the tile while on. */
@Composable
internal fun Modifier.neonGlow(active: Boolean, radius: Dp, tone: Color? = null): Modifier {
    val glow = animateFloatAsState(if (active) 1f else 0f, label = "QSTileNeonGlow")
    val glowColor = tone ?: colorResource(android.R.color.system_primary_dark)
    return glowBehind(glow, glowColor, .55f, 10.dp, radius)
}

/** Neon and Blueprint: an outline, accent while on. */
@Composable
internal fun Modifier.styleOutline(
    active: Boolean,
    shape: Shape,
    off: Color,
    width: Dp,
    tone: Color? = null,
): Modifier {
    val color by
        animateColorAsState(
            if (active) tone ?: colorResource(android.R.color.system_primary_dark) else off,
            label = "QSTileStyleOutline",
        )
    return border(width, color, shape)
}

/** Soft: the tile casts a dark shadow down and a light one up while raised (off). */
@Composable
internal fun Modifier.softRaise(pressed: Boolean, shape: Shape): Modifier {
    val raise by animateFloatAsState(if (pressed) 0f else 1f, label = "QSTileSoftRaise")
    return drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            .asAndroidPath()
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(7.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
        val offset = 4.dp.toPx()
        onDrawBehind {
            if (raise <= 0f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.clipOutPath(path)
                paint.color = SoftDark.copy(alpha = .9f * raise).toArgb()
                native.save()
                native.translate(offset, offset)
                native.drawPath(path, paint)
                native.restore()
                paint.color = SoftLight.copy(alpha = raise).toArgb()
                native.save()
                native.translate(-offset * .8f, -offset * .8f)
                native.drawPath(path, paint)
                native.restore()
                native.restore()
            }
        }
    }
}

/** Soft: shadows inside the tile's edges while pressed (on). Draws under the content. */
@Composable
internal fun Modifier.softInset(pressed: Boolean, shape: Shape): Modifier {
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "QSTileSoftInset")
    return drawWithCache {
        val tile = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            .asAndroidPath()
        // Everything around the tile; blurred and shifted, its edge shades the inside.
        val frame =
            android.graphics.Path().apply {
                val grow = 24.dp.toPx()
                addRect(-grow, -grow, size.width + grow, size.height + grow,
                    android.graphics.Path.Direction.CW)
                op(tile, android.graphics.Path.Op.DIFFERENCE)
            }
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(6.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
        val offset = 4.dp.toPx()
        onDrawWithContent {
            if (press > 0f) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.clipPath(tile)
                    paint.color = SoftDark.copy(alpha = press).toArgb()
                    native.save()
                    native.translate(offset, offset)
                    native.drawPath(frame, paint)
                    native.restore()
                    paint.color = SoftLight.copy(alpha = .8f * press).toArgb()
                    native.translate(-offset * .8f, -offset * .8f)
                    native.drawPath(frame, paint)
                    native.restore()
                }
            }
            drawContent()
        }
    }
}

/** Lobes and depth of the shape an on tile takes in Shapes, picked per tile. */
private val ShapeLobes = listOf(8 to .07f, 4 to .13f, 6 to .09f, 12 to .045f, 5 to .1f)

/** A circle whose edge bulges into [lobes] soft lobes, [depth] of its radius deep. */
internal fun blobPath(width: Float, height: Float, lobes: Int, depth: Float, path: Path) {
    path.reset()
    val cx = width / 2
    val cy = height / 2
    val base = minOf(cx, cy) / (1 + depth)
    val steps = 96
    for (i in 0 until steps) {
        val angle = i * 2 * PI / steps
        val r = base * (1 + depth * cos(lobes * angle)).toFloat()
        val x = cx + r * sin(angle).toFloat()
        val y = cy - r * cos(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** [blobPath] as a shape, for clipping. */
internal class BlobShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Generic(Path().also { blobPath(size.width, size.height, lobes, depth, it) })
}

/** Shapes: a circle that grows lobes while on, with its label under it. */
@Composable
internal fun ShapeTileContent(
    label: String,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    active: Boolean,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val (lobes, depth) = ShapeLobes[Math.floorMod(label.hashCode(), ShapeLobes.size)]
    val morph by animateFloatAsState(if (active) 1f else 0f, label = "QSTileShapeMorph")
    val fill by animateColorAsState(colors.iconBackground, label = "QSTileShapeColor")
    val path = remember { Path() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                iconModifier.size(58.dp).drawBehind {
                    blobPath(size.width, size.height, lobes, depth * morph, path)
                    drawPath(path, fill)
                },
        ) {
            SmallTileContent(iconProvider = iconProvider, color = colors.icon, size = { iconSize })
        }
        StyledLabel(
            text = label,
            color = colors.label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/**
 * Duo: a large tile split in two, the icon segment on the left (the toggle on dual target tiles)
 * and the labels on the right.
 */
@Composable
internal fun DuoTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    look: TileLook,
    accessibilityUiState: AccessibilityUiState?,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val segment by animateColorAsState(colors.iconBackground, label = "QSTileDuoSegment")
    val r = look.activeRadius
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxHeight()
                    .width(look.toggleSize)
                    .clip(RoundedCornerShape(r, 6.dp, 6.dp, r))
                    .drawBehind { drawRect(segment) }
                    .then(
                        if (toggleClick == null) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                    onClick = toggleClick,
                                    onLongClick = onLongClick,
                                    hapticFeedbackEnabled = false,
                                )
                                .semantics {
                                    accessibilityUiState?.let {
                                        contentDescription = it.contentDescription
                                        stateDescription = it.stateDescription
                                        it.toggleableState?.let { state -> toggleableState = state }
                                    }
                                    role = Role.Switch
                                }
                        }
                    ),
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { look.largeIconSize },
                modifier = iconModifier,
            )
        }
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            labelStyle = look.labelStyle,
            secondaryLabelStyle = look.secondaryLabelStyle,
            modifier =
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(6.dp, r, r, 6.dp))
                    .background(DuoSegment)
                    .padding(horizontal = 12.dp),
        )
    }
}
