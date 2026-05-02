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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardSmartspaceRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.shade.domain.interactor.enableSingleShade
import com.android.systemui.shade.domain.interactor.enableSplitShade
import com.android.systemui.statusbar.lockscreen.lockscreenSmartspaceController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardSmartspaceViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { keyguardSmartspaceViewModel }

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        kosmos.fakeKeyguardClockRepository.setCurrentClock(clockController)
    }

    @Test
    fun testWhenWeatherEnabled_notCustomWeatherDataDisplay_isWeatherVisible_shouldBeTrue() =
        kosmos.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            keyguardSmartspaceRepository.setIsWeatherEnabled(true)
            keyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(isWeatherVisible).isEqualTo(true)
        }

    @Test
    fun testWhenWeatherEnabled_notCustomWeatherDataDisplay_isWeatherVisible_smallClock_shouldBeTrue() =
        kosmos.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.smallClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            keyguardSmartspaceRepository.setIsWeatherEnabled(true)
            keyguardClockRepository.setClockSize(ClockSize.SMALL)

            assertThat(isWeatherVisible).isEqualTo(true)
        }

    @Test
    fun testWhenWeatherEnabled_hasCustomWeatherDataDisplay_isWeatherVisible_shouldBeFalse() =
        kosmos.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay).thenReturn(true)

            keyguardSmartspaceRepository.setIsWeatherEnabled(true)
            keyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(isWeatherVisible).isEqualTo(false)
        }

    @Test
    fun testWhenWeatherEnabled_hasCustomWeatherDataDisplay_isWeatherVisible_smallClock_shouldBeTrue() =
        kosmos.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.smallClock.config.hasCustomWeatherDataDisplay).thenReturn(true)

            keyguardSmartspaceRepository.setIsWeatherEnabled(true)
            keyguardClockRepository.setClockSize(ClockSize.SMALL)

            assertThat(isWeatherVisible).isEqualTo(true)
        }

    @Test
    fun testWhenWeatherEnabled_notCustomWeatherDataDisplay_notIsWeatherVisible_shouldBeFalse() =
        kosmos.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            keyguardSmartspaceRepository.setIsWeatherEnabled(false)
            keyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(isWeatherVisible).isEqualTo(false)
        }

    @Test
    fun isDateVisible_notCustomWeatherDataDisplay_largeClock_shouldBeTrue() =
        kosmos.runTest {
            val isDateVisible by collectLastValue(underTest.isDateVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            keyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(isDateVisible).isEqualTo(true)
        }

    @Test
    fun isDateVisible_hasCustomWeatherDataDisplay_largeClock_shouldBeFalse() =
        kosmos.runTest {
            val isDateVisible by collectLastValue(underTest.isDateVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay).thenReturn(true)

            keyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(isDateVisible).isEqualTo(false)
        }

    @Test
    fun isDateVisible_hasCustomWeatherDataDisplay_smallClock_shouldBeTrue() =
        kosmos.runTest {
            val isDateVisible by collectLastValue(underTest.isDateVisible)
            whenever(clockController.smallClock.config.hasCustomWeatherDataDisplay).thenReturn(true)

            keyguardClockRepository.setClockSize(ClockSize.SMALL)

            assertThat(isDateVisible).isEqualTo(true)
        }

    @Test
    fun isDateVisible_notCustomWeatherDataDisplay_smallClock_shouldBeTrue() =
        kosmos.runTest {
            val isDateVisible by collectLastValue(underTest.isDateVisible)
            whenever(clockController.smallClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            keyguardClockRepository.setClockSize(ClockSize.SMALL)

            assertThat(isDateVisible).isEqualTo(true)
        }

    @Test
    fun isFullWidthShade_withConfigTrue_false() =
        kosmos.runTest {
            val isFullWidthShade by collectLastValue(underTest.isFullWidthShade)
            enableSplitShade()

            assertThat(isFullWidthShade).isFalse()
        }

    @Test
    fun isFullWidthShade_withConfigFalse_true() =
        kosmos.runTest {
            val isFullWidthShade by collectLastValue(underTest.isFullWidthShade)
            enableSingleShade()

            assertThat(isFullWidthShade).isTrue()
        }

    @Test
    fun isSmartspaceEnabled_reflectsCurrentControllerState() =
        kosmos.runTest {
            whenever(lockscreenSmartspaceController.isEnabled).thenReturn(true)

            assertThat(underTest.isSmartspaceEnabled).isTrue()

            whenever(lockscreenSmartspaceController.isEnabled).thenReturn(false)

            assertThat(underTest.isSmartspaceEnabled).isFalse()
        }
}
