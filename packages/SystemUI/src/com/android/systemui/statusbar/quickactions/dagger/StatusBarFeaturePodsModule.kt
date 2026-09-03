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

package com.android.systemui.statusbar.quickactions.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.quickactions.assistant.data.repository.AssistantRepository
import com.android.systemui.statusbar.quickactions.assistant.data.repository.AssistantRepositoryImpl
import com.android.systemui.statusbar.quickactions.assistant.domain.interactor.AssistantIconInteractor
import com.android.systemui.statusbar.quickactions.assistant.domain.interactor.AssistantIconInteractorImpl
import com.android.systemui.statusbar.quickactions.media.MediaControlChipStartable
import com.android.systemui.statusbar.quickactions.sharescreen.domain.interactor.ShareScreenPrivacyIndicatorInteractor
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Similar in purpose to [StatusBarModule], but scoped only to feature pods. */
@Module
interface StatusBarFeaturePodsModule {
    @Binds
    @SysUISingleton
    fun bindAssistantRepository(impl: AssistantRepositoryImpl): AssistantRepository

    @Binds
    @SysUISingleton
    fun bindAssistantIconInteractor(impl: AssistantIconInteractorImpl): AssistantIconInteractor

    @Binds
    @IntoMap
    @ClassKey(ShareScreenPrivacyIndicatorInteractor::class)
    fun bindShareScreenPrivacyIndicatorInteractor(
        impl: ShareScreenPrivacyIndicatorInteractor
    ): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(MediaControlChipStartable::class)
    fun bindMediaControlChipStartable(
        impl: MediaControlChipStartable
    ): CoreStartable
}
