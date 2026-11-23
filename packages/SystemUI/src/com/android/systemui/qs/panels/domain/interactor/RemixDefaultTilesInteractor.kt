/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.domain.interactor

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.data.repository.QsPanelStyleRepository
import com.android.systemui.qs.panels.shared.model.QsPanelStyle
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.RemixBrightnessTile
import com.android.systemui.qs.tiles.RemixMediaTile
import com.android.systemui.qs.tiles.RemixSoundTile
import com.android.systemui.qs.tiles.RemixVolumeTile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the Remix player and slider tiles at the front of the panel the first time the Remix panel
 * style is picked. The default tile lists do not have them, so the other panel styles keep their
 * own first tiles.
 *
 * It only acts on a switch to the Remix style, once. A panel that is already Remix when this
 * starts is left as it is, and so is one that has any of the tiles already.
 */
@SysUISingleton
class RemixDefaultTilesInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val styleRepository: QsPanelStyleRepository,
    private val currentTilesInteractor: CurrentTilesInteractor,
) {
    private val started = AtomicBoolean(false)

    /** Starts watching the panel style. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: QsPanelStyle? = null
            styleRepository.style.collect { style ->
                if (previous == null && style == QsPanelStyle.REMIX) {
                    // Remix was picked before this existed: those panels are theirs already.
                    markDone()
                } else if (previous != null && previous != QsPanelStyle.REMIX &&
                    style == QsPanelStyle.REMIX && !isDone()
                ) {
                    addTiles()
                }
                previous = style
            }
        }
    }

    private suspend fun addTiles() {
        // The tiles load a moment after the panel starts.
        val tiles = currentTilesInteractor.currentTiles.first { it.isNotEmpty() }
        val present = tiles.map { it.spec.spec }.toSet()
        if (TILES.none { it in present }) {
            TILES.forEachIndexed { index, spec ->
                currentTilesInteractor.addTile(TileSpec.create(spec), index)
            }
        }
        markDone()
    }

    private suspend fun isDone(): Boolean =
        withContext(bgDispatcher) {
            Settings.System.getIntForUser(
                context.contentResolver,
                SETTING,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        }

    private suspend fun markDone() {
        withContext(bgDispatcher) {
            try {
                Settings.System.putIntForUser(
                    context.contentResolver,
                    SETTING,
                    1,
                    UserHandle.USER_CURRENT,
                )
            } catch (_: Throwable) {
                // It asks again at the next switch to the Remix style.
            }
        }
    }

    private companion object {
        const val SETTING = "qs_remix_default_tiles_added"

        val TILES =
            listOf(
                RemixMediaTile.TILE_SPEC,
                RemixBrightnessTile.TILE_SPEC,
                RemixVolumeTile.TILE_SPEC,
                RemixSoundTile.TILE_SPEC,
            )
    }
}
