/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 * Per-apps device spoofing
 */
public final class PerAppsPropsUtils {

    private static final Map<String, Map<String, Object>> propsToChange = new HashMap<>();

    static {
        propsToChange.put("BS4C", createBS4CProps());
        propsToChange.put("F5", createF5Props());
        propsToChange.put("GZF5", createGZF5Props());
        propsToChange.put("HMV2R", createHMV2RProps());
        propsToChange.put("LY700", createLY700Props());
        propsToChange.put("LY70023", createLY70023Props());
        propsToChange.put("MI11TP", createMI11TPProps());
        propsToChange.put("MI13", createMI13Props());
        propsToChange.put("MI13P", createMI13PProps());
        propsToChange.put("MI14P", createMI14PProps());
        propsToChange.put("OP12", createOP12Props());
        propsToChange.put("OP13", createOP13Props());
        propsToChange.put("OP8P5G", createOP8P5GProps());
        propsToChange.put("PXL", createPXLProps());
        propsToChange.put("PXL10PXL", createPXL10PXLProps());
        propsToChange.put("RM9P", createRM9PProps());
        propsToChange.put("RM10P", createRM10PProps());
        propsToChange.put("RM15P5G", createRM15P5GProps());
        propsToChange.put("RMX14", createRMX14Props());
        propsToChange.put("RMP35G", createRMP35GProps());
        propsToChange.put("ROG6DU", createROG6DUProps());
        propsToChange.put("ROG8P", createROG8PProps());
        propsToChange.put("ROG9P", createROG9PProps());
        propsToChange.put("S25U", createS25UProps());
    }

