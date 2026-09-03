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

package com.android.systemui.statusbar.quickactions.media.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.ChipColors
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.flow.map

/**
 * [StatusBarPopupChipViewModel] for a media control chip in the status bar. This view model is
 * used to display a media control chip.
 */
class MediaControlChipViewModel
@AssistedInject
constructor(
    mediaControlChipInteractor: MediaControlChipInteractor,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    override val chip: QuickActionChipModel by
        mediaControlChipInteractor.mediaControlChipModel
            .map { model -> toPopupChipModel(model) }
            .hydratedStateOf(
                traceName = "chip",
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.MediaControl),
            )

    private fun toPopupChipModel(model: MediaControlChipModel?): QuickActionChipModel {
        if (model == null || model.songName.isNullOrEmpty()) {
            return QuickActionChipModel.Hidden(QuickActionChipId.MediaControl)
        }

        val contentDescription = model.appName?.let { ContentDescription.Loaded(description = it) }
        val defaultIcon =
            model.artworkIcon
                ?: model.appIcon
                ?: Icon.Resource(
                    resId = com.android.internal.R.drawable.ic_audio_media,
                    contentDescription = contentDescription,
                )

        val songTitle = normalizeSongTitle(model.songName.toString(), model.artistName?.toString())

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.MediaControl,
            icons = listOf(ChipIcon(icon = defaultIcon, onClick = model.openApp)),
            chipContent = ChipContent.Text(songTitle),
            colors = ChipColors.DynamicIsland,
            contentDescription = contentDescription,
            popupContent = PopupContentModel.Media(model),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): MediaControlChipViewModel
    }

    private fun normalizeSongTitle(title: String, artist: String?): String {
        if (title.isBlank()) return title
        val separatorRegex = Regex("\\s*[-–—|•]\\s*")
        val parts = title.split(separatorRegex).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val lowerArtist = artist?.lowercase(Locale.getDefault())?.trim()
            if (lowerArtist != null && lowerArtist.isNotEmpty()) {
                if (parts[0].lowercase(Locale.getDefault()) == lowerArtist) {
                    return parts.subList(1, parts.size).joinToString(" - ")
                }
                if (parts.last().lowercase(Locale.getDefault()) == lowerArtist) {
                    return parts.subList(0, parts.size - 1).joinToString(" - ")
                }
            }
            val parenArtistRegex = Regex("\\((?:feat|ft|with|by)[^)]*\\)", RegexOption.IGNORE_CASE)
            return title.replace(parenArtistRegex, "").trim()
        }
        return title
    }
}
