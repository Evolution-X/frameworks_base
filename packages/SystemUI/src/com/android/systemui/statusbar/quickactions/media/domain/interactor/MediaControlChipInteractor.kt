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

import android.app.PendingIntent
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.popups.shared.DynamicIslandFeatureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PLAYBACK_POSITION_POLL_INTERVAL_MS = 1000L

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
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) {
    private val isEnabled = MutableStateFlow(false)
    private val isDynamicIslandEnabled = MutableStateFlow(false)
    private var isInitialized = false
    private val currentLyrics = MutableStateFlow<String?>(null)
    private val currentSyncedLyrics = MutableStateFlow<String?>(null)
    private var lastFetchedSong: String? = null
    private var lastFetchedArtist: String? = null
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateDynamicIslandState()
            }
        }

    private val mediaControlChipModelForScene: Flow<MediaControlState> = snapshotFlow {
        mediaRepository.currentMedia.firstOrNull { it.isActive }?.toMediaControlState(
            context = context,
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            lockscreenUserManager = lockscreenUserManager,
            keyguardStateController = keyguardStateController,
        )
            ?: MediaControlState.Hidden
    }

    /**
     * A flow of [MediaControlChipModel] representing the current state of the media controls chip.
     * This flow emits null when no active media is playing or when playback information is
     * unavailable. This flow is only active when [MediaControlsInComposeFlag] is disabled.
     */
    private val mediaControlChipModelLegacy = MutableStateFlow(MediaControlState.Hidden)

    fun updateMediaControlChipModelLegacy(mediaData: MediaData?) {
        if (!MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelLegacy.value =
                mediaData?.toMediaControlState(
                    context = context,
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                ) ?: MediaControlState.Hidden
        }
    }

    private val mediaControlState: Flow<MediaControlState> =
        if (MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelForScene
        } else {
            mediaControlChipModelLegacy
        }

    private val livePlaybackInfo: Flow<PlaybackInfo?> =
        mediaControlState
            .flatMapLatest { state -> state.token.playbackInfoFlow(context) }
            .distinctUntilChanged()

    private val baseMediaControlChipModel: Flow<MediaControlChipModel?> =
        combine(
            mediaControlState,
            livePlaybackInfo,
            isEnabled,
            isDynamicIslandEnabled,
            DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context,
                DynamicIslandFeatureSettings.MEDIA_CONTROLS,
            ),
        ) {
            mediaControlState,
            playbackInfo,
            isEnabled,
            isDynamicIslandEnabled,
            mediaControlsEnabled ->
                if (isEnabled && isDynamicIslandEnabled && mediaControlsEnabled) {
                    mediaControlState.model?.withPlaybackInfo(playbackInfo)
                } else {
                    null
                }
        }

    /** The currently active [MediaControlChipModel] */
    val mediaControlChipModel: StateFlow<MediaControlChipModel?> =
        combine(
            baseMediaControlChipModel,
            currentLyrics,
            currentSyncedLyrics,
        ) { baseModel, lyrics, syncedLyrics ->
            baseModel?.copy(lyrics = lyrics, syncedLyrics = syncedLyrics)
        }
        .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)

    /** Initializes setting observation. This must be called from a CoreStartable. */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND
            ),
            false,
            dynamicIslandObserver,
            UserHandle.USER_ALL,
        )
        updateDynamicIslandState()
        isEnabled.value = true
        setupLyricsWorker()
    }

    private fun updateDynamicIslandState() {
        isDynamicIslandEnabled.value =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
    }

    private fun setupLyricsWorker() {
        backgroundScope.launch {
            combine(
                mediaControlState,
                DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                    context,
                    DynamicIslandFeatureSettings.SHOW_LYRICS,
                    false,
                ),
            ) { state, lyricsEnabled ->
                Pair(state.model, lyricsEnabled)
            }.collect { (model, lyricsEnabled) ->
                if (model != null && lyricsEnabled) {
                    val song = model.songName?.toString()
                    val artist = model.artistName?.toString()
                    val packageName = model.packageName
                    val isSupportedApp =
                        packageName?.contains("music", ignoreCase = true) == true ||
                            packageName?.contains("youtube", ignoreCase = true) == true ||
                            packageName?.contains("spotify", ignoreCase = true) == true ||
                            packageName?.contains("vanced", ignoreCase = true) == true ||
                            packageName?.contains("rvx", ignoreCase = true) == true

                    if (song != null && artist != null && isSupportedApp) {
                        if (song != lastFetchedSong || artist != lastFetchedArtist) {
                            lastFetchedSong = song
                            lastFetchedArtist = artist
                            currentLyrics.value = null
                            currentSyncedLyrics.value = null
                            fetchLyrics(cleanArtistName(artist), cleanSongTitle(song))
                        }
                    } else {
                        lastFetchedSong = null
                        lastFetchedArtist = null
                        currentLyrics.value = null
                        currentSyncedLyrics.value = null
                    }
                } else {
                    if (model == null) {
                        lastFetchedSong = null
                        lastFetchedArtist = null
                    }
                    currentLyrics.value = null
                    currentSyncedLyrics.value = null
                }
            }
        }
    }

    private fun cleanSongTitle(title: String): String {
        if (title.isBlank()) return title
        val separatorRegex = Regex("\\s+[-–—|•]\\s+")
        var cleaned = title.split(separatorRegex)[0]
        val parentheticalRegex =
            Regex("(?i)\\s*[\\(\\[]([^\\)\\]]*(?:feat|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded)[^\\)\\]]*)[\\]\\)]")
        cleaned = cleaned.replace(parentheticalRegex, "")
        val featRegex = Regex("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*")
        cleaned = cleaned.replace(featRegex, "")
        return cleaned.trim()
    }

    private fun cleanArtistName(artist: String): String {
        if (artist.isBlank()) return artist
        val splitRegex = Regex("(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+")
        return artist.split(splitRegex)[0].trim()
    }

    private suspend fun fetchLyrics(artist: String, song: String) =
        withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val query = java.net.URLEncoder.encode("$artist $song", "UTF-8")
                val url = java.net.URL("https://lrclib.net/api/search?q=$query")
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty(
                    "User-Agent",
                    "SystemUI-DynamicIslandLyrics/1.0 (https://lineageos.org)",
                )

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    if (jsonArray.length() > 0) {
                        val bestMatch = jsonArray.getJSONObject(0)
                        val plainLyrics = bestMatch.optString("plainLyrics", "")
                        val syncedLyrics = bestMatch.optString("syncedLyrics", "")

                        if (plainLyrics.isNotBlank() || syncedLyrics.isNotBlank()) {
                            currentLyrics.value = plainLyrics.takeIf { it.isNotBlank() }
                            currentSyncedLyrics.value = syncedLyrics.takeIf { it.isNotBlank() }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaControlChipInteractor", "Failed to fetch lyrics from LRCLIB", e)
            } finally {
                connection?.disconnect()
            }
        }
}

