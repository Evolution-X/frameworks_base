/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2024-2026 Lunaris AOSP
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
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.roundToInt

class PulseEngine(
    private val context: Context,
    private val settingsRepo: PulseSettingsRepository,
    private val onDataProcessed: (FloatArray) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var fftAverage: Array<FFTAverage>? = null
    private var waveformAverage: Array<FFTAverage>? = null
    private val fudgeFactor = 20

    fun processFFT(data: ByteArray) {
        scope.launch {
            val barCount = settingsRepo.getBarCount()
            if (fftAverage == null || fftAverage!!.size != barCount) {
                fftAverage = Array(barCount) { FFTAverage() }
            }
            val heightMultiplier = settingsRepo.getHeightMultiplier()
            val output = FloatArray(barCount)
            for (i in 0 until barCount) {
                val realIndex = i * 2 + 2
                val imagIndex = i * 2 + 3
                if (realIndex >= data.size || imagIndex >= data.size) continue
                val rfk = data[realIndex].toInt()
                val ifk = data[imagIndex].toInt()
                val magnitude = (rfk * rfk + ifk * ifk).toFloat()
                var dbValue = if (magnitude > 0) (10 * log10(magnitude.toDouble())).toInt() else 0
                dbValue = fftAverage!![i].average(dbValue)
                output[i] = dbValue * fudgeFactor.toFloat() * heightMultiplier
            }
            withContext(Dispatchers.Main) {
                onDataProcessed(output)
            }
        }
    }

    fun processWaveform(data: ByteArray) {
        scope.launch {
            val barCount = settingsRepo.getBarCount()
            if (waveformAverage == null || waveformAverage!!.size != barCount) {
                waveformAverage = Array(barCount) { FFTAverage() }
            }
            val output = FloatArray(barCount)
            val samplesPerBar = (data.size / barCount).coerceAtLeast(1)
            val heightMultiplier = settingsRepo.getHeightMultiplier()

            for (i in 0 until barCount) {
                val start = i * samplesPerBar
                val end = (start + samplesPerBar).coerceAtMost(data.size)
                if (start >= data.size) continue

                var sum = 0
                for (j in start until end) {
                    val centered = (data[j].toInt() and 0xFF) - 128
                    sum += kotlin.math.abs(centered)
                }
                val avgAmplitude = if (end > start) sum / (end - start) else 0

                val smoothed = waveformAverage!![i].average(avgAmplitude)
                output[i] = smoothed * fudgeFactor.toFloat() * heightMultiplier
            }
            withContext(Dispatchers.Main) {
                onDataProcessed(output)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private class FFTAverage {
        companion object {
            private const val WINDOW_LENGTH = 2
        }

        private val window = ArrayDeque<Float>(WINDOW_LENGTH)
        private var average = 0f

        fun average(db: Int): Int {
            if (window.size >= WINDOW_LENGTH) {
                val removed = window.removeFirst()
                average -= removed
            }

            val newVal = db / WINDOW_LENGTH.toFloat()
            average += newVal
            window.addLast(newVal)

            return average.roundToInt()
        }
    }
}
