/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
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
package com.android.systemui.theme;

import android.provider.Settings;

public class RisingSettingsConstants {

    public static final String[] SYSTEM_SETTINGS_KEYS = {
        "status_bar_logo",
        "statusbar_clock_chip",
        "qs_show_data_usage_tile"
    };
    
    public static final String[] SECURE_SETTINGS_KEYS = {
        "clock_text_accent_color",
        "clock_text_opacity"
    };
    
    public static final String[] SYSTEM_SETTINGS_NOTIFY_ONLY_KEYS = {
    };
    
    public static final String[] SECURE_SETTINGS_NOTIFY_ONLY_KEYS = {
    };
}
