/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.media

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A [CoreStartable] that initializes and starts the media control chip functionality. */
@SysUISingleton
class MediaControlChipStartable
@Inject
constructor(
    @Background val bgScope: CoroutineScope,
    private val mediaControlChipInteractor: MediaControlChipInteractor,
) : CoreStartable {

    override fun start() {
        bgScope.launch { mediaControlChipInteractor.initialize() }
    }
}
