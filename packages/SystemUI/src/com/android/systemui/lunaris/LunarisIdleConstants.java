/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.lunaris;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class LunarisIdleConstants {

    private LunarisIdleConstants() {}

    public static final Set<String> PROTECTED_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "android",
                "com.android.systemui",
                "com.android.phone",
                "com.android.providers.telephony",
                "com.android.server.telecom",
                "com.google.android.apps.messaging",
                "com.google.android.dialer",
                "com.whatsapp",
                "org.lunaris.dolby"
            )));
}

