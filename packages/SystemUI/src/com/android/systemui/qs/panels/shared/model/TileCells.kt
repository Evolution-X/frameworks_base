/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

/** The grid cells a tile takes. */
data class TileCells(val columns: Int, val rows: Int)

/** Where a tile sits in the grid. */
data class TilePlacement(val column: Int, val row: Int, val columns: Int, val rows: Int)

/** Tracks the taken cells of a grid and finds the first free spot for a tile. */
private class CellGrid(private val columns: Int) {
    private val taken = ArrayList<BooleanArray>()

    fun place(tile: TileCells, maxRows: Int): TilePlacement? {
        var row = 0
        while (row + tile.rows <= maxRows) {
            for (column in 0..columns - tile.columns) {
                if (isFree(column, row, tile)) {
                    take(column, row, tile)
                    return TilePlacement(column, row, tile.columns, tile.rows)
                }
            }
            row++
        }
        return null
    }

    private fun isFree(column: Int, row: Int, tile: TileCells): Boolean {
        for (y in row until row + tile.rows) {
            val cells = taken.getOrNull(y) ?: return true
            for (x in column until column + tile.columns) {
                if (cells[x]) return false
            }
        }
        return true
    }

    private fun take(column: Int, row: Int, tile: TileCells) {
        while (taken.size < row + tile.rows) taken.add(BooleanArray(columns))
        for (y in row until row + tile.rows) {
            for (x in column until column + tile.columns) taken[y][x] = true
        }
    }
}

/** Places each tile in the first free spot, so smaller tiles fill the gaps next to bigger ones. */
fun packTiles(tiles: List<TileCells>, columns: Int): List<TilePlacement> {
    val grid = CellGrid(columns)
    return tiles.map { checkNotNull(grid.place(it, Int.MAX_VALUE)) }
}

fun List<TilePlacement>.isBeside(index: Int, other: Int): Boolean {
    val a = getOrNull(minOf(index, other)) ?: return false
    val b = getOrNull(maxOf(index, other)) ?: return false
    return a.row == b.row && a.rows == b.rows && a.column + a.columns == b.column
}

/** Splits [tiles] into pages of [rows] rows. The result holds the tile indexes of each page. */
fun paginateTiles(tiles: List<TileCells>, columns: Int, rows: Int): List<List<Int>> {
    if (tiles.isEmpty()) return emptyList()
    val pages = mutableListOf(mutableListOf<Int>())
    var grid = CellGrid(columns)
    tiles.forEachIndexed { index, tile ->
        val fitted = tile.copy(rows = tile.rows.coerceAtMost(rows))
        if (grid.place(fitted, rows) == null) {
            grid = CellGrid(columns)
            grid.place(fitted, rows)
            pages.add(mutableListOf())
        }
        pages.last().add(index)
    }
    return pages
}

/** The cells of the tile [spec], on a grid of [columns] columns. */
fun QsTweaks.cellsOf(
    spec: String,
    theme: QsStyle,
    isLarge: Boolean,
    largeSpan: Int,
    columns: Int,
): TileCells {
    val shape = shapeFrom ?: theme
    if (spec == QsMediaTileSize.TILE_SPEC) return mediaCells(shape, columns)
    if (spec == QsVolumeSize.TILE_SPEC) {
        return levelCells(shape, columns, volumeSize ?: QsVolumeSize.TALL, volumeLook)
    }
    if (spec == QsVolumeSize.BRIGHTNESS_SPEC) {
        return levelCells(shape, columns, brightnessSize ?: QsVolumeSize.TALL, null)
    }
    if (spec == QsVolumeSize.SOUND_SPEC) {
        return when (soundSize) {
            QsVolumeSize.SMALL -> TileCells(1, 1)
            QsVolumeSize.WIDE -> TileCells(columns, 1)
            else -> TileCells(2.coerceAtMost(columns), 1)
        }
    }
    val long = TileCells(largeSpan.coerceAtMost(columns), 1)
    val own = tiles[spec]
    val auto =
        when {
            sizing == QsTileSizing.LONG -> long
            sizing == QsTileSizing.ICON -> TileCells(1, 1)
            !shape.uniformTiles && isLarge -> long
            else -> TileCells(1, 1)
        }
    return TileCells(
        (own?.columns ?: auto.columns).coerceIn(1, columns),
        (own?.rows ?: auto.rows).coerceAtLeast(1),
    )
}

