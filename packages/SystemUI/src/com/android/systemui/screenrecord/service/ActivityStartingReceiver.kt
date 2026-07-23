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

package com.android.systemui.screenrecord.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * This [BroadcastReceiver] serves as a bridge when System UI needs to start an activity exposing
 * user content from the system user. For example:
 * - content uri is filled by the user10;
 * - user0 needs to share this content via [Intent.createChooser] and [Intent.ACTION_SEND];
 *
 * This is when we need to proxy this intent via this receiver for the content to be available for
 * the app.
 */
class ActivityStartingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val optionsBundle: Bundle? = intent.getBundleExtra(EXTRA_OPTIONS_BUNDLE)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Received event in user=${context.userId}, action=${intent.action}")
        }
        val targetIntent = intent.getParcelableExtra(EXTRA_INTENT, Intent::class.java)
        if (targetIntent != null) {
            try {
                context.startActivity(targetIntent, optionsBundle)
            } catch (e: Exception) {
                Log.e(TAG, "No activity found to handle intent $targetIntent", e)
            }
        }
    }

    companion object {
        private const val TAG = "ActivityStartingReceiver"
        const val EXTRA_INTENT = "extra_intent"
        const val EXTRA_OPTIONS_BUNDLE = "extra_options_bundle"

        fun wrapIntent(context: Context, intent: Intent, optionsBundle: Bundle?): Intent =
            Intent(context, ActivityStartingReceiver::class.java)
                .putExtra(EXTRA_INTENT, intent)
                .putExtra(EXTRA_OPTIONS_BUNDLE, optionsBundle)
    }
}
