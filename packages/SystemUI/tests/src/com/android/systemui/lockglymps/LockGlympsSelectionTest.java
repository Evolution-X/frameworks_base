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

import java.io.File;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression tests for wallpaper topic matching and recently applied identities. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LockGlympsSelectionTest {
    @Test
    public void separateSelectedTopicsDoNotShareWallpaperCacheNamespace() {
        String mountain = LockGlympsService.cacheQueryPrefix("openverse", "mountains dark", "1080", "portrait");
        String flowers = LockGlympsService.cacheQueryPrefix("openverse", "flowers dark", "1080", "portrait");
        assertThat(mountain).isNotEqualTo(flowers);
        assertThat(mountain).startsWith("glymps_openverse_");
        assertThat(flowers).startsWith("glymps_openverse_");
    }

    @Test
    public void sameSelectionUsesStableNamespaceAcrossServiceRestarts() {
        String topic = "minimal architecture";
        assertThat(LockGlympsService.cacheQueryPrefix("wallhaven", topic, "1080", "portrait"))
                .isEqualTo(LockGlympsService.cacheQueryPrefix("wallhaven", topic, "1080", "portrait"));
        assertThat(LockGlympsService.cacheQueryPrefix("wallhaven", topic, "1080", "portrait"))
                .isNotEqualTo(LockGlympsService.cacheQueryPrefix("openverse", topic, "1080", "portrait"));
    }

    @Test
    public void changingNativeResolutionInvalidatesPreviouslyCachedSelection() {
        String base = LockGlympsService.cacheQueryPrefix(
                "openverse", "night sky", "1080", "portrait");
        String higher = LockGlympsService.cacheQueryPrefix(
                "openverse", "night sky", "4320", "portrait");
        assertThat(base).isNotEqualTo(higher);
    }

    @Test
    public void changingOrientationInvalidatesPreviouslyCachedSelection() {
        String portrait = LockGlympsService.cacheQueryPrefix(
                "wallhaven", "nature", "2160", "portrait");
        String landscape = LockGlympsService.cacheQueryPrefix(
                "wallhaven", "nature", "2160", "landscape");
        assertThat(portrait).isNotEqualTo(landscape);
    }

    @Test
    public void recoverOnlyTheImageIdentityNotTheTimestamp() {
        String prefix = LockGlympsService.cacheQueryPrefix("openverse", "forest", "1080", "portrait");
        File cached = new File(prefix + "abc123_1791780000000.img");
        assertThat(LockGlympsService.cachedHistoryKey(cached)).isEqualTo("seen:abc123");
    }

    @Test
    public void native4kAnd8kRequireActualSourcePixels() {
        assertThat(LockGlympsService.meetsNativeResolution(
                3840, 2160, "2160")).isTrue();
        assertThat(LockGlympsService.meetsNativeResolution(
                1920, 1080, "2160")).isFalse();
        assertThat(LockGlympsService.meetsNativeResolution(
                7680, 4320, "4320")).isTrue();
        assertThat(LockGlympsService.meetsNativeResolution(
                7680, 4319, "4320")).isFalse();
        assertThat(LockGlympsService.meetsNativeResolution(
                3840, 2160, "4320")).isFalse();
        assertThat(LockGlympsService.meetsNativeResolution(
                4320, 7680, "4320")).isTrue();
    }

    @Test
    public void higherResolutionRemainsSubjectToBoundedDataCaps() {
        assertThat(LockGlympsService.downloadLimitForResolution("auto"))
                .isEqualTo(12L * 1024L * 1024L);
        assertThat(LockGlympsService.downloadLimitForResolution("2160"))
                .isEqualTo(16L * 1024L * 1024L);
        assertThat(LockGlympsService.downloadLimitForResolution("4320"))
                .isEqualTo(24L * 1024L * 1024L);
    }

    @Test
    public void rejectInvalidCacheFileSuffix() {
        assertThat(LockGlympsService.cachedHistoryKey(new File("bad.jpg"))).isNull();
    }
}
