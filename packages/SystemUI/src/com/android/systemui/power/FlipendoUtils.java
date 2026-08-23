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

package com.android.systemui.power;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * Client for the Flipendo (Extreme Battery Saver) content provider API.
 *
 * <p>Flipendo ships as a separate app exposing the {@code com.google.android.flipendo.api}
 * content provider:
 * <ul>
 *   <li>{@code get_flipendo_state} returns a Bundle with {@code flipendo_state} (whether Extreme
 *       Battery Saver is currently enabled) and {@code is_flipendo_aggressive}.
 *   <li>{@code force_enable_flipendo_method} turns Extreme Battery Saver on.
 * </ul>
 */
public final class FlipendoUtils {

    private static final String TAG = "FlipendoUtils";
    private static final String FLIPENDO_AUTHORITY = "com.google.android.flipendo.api";
    private static final String METHOD_GET_FLIPENDO_STATE = "get_flipendo_state";
    private static final String METHOD_FORCE_ENABLE_FLIPENDO = "force_enable_flipendo_method";
    private static final String KEY_FLIPENDO_STATE = "flipendo_state";
    private static final String KEY_IS_FLIPENDO_AGGRESSIVE = "is_flipendo_aggressive";

    private FlipendoUtils() {
    }

    /** Returns whether Extreme Battery Saver (Flipendo) is currently enabled. */
    public static boolean isFlipendoEnabled(ContentResolver resolver) {
        try {
            final Bundle bundle =
                    resolver.call(FLIPENDO_AUTHORITY, METHOD_GET_FLIPENDO_STATE,
                            null /* arg */, Bundle.EMPTY);
            return bundle != null && bundle.getBoolean(KEY_FLIPENDO_STATE, false);
        } catch (Exception e) {
            Log.e(TAG, "isFlipendoEnabled() failed", e);
            return false;
        }
    }

    /** Returns whether Extreme Battery Saver (Flipendo) is in aggressive mode. */
    public static boolean isFlipendoAggressive(ContentResolver resolver) {
        try {
            final Bundle bundle =
                    resolver.call(FLIPENDO_AUTHORITY, METHOD_GET_FLIPENDO_STATE,
                            null /* arg */, Bundle.EMPTY);
            if (bundle == null) {
                Log.w(TAG, "get_flipendo_state returned null");
                return false;
            }
            return bundle.getBoolean(KEY_IS_FLIPENDO_AGGRESSIVE, false);
        } catch (Exception e) {
            Log.e(TAG, "isFlipendoAggressive() failed", e);
            return false;
        }
    }

    /** Turns on Extreme Battery Saver (Flipendo). Must not be called on the main thread. */
    public static void enableFlipendo(Context context) {
        try {
            context.getContentResolver()
                    .call(FLIPENDO_AUTHORITY, METHOD_FORCE_ENABLE_FLIPENDO,
                            null /* arg */, null /* extras */);
        } catch (Exception e) {
            Log.e(TAG, "enableFlipendo() failed", e);
        }
    }
}
