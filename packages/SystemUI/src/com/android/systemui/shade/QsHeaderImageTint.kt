/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade

import android.graphics.Rect
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the parts of the QS header that sit on the header image are over a light part of it, so
 * their icons have to be drawn dark. The views report where they are in [setBounds], the shade
 * samples the image behind those bounds and reports the result in [setLight].
 */
object QsHeaderImageTint {

    enum class Region {
        /** The carrier name, the status icons and the battery. */
        STATUS,
        /** The edit button of the ColorOS header. */
        EDIT,
        /** The settings button of the ColorOS header. */
        SETTINGS,
    }

    private val bounds = Region.values().associateWith { Rect() }
    private val light = Region.values().associateWith { MutableStateFlow(false) }
    private val statusListeners = CopyOnWriteArrayList<Runnable>()

    @JvmStatic fun lightFlow(region: Region): StateFlow<Boolean> = light.getValue(region)

    @JvmStatic fun isLight(region: Region): Boolean = light.getValue(region).value

    /** Sets the bounds of [region] in the coordinates of the window. */
    @JvmStatic
    fun setBounds(region: Region, left: Int, top: Int, right: Int, bottom: Int) {
        bounds.getValue(region).set(left, top, right, bottom)
    }

    @JvmStatic
    fun getBounds(region: Region, out: Rect) {
        out.set(bounds.getValue(region))
    }

    @JvmStatic
    fun setLight(region: Region, value: Boolean) {
        val flow = light.getValue(region)
        if (flow.value == value) return
        flow.value = value
        if (region == Region.STATUS) {
            statusListeners.forEach { it.run() }
        }
    }

    /** Draws every region light again, when there is no header image behind them. */
    @JvmStatic
    fun reset() {
        Region.values().forEach { setLight(it, false) }
    }

    @JvmStatic fun addStatusListener(listener: Runnable) = statusListeners.add(listener)

    @JvmStatic fun removeStatusListener(listener: Runnable) = statusListeners.remove(listener)
}
