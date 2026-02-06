/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2024-2026 Lunaris AOSP
 *           (C) 2026 crDroid Android Project
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
package com.android.systemui.pulse

import android.content.Context
import android.media.session.PlaybackState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private val mainScope = MainScope()
    private var listenersRegistered = false

    private var isMediaPlaying = false
    private var bouncerShowingOrKeyguardDismissing = false
    private var keyguardShowing = false
    private var isDozing = false
    private var isPulsing = false
    private var isScreenOff = false

    private val settingsRepository: PulseSettingsRepository =
        PulseSettingsRepository(context)

    private val view: PulseView =
        PulseView(context)

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    private val bassHaptics: PulseBassHaptics =
        PulseBassHaptics(context)

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled()

    private val isCollapsed: Boolean
        get() = ScrimUtils.get().isPanelFullyCollapsed()

    private val hapticsMode: Int
        get() = settingsRepository.getPulseHapticsMode()

    var pulseRunning: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updatePulseDisplay(value)
        }

    private var showDelayJob: Job? = null
    private var hideDelayJob: Job? = null
    
    private val PULSE_SHOW_DELAY_MS = 300L
    private val PULSE_HIDE_DELAY_MS = 100L
    private val PULSE_FADE_IN_DURATION_MS = 200L
    private val PULSE_FADE_OUT_DURATION_MS = 150L

    init {
        INSTANCE = this

        view.initialize(settingsRepository)
        settingsRepository.setOnSettingsChangedListener { onSettingsChanged() }
        settingsRepository.startObserving()
        onSettingsChanged()
    }

    fun getPulseView(): PulseView = view

    private fun updateState() {
        if (!pulseEnabled) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            pulseRunning = false
            return
        }
        
        val shouldShow = isMediaPlaying 
                && !bouncerShowingOrKeyguardDismissing
                && isCollapsed
                && !isScreenOff
                && ((keyguardShowing && !isDozing && !isPulsing)
                || ((isDozing || isPulsing) && settingsRepository.isPulseShowOnAmbient()))
        
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        
        if (shouldShow && !pulseRunning) {
            showDelayJob = mainScope.launch {
                delay(PULSE_SHOW_DELAY_MS)
                if (isMediaPlaying 
                    && !bouncerShowingOrKeyguardDismissing
                    && isCollapsed
                    && ((keyguardShowing && !isDozing && !isPulsing)
                    || ((isDozing || isPulsing) && settingsRepository.isPulseShowOnAmbient()))) {
                    pulseRunning = true
                }
            }
        } else if (!shouldShow && pulseRunning) {
            hideDelayJob = mainScope.launch {
                delay(PULSE_HIDE_DELAY_MS)
                if (!(isMediaPlaying 
                    && !bouncerShowingOrKeyguardDismissing
                    && isCollapsed
                    && ((keyguardShowing && !isDozing && !isPulsing)
                    || ((isDozing || isPulsing) && settingsRepository.isPulseShowOnAmbient())))) {
                    pulseRunning = false
                }
            }
        }
    }

    private fun onSettingsChanged() {
        val enabled = pulseEnabled
        if (enabled && !listenersRegistered) {
            ScrimUtils.get().addListener(this)
            MediaSessionManager.get().addListener(this)
            listenersRegistered = true
        } else if (!enabled && listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            listenersRegistered = false
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            pulseRunning = false
            mainScope.launch {
                view.setVisibility(false)
                audioProcessor.stopCapture()
            }
        }
        updateState()
        // Force update
        updatePulseDisplay(pulseRunning)
    }

    private fun updatePulseDisplay(show: Boolean) {
        mainScope.launch {
            view.setVisibility(show)
            if (pulseEnabled && (show || hapticsMode > 1)) {
                audioProcessor.startCapture()
                view.fadeIn(PULSE_FADE_IN_DURATION_MS)
            } else {
                view.fadeOut(PULSE_FADE_OUT_DURATION_MS) {
                    audioProcessor.stopCapture()
                    bassHaptics.reset()
                }
            }
        }
    }

    override fun onDataUpdate(data: PulseData) {
        if (hapticsMode > 0) {
            bassHaptics.process(data.fftBytes)
        }
        if (pulseRunning) {
            mainScope.launch { 
                view.updateVisualizerData(data) 
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        isMediaPlaying = state == PlaybackState.STATE_PLAYING
        updateState()
    }

    override fun onMediaColorsChanged(color: Int) {
        if (pulseEnabled) view.onMediaColorsChanged(color)
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        keyguardShowing = showing
        updateState()
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun setPulsing(pulsing: Boolean) {
        isPulsing = pulsing
        updateState()
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            pulseRunning = false
        } else {
            updateState()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            pulseRunning = false
        } else {
            updateState()
        }
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        updateState()
    }

    override fun onScreenTurnedOff() {
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        pulseRunning = false
        isScreenOff = true
        updateState()
    }

    override fun onStartedWakingUp() {
        isScreenOff = false
        updateState()
    }

    override fun onUserChanged() {
        settingsRepository.invalidateCache()
        bassHaptics.reset()
        updateState()
    }

    fun destroy() {
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        pulseRunning = false
        settingsRepository.stopObserving()
        if (listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            listenersRegistered = false
        }
        audioProcessor.cleanup()
        bassHaptics.reset()
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "PulseViewController"

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException(
                "PulseViewController not initialized"
            )
        }
    }
}
