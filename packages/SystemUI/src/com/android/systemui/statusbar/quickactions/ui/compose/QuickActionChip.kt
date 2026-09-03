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

package com.android.systemui.statusbar.quickactions.ui.compose

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.viewmodel.rememberChronometerState
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A clickable chip that can show an anchored popup containing relevant system controls. The chip
 * can show an icon that can have its own separate action distinct from its parent chip. Moreover,
 * the chip can show text containing contextual information.
 */
@Composable
fun QuickActionChip(
    isSelected: Boolean,
    chipContent: ChipContent?,
    icons: List<ChipIcon>,
    colors: ChipColors,
    contentDescription: ContentDescription?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cornerRadius: Dp = dimensionResource(id = R.dimen.ongoing_activity_chip_corner_radius),
    isMediaChip: Boolean = false,
    horizontalPadding: PaddingValues =
        PaddingValues(
            start = if (isMediaChip) 6.dp else 4.dp,
            end =
                if (
                    (chipContent is ChipContent.Text || chipContent is ChipContent.Timer) &&
                        icons.isNotEmpty()
                )
                    8.dp
                else (if (isMediaChip) 6.dp else 4.dp),
        ),
) {
    val hoveredState by interactionSource.collectIsHoveredAsState()
    val indication = if (hoveredState) null else LocalIndication.current
    val chipShape = RoundedCornerShape(cornerRadius)
    val chipBackgroundColor =
        colors.chipBackground(isSelected = isSelected, colorScheme = MaterialTheme.colorScheme)

    val chipHeight =
        if (isMediaChip) {
            dimensionResource(R.dimen.ongoing_appops_chip_height) + 2.dp
        } else {
            dimensionResource(R.dimen.ongoing_appops_chip_height)
        }

    // Use a Box with `fillMaxHeight` to create a larger click surface for the chip. The visible
    // height of the chip is determined by the height of the background of the Row below. The
    // `indication` for Clicks is applied in the Row below as well.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .contentDescription(contentDescription)
                .clickable(
                    onClick = onClick,
                    indication = null,
                    interactionSource = interactionSource,
                ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.height(chipHeight)
                    .defaultMinSize(minWidth = 0.dp)
                    .clip(chipShape)
                    .background(chipBackgroundColor)
                    .border(
                        width = dimensionResource(id = R.dimen.ongoing_activity_chip_outline_width),
                        color =
                            colors.chipOutline(
                                isSelected = isSelected,
                                colorScheme = MaterialTheme.colorScheme,
                            ),
                        shape = chipShape,
                    )
                    .indication(interactionSource, indication)
                    .padding(horizontalPadding),
        ) {
            ChipIcons(
                chipIcons = icons,
                colors = colors,
                isSelected = isSelected,
                isMediaChip = isMediaChip,
            )

            when (chipContent) {
                is ChipContent.Text -> {
                    ChipText(
                        text = chipContent.text,
                        colors = colors,
                        isSelected = isSelected,
                        isMediaChip = isMediaChip,
                    )
                }
                is ChipContent.Timer -> {
                    val timerState =
                        rememberChronometerState(
                            chronometer = chipContent.chronometer,
                            timeSource = chipContent.timeSource,
                        )
                    timerState.currentTimeText?.let {
                        ChipText(
                            text = it,
                            colors = colors,
                            isSelected = isSelected,
                            isMediaChip = isMediaChip,
                            textStyle =
                                MaterialTheme.typography.labelLarge.copy(
                                    fontFeatureSettings = "tnum"
                                ),
                        )
                    }
                }
                is ChipContent.IconOnly -> {
                    Icon(
                        icon = chipContent.icon,
                        modifier = Modifier.size(13.3.dp).clickable { onClick() },
                        tint =
                            colors.chipContent(
                                isSelected = isSelected,
                                colorScheme = MaterialTheme.colorScheme,
                            ),
                    )
                }
                null -> {}
            }

            if (isMediaChip) {
                MusicVisualizerBars(
                    isPlaying = isMediaPlaying(icons),
                    color =
                        colors.chipContent(
                            isSelected = isSelected,
                            colorScheme = MaterialTheme.colorScheme,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ChipText(
    text: String,
    colors: ChipColors,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    isMediaChip: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val textMeasurer = rememberTextMeasurer()
    var textOverflow by remember { mutableStateOf(false) }
    val maxTextWidth =
        dimensionResource(id = R.dimen.ongoing_activity_chip_max_text_width) +
            if (isMediaChip) 32.dp else 0.dp

    Text(
        text = text,
        style = textStyle,
        softWrap = false,
        color =
            colors.chipContent(isSelected = isSelected, colorScheme = MaterialTheme.colorScheme),
        modifier =
            modifier
                .widthIn(max = maxTextWidth)
                .layout { measurables, constraints ->
                    val placeable = measurables.measure(constraints)
                    val intrinsicWidth =
                        textMeasurer.measure(text, textStyle, softWrap = false).size.width
                    textOverflow = intrinsicWidth > constraints.maxWidth

                    layout(placeable.width, placeable.height) {
                        if (textOverflow) {
                            placeable.placeWithLayer(0, 0) {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                        } else {
                            placeable.place(0, 0)
                        }
                    }
                }
                .overflowFadeOut(
                    hasOverflow = { textOverflow },
                    fadeLength =
                        dimensionResource(
                            id = R.dimen.ongoing_activity_chip_text_fading_edge_length
                        ),
                ),
    )
}

@Composable
private fun ChipIcons(
    chipIcons: List<ChipIcon>,
    colors: ChipColors,
    isSelected: Boolean,
    isMediaChip: Boolean = false,
) {
    val iconBackgroundColor =
        colors.iconBackground(isSelected = isSelected, colorScheme = MaterialTheme.colorScheme)

    chipIcons.forEachIndexed { index, chipIcon ->
        val shouldUseArtworkStyle = isMediaChip && index == 0
        Icon(
            icon = chipIcon.icon,
            modifier =
                Modifier.size(if (shouldUseArtworkStyle) 18.dp else 20.dp)
                    .thenIf(shouldUseArtworkStyle) {
                        Modifier.clip(RoundedCornerShape(5.dp))
                    }
                    .thenIf(chipIcon.onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = chipIcon.onClick!!)
                    }
                    .thenIf(chipIcon.isHighlighted && iconBackgroundColor != Color.Unspecified) {
                        Modifier.background(color = iconBackgroundColor, shape = CircleShape)
                            .padding(2.dp)
                    },
            tint =
                if (shouldUseArtworkStyle) {
                    Color.Unspecified
                } else {
                    colors.icon(
                        isSelected = isSelected,
                        isHighlighted = chipIcon.isHighlighted,
                        colorScheme = MaterialTheme.colorScheme,
                    )
                },
        )
    }
}

private fun Modifier.overflowFadeOut(hasOverflow: () -> Boolean, fadeLength: Dp): Modifier {
    return drawWithCache {
        val width = size.width
        val start = (width - fadeLength.toPx()).coerceAtLeast(0f)
        val gradient =
            Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = start,
                endX = width,
            )
        onDrawWithContent {
            drawContent()
            if (hasOverflow()) drawRect(brush = gradient, blendMode = BlendMode.DstIn)
        }
    }
}

@Composable
private fun Modifier.contentDescription(description: ContentDescription?): Modifier {
    val resolvedDescription = description?.load() ?: return this
    return this.semantics { contentDescription = resolvedDescription }
}

private fun isMediaPlaying(icons: List<ChipIcon>): Boolean {
    val buttonIcon = icons.firstOrNull()?.icon ?: return false
    val description =
        (buttonIcon.contentDescription as? ContentDescription.Loaded)?.description
            ?.lowercase()
            ?: return false
    return description.contains("pause")
}

@Composable
private fun MusicVisualizerBars(isPlaying: Boolean, color: Color) {
    val levels = remember { mutableStateListOf(0.22f, 0.45f, 0.34f, 0.56f) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(isPlaying) {
        if (!isPlaying) {
            levels[0] = 0.22f
            levels[1] = 0.45f
            levels[2] = 0.34f
            levels[3] = 0.56f
            onDispose {}
        } else {
            var visualizer: Visualizer? = null
            val listener =
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null || waveform.isEmpty()) return
                        val quarter = waveform.size / 4
                        val secondQuarter = quarter * 2
                        val thirdQuarter = quarter * 3
                        val newLevels =
                            floatArrayOf(
                                waveform.windowEnergy(0, quarter),
                                waveform.windowEnergy(quarter, secondQuarter),
                                waveform.windowEnergy(secondQuarter, thirdQuarter),
                                waveform.windowEnergy(thirdQuarter, waveform.size),
                            )

                        mainHandler.post {
                            for (i in newLevels.indices) {
                                levels[i] =
                                    (levels[i] * 0.65f + newLevels[i] * 0.35f).coerceIn(0.1f, 1f)
                            }
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                }

            runCatching {
                    visualizer =
                        Visualizer(0).apply {
                            val captureSizes = Visualizer.getCaptureSizeRange()
                            captureSize = captureSizes[1].coerceAtMost(256)
                            setDataCaptureListener(
                                listener,
                                Visualizer.getMaxCaptureRate() / 2,
                                true,
                                false,
                            )
                            enabled = true
                        }
                }
                .onFailure {
                    levels[0] = 0.24f
                    levels[1] = 0.48f
                    levels[2] = 0.36f
                    levels[3] = 0.52f
                }

            onDispose {
                runCatching { visualizer?.enabled = false }
                runCatching { visualizer?.release() }
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier.padding(start = 2.dp),
    ) {
        repeat(levels.size) { index ->
            val scale = levels[index]
            Box(
                modifier = Modifier.size(width = 2.dp, height = 11.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.size(width = 2.dp, height = 11.dp * scale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                )
            }
        }
    }
}

private fun ByteArray.windowEnergy(start: Int, end: Int): Float {
    if (start >= end || start < 0 || end > size) return 0.1f
    var sum = 0f
    var count = 0
    for (i in start until end) {
        val normalized = abs(this[i].toInt()) / 128f
        sum += normalized * normalized
        count++
    }
    if (count == 0) return 0.1f
    return sqrt(sum / count).coerceIn(0.1f, 1f)
}
