/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.common

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.framework.util.SystemProperties
import com.android.settingslib.spa.restricted.RestrictedRepository
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

private const val TAG = "SpaEnvironment"

object SpaEnvironmentFactory {
    private var spaEnvironment: SpaEnvironment? = null

    /**
     * Resets the SpaEnvironment to the given instance, which is usually required step to set up
     * SPA.
     *
     * This is usually be called in an Application class, but could be called in app initializors or
     * setup listeners.
     */
    fun reset(env: SpaEnvironment) {
        spaEnvironment = env
        Log.d(TAG, "reset")
    }

    @Composable
    fun resetForPreview() {
        val context = LocalContext.current
        spaEnvironment = object : SpaEnvironment(context) {
            override val pageProviderRepository = lazy {
                SettingsPageProviderRepository(
                    allPageProviders = emptyList(),
                    rootPages = emptyList()
                )
            }
        }
        Log.d(TAG, "resetForPreview")
    }

    fun isReady(): Boolean = spaEnvironment != null

    val instance: SpaEnvironment
        get() {
            if (spaEnvironment == null)
                throw UnsupportedOperationException("Spa environment is not set")
            return spaEnvironment!!
        }

    /**
     * Optional instance of SpaEnvironment.
     *
     * Useful when there is fallback logic.
     */
    internal val optionalInstance: SpaEnvironment?
        get() = spaEnvironment

    @VisibleForTesting
    internal fun clear() {
        spaEnvironment = null
    }
}

/**
 * The environment of SPA.
 *
 * This class is used to hold the global configurations of SPA.
 *
 * To set up SpaEnvironment,
 * 1. create a concrete class that extends [SpaEnvironment].
 * 2. call [SpaEnvironmentFactory.reset] with your implementation to set the global environment.
 */
abstract class SpaEnvironment(context: Context) {
    /** The repository of all page providers, SPA pages are setup here. */
    abstract val pageProviderRepository: Lazy<SettingsPageProviderRepository>

    val entryRepository = lazy { SettingsEntryRepository(pageProviderRepository.value) }

    // The application context. Use local context as fallback when applicationContext is not
    // available (e.g. in Robolectric test).
    val appContext: Context = context.applicationContext ?: context

    // Set your SpaLogger implementation, for any SPA events logging.
    open val logger: SpaLogger = object : SpaLogger {}

    // Specify class name of browse activity, which is used to
    // generate the necessary intents.
    open val browseActivityClass: Class<out Activity>? = null

    // Specify provider authorities for debugging purpose.
    open val searchProviderAuthorities: String? = null

    /** Specify default dispatcher. */
    open val defaultDispatcher: CoroutineContext = Dispatchers.Default

    /** Specify whether expressive design is enabled. */
    open val isSpaExpressiveEnabled by lazy {
        SystemProperties.getBoolean("is_expressive_design_enabled", true)
    }

    /** Specify the [RestrictedRepository]. */
    open fun getRestrictedRepository(context: Context): RestrictedRepository? = null

    companion object {
        /**
         * Whether debug mode is on or off.
         *
         * If set to true, this will also enable all the pages under development (allows browsing
         * and searching).
         */
        const val IS_DEBUG = false
    }
}
