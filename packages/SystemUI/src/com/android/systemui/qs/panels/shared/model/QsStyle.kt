/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

import androidx.compose.runtime.staticCompositionLocalOf

/** The look of the Remix QS panel style, picked in Evolver and stored in Settings.System [SETTING]. */
enum class QsStyle(val value: Int) {
    STOCK(0),
    REMIX(1),
    COMPACT(2),
    GLASS(3),
    CIRCLES(4),
    CLASSIC(5),
    CARDS(6),
    NEON(8),
    BENTO(9),
    SHAPES(10),
    BLUEPRINT(11),
    DUO(12),
    SOFT(13);

    /** Every tile is one column wide and shows its label. */
    val uniformTiles: Boolean
        get() = this == CLASSIC || this == CARDS || this == SHAPES

    fun columns(base: Int): Int =
        when (this) {
            CLASSIC,
            SHAPES -> base + base / 4
            CARDS -> maxOf(2, base * 3 / 4)
            else -> base
        }

    companion object {
        const val SETTING = "qs_remix_style"
        const val LOCKSCREEN_MEDIA_SETTING = "qs_remix_lockscreen_media_style"

        fun of(value: Int): QsStyle = entries.firstOrNull { it.value == value } ?: STOCK
    }
}

val LocalQsStyle = staticCompositionLocalOf { QsStyle.STOCK }

val LocalQsMediaStyle = staticCompositionLocalOf<QsStyle?> { null }

enum class QsMediaTileSize {
    SMALL,
    SQUARE,
    WIDE,
    TALL,
    FULL;

    companion object {
        const val TILE_SPEC = "remixmedia"
    }
}

val LocalQsMediaTileSize = staticCompositionLocalOf { QsMediaTileSize.SQUARE }

/** Liquid Glass material on the tiles; [SOLID] while glass is off for QS or blur is off. */
enum class QsGlass {
    SOLID,
    FROSTED,
    LIQUID,
}

val LocalQsGlass = staticCompositionLocalOf { QsGlass.SOLID }

/**
 * How much of its color the glass shows, 0 to 1, and whether Liquid glass has the RR sheen. Evolution X
 * has no glass tune settings of its own, so these stay at their defaults.
 */
data class QsGlassTune(val tint: Float = .5f, val sheen: Boolean = false)

val LocalQsGlassTune = staticCompositionLocalOf { QsGlassTune() }