private fun QsTweaks.levelCells(
    shape: QsStyle,
    columns: Int,
    size: QsVolumeSize,
    look: QsVolumeLook?,
): TileCells {
    val short = (tileHeight ?: if (shape == QsStyle.COMPACT) 48 else 64) < 56
    val rows = if (short) 3 else 2
    val half = 2.coerceAtMost(columns)
    if (look == QsVolumeLook.STACK) {
        return TileCells(if (size == QsVolumeSize.WIDE) columns else half, rows)
    }
    return when (size) {
        QsVolumeSize.SMALL -> TileCells(1, 1)
        QsVolumeSize.HALF -> TileCells(half, 1)
        QsVolumeSize.WIDE -> TileCells(columns, 1)
        QsVolumeSize.TALL -> TileCells(1, rows)
        QsVolumeSize.SQUARE -> TileCells(half, rows)
    }
}

private fun QsTweaks.mediaCells(shape: QsStyle, columns: Int): TileCells {
    val short = (tileHeight ?: if (shape == QsStyle.COMPACT) 48 else 64) < 56
    val block = if (columns >= 5) 3 else 2.coerceAtMost(columns)
    val rows = if (short) 3 else 2
    return when (mediaSize ?: QsMediaTileSize.SQUARE) {
        QsMediaTileSize.SMALL -> TileCells(1, 1)
        QsMediaTileSize.WIDE -> TileCells(columns, 1)
        QsMediaTileSize.SQUARE -> TileCells(block, rows)
        QsMediaTileSize.TALL -> TileCells(block, rows + 1)
        QsMediaTileSize.FULL -> TileCells(columns, rows)
    }
}

/**
 * The columns of the Remix grid: the columns tweak wins over the count of the look the tiles take
 * their shape from. The grid and the pager both use it, so they cut the same rows.
 */
fun QsTweaks.remixColumns(theme: QsStyle, base: Int): Int =
    columns ?: (shapeFrom ?: theme).columns(base)

/**
 * The rows of a Remix QS page: everything on one page when the panel scrolls, else the rows tweak,
 * else [base], the rows set in Settings. The pager and the collapsed panel both use it, so the
 * collapsed panel keeps showing the first rows of the first page.
 */
fun QsTweaks.remixRowsPerPage(base: Int): Int =
    if (scroll) ONE_PAGE_ROWS else (rowsPerPage ?: base).coerceAtLeast(1)

private const val ONE_PAGE_ROWS = 1000

/**
 * The tweaks after [spec]'s corner was dragged to [columns] by [rows] cells on a grid of
 * [gridColumns]. The player and the slider tiles keep their named sizes, so the nearest one is picked.
 */
fun QsTweaks.resized(
    spec: String,
    theme: QsStyle,
    columns: Int,
    rows: Int,
    gridColumns: Int,
): QsTweaks {
    val shape = shapeFrom ?: theme
    val short = (tileHeight ?: if (shape == QsStyle.COMPACT) 48 else 64) < 56
    val tall = if (short) 3 else 2
    if (spec == QsMediaTileSize.TILE_SPEC) {
        val size =
            when {
                rows == 1 && columns == 1 -> QsMediaTileSize.SMALL
                rows == 1 -> QsMediaTileSize.WIDE
                columns >= gridColumns -> QsMediaTileSize.FULL
                rows > tall -> QsMediaTileSize.TALL
                else -> QsMediaTileSize.SQUARE
            }
        return copy(mediaSize = size.takeIf { it != QsMediaTileSize.SQUARE })
    }
    if (spec == QsVolumeSize.TILE_SPEC || spec == QsVolumeSize.BRIGHTNESS_SPEC) {
        val size =
            when {
                rows == 1 && columns == 1 -> QsVolumeSize.SMALL
                rows == 1 && columns >= gridColumns -> QsVolumeSize.WIDE
                rows == 1 -> QsVolumeSize.HALF
                columns == 1 -> QsVolumeSize.TALL
                else -> QsVolumeSize.SQUARE
            }.takeIf { it != QsVolumeSize.TALL }
        return if (spec == QsVolumeSize.TILE_SPEC) copy(volumeSize = size)
        else copy(brightnessSize = size)
    }
    if (spec == QsVolumeSize.SOUND_SPEC) {
        val size =
            when {
                columns == 1 -> QsVolumeSize.SMALL
                columns >= gridColumns -> QsVolumeSize.WIDE
                else -> null
            }
        return copy(soundSize = size)
    }
    return withTile(spec) { it.copy(columns = columns, rows = rows.takeIf { r -> r > 1 }) }
}
