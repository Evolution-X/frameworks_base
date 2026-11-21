/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

import android.util.Base64
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class QsTileSizing {
    LONG,
    ICON,
}

enum class QsTileLabel {
    INSIDE,
    UNDER,
    NONE,
}

enum class QsIconBacking {
    NONE,
    CIRCLE,
    ROUNDED,
    BLOB,
}

enum class QsTileFont {
    SANS,
    MONO,
    CAPS,
}

enum class QsOnFill {
    SOLID,
    GRADIENT,
    TINT,
    OUTLINE,
    ICON,
}

enum class QsOffFill {
    SURFACE,
    CLEAR,
    DARK,
    OUTLINE,
}

enum class QsTileDepth {
    FLAT,
    RAISED,
}

enum class QsMediaPlace {
    GRID,
    ABOVE,
    BELOW,
}

enum class QsSliderPlace {
    TOP,
    UNDER,
    OFF,
}

enum class QsMediaArt {
    ROUNDED,
    CIRCLE,
    BLOB,
    COVER,
    NONE,
}

enum class QsMediaBar {
    LINE,
    GLOW,
    BLOCKS,
    WAVE,
    NONE,
}

enum class QsMediaButtons {
    TRIO,
    PLAY,
}

enum class QsVolumeSize {
    SMALL,
    HALF,
    WIDE,
    TALL,
    SQUARE;

    companion object {
        const val TILE_SPEC = "rrvolume"
        const val BRIGHTNESS_SPEC = "rrbrightness"
        const val SOUND_SPEC = "rrsound"
    }
}

enum class QsVolumeLook {
    SLIDER,
    FILL,
    BLOCKS,
    STACK,
    LIQUID,
}

enum class QsSliderLayout {
    PAIR,
    SPLIT,
    RAILS,
    CORNER,
}

enum class QsSliderPercent {
    ALWAYS,
    DRAG,
    OFF,
}

fun levelLookFor(own: QsVolumeLook?, theme: QsStyle, liquidGlass: Boolean): QsVolumeLook =
    own
        ?: when {
            liquidGlass -> QsVolumeLook.LIQUID
            theme == QsStyle.CLASSIC || theme == QsStyle.SOFT -> QsVolumeLook.SLIDER
            theme == QsStyle.BLUEPRINT -> QsVolumeLook.BLOCKS
            else -> QsVolumeLook.FILL
        }

enum class QsVolumeSound {
    MEDIA,
    RING,
    ALARM,
    CALL,
}

enum class QsVolumeShow {
    ALL,
    PERCENT,
    ICON,
}

enum class QsVolumeTap {
    MUTE,
    PANEL,
}

/** Changes to one tile, made by tapping it in QS edit mode. */
data class QsTileTweak(
    val columns: Int? = null,
    val rows: Int? = null,
    val color: Int? = null,
    val icon: String? = null,
    val hideLabel: Boolean = false,
    /** The theme this one tile is drawn in, on the panel's grid. */
    val look: QsStyle? = null,
) {
    val isEmpty: Boolean
        get() = this == QsTileTweak()
}

/** Changes on top of a [QsStyle]. A null value keeps what the theme does. */
data class QsTweaks(
    val columns: Int? = null,
    val rowsPerPage: Int? = null,
    val scroll: Boolean = false,
    val tileHeight: Int? = null,
    val spacing: Int? = null,
    val sizing: QsTileSizing? = null,
    val radius: Int? = null,
    val label: QsTileLabel? = null,
    val iconBacking: QsIconBacking? = null,
    val secondLine: Boolean = true,
    val iconSize: Int? = null,
    val font: QsTileFont? = null,
    val accent: Int? = null,
    val onFill: QsOnFill? = null,
    val offFill: QsOffFill? = null,
    val glow: Int? = null,
    val depth: QsTileDepth? = null,
    val shapeFrom: QsStyle? = null,
    val paintFrom: QsStyle? = null,
    val sliderFrom: QsStyle? = null,
    val footerFrom: QsStyle? = null,
    val mediaFrom: QsStyle? = null,
    val mediaSize: QsMediaTileSize? = null,
    val mediaPlace: QsMediaPlace? = null,
    val mediaArt: QsMediaArt? = null,
    val mediaBar: QsMediaBar? = null,
    val mediaButtons: QsMediaButtons? = null,
    val volumeSize: QsVolumeSize? = null,
    val volumeLook: QsVolumeLook? = null,
    val volumeSound: QsVolumeSound? = null,
    val volumeShow: QsVolumeShow? = null,
    val volumeTap: QsVolumeTap? = null,
    val volumeTicks: Boolean? = null,
    val brightnessSize: QsVolumeSize? = null,
    val brightnessLook: QsVolumeLook? = null,
    val soundSize: QsVolumeSize? = null,
    val sliderPercent: QsSliderPercent? = null,
    val sliderLayout: QsSliderLayout? = null,
    val sliderPlace: QsSliderPlace? = null,
    val slidersByMedia: Boolean? = null,
    val tiles: Map<String, QsTileTweak> = emptyMap(),
) {
    fun withTile(spec: String, edit: (QsTileTweak) -> QsTileTweak): QsTweaks {
        val tile = edit(tiles[spec] ?: QsTileTweak())
        return copy(tiles = if (tile.isEmpty) tiles - spec else tiles + (spec to tile))
    }

    /** Circles keeps its player under the tiles, with tall sliders beside it. */
    fun mediaPlaceIn(theme: QsStyle): QsMediaPlace =
        mediaPlace
            ?: if ((shapeFrom ?: theme) == QsStyle.CIRCLES) QsMediaPlace.BELOW else QsMediaPlace.GRID

    fun slidersByMediaIn(theme: QsStyle): Boolean =
        slidersByMedia ?: ((shapeFrom ?: theme) == QsStyle.CIRCLES)
}

