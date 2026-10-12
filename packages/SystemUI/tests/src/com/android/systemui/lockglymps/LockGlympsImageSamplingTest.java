/*
 * Copyright (C) 2026 Evolution X contributors
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

package com.android.systemui.lockglymps;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Keeps untrusted downloaded/local images within the SystemUI decoded bitmap budget. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LockGlympsImageSamplingTest {
    @Test
    public void normalPhoneWallpaperKeepsResolution() {
        assertThat(LockGlympsService.calculateDecodeSampleForDisplay(
                1080, 2400, 1440, 2560)).isEqualTo(1);
    }

    @Test
    public void panoramaIsSampledDespiteShortSideBeingCloseToDisplaySize() {
        assertAtMostEightMillionPixels(16384, 3000);
    }

    @Test
    public void oversizedPortraitIsSampledBelowTheMemoryCeiling() {
        assertAtMostEightMillionPixels(4320, 7680);
    }

    @Test
    public void unusuallyLargeSquareIsSampledBelowTheMemoryCeiling() {
        assertAtMostEightMillionPixels(11000, 11000);
    }

    @Test
    public void invalidBoundsDoNotCauseDivisionByZero() {
        assertThat(LockGlympsService.calculateDecodeSampleForDisplay(
                0, 0, 1440, 2560)).isEqualTo(1);
    }

    private static void assertAtMostEightMillionPixels(int width, int height) {
        int sample = LockGlympsService.calculateDecodeSampleForDisplay(
                width, height, 1440, 2560);
        assertThat(sample).isAtLeast(1);
        assertThat((long) ((width + sample - 1) / sample)
                * ((height + sample - 1) / sample))
                .isAtMost(8_000_000L);
    }
}
