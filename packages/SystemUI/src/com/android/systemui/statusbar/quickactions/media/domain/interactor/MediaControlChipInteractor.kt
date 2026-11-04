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
import com.android.systemui.media.controls.domain.pipeline.getNotificationActions
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PLAYBACK_POSITION_POLL_INTERVAL_MS = 1000L
private const val LYRICS_RETRY_DELAY_MS = 15_000L

/** How far the length of a lyrics entry can be from the song's before its timing is off. */
private const val LYRICS_DURATION_TOLERANCE_S = 3.0

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

    val mediaUseWaveform: StateFlow<Boolean> =
        DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
            context,
            Settings.System.MEDIA_WAVEFORM_SEEKBAR,
            false,
        ).stateIn(backgroundScope, SharingStarted.WhileSubscribed(), false)

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
                    livePlaybackInfo,
                    DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                        context,
                        DynamicIslandFeatureSettings.SHOW_LYRICS,
                        true,
                    ),
                ) { state, playbackInfo, lyricsEnabled ->
                    val model = state.model
                    // The title, artist and length come from the one metadata snapshot of the
                    // player, so they always belong to the same song.
                    val song =
                        playbackInfo?.songName?.takeIf { it.isNotBlank() }
                            ?: model?.songName?.toString()
                    val artist =
                        playbackInfo?.artistName?.takeIf { it.isNotBlank() }
                            ?: model?.artistName?.toString()
                    val durationMs =
                        playbackInfo?.durationMs?.takeIf { it > 0L } ?: model?.durationMs ?: 0L
                    if (model != null && lyricsEnabled && !song.isNullOrBlank() &&
                        !artist.isNullOrBlank()
                    ) {
                        LyricsRequest(
                            artist = cleanArtistName(artist),
                            song = cleanSongTitle(song),
                            durationSec = (durationMs / 1000L).takeIf { it > 0L },
                        )
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { request ->
                    currentLyrics.value = null
                    currentSyncedLyrics.value = null
                    if (request == null) {
                        return@collectLatest
                    }
                    var result = fetchLyrics(request)
                    // A lookup that failed, such as without a connection, is tried again later.
                    while (result.item == null && !result.conclusive) {
                        delay(LYRICS_RETRY_DELAY_MS)
                        result = fetchLyrics(request)
                    }
                    result.item?.let { publishLyrics(it) }
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

    private data class LyricsRequest(val artist: String, val song: String, val durationSec: Long?)

    private class LyricsResult(val item: org.json.JSONObject?, val conclusive: Boolean)

    /**
     * Looks the lyrics up on LRCLIB, by the exact track and length first, as an entry of another
     * length has other timing. The result is not conclusive if the lookup failed and is worth
     * trying again. It is published by the caller, so a lookup of a song that is no longer playing
     * is dropped with its coroutine.
     */
    private suspend fun fetchLyrics(request: LyricsRequest): LyricsResult =
        withContext(Dispatchers.IO) {
            val durationSec = request.durationSec
            val a = java.net.URLEncoder.encode(request.artist, "UTF-8")
            val t = java.net.URLEncoder.encode(request.song, "UTF-8")
            var conclusive = true
            try {
                if (durationSec != null) {
                    val exact = lrclibGet("get?artist_name=$a&track_name=$t&duration=$durationSec")
                    if (exact.code == 200) {
                        val item = org.json.JSONObject(exact.body.orEmpty())
                        if (item.hasLyrics()) {
                            return@withContext LyricsResult(item, true)
                        }
                    } else if (exact.code != 404) {
                        conclusive = false
                    }
                }
                val queries =
                    listOf(
                        "search?artist_name=$a&track_name=$t",
                        "search?q=" +
                            java.net.URLEncoder.encode("${request.artist} ${request.song}", "UTF-8"),
                    )
                for (query in queries) {
                    val response = lrclibGet(query)
                    if (response.code != 200) {
                        conclusive = false
                        continue
                    }
                    val items = org.json.JSONArray(response.body.orEmpty())
                    val item = pickLyrics(items, durationSec)
                    if (item != null) {
                        return@withContext LyricsResult(item, true)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaControlChipInteractor", "Failed to fetch lyrics from LRCLIB", e)
                conclusive = false
            }
            LyricsResult(null, conclusive)
        }

    private class LrclibResponse(val code: Int, val body: String?)

    private fun lrclibGet(path: String): LrclibResponse {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = java.net.URL("https://lrclib.net/api/$path").openConnection()
                as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty(
                "User-Agent",
                "SystemUI-DynamicIslandLyrics/1.0 (https://lineageos.org)",
            )
            val code = connection.responseCode
            val body =
                if (code == 200) connection.inputStream.bufferedReader().use { it.readText() }
                else null
            return LrclibResponse(code, body)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * The entry to show: one of the right length with synced lyrics, then any of the right length.
     * One of another length is only used for its plain lyrics, as its timing would be off.
     */
    private fun pickLyrics(items: org.json.JSONArray, durationSec: Long?): org.json.JSONObject? {
        val withLyrics =
            (0 until items.length()).map { items.getJSONObject(it) }.filter { it.hasLyrics() }
        val matching =
            withLyrics.filter {
                durationSec == null ||
                    kotlin.math.abs(it.optDouble("duration", -1000.0) - durationSec) <=
                        LYRICS_DURATION_TOLERANCE_S
            }
        return matching.firstOrNull { it.lyric("syncedLyrics").isNotBlank() }
            ?: matching.firstOrNull()
            ?: withLyrics.firstOrNull { it.lyric("syncedLyrics").isBlank() }
    }

    private fun publishLyrics(item: org.json.JSONObject) {
        currentLyrics.value = item.lyric("plainLyrics").takeIf { it.isNotBlank() }
        currentSyncedLyrics.value = item.lyric("syncedLyrics").takeIf { it.isNotBlank() }
    }

    // A track without synced lyrics has them as null, which optString turns into "null".
    private fun org.json.JSONObject.lyric(name: String): String =
        if (isNull(name)) "" else optString(name, "").trim()

    private fun org.json.JSONObject.hasLyrics(): Boolean =
        lyric("plainLyrics").isNotBlank() || lyric("syncedLyrics").isNotBlank()
}

private fun MediaDataModel.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    val notifActions = getNotificationActions(notificationActions, activityStarter)
    val nextAction =
        playbackStateActions?.getActionById(R.id.actionNext)
            ?: playbackStateActions?.actionsOrCustom?.firstOrNull { it.isNextAction() }
            ?: notifActions.firstOrNull { it.isNextAction() }
            ?: notifActions.getOrNull(2)
    val previousAction =
        playbackStateActions?.getActionById(R.id.actionPrev)
            ?: playbackStateActions?.actionsOrCustom?.firstOrNull { it.isPrevAction() }
            ?: notifActions.firstOrNull { it.isPrevAction() }
            ?: notifActions.getOrNull(0)
    val playOrPauseAction =
        playbackStateActions?.getActionById(R.id.actionPlayPause)
            ?: playbackStateActions?.actionsOrCustom?.firstOrNull { it.isPlayOrPauseAction() }
            ?: notifActions.firstOrNull { it.isPlayOrPauseAction() }
            ?: notifActions.getOrNull(1)

    return MediaControlState(
        model =
            MediaControlChipModel(
                packageName = packageName,
                appIcon = appIcon,
                artworkIcon = background ?: appIcon,
                appName = appName,
                artistName = subtitle,
                songName = title,
                playOrPause = playOrPauseAction,
                nextAction = nextAction,
                previousAction = previousAction,
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
    val notifActions = getNotificationActions(actions, activityStarter)
    val nextAction =
        semanticActions?.getActionById(R.id.actionNext)
            ?: semanticActions?.actionsOrCustom?.firstOrNull { it.isNextAction() }
            ?: notifActions.firstOrNull { it.isNextAction() }
            ?: notifActions.getOrNull(2)
    val previousAction =
        semanticActions?.getActionById(R.id.actionPrev)
            ?: semanticActions?.actionsOrCustom?.firstOrNull { it.isPrevAction() }
            ?: notifActions.firstOrNull { it.isPrevAction() }
            ?: notifActions.getOrNull(0)
    val playOrPauseAction =
        semanticActions?.getActionById(R.id.actionPlayPause)
            ?: semanticActions?.actionsOrCustom?.firstOrNull { it.isPlayOrPauseAction() }
            ?: notifActions.firstOrNull { it.isPlayOrPauseAction() }
            ?: notifActions.getOrNull(1)

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

private val MediaButton.actionsOrCustom: List<MediaAction>
    get() = listOfNotNull(playOrPause, nextOrCustom, prevOrCustom, custom0, custom1)

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
    val isPlaying = playbackState?.isActivePlaybackState() == true
    val transportControls = transportControls

    val playOrPauseAction =
        playbackState?.toPlayPauseAction(context = context, controller = this)
            ?: if (isPlaying) {
                MediaAction(
                    icon = context.getDrawable(R.drawable.ic_media_pause_button),
                    action = Runnable { transportControls.pause() },
                    contentDescription = context.getString(R.string.controls_media_button_pause),
                    background = context.getDrawable(R.drawable.ic_media_pause_button_container),
                )
            } else {
                MediaAction(
                    icon = context.getDrawable(R.drawable.ic_media_play_button),
                    action = Runnable { transportControls.play() },
                    contentDescription = context.getString(R.string.controls_media_button_play),
                    background = context.getDrawable(R.drawable.ic_media_play_button_container),
                )
            }

    val nextAction =
        MediaAction(
            icon = context.getDrawable(R.drawable.ic_skip_next_filled),
            action = Runnable { transportControls.skipToNext() },
            contentDescription = context.getString(R.string.controls_media_button_next),
            background = null,
        )

    val previousAction =
        MediaAction(
            icon = context.getDrawable(R.drawable.ic_skip_previous_filled),
            action = Runnable { transportControls.skipToPrevious() },
            contentDescription = context.getString(R.string.controls_media_button_prev),
            background = null,
        )

    return PlaybackInfo(
        songName = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artistName =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        resolvedAtMs = SystemClock.elapsedRealtime(),
        durationMs = durationMs,
        positionMs = playbackState?.computeActualPosition(durationMs) ?: 0L,
        canSeek =
            (playbackState?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L || durationMs > 0L) && durationMs > 0L,
        isPlaying = isPlaying,
        playOrPause = playOrPauseAction,
        nextAction = nextAction,
        previousAction = previousAction,
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
    return when {
        NotificationMediaManager.isPlayingState(state) && supportsPlaybackAction(PlaybackState.ACTION_PAUSE) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_pause_button),
                action = Runnable { controller.togglePlayPause() },
                contentDescription = context.getString(R.string.controls_media_button_pause),
                background = context.getDrawable(R.drawable.ic_media_pause_button_container),
            )
        supportsPlaybackAction(PlaybackState.ACTION_PLAY) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_play_button),
                action = Runnable { controller.togglePlayPause() },
                contentDescription = context.getString(R.string.controls_media_button_play),
                background = context.getDrawable(R.drawable.ic_media_play_button_container),
            )
        else -> null
    }
}

// The action outlives the playback state it was built for, so decide at click time.
private fun MediaController.togglePlayPause() {
    if (playbackState?.let { NotificationMediaManager.isPlayingState(it.state) } == true) {
        transportControls.pause()
    } else {
        transportControls.play()
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
    val songName: String?,
    val artistName: String?,
    /** When [positionMs] was read, on the [SystemClock.elapsedRealtime] clock. */
    val resolvedAtMs: Long,
    val durationMs: Long,
    val positionMs: Long,
    val canSeek: Boolean,
    val isPlaying: Boolean,
    val playOrPause: MediaAction?,
    val nextAction: MediaAction?,
    val previousAction: MediaAction?,
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
        nextAction = playbackInfo.nextAction ?: nextAction,
        previousAction = playbackInfo.previousAction ?: previousAction,
        durationMs = durationMs.takeIf { it > 0L } ?: playbackInfo.durationMs,
        positionMs = playbackInfo.positionMs,
        positionUpdatedAtMs = playbackInfo.resolvedAtMs,
        canBeScrubbed = canBeScrubbed || playbackInfo.canSeek,
        isPlaying = playbackInfo.isPlaying,
    )
}