private fun MediaDataModel.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    return MediaControlState(
        model =
            MediaControlChipModel(
                packageName = packageName,
                appIcon = appIcon,
                artworkIcon = background ?: appIcon,
                appName = appName,
                artistName = subtitle,
                songName = title,
                playOrPause = playbackStateActions?.getActionById(R.id.actionPlayPause),
                nextAction = playbackStateActions?.getActionById(R.id.actionNext),
                previousAction = playbackStateActions?.getActionById(R.id.actionPrev),
                openApp =
                    clickIntent.toAction(
                        activityStarter = activityStarter,
                        activityIntentHelper = activityIntentHelper,
                        lockscreenUserManager = lockscreenUserManager,
                        keyguardStateController = keyguardStateController,
                    ),
                seekTo = token.toSeekAction(context),
                durationMs = durationMs,
                positionMs = positionMs,
                canBeScrubbed = canBeScrubbed,
                isPlaying = state is MediaSessionState.Playing || state is MediaSessionState.Buffering,
            ),
        token = token,
    )
}

private fun MediaData.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    val openApp =
        clickIntent.toAction(
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            lockscreenUserManager = lockscreenUserManager,
            keyguardStateController = keyguardStateController,
        )
    val appIcon =
        appIcon?.loadUiIcon(
            context = context,
            contentDescription = ContentDescription.Loaded(app),
        )
    val artworkIcon =
        artwork?.loadUiIcon(
            context = context,
            contentDescription =
                ContentDescription.Loaded(listOfNotNull(artist, song).joinToString(" - ")),
        ) ?: appIcon
    val nextAction =
        semanticActions?.actionsOrCustom?.firstOrNull { it.isNextAction() }
            ?: actions.getOrNull(2)
    val previousAction =
        semanticActions?.actionsOrCustom?.firstOrNull { it.isPrevAction() }
            ?: actions.getOrNull(0)
    val playOrPauseAction =
        semanticActions?.getActionById(R.id.actionPlayPause)
            ?: semanticActions?.actionsOrCustom?.firstOrNull { it.isPlayOrPauseAction() }
            ?: actions.getOrNull(1)

    return MediaControlState(
        model =
            MediaControlChipModel(
                packageName = packageName,
                appIcon = appIcon,
                artworkIcon = artworkIcon,
                appName = app,
                artistName = artist,
                songName = song,
                playOrPause = playOrPauseAction,
                nextAction = nextAction,
                previousAction = previousAction,
                openApp = openApp,
                seekTo = token.toSeekAction(context),
                durationMs = 0L,
                positionMs = 0L,
                canBeScrubbed = false,
                isPlaying = playOrPauseAction.isPauseAction(),
            ),
        token = token,
    )
}

