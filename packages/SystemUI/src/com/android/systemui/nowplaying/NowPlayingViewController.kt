/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.palette.graphics.Palette
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.nowplaying.ambient.NowPlayingAmbientContainer
import com.android.systemui.nowplaying.ambient.NowPlayingAmbientViewModel
import com.android.systemui.nowplaying.ambient.PixelAmbientIndicationDetector
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.util.ScrimUtils
import com.android.systemui.statusbar.phone.LyricsFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class NowPlayingViewController
@Inject
constructor(
    private val context: Context,
    private val ambientViewModel: NowPlayingAmbientViewModel,
    private val falsingManager: FalsingManager,
    private val powerInteractor: PowerInteractor,
) : ScrimUtils.ScrimEventListener {

    private val nativeAmbientIndicationAvailable: Boolean by lazy {
        PixelAmbientIndicationDetector.shouldUseNativeAmbientIndication(context)
    }

    private val ambientContainer: NowPlayingAmbientContainer by lazy {
        NowPlayingAmbientContainer.inflate(context).apply {
            id = R.id.now_playing_view
        }
    }
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
    private var isScreenOff: Boolean = false

    private var isKeyguardShowing: Boolean = false
    private var isDozing: Boolean = false

    private var showDelayJob: Job? = null
    private var hideDelayJob: Job? = null

    private var controllerResyncJob: Job? = null

    private var lyricsCallbackRegistered = false

    private val lyricsFetcher: LyricsFetcher by lazy { LyricsFetcher.getInstance(context) }

    private val lyricsCallback = object : LyricsFetcher.Callback {
        override fun onSyncedLineChanged(prevLine: String?, currentLine: String?, nextLine: String?) {
            ambientContainer.setLyricLine(currentLine)
        }

        override fun onPlainLyricsAvailable(plainLyrics: String) {
            ambientContainer.setLyricLine(plainLyrics)
        }

        override fun onLyricsCleared() {
            ambientContainer.setLyricLine(null)
        }
    }

    private companion object {
        private const val TAG = "NowPlayingViewController"
        private const val SHOW_DELAY_MS = 300L
        private const val HIDE_DELAY_MS = 150L
        private const val CONTROLLER_RESYNC_INTERVAL_MS = 3000L

        @Volatile
        private var INSTANCE: NowPlayingViewController? = null

        @JvmStatic
        fun get(context: Context): NowPlayingViewController {
            return INSTANCE ?: throw IllegalStateException(
                "NowPlayingViewController not initialized"
            )
        }
    }

    private var currentAlbumArtColor: Int? = null

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            refreshActiveController()
        }
    }

    private fun refreshActiveController() {
        try {
            updateActiveController(mediaSessionManager.getActiveSessions(null))
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active controller", e)
        }
    }

    private fun startControllerResync() {
        if (controllerResyncJob?.isActive == true) return
        controllerResyncJob = scope.launch {
            while (true) {
                delay(CONTROLLER_RESYNC_INTERVAL_MS)
                refreshActiveController()
            }
        }
    }

    private fun stopControllerResync() {
        controllerResyncJob?.cancel()
        controllerResyncJob = null
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    init {
        INSTANCE = this

        if (!nativeAmbientIndicationAvailable) {
            try {
                ScrimUtils.get()?.addListener(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding ScrimUtils listener", e)
            }

            observeSettings()
            observeBurnInTranslation()
            observeRootViewTapOutside()

            startMediaMonitoring()

            ambientContainer.isClickable = true
            ambientContainer.isFocusable = true
            ambientContainer.falsingManager = falsingManager
            ambientContainer.powerInteractor = powerInteractor
        }
    }

    fun getNowPlayingView(): View = ambientContainer

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

    /**
     * Applies AOD burn-in-protection translation to the ambient pill, keeping it in sync with
     * the rest of the keyguard's burn-in-protected elements instead of sitting static in one
     * spot for hours during doze.
     */
    private fun observeBurnInTranslation() {
        scope.launch {
            ambientContainer.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        ambientViewModel.translationX.collect { x ->
                            ambientContainer.translationX = x
                        }
                    }
                    launch {
                        ambientViewModel.translationY.collect { y ->
                            ambientContainer.translationY = y
                        }
                    }
                }
            }
        }
    }

    /**
     * Collapses the expanded album-art card when the user taps anywhere on the keyguard root
     * view outside the pill/card's own bounds, matching Google's tap-outside-to-collapse
     * behavior for the Pixel ambient indication.
     */
    private fun observeRootViewTapOutside() {
        scope.launch {
            ambientContainer.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        ambientViewModel.rootViewTapPosition.collect { point ->
                            val rect = Rect()
                            ambientContainer.getHitRect(rect)
                            if (!rect.contains(point.x, point.y)) {
                                ambientContainer.collapseIfExpanded()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateViewWithSettings(settings: NowPlayingSettings) {
        val textColor = resolveTextColor(settings.colorMode)

        setLyricsFetcherEnabled(settings.useLyricsMode)

        if (settings.isEnabled) {
            startControllerResync()
        } else {
            stopControllerResync()
        }

        ambientContainer.apply {
            setTextColor(textColor)
            setIconStyle(settings.iconStyle, settings.iconSize)
            setTrackAndArtistTextSize(settings.trackTextSize, settings.artistTextSize)
            setLyricsModeEnabled(settings.useLyricsMode)
            setExpandOnTap(settings.tapToExpand)
        }

        updateState()
    }

    private fun setLyricsFetcherEnabled(enabled: Boolean) {
        if (enabled && !lyricsCallbackRegistered) {
            lyricsCallbackRegistered = true
            lyricsFetcher.addCallback(lyricsCallback)
        } else if (!enabled && lyricsCallbackRegistered) {
            lyricsCallbackRegistered = false
            lyricsFetcher.removeCallback(lyricsCallback)
            ambientContainer.setLyricLine(null)
        }
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
        val newController = controllers?.firstOrNull()
        val isSameController = activeController != null && newController != null
                && activeController?.packageName == newController.packageName
                && activeController?.sessionToken == newController.sessionToken
        val bothNull = activeController == null && newController == null

        if (isSameController || bothNull) {
            return
        }

        activeController?.unregisterCallback(mediaCallback)

        activeController = newController
        activeController?.registerCallback(mediaCallback)

        currentPackageName = activeController?.packageName ?: ""
        ambientContainer.setAppPackageName(currentPackageName)

        updateMetadata(activeController?.metadata)
        updatePlaybackState(activeController?.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        currentTrackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        currentArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        val albumArt: Bitmap? = run {
            val meta = metadata ?: return@run null
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

        if (albumArt != null) {
            scope.launch(Dispatchers.IO) {
                val extracted = extractAlbumArtColor(albumArt)
                withContext(Dispatchers.Main) {
                    currentAlbumArtColor = extracted
                    ambientContainer.setTextColor(resolveTextColor(currentSettings.colorMode))
                }
            }
        } else {
            currentAlbumArtColor = null
        }

        ambientContainer.setTrackAndArtist(currentTrackTitle, currentArtist, albumArt)

        updateState()
    }

    private fun resolveTextColor(colorMode: Int): Int =
        when (colorMode) {
            NowPlayingSettingsRepository.COLOR_MODE_ACCENT ->
                Utils.getColorAccentDefaultColor(context)
            NowPlayingSettingsRepository.COLOR_MODE_ALBUM ->
                currentAlbumArtColor?.let { boostSaturation(it) }
                    ?: 0xFFFFFFFF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    private fun extractAlbumArtColor(bitmap: Bitmap): Int? {
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
            ?: return null
        return swatch.rgb
    }

    private fun boostSaturation(color: Int, amount: Float = 0.20f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] + amount).coerceAtMost(1.0f)
        return Color.HSVToColor(hsv)
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        isPlaying = state?.state == PlaybackState.STATE_PLAYING
        updateState()
    }

    private fun updateState() {
        if (!currentSettings.isEnabled) {
            cancelDebounceJobs()
            ambientContainer.hideAmbient()
            return
        }

        val isPanelCollapsed = try {
            ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking panel state", e)
            true
        }

        val shouldShow = isPlaying
                && currentTrackTitle.isNotEmpty()
                && !bouncerShowingOrKeyguardDismissing
                && isPanelCollapsed
                && !isScreenOff
                && ((isKeyguardShowing && !isDozing && currentSettings.showOnLockscreen)
                        || (isDozing && currentSettings.showOnAod))

        if (shouldShow) {
            hideDelayJob?.cancel()
            hideDelayJob = null
            if (ambientContainer.isAmbientVisible) return
            showDelayJob?.cancel()
            showDelayJob = scope.launch {
                delay(SHOW_DELAY_MS)
                val stillCollapsed = try {
                    ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
                } catch (e: Exception) { true }
                if (isPlaying
                        && currentTrackTitle.isNotEmpty()
                        && !bouncerShowingOrKeyguardDismissing
                        && stillCollapsed
                        && !isScreenOff
                        && ((isKeyguardShowing && !isDozing && currentSettings.showOnLockscreen)
                                || (isDozing && currentSettings.showOnAod))) {
                    ambientContainer.showAmbient()
                }
                showDelayJob = null
            }
        } else {
            showDelayJob?.cancel()
            showDelayJob = null
            if (!ambientContainer.isAmbientVisible) return
            hideDelayJob?.cancel()
            hideDelayJob = scope.launch {
                delay(HIDE_DELAY_MS)
                val stillCollapsed = try {
                    ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
                } catch (e: Exception) { true }
                if (!(isPlaying
                            && currentTrackTitle.isNotEmpty()
                            && !bouncerShowingOrKeyguardDismissing
                            && stillCollapsed
                            && !isScreenOff
                            && ((isKeyguardShowing && !isDozing && currentSettings.showOnLockscreen)
                                    || (isDozing && currentSettings.showOnAod)))) {
                    ambientContainer.hideAmbient()
                }
                hideDelayJob = null
            }
        }
    }

    private fun forceHide() {
        cancelDebounceJobs()
        ambientContainer.hideAmbient()
    }

    private fun cancelDebounceJobs() {
        showDelayJob?.cancel()
        showDelayJob = null
        hideDelayJob?.cancel()
        hideDelayJob = null
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        isKeyguardShowing = showing
        updateState()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        if (showing) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun setPulsing(pulsing: Boolean) {
        if (pulsing && currentSettings.showOnAod) {
            updateState()
        }
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onScreenTurnedOff() {
        isScreenOff = true
        forceHide()
    }

    override fun onStartedWakingUp() {
        isScreenOff = false
        refreshActiveController()
        updateState()
    }

    fun cleanup() {
        if (nativeAmbientIndicationAvailable) return
        activeController?.unregisterCallback(mediaCallback)
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            ScrimUtils.get()?.removeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        cancelDebounceJobs()
        settingsJob?.cancel()
        scope.cancel()
    }
}