    private static Map<String, Object> createBS4CProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Black Shark");
        props.put("DEVICE", "Black Shark 4 (China)");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("MODEL", "2SM-X706B");
        props.put("FINGERPRINT", "BlackShark/PRS-H0/Black Shark 4:13/TQ3A.230805.001/20230315:user/release-keys");
        props.put("PRODUCT", "2SM-X706B");
        return props;
    }

    private static Map<String, Object> createF5Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Xiaomi");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("DEVICE", "marble");
        props.put("MODEL", "23049PCD8G");
        props.put("FINGERPRINT", "Xiaomi/marble_global/marble:14/UKQ1.230917.001/V816.0.2.0.UMRMIXM:user/release-keys");
        props.put("PRODUCT", "marble");
        return props;
    }

    private static Map<String, Object> createGZF5Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "samsung");
        props.put("DEVICE", "Galaxy Z Fold 5");
        props.put("MANUFACTURER", "samsung");
        props.put("MODEL", "SM-F9460");
        props.put("FINGERPRINT", "samsung/q2qzh/q2q:15/UP1A.231005.007/F946BXXU1BWK4:user/release-keys");
        props.put("PRODUCT", "SM-F9460");
        return props;
    }

    private static Map<String, Object> createHMV2RProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "HONOR");
        props.put("DEVICE", "Honor Magic V2 RSR");
        props.put("MANUFACTURER", "HONOR");
        props.put("MODEL", "VER-N49DP");
        props.put("FINGERPRINT", "HONOR/VER-N49DP/VER:13/ENG.20240918.123456:user/release-keys");
        props.put("PRODUCT", "VER-N49DP");
        return props;
    }

    private static Map<String, Object> createLY700Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Lenovo");
        props.put("DEVICE", "Lenovo Y700");
        props.put("MANUFACTURER", "Lenovo");
        props.put("MODEL", "Lenovo TB-9707F");
        return props;
    }

    private static Map<String, Object> createLY70023Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Lenovo");
        props.put("DEVICE", "Legion Y700 (2023)");
        props.put("MANUFACTURER", "Lenovo");
        props.put("MODEL", "TB-9707F");
        props.put("FINGERPRINT", "Lenovo/TB-9707F/Lenovo TB-9707F:13/TQ3A.230805.001/20230901:user/release-keys");
        props.put("PRODUCT", "TB-9707F");
        return props;
    }

    private static Map<String, Object> createMI11TPProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Xiaomi");
        props.put("DEVICE", "Xiaomi 11T Pro");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("MODEL", "2107113SG");
        props.put("FINGERPRINT", "Xiaomi/2107113SI/Mi 11T Pro:13/RKQ1.211001.001/20230410:user/release-keys");
        props.put("PRODUCT", "2107113SG");
        return props;
    }

    private static Map<String, Object> createMI13Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Xiaomi");
        props.put("DEVICE", "Xiaomi 13");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("MODEL", "2211133G");
        props.put("FINGERPRINT", "Xiaomi/fuxi_eea/fuxi:13/TKQ1.221114.001/OS2.0.102.0.VMCEUXM:user/release-keys");
        props.put("PRODUCT", "2211133G");
        return props;
    }

    private static Map<String, Object> createMI13PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Xiaomi");
        props.put("DEVICE", "Xiaomi 13 Pro");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("MODEL", "2210132G");
        props.put("FINGERPRINT", "Xiaomi/fuxi_eea/fuxi:13/TKQ1.221114.001/OS2.0.102.0.VMCEUXM:user/release-keys");
        props.put("PRODUCT", "2210132G");
        return props;
    }

    private static Map<String, Object> createMI14PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Xiaomi");
        props.put("MANUFACTURER", "Xiaomi");
        props.put("DEVICE", "houji");
        props.put("MODEL", "23116PN5BC");
        props.put("FINGERPRINT", "Xiaomi/houji/houji:14/UKQ1.230917.001/V816.0.2.0.UNBCNXM:user/release-keys");
        props.put("PRODUCT", "houji");
        return props;
    }

    private static Map<String, Object> createOP12Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "OnePlus");
        props.put("MANUFACTURER", "OnePlus");
        props.put("DEVICE", "OP594DL1");
        props.put("MODEL", "CPH2581");
        props.put("FINGERPRINT", "OnePlus/OP594DL1/OP594DL1:14/UKQ1.230917.001/1702951307528:user/release-keys");
        props.put("PRODUCT", "OP594DL1");
        return props;
    }

    private static Map<String, Object> createOP13Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "OnePlus");
        props.put("MANUFACTURER", "OnePlus");
        props.put("DEVICE", "OnePlus 13");
        props.put("MODEL", "PJZ110");
        props.put("FINGERPRINT", "OnePlus/PJZ110/OP5D0DL1:15/AP3A.240617.008/V.1bd19a1-1-2:user/release-keys");
        props.put("PRODUCT", "PJZ110");
        return props;
    }

    private static Map<String, Object> createOP8P5GProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "OnePlus");
        props.put("DEVICE", "OnePlus 8 Pro 5G");
        props.put("MANUFACTURER", "OnePlus");
        props.put("MODEL", "IN2023");
        props.put("FINGERPRINT", "OnePlus/IN2023/OnePlus8Pro:13/RKQ1.211119.001/20230501:user/release-keys");
        props.put("PRODUCT", "IN2023");
        return props;
    }

    private static Map<String, Object> createPXLProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "google");
        props.put("DEVICE", "Pixel XL");
        props.put("MANUFACTURER", "Google");
        props.put("MODEL", "marlin");
        props.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        props.put("PRODUCT", "marlin");
        return props;
    }

    private static Map<String, Object> createPXL10PXLProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "google");
        props.put("MANUFACTURER", "Google");
        props.put("DEVICE", "mustang");
        props.put("MODEL", "Pixel 10 Pro XL");
        props.put("FINGERPRINT", "google/mustang/mustang:16/CP1A.260305.018/14887507:user/release-keys");
        props.put("PRODUCT", "mustang");
        return props;
    }

    private static Map<String, Object> createRM9PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "nubia");
        props.put("DEVICE", "REDMAGIC 9 Pro");
        props.put("MANUFACTURER", "ZTE");
        props.put("MODEL", "NX769J");
        props.put("FINGERPRINT", "nubia/NX769J/NX769J:14/UKQ1.230917.001/20240813.173312:user/release-keys");
        props.put("PRODUCT", "NX769J");
        return props;
    }

    private static Map<String, Object> createRM10PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "nubia");
        props.put("DEVICE", "RedMagic 10 Pro");
        props.put("MANUFACTURER", "ZTE");
        props.put("MODEL", "NX789J");
        props.put("FINGERPRINT", "nubia/NX789J-UN/NX789J:15/AQ3A.240812.002/20241212.194919:user/release-keys");
        props.put("PRODUCT", "NX789J");
        return props;
    }

    private static Map<String, Object> createRM15P5GProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "realme");
        props.put("DEVICE", "Realme 15 Pro 5G");
        props.put("MANUFACTURER", "realme");
        props.put("MODEL", "RMX5101");
        props.put("FINGERPRINT", "realme/RMX5101IN/RE60B4L1:15/AP3A.240617.008/V.R4T2.26cec0e-80bb4e-80b757:user/release-keys");
        props.put("PRODUCT", "RMX5101");
        return props;
    }

    private static Map<String, Object> createRMX14Props() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "realme");
        props.put("DEVICE", "Realme 14");
        props.put("MANUFACTURER", "realme");
        props.put("MODEL", "RMX5070");
        return props;
    }

    private static Map<String, Object> createRMP35GProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "realme");
        props.put("DEVICE", "Realme P3 5G");
        props.put("MANUFACTURER", "realme");
        props.put("MODEL", "RMX5070");
        props.put("FINGERPRINT", "realme/RMX5070/RMX5070:15/SKQ1.230119.001/eng.user.20250415.155201:user/release-keys");
        props.put("PRODUCT", "RMX5070");
        return props;
    }

    private static Map<String, Object> createROG6DUProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "ASUS");
        props.put("DEVICE", "ROG Phone 6D Ultimate");
        props.put("MANUFACTURER", "ASUS");
        props.put("MODEL", "AI2203");
        props.put("FINGERPRINT", "ASUS/AI2203/ROG Phone 6D:14/UP1A.231005.007/20240315:user/release-keys");
        props.put("PRODUCT", "AI2203");
        return props;
    }

    private static Map<String, Object> createROG8PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "asus");
        props.put("MANUFACTURER", "asus");
        props.put("DEVICE", "ASUS_AI2401_D");
        props.put("MODEL", "ASUS_AI2401_D");
        props.put("FINGERPRINT", "asus/ASUS_AI2401_D/ASUS_AI2401:14/UKQ1.230804.001/34.0210.0210.222-0:user/release-keys");
        props.put("PRODUCT", "ASUS_AI2401_D");
        return props;
    }

    private static Map<String, Object> createROG9PProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Asus");
        props.put("DEVICE", "ROG Phone 9 PRO");
        props.put("MANUFACTURER", "Asus");
        props.put("MODEL", "ASUS_AI2501");
        return props;
    }

    private static Map<String, Object> createS25UProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "Samsung");
        props.put("DEVICE", "Samsung S25 Ultra");
        props.put("MANUFACTURER", "samsung");
        props.put("MODEL", "SM-S938B");
        return props;
    }

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();

        if (TextUtils.isEmpty(packageName)) {
            return;
        }

        final String spoofedApps;
        try {
            final ContentResolver contentResolver = context.getContentResolver();
            spoofedApps = Settings.Secure.getString(contentResolver, Settings.Secure.PER_APPS_DEVICE_SPOOF);
        } catch (Exception e) {
            PixelPropsUtils.dlog("PerAppsPropsUtils: Failed to read spoofed apps setting: " + e.getMessage());
            return;
        }

        if (TextUtils.isEmpty(spoofedApps)) {
            return;
        }

        Map<String, Map<String, Object>> allProps = new HashMap<>(propsToChange);
        try {
            final ContentResolver contentResolver = context.getContentResolver();
            String customProfilesJson = Settings.Secure.getString(contentResolver, Settings.Secure.CUSTOM_SPOOF_PROFILES);
            if (!TextUtils.isEmpty(customProfilesJson)) {
                org.json.JSONArray jsonArray = new org.json.JSONArray(customProfilesJson);
                for (int i = 0; i < jsonArray.length(); i++) {
                    org.json.JSONObject obj = jsonArray.getJSONObject(i);
                    String id = obj.getString("id");
                    Map<String, Object> props = new HashMap<>();
                    props.put("BRAND", obj.optString("brand", ""));
                    props.put("MANUFACTURER", obj.optString("manufacturer", ""));
                    props.put("DEVICE", obj.optString("device", ""));
                    props.put("MODEL", obj.optString("model", ""));
                    String fp = obj.optString("fingerprint", "");
                    if (!TextUtils.isEmpty(fp)) props.put("FINGERPRINT", fp);
                    String prod = obj.optString("product", "");
                    if (!TextUtils.isEmpty(prod)) props.put("PRODUCT", prod);
                    allProps.put(id, props);
                }
            }
        } catch (Exception e) {
            PixelPropsUtils.dlog("PerAppsPropsUtils: Failed to parse custom profiles: " + e.getMessage());
        }

        String[] apps = spoofedApps.split(",");
        for (String app : apps) {
            String[] values = app.split(":");
            if (values.length != 2) {
                continue;
            }
            String pkg = values[0];
            String device = values[1];
            if (pkg.equals(packageName)) {
                if (allProps.containsKey(device)) {
                    PixelPropsUtils.dlog("PerAppsPropsUtils: Applying profile for: " + packageName + " as " + device);
                    Map<String, Object> props = allProps.get(device);
                    for (Map.Entry<String, Object> prop : props.entrySet()) {
                        PixelPropsUtils.setPropValue(prop.getKey(), prop.getValue());
                    }
                }
                break;
            }
        }
    }
}