private fun MediaAction?.isPlayOrPauseAction(): Boolean {
    val description = this?.contentDescription ?: return false
    return isPlayAction() || isPauseAction()
}

private fun MediaAction?.isPlayAction(): Boolean {
    val description = this?.contentDescription ?: return false
    return description.toString().lowercase().contains("play")
}

private fun MediaAction?.isPauseAction(): Boolean {
    val description = this?.contentDescription ?: return false
    return description.toString().lowercase().contains("pause")
}

private fun MediaAction?.isNextAction(): Boolean {
    val description = this?.contentDescription ?: return false
    return description.toString().lowercase().contains("next")
}

private fun MediaAction?.isPrevAction(): Boolean {
    val description = this?.contentDescription ?: return false
    val text = description.toString().lowercase()
    return text.contains("previous") || text.contains("prev")
}

private fun android.graphics.drawable.Icon?.loadUiIcon(
    context: Context,
    contentDescription: ContentDescription?,
): UiIcon? {
    return this?.loadDrawable(context)?.let { UiIcon.Loaded(it, contentDescription) }
}

private fun PendingIntent?.toAction(
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            val showOverLockscreen =
                keyguardStateController.isShowing &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        pendingIntent,
                        lockscreenUserManager.currentUserId,
                    )

            if (showOverLockscreen) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(
                    pendingIntent,
                    null,
                    null,
                )
            } else {
                activityStarter.postStartActivityDismissingKeyguard(pendingIntent, null)
            }
        }
    }
}

private fun MediaSession.Token?.toSeekAction(context: Context): ((Long) -> Unit)? {
    return this?.let { token ->
        { targetPositionMs ->
            runCatching {
                MediaController(context, token)
                    .transportControls
                    .seekTo(targetPositionMs.coerceAtLeast(0L))
            }
        }
    }
}

private fun MediaSession.Token?.playbackInfoFlow(context: Context): Flow<PlaybackInfo?> {
    if (this == null) {
        return flowOf(null)
    }
    return callbackFlow {
        val controller =
            runCatching { MediaController(context, this@playbackInfoFlow) }.getOrNull()
                ?: run {
                    trySend(null)
                    close()
                    return@callbackFlow
                }
        var poller: Job? = null

        fun updatePolling(playbackInfo: PlaybackInfo) {
            if (playbackInfo.isPlaying) {
                if (poller?.isActive == true) {
                    return
                }
                poller =
                    launch {
                        while (isActive) {
                            delay(PLAYBACK_POSITION_POLL_INTERVAL_MS)
                            trySend(controller.resolvePlaybackInfo(context))
                        }
                    }
            } else {
                poller?.cancel()
                poller = null
            }
        }

        fun dispatchPlaybackInfo() {
            val playbackInfo = controller.resolvePlaybackInfo(context)
            trySend(playbackInfo)
            updatePolling(playbackInfo)
        }

        val callback =
            object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    dispatchPlaybackInfo()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    dispatchPlaybackInfo()
                }

                override fun onSessionDestroyed() {
                    trySend(null)
                    close()
                }
            }

        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        dispatchPlaybackInfo()

        awaitClose {
            poller?.cancel()
            runCatching { controller.unregisterCallback(callback) }
        }
    }
}

