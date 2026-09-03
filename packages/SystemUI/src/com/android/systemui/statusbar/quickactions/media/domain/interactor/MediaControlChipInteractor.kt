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

package com.android.systemui.statusbar.quickactions.media.domain.interactor

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for managing the state of the media control chip in the status bar.
 *
 * Provides a [StateFlow] of [MediaControlChipModel] representing the current state of the media
 * control chip. Emits a new [MediaControlChipModel] when there is an active media session and the
 * corresponding user preference is found, otherwise emits null.
 */
@SysUISingleton
class MediaControlChipInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val mediaRepository: MediaRepositoryImpl,
) {
    private val isEnabled = MutableStateFlow(false)
    private val isMusicTickerEnabled = MutableStateFlow(false)
    private var isInitialized = false
    private val musicTickerObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateMusicTickerState()
            }
        }

    private val mediaControlChipModelForScene: Flow<MediaControlChipModel?> = snapshotFlow {
        mediaRepository.currentMedia.firstOrNull { it.isActive }?.toMediaControlChipModel()
    }

    /**
     * A flow of [MediaControlChipModel] representing the current state of the media controls chip.
     * This flow emits null when no active media is playing or when playback information is
     * unavailable. This flow is only active when [MediaControlsInComposeFlag] is disabled.
     */
    private val mediaControlChipModelLegacy = MutableStateFlow<MediaControlChipModel?>(null)

    fun updateMediaControlChipModelLegacy(mediaData: MediaData?) {
        if (!MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelLegacy.value = mediaData?.toMediaControlChipModel()
        }
    }

    private val _mediaControlChipModel: Flow<MediaControlChipModel?> =
        if (MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelForScene
        } else {
            mediaControlChipModelLegacy
        }

    /** The currently active [MediaControlChipModel] */
    val mediaControlChipModel: StateFlow<MediaControlChipModel?> =
        combine(_mediaControlChipModel, isEnabled, isMusicTickerEnabled) {
            mediaControlChipModel,
            isEnabled,
            isMusicTickerEnabled ->
                if (isEnabled && isMusicTickerEnabled) {
                    mediaControlChipModel
                } else {
                    null
                }
            }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)

    /** Initializes setting observation. This must be called from a CoreStartable. */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_MUSIC_TICKER),
            false,
            musicTickerObserver,
            UserHandle.USER_ALL,
        )
        updateMusicTickerState()
        isEnabled.value = true
    }

    private fun updateMusicTickerState() {
        isMusicTickerEnabled.value =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.STATUS_BAR_SHOW_MUSIC_TICKER,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
    }
}

private fun MediaDataModel.toMediaControlChipModel(): MediaControlChipModel {
    return MediaControlChipModel.Compose(
        appIcon = this.appIcon,
        artworkIcon = this.background,
        appName = this.appName,
        artistName = this.subtitle,
        songName = this.title,
        playOrPause = this.playbackStateActions?.getActionById(R.id.actionPlayPause),
    )
}

private fun MediaData.toMediaControlChipModel(): MediaControlChipModel {
    return MediaControlChipModel.Legacy(
        appIcon = this.appIcon,
        artworkIcon = this.artwork,
        appName = this.app,
        artistName = this.artist,
        songName = this.song,
        playOrPause = this.semanticActions?.getActionById(R.id.actionPlayPause),
    )
}
