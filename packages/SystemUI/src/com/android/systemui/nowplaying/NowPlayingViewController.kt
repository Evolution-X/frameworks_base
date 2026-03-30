/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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
package com.android.systemui.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class NowPlayingViewController
@Inject
constructor(
    private val context: Context,
) : ScrimUtils.ScrimEventListener {

    private val nowPlayingView = NowPlayingView(context)
    private val settingsRepo = NowPlayingSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentSettings = settingsRepo.currentSettings()
    private var settingsJob: Job? = null

    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
    private var activeController: MediaController? = null
    private var bouncerShowingOrKeyguardDismissing = false
    private var currentTrackTitle: String = ""
    private var currentArtist: String = ""
    private var currentPackageName: String = ""
    private var isPlaying: Boolean = false

    private var isKeyguardShowing: Boolean = false
    private var isDozing: Boolean = false

    private val expandedOverlay: NowPlayingExpandedOverlay by lazy {
        NowPlayingExpandedOverlay(
            context = context,
            windowManager = context.getSystemService(WindowManager::class.java)!!,
            mediaSessionManager = mediaSessionManager,
        )
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    init {
        INSTANCE = this

        try {
            ScrimUtils.get()?.addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ScrimUtils listener", e)
        }
        
        observeSettings()
        
        startMediaMonitoring()

        nowPlayingView.isClickable = true
        nowPlayingView.isFocusable = true
        nowPlayingView.setOnClickListener {
            if (NowPlayingOverlayState.isOverlayOpen.value) {
                expandedOverlay.hide()
            } else {
                expandedOverlay.show()
            }
        }
    }

    fun getNowPlayingView(): FrameLayout = nowPlayingView

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settingsFlow
                .catch { e -> 
                    Log.e(TAG, "Error observing settings", e)
                }
                .collect { settings ->
                    currentSettings = settings
                    updateViewWithSettings(settings)
                }
        }
    }

    private fun updateViewWithSettings(settings: NowPlayingSettings) {
        val textColor = if (settings.useAccentColor) {
            Utils.getColorAccentDefaultColor(context)
        } else {
            0xFFFFFFFF.toInt()
        }
        
        nowPlayingView.apply {
            this.textColor = textColor
            iconStyle = settings.iconStyle
            iconSizeDp = settings.iconSize
            useCompactStyle = settings.useCompactStyle
            verticalPosition = settings.verticalPosition
            updateTextSize(settings.trackTextSize, settings.artistTextSize)
            NowPlayingOverlayState.update {
                copy(useWaveformSeekBar = settings.useWaveformSeekBar)
            }
        }
        
        updateVisibility()
    }

    private fun startMediaMonitoring() {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null
            )
            
            val controllers = mediaSessionManager.getActiveSessions(null)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting media monitoring", e)
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(mediaCallback)
        
        activeController = controllers?.firstOrNull()
        activeController?.registerCallback(mediaCallback)
        
        currentPackageName = activeController?.packageName ?: ""
        nowPlayingView.appPackageName = currentPackageName
        
        updateMetadata(activeController?.metadata)
        updatePlaybackState(activeController?.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        currentTrackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        currentArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        
        nowPlayingView.trackTitle = currentTrackTitle
        nowPlayingView.artistName = currentArtist

        val albumArt: Bitmap? = run {
            val meta = metadata ?: return@run null
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

        NowPlayingOverlayState.update {
            copy(
                track = currentTrackTitle,
                artist = currentArtist,
                packageName = currentPackageName,
                albumArt = albumArt,
                useWaveformSeekBar = currentSettings.useWaveformSeekBar,
            )
        }
        
        updateVisibility()
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val duration = activeController?.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = state?.position?.coerceAtLeast(0L) ?: 0L
        val progress = if (duration > 0L) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
        NowPlayingOverlayState.update {
            copy(
                isPlaying = this@NowPlayingViewController.isPlaying,
                duration = duration,
                position = pos,
                progress = progress,
                playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                positionUpdateTime = state?.lastPositionUpdateTime ?: 0L,
                packageName = currentPackageName,
            )
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        if (!currentSettings.isEnabled) {
            nowPlayingView.hide()
            return
        }

        val isPanelCollapsed = try {
            ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking panel state", e)
            true
        }

        val shouldShow = when {
            !isPlaying || currentTrackTitle.isEmpty() -> false
            bouncerShowingOrKeyguardDismissing -> false
            !isPanelCollapsed -> false
            isDozing -> currentSettings.showOnAod
            isKeyguardShowing -> currentSettings.showOnLockscreen
            else -> false
        }

        if (shouldShow) {
            nowPlayingView.show()
        } else {
            nowPlayingView.hide()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        isKeyguardShowing = showing
        updateVisibility()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        if (showing) {
            nowPlayingView.hide()
            expandedOverlay.hide()
        } else {
            updateVisibility()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) {
            nowPlayingView.hide()
            expandedOverlay.hide()
        } else {
            updateVisibility()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) {
            nowPlayingView.hide()
            expandedOverlay.hide()
        } else {
            updateVisibility()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        try {
            isDozing = ScrimUtils.get()?.isDozing() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error getting dozing state", e)
        }
        updateVisibility()
    }

    override fun setPulsing(pulsing: Boolean) {
        if (pulsing && currentSettings.showOnAod) {
            updateVisibility()
        }
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateVisibility()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateVisibility()
    }

    override fun onBarStateChanged(state: Int) {
        updateVisibility()
    }

    fun cleanup() {
        activeController?.unregisterCallback(mediaCallback)
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            ScrimUtils.get()?.removeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        expandedOverlay.hide()
        settingsJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "NowPlayingViewController"

        @Volatile
        private var INSTANCE: NowPlayingViewController? = null
        
        @JvmStatic
        fun get(context: Context): NowPlayingViewController {
            return INSTANCE ?: throw IllegalStateException(
                "NowPlayingViewController not initialized"
            )
        }
    }
}
