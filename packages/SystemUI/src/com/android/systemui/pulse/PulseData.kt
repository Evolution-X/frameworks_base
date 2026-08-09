/*
 * Copyright (C) 2025 The AxionAOSP Project
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

interface PulseData {
    val fftBytes: ByteArray?
    val waveformBytes: ByteArray?
    val isDataValid: Boolean

    fun updateFFTData(bytes: ByteArray)
    fun updateWaveformData(bytes: ByteArray)
    fun reset()
}

class PulseFFTData : PulseData {
    private var _fftBytes: ByteArray? = null
    private var _waveformBytes: ByteArray? = null

    override val fftBytes: ByteArray?
        get() = _fftBytes

    override val waveformBytes: ByteArray?
        get() = _waveformBytes

    override val isDataValid: Boolean
        get() = (_fftBytes != null && _fftBytes!!.isNotEmpty()) ||
                (_waveformBytes != null && _waveformBytes!!.isNotEmpty())

    override fun updateFFTData(bytes: ByteArray) {
        _fftBytes = bytes.copyOf()
    }

    override fun updateWaveformData(bytes: ByteArray) {
        _waveformBytes = bytes.copyOf()
    }

    override fun reset() {
        _fftBytes = null
        _waveformBytes = null
    }
}
