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

package com.android.systemui.qs.composefragment.ui

import androidx.compose.animation.core.FastOutLinearInEasing
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.qs.shared.ui.QuickSettings.Elements

/** End of the progress range in which the QQS brightness slider fades out (ColorOS style). */
private const val QQS_BRIGHTNESS_FADE_END = 0.3f

/** End of the progress range in which the QQS tiles fade out (ColorOS style). */
private const val QQS_TILES_FADE_END = 0.3f

/**
 * @param animateTilesExpansion whether the tiles morph from QQS to QS.
 * @param fadeOutBrightnessSlider whether the QQS brightness slider just fades out instead of
 *   morphing into the QS one. Used by the ColorOS style, whose QS panel has its own tall slider
 *   and therefore no brightness slider element for the QQS one to move to.
 * @param fadeOutQqsTiles whether the QQS tiles fade out early where they are instead of fading and
 *   moving to the grid anchor. Used by the ColorOS style, whose panel does not have the grid they
 *   would move to, so they would otherwise fly over the panel while it fades in.
 */
fun TransitionBuilder.quickQuickSettingsToQuickSettings(
    animateTilesExpansion: () -> Boolean = { true },
    fadeOutBrightnessSlider: () -> Boolean = { false },
    fadeOutQqsTiles: () -> Boolean = { false },
) {

    fractionRange(start = 0.43f) { fade(Elements.QuickSettingsContent) }

    fractionRange(start = 0.9f) { fade(Elements.FooterActions) }

    anchoredTranslate(Elements.QuickSettingsContent, Elements.GridAnchor)

    sharedElement(Elements.TileElementMatcher, enabled = animateTilesExpansion())
    if (fadeOutBrightnessSlider()) {
        // Nothing to morph into: without this the QQS slider stays fully opaque for the whole
        // swipe and draws on top of the incoming ColorOS panel. Fade it out early with an
        // accelerating curve, so it is gone before the panel content starts to appear (0.43f).
        // The progress is mirrored when collapsing, so it fades back in at the end of the swipe.
        fractionRange(end = QQS_BRIGHTNESS_FADE_END, easing = FastOutLinearInEasing) {
            fade(Elements.BrightnessSlider)
        }
    } else {
        sharedElement(Elements.BrightnessSlider)
    }

    if (fadeOutQqsTiles()) {
        // The ColorOS panel starts to appear at 0.43f, and the QQS tiles would still be visible and
        // sliding over it until 0.5f. Fade them out early with an accelerating curve, so they are
        // gone before it does, and leave them where they are. The progress is mirrored when
        // collapsing, so they fade back in at the end of the swipe.
        fractionRange(end = QQS_TILES_FADE_END, easing = FastOutLinearInEasing) {
            fade(SceneKeys.QqsTileElementMatcher)
        }
    } else {
        // This will animate between 0f (QQS) and 0.5, fading in the QQS tiles when coming back
        // from non first page QS. The QS content ends fading out at 0.43f, so there's a brief
        // overlap, but because they are really faint, it looks better than complete black without
        // overlap.
        fractionRange(end = 0.5f) { fade(SceneKeys.QqsTileElementMatcher) }
        anchoredTranslate(SceneKeys.QqsTileElementMatcher, Elements.GridAnchor)
    }
}
