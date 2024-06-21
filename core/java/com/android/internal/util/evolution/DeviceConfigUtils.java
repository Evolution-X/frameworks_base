/*
 * Copyright (C) 2023 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.evolution;

import android.content.res.Resources;
import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.internal.util.ArrayUtils;

public class DeviceConfigUtils {

    private static final String TAG = DeviceConfigUtils.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static String[] getDeviceConfigsOverride() {
        String[] globalDeviceConfigs =
            Resources.getSystem().getStringArray(com.android.internal.R.array.global_device_configs_override);
        String[] deviceConfigs =
            Resources.getSystem().getStringArray(com.android.internal.R.array.device_configs_override);
        String[] allDeviceConfigs = Arrays.copyOf(globalDeviceConfigs, globalDeviceConfigs.length + deviceConfigs.length);
        System.arraycopy(deviceConfigs, 0, allDeviceConfigs, globalDeviceConfigs.length, deviceConfigs.length);
        return allDeviceConfigs;
    }

    private static String[] getDeviceConfigsKeep() {
        return Resources.getSystem()
                    .getStringArray(com.android.internal.R.array.device_configs_propertyToKeep);
    }

    private static List<String> getAllowedPackages() {
        return Arrays.asList(Resources.getSystem()
                    .getStringArray(com.android.internal.R.array.device_configs_modifyAllowedPackages));
    }

    private static boolean shouldKeepProperty(String namespace, String property) {
        for (String p : getDeviceConfigsKeep()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");

            if (nsKey[0].equals(namespace) && nsKey[1].equals(property)) {
                return true;
            }
        }

        return false;
    }

    public static Map<String, String> filterKeepValue(String namespace, Map<String, String> keyValues) {
        for (String p : getDeviceConfigsKeep()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");

            if (!nsKey[0].equals(namespace)) {
                continue;
            }
            if (keyValues.containsKey(nsKey[1])) {
                keyValues.remove(nsKey[1]);
            }
        }

        return keyValues;
    }

    public static boolean shouldDenyDeviceConfigControl(
            String packageName, String namespace, String property) {
        if (!TextUtils.isEmpty(packageName)
                && getAllowedPackages().contains(packageName)) {
            logd("shouldAllowDeviceConfigControl, allow, package=" + packageName + ", namespace=" + namespace + ", property=" + property);
            return false;
        }

        logd("shouldAllowDeviceConfigControl, package=" + packageName + ", namespace=" + namespace + ", property=" + property);

        if (shouldKeepProperty(namespace, property)) {
            logd("shouldAllowDeviceConfigControl, deny, package=" + packageName + ", namespace=" + namespace + ", property=" + property);
            return true;
        }

        for (String p : getDeviceConfigsOverride()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");
            if (nsKey[0].equals(namespace) && nsKey[1].equals(property)) {
                logd("shouldAllowDeviceConfigControl, deny, package=" + packageName + ", namespace=" + namespace + ", property=" + property);
                return true;
            }
        }
        logd("shouldAllowDeviceConfigControl, allow, package=" + packageName + ", namespace=" + namespace + ", property=" + property);
        return false;
    }

    public static void setDefaultProperties(String filterNamespace, String filterProperty) {
        logd("setDefaultProperties");
        for (String p : getDeviceConfigsOverride()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");

            String namespace = nsKey[0];
            String key = nsKey[1];

            if (filterNamespace != null && namespace.equals(filterNamespace)) {
                continue;
            }

            if (filterProperty != null && key.equals(filterProperty)) {
                continue;
            }

            String value = "";
            if (kv.length > 1) {
                value = kv[1];
            }
            Settings.Config.putString(namespace, key, value, false);
        }
    }

    private static void logd(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
