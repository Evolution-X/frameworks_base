/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.android.systemui.qs.panels.shared.model.TilePlacement
import kotlin.math.roundToInt

/**
 * A grid whose tiles can span rows as well as columns. One row tiles set the height of their row
 * and sit in the middle of it, and a taller tile only stretches the rows it spans when it doesn't
 * fit in them.
 */
@Composable
internal fun TilePlacementGrid(
    placements: List<TilePlacement>,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    keys: (index: Int) -> Any,
    modifier: Modifier = Modifier,
    itemModifier: (index: Int) -> Modifier = { Modifier },
    content: @Composable (index: Int) -> Unit,
) {
    Layout(
        content = {
            placements.indices.forEach { index ->
                key(keys(index)) {
                    val info = CollectionItemInfo(index, 1, 0, 1)
                    Box(itemModifier(index).semantics { collectionItemInfo = info }) {
                        content(index)
                    }
                }
            }
        },
        modifier = modifier.semantics { collectionInfo = CollectionInfo(placements.size, 1) },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapX = columnSpacing.roundToPx()
        val gapY = rowSpacing.roundToPx()
        val cellWidth = (width - gapX * (columns - 1)) / columns.toFloat()
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val cells = placements[index].columns
                measurable.measure(
                    Constraints.fixedWidth((cellWidth * cells + gapX * (cells - 1)).roundToInt())
                )
            }
        val rows = placements.maxOfOrNull { it.row + it.rows } ?: 0
        val heights = IntArray(rows)
        placements.forEachIndexed { index, place ->
            if (place.rows == 1) {
                heights[place.row] = maxOf(heights[place.row], placeables[index].height)
            }
        }
        placements.forEachIndexed { index, place ->
            if (place.rows > 1) {
                val spanned = place.row until place.row + place.rows
                val missing =
                    placeables[index].height -
                        gapY * (place.rows - 1) -
                        spanned.sumOf { heights[it] }
                if (missing > 0) {
                    val empty = spanned.filter { heights[it] == 0 }
                    val growing = empty.ifEmpty { spanned.toList() }
                    growing.forEach { heights[it] += (missing + growing.size - 1) / growing.size }
                }
            }
        }
        val tops = IntArray(rows)
        for (row in 1 until rows) tops[row] = tops[row - 1] + heights[row - 1] + gapY
        val height = if (rows == 0) 0 else tops[rows - 1] + heights[rows - 1]
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val place = placements[index]
                val offset = if (place.rows == 1) (heights[place.row] - placeable.height) / 2 else 0
                placeable.placeRelative(
                    (place.column * (cellWidth + gapX)).roundToInt(),
                    tops[place.row] + offset,
                )
            }
        }
    }
}