val LocalQsTweaks = staticCompositionLocalOf { QsTweaks() }

/** A theme saved under a name, with its changes. */
data class QsMyTheme(val name: String, val style: QsStyle, val tweaks: QsTweaks)

object QsTweaksCodec {
    const val SETTING = "rr_qs_tweaks"
    const val MY_THEMES_SETTING = "rr_qs_my_themes"
    private const val CODE_PREFIX = "RRQS1:"

    fun parseStore(json: String?): Map<Int, QsTweaks> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                for (key in root.keys()) {
                    val theme = key.toIntOrNull() ?: continue
                    put(theme, root.getJSONObject(key).toTweaks())
                }
            }
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    fun writeStore(store: Map<Int, QsTweaks>): String {
        val root = JSONObject()
        store.forEach { (theme, tweaks) -> root.put(theme.toString(), tweaks.toJson()) }
        return root.toString()
    }

    fun parseMyThemes(json: String?): List<QsMyTheme> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getJSONObject(it).toMyTheme() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun writeMyThemes(themes: List<QsMyTheme>): String {
        val array = JSONArray()
        themes.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /** The theme as text someone else can paste. */
    fun toCode(theme: QsMyTheme): String =
        CODE_PREFIX +
            Base64.encodeToString(theme.toJson().toString().toByteArray(), Base64.NO_WRAP)

    fun fromCode(code: String?): QsMyTheme? {
        val trimmed = code?.trim().orEmpty()
        if (!trimmed.startsWith(CODE_PREFIX)) return null
        return try {
            val json = String(Base64.decode(trimmed.removePrefix(CODE_PREFIX), Base64.NO_WRAP))
            JSONObject(json).toMyTheme()
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    private fun QsMyTheme.toJson() =
        JSONObject().put("name", name).put("style", style.value).put("tweaks", tweaks.toJson())

    private fun JSONObject.toMyTheme() =
        QsMyTheme(
            name = optString("name"),
            style = QsStyle.of(optInt("style")),
            tweaks = optJSONObject("tweaks")?.toTweaks() ?: QsTweaks(),
        )

    private fun QsTweaks.toJson(): JSONObject =
        JSONObject().apply {
            putOpt("columns", columns)
            putOpt("rowsPerPage", rowsPerPage)
            if (scroll) put("scroll", true)
            putOpt("tileHeight", tileHeight)
            putOpt("spacing", spacing)
            putOpt("sizing", sizing?.name)
            putOpt("radius", radius)
            putOpt("label", label?.name)
            putOpt("iconBacking", iconBacking?.name)
            if (!secondLine) put("secondLine", false)
            putOpt("iconSize", iconSize)
            putOpt("font", font?.name)
            putOpt("accent", accent)
            putOpt("onFill", onFill?.name)
            putOpt("offFill", offFill?.name)
            putOpt("glow", glow)
            putOpt("depth", depth?.name)
            putOpt("shapeFrom", shapeFrom?.value)
            putOpt("paintFrom", paintFrom?.value)
            putOpt("sliderFrom", sliderFrom?.value)
            putOpt("footerFrom", footerFrom?.value)
            putOpt("mediaFrom", mediaFrom?.value)
            putOpt("mediaSize", mediaSize?.name)
            putOpt("mediaPlace", mediaPlace?.name)
            putOpt("mediaArt", mediaArt?.name)
            putOpt("mediaBar", mediaBar?.name)
            putOpt("mediaButtons", mediaButtons?.name)
            putOpt("volumeSize", volumeSize?.name)
            putOpt("volumeLook", volumeLook?.name)
            putOpt("volumeSound", volumeSound?.name)
            putOpt("volumeShow", volumeShow?.name)
            putOpt("volumeTap", volumeTap?.name)
            putOpt("volumeTicks", volumeTicks)
            putOpt("brightnessSize", brightnessSize?.name)
            putOpt("brightnessLook", brightnessLook?.name)
            putOpt("soundSize", soundSize?.name)
            putOpt("sliderPercent", sliderPercent?.name)
            putOpt("sliderLayout", sliderLayout?.name)
            putOpt("sliderPlace", sliderPlace?.name)
            putOpt("slidersByMedia", slidersByMedia)
            if (tiles.isNotEmpty()) {
                val json = JSONObject()
                tiles.forEach { (spec, tile) -> json.put(spec, tile.toJson()) }
                put("tiles", json)
            }
        }

    private fun JSONObject.toTweaks(): QsTweaks =
        QsTweaks(
            columns = intOrNull("columns"),
            rowsPerPage = intOrNull("rowsPerPage"),
            scroll = optBoolean("scroll"),
            tileHeight = intOrNull("tileHeight"),
            spacing = intOrNull("spacing"),
            sizing = enumOrNull<QsTileSizing>("sizing"),
            radius = intOrNull("radius"),
            label = enumOrNull<QsTileLabel>("label"),
            iconBacking = enumOrNull<QsIconBacking>("iconBacking"),
            secondLine = optBoolean("secondLine", true),
            iconSize = intOrNull("iconSize"),
            font = enumOrNull<QsTileFont>("font"),
            accent = intOrNull("accent"),
            onFill = enumOrNull<QsOnFill>("onFill"),
            offFill = enumOrNull<QsOffFill>("offFill"),
            glow = intOrNull("glow"),
            depth = enumOrNull<QsTileDepth>("depth"),
            shapeFrom = styleOrNull("shapeFrom"),
            paintFrom = styleOrNull("paintFrom"),
            sliderFrom = styleOrNull("sliderFrom"),
            footerFrom = styleOrNull("footerFrom"),
            mediaFrom = styleOrNull("mediaFrom"),
            mediaSize = enumOrNull<QsMediaTileSize>("mediaSize"),
            mediaPlace = enumOrNull<QsMediaPlace>("mediaPlace"),
            mediaArt = enumOrNull<QsMediaArt>("mediaArt"),
            mediaBar = enumOrNull<QsMediaBar>("mediaBar"),
            mediaButtons = enumOrNull<QsMediaButtons>("mediaButtons"),
            volumeSize = enumOrNull<QsVolumeSize>("volumeSize"),
            volumeLook = enumOrNull<QsVolumeLook>("volumeLook"),
            volumeSound = enumOrNull<QsVolumeSound>("volumeSound"),
            volumeShow = enumOrNull<QsVolumeShow>("volumeShow"),
            volumeTap = enumOrNull<QsVolumeTap>("volumeTap"),
            volumeTicks = if (has("volumeTicks")) optBoolean("volumeTicks") else null,
            brightnessSize = enumOrNull<QsVolumeSize>("brightnessSize"),
            brightnessLook = enumOrNull<QsVolumeLook>("brightnessLook"),
            soundSize = enumOrNull<QsVolumeSize>("soundSize"),
            sliderPercent = enumOrNull<QsSliderPercent>("sliderPercent"),
            sliderLayout = enumOrNull<QsSliderLayout>("sliderLayout"),
            sliderPlace = enumOrNull<QsSliderPlace>("sliderPlace"),
            slidersByMedia = if (has("slidersByMedia")) optBoolean("slidersByMedia") else null,
            tiles = optJSONObject("tiles")?.toTiles() ?: emptyMap(),
        )

    private fun QsTileTweak.toJson(): JSONObject =
        JSONObject().apply {
            putOpt("columns", columns)
            putOpt("rows", rows)
            putOpt("color", color)
            putOpt("icon", icon)
            if (hideLabel) put("hideLabel", true)
            putOpt("look", look?.value)
        }

    private fun JSONObject.toTiles(): Map<String, QsTileTweak> = buildMap {
        for (spec in keys()) {
            val tile = getJSONObject(spec)
            put(
                spec,
                QsTileTweak(
                    columns = tile.intOrNull("columns"),
                    rows = tile.intOrNull("rows"),
                    color = tile.intOrNull("color"),
                    icon = tile.optString("icon").takeIf { it.isNotEmpty() },
                    hideLabel = tile.optBoolean("hideLabel"),
                    look = tile.styleOrNull("look"),
                ),
            )
        }
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null

    private fun JSONObject.styleOrNull(key: String): QsStyle? =
        intOrNull(key)?.let { value -> QsStyle.entries.firstOrNull { it.value == value } }

    private inline fun <reified T : Enum<T>> JSONObject.enumOrNull(key: String): T? {
        val name = optString(key)
        return enumValues<T>().firstOrNull { it.name == name }
    }
}