private fun MediaController.resolvePlaybackInfo(context: Context): PlaybackInfo {
    val metadata = metadata
    val playbackState = playbackState
    val durationMs =
        metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    return PlaybackInfo(
        durationMs = durationMs,
        positionMs = playbackState?.computeActualPosition(durationMs) ?: 0L,
        canSeek =
            playbackState?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L && durationMs > 0L,
        isPlaying = playbackState?.isActivePlaybackState() == true,
        playOrPause = playbackState?.toPlayPauseAction(context = context, controller = this),
    )
}

private fun PlaybackState.computeActualPosition(durationMs: Long): Long {
    var currentPosition = position.coerceAtLeast(0L)
    if (NotificationMediaManager.isPlayingState(state) && lastPositionUpdateTime > 0L) {
        currentPosition =
            ((playbackSpeed * (SystemClock.elapsedRealtime() - lastPositionUpdateTime)).toLong() +
                    position)
                .coerceAtLeast(0L)
    }
    return if (durationMs > 0L) currentPosition.coerceAtMost(durationMs) else currentPosition
}

private fun PlaybackState.toPlayPauseAction(
    context: Context,
    controller: MediaController,
): MediaAction? {
    val transportControls = controller.transportControls
    return when {
        NotificationMediaManager.isPlayingState(state) && supportsPlaybackAction(PlaybackState.ACTION_PAUSE) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_pause_button),
                action = Runnable { transportControls.pause() },
                contentDescription = context.getString(R.string.controls_media_button_pause),
                background = context.getDrawable(R.drawable.ic_media_pause_button_container),
            )
        supportsPlaybackAction(PlaybackState.ACTION_PLAY) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_play_button),
                action = Runnable { transportControls.play() },
                contentDescription = context.getString(R.string.controls_media_button_play),
                background = context.getDrawable(R.drawable.ic_media_play_button_container),
            )
        else -> null
    }
}

private fun PlaybackState.supportsPlaybackAction(@PlaybackState.Actions action: Long): Boolean {
    if (
        (action == PlaybackState.ACTION_PLAY || action == PlaybackState.ACTION_PAUSE) &&
            (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
    ) {
        return true
    }
    return (actions and action) != 0L
}

private fun PlaybackState.isActivePlaybackState(): Boolean {
    return state == PlaybackState.STATE_PLAYING ||
        state == PlaybackState.STATE_BUFFERING ||
        state == PlaybackState.STATE_FAST_FORWARDING ||
        state == PlaybackState.STATE_REWINDING ||
        state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
}

private data class PlaybackInfo(
    val durationMs: Long,
    val positionMs: Long,
    val canSeek: Boolean,
    val isPlaying: Boolean,
    val playOrPause: MediaAction?,
)

private data class MediaControlState(
    val model: MediaControlChipModel?,
    val token: MediaSession.Token?,
) {
    companion object {
        val Hidden = MediaControlState(model = null, token = null)
    }
}

private fun MediaControlChipModel.withPlaybackInfo(playbackInfo: PlaybackInfo?): MediaControlChipModel {
    if (playbackInfo == null) {
        return this
    }
    return copy(
        playOrPause = playbackInfo.playOrPause ?: playOrPause,
        durationMs = durationMs.takeIf { it > 0L } ?: playbackInfo.durationMs,
        positionMs = playbackInfo.positionMs,
        canBeScrubbed = canBeScrubbed || playbackInfo.canSeek,
        isPlaying = playbackInfo.isPlaying,
    )
}
