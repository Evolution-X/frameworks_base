package com.android.systemui.pulse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class PulseAudioBridge @Inject constructor(
    private val context: Context,
    private val settingsRepository: PulseSettingsRepository,
) {
    private val pulseEnabledState = mutableStateOf(
    settingsRepository.isPulseEnabled() && settingsRepository.isPulseMediaEnabled()
    )
    val pulseEnabled: Boolean by pulseEnabledState

    private var activeClientCount = 0
    private var onPulseEnabledChanged: (() -> Unit)? = null

    val renderer: PulseRenderer by lazy { PulseRenderer(context, settingsRepository) }

    private val engine: PulseEngine by lazy {
        PulseEngine(context, settingsRepository) { heights ->
            renderer.updateHeights(heights)
        }
    }

    fun setOnPulseEnabledChangedListener(listener: () -> Unit) {
        onPulseEnabledChanged = listener
    }

    private val dataListener = object : PulseAudioDataProcessor.DataListener {
        override fun onDataUpdate(data: PulseData) {
            when (audioProcessor.captureMode) {
                PulseAudioDataProcessor.CaptureMode.FFT ->
                    data.fftBytes?.let { engine.processFFT(it) }
                PulseAudioDataProcessor.CaptureMode.WAVEFORM ->
                    data.waveformBytes?.let { engine.processWaveform(it) }
            }
        }
    }

    private val audioProcessor: PulseAudioDataProcessor by lazy {
        PulseAudioDataProcessor(context).apply {
            captureMode = settingsRepository.getCaptureMode()
            setDataListener(dataListener)
        }
    }

    init {
        settingsRepository.setOnSettingsChangedListener {
            val newEnabled = settingsRepository.isPulseEnabled() && settingsRepository.isPulseMediaEnabled()
            if (newEnabled != pulseEnabledState.value) {
                pulseEnabledState.value = newEnabled
                onPulseEnabledChanged?.invoke()
            }
            audioProcessor.captureMode = settingsRepository.getCaptureMode()
        }
        settingsRepository.startObserving()
    }

    fun acquire() {
        activeClientCount++
        if (!pulseEnabled) return
        if (!audioProcessor.isCapturing()) audioProcessor.startCapture()
    }

    fun release() {
        activeClientCount = (activeClientCount - 1).coerceAtLeast(0)
        if (activeClientCount == 0) {
            audioProcessor.stopCapture()
        }
    }

    fun destroy() {
        engine.stop()
        audioProcessor.cleanup()
        renderer.cleanup()
    }

    fun onMediaColorsChanged(color: Int) {
        renderer.onMediaColorsChanged(color)
    }
}
