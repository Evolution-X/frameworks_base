/*
 * Copyright (C) 2025-2026 AxionOS
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

package android.security.gameprops;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified device-props spoofing service.
 *
 * Handles two independent sources:
 *
 *   1. JSON game-props config (stored in Settings.Secure via system_server) — the original
 *      GamePropsSpoofService behaviour, keyed by package name with arbitrary Build field maps.
 *
 *   2. Per-app spoof map (stored in Settings.Secure.PER_APPS_DEVICE_SPOOF) — formerly
 *      PerAppsPropsUtils, maps a package name to a named device profile (e.g. "ROG8P").
 *      Requires a Context to read Settings.Secure at spoof time.
 *
 * Source (1) takes priority: if a package has a JSON game-props entry that entry is applied
 * and per-app lookup is skipped for that package.
 *
 * @hide
 */
public final class GamePropsSpoofService {

    private static final String TAG = "GameProps";

    // -------------------------------------------------------------------------
    // Settings keys (mirrors Settings.Secure constants used by the UI)
    // -------------------------------------------------------------------------

    /** Comma-separated "pkg:profileId" pairs for the active per-app spoof map. */
    private static final String SETTING_PER_APPS        = "per_apps_device_spoof";
    /** Master enable flag for per-app spoofing (int, default 1). */
    private static final String SETTING_PER_APPS_ENABLED = "per_apps_device_spoof_enabled";
    /** JSON array of custom user-defined profiles. */
    private static final String SETTING_CUSTOM_PROFILES = "custom_spoof_profiles";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static GamePropsSpoofService sInstance;

    private GamePropsSpoofService() {}

    /** @hide */
    public static synchronized GamePropsSpoofService getInstance() {
        if (sInstance == null) {
            sInstance = new GamePropsSpoofService();
            sInstance.loadConfig();
        }
        return sInstance;
    }

    // -------------------------------------------------------------------------
    // JSON game-props state (source 1)
    // -------------------------------------------------------------------------

    private volatile boolean mEnabled      = false;
    private volatile boolean mDebug        = false;
    private volatile boolean mConfigLoaded = false;

    /** packageName → { fieldName → value } from JSON game-props config */
    private final Map<String, Map<String, String>> mGameConfigs = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Built-in device profiles (formerly in PerAppsPropsUtils static block)
    // -------------------------------------------------------------------------

    /**
     * Built-in named profiles.  Keys match the profile IDs used by
     * UserSelectedAppSpoofSettings / PerAppsPropsUtils.
     *
     * @hide
     */
    public static final Map<String, Map<String, Object>> BUILTIN_PROFILES;

    static {
        BUILTIN_PROFILES = new HashMap<>();
        BUILTIN_PROFILES.put("BS4C",    profile("Black Shark", "2SM-X706B",   "Xiaomi",  "2SM-X706B",   "BlackShark/PRS-H0/Black Shark 4:13/TQ3A.230805.001/20230315:user/release-keys",                                    "2SM-X706B"));
        BUILTIN_PROFILES.put("F5",      profile("Xiaomi",      "23049PCD8G",  "Xiaomi",  "marble",      "Xiaomi/marble_global/marble:14/UKQ1.230917.001/V816.0.2.0.UMRMIXM:user/release-keys",                            "marble"));
        BUILTIN_PROFILES.put("GZF5",    profile("samsung",     "SM-F9460",    "samsung", "Galaxy Z Fold 5", "samsung/q2qzh/q2q:15/UP1A.231005.007/F946BXXU1BWK4:user/release-keys",                                       "SM-F9460"));
        BUILTIN_PROFILES.put("HMV2R",   profile("HONOR",       "VER-N49DP",   "HONOR",   "Honor Magic V2 RSR", "HONOR/VER-N49DP/VER:13/ENG.20240918.123456:user/release-keys",                                            "VER-N49DP"));
        BUILTIN_PROFILES.put("LY700",   profile("Lenovo",      "Lenovo TB-9707F", "Lenovo", "Lenovo Y700", null,                                                                                                           null));
        BUILTIN_PROFILES.put("LY70023", profile("Lenovo",      "TB-9707F",    "Lenovo",  "Legion Y700 (2023)", "Lenovo/TB-9707F/Lenovo TB-9707F:13/TQ3A.230805.001/20230901:user/release-keys",                          "TB-9707F"));
        BUILTIN_PROFILES.put("MI11TP",  profile("Xiaomi",      "2107113SG",   "Xiaomi",  "Xiaomi 11T Pro", "Xiaomi/2107113SI/Mi 11T Pro:13/RKQ1.211001.001/20230410:user/release-keys",                                  "2107113SG"));
        BUILTIN_PROFILES.put("MI13",    profile("Xiaomi",      "2211133G",    "Xiaomi",  "Xiaomi 13",   "Xiaomi/fuxi_eea/fuxi:13/TKQ1.221114.001/OS2.0.102.0.VMCEUXM:user/release-keys",                                 "2211133G"));
        BUILTIN_PROFILES.put("MI13P",   profile("Xiaomi",      "2210132G",    "Xiaomi",  "Xiaomi 13 Pro", "Xiaomi/fuxi_eea/fuxi:13/TKQ1.221114.001/OS2.0.102.0.VMCEUXM:user/release-keys",                              "2210132G"));
        BUILTIN_PROFILES.put("MI14P",   profile("Xiaomi",      "23116PN5BC",  "Xiaomi",  "houji",       "Xiaomi/houji/houji:14/UKQ1.230917.001/V816.0.2.0.UNBCNXM:user/release-keys",                                   "houji"));
        BUILTIN_PROFILES.put("OP12",    profile("OnePlus",     "CPH2581",     "OnePlus", "OP594DL1",    "OnePlus/OP594DL1/OP594DL1:14/UKQ1.230917.001/1702951307528:user/release-keys",                                  "OP594DL1"));
        BUILTIN_PROFILES.put("OP13",    profile("OnePlus",     "PJZ110",      "OnePlus", "OnePlus 13",  "OnePlus/PJZ110/OP5D0DL1:15/AP3A.240617.008/V.1bd19a1-1-2:user/release-keys",                                   "PJZ110"));
        BUILTIN_PROFILES.put("OP8P5G",  profile("OnePlus",     "IN2023",      "OnePlus", "OnePlus 8 Pro 5G", "OnePlus/IN2023/OnePlus8Pro:13/RKQ1.211119.001/20230501:user/release-keys",                                 "IN2023"));
        BUILTIN_PROFILES.put("PXL",     profile("google",      "marlin",      "Google",  "Pixel XL",    "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys",                                          "marlin"));
        BUILTIN_PROFILES.put("PXL10PXL",profile("google",      "Pixel 10 Pro XL", "Google", "mustang", "google/mustang/mustang:16/CP1A.260305.018/14887507:user/release-keys",                                           "mustang"));
        BUILTIN_PROFILES.put("RM9P",    profile("nubia",       "NX769J",      "ZTE",     "REDMAGIC 9 Pro", "nubia/NX769J/NX769J:14/UKQ1.230917.001/20240813.173312:user/release-keys",                                  "NX769J"));
        BUILTIN_PROFILES.put("RM10P",   profile("nubia",       "NX789J",      "ZTE",     "RedMagic 10 Pro", "nubia/NX789J-UN/NX789J:15/AQ3A.240812.002/20241212.194919:user/release-keys",                              "NX789J"));
        BUILTIN_PROFILES.put("RM15P5G", profile("realme",      "RMX5101",     "realme",  "Realme 15 Pro 5G", "realme/RMX5101IN/RE60B4L1:15/AP3A.240617.008/V.R4T2.26cec0e-80bb4e-80b757:user/release-keys",            "RMX5101"));
        BUILTIN_PROFILES.put("RMX14",   profile("realme",      "RMX5070",     "realme",  "Realme 14",   null,                                                                                                             null));
        BUILTIN_PROFILES.put("RMP35G",  profile("realme",      "RMX5070",     "realme",  "Realme P3 5G", "realme/RMX5070/RMX5070:15/SKQ1.230119.001/eng.user.20250415.155201:user/release-keys",                        "RMX5070"));
        BUILTIN_PROFILES.put("ROG6DU",  profile("ASUS",        "AI2203",      "ASUS",    "ROG Phone 6D Ultimate", "ASUS/AI2203/ROG Phone 6D:14/UP1A.231005.007/20240315:user/release-keys",                             "AI2203"));
        BUILTIN_PROFILES.put("ROG8P",   profile("asus",        "ASUS_AI2401_D", "asus",  "ASUS_AI2401_D", "asus/ASUS_AI2401_D/ASUS_AI2401:14/UKQ1.230804.001/34.0210.0210.222-0:user/release-keys",                   "ASUS_AI2401_D"));
        BUILTIN_PROFILES.put("ROG9P",   profile("Asus",        "ASUS_AI2501",  "Asus",   "ROG Phone 9 PRO", null,                                                                                                        null));
        BUILTIN_PROFILES.put("S25U",    profile("Samsung",     "SM-S938B",    "samsung", "Samsung S25 Ultra", null,                                                                                                       null));
    }

    /** Convenience builder for a profile map. Null fp/product are skipped. */
    private static Map<String, Object> profile(String brand, String model, String manufacturer,
            String device, String fingerprint, String product) {
        Map<String, Object> m = new HashMap<>();
        m.put("BRAND",        brand);
        m.put("MODEL",        model);
        m.put("MANUFACTURER", manufacturer);
        m.put("DEVICE",       device);
        if (fingerprint != null) m.put("FINGERPRINT", fingerprint);
        if (product     != null) m.put("PRODUCT",     product);
        return m;
    }

    // -------------------------------------------------------------------------
    // JSON config loading (source 1)
    // -------------------------------------------------------------------------

    /** @hide */
    public void loadConfig() {
        mGameConfigs.clear();
        mEnabled      = false;
        mConfigLoaded = false;

        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            Log.w(TAG, "ActivityManager not ready, skipping gameprops config load");
            return;
        }

        String content;
        try {
            content = am.getSpoofGamePropsConfig();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to fetch gameprops config from system_server", e);
            return;
        }

        if (content == null || content.isEmpty()) {
            Log.w(TAG, "No gameprops config in Settings.Secure");
            return;
        }

        try {
            parseJson(content);
            mConfigLoaded = true;
            Log.i(TAG, "Game props config loaded, games=" + mGameConfigs.size()
                    + ", enabled=" + mEnabled);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse game props config", e);
        }
    }

    private void parseJson(String content) {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("enabled".equals(key)) {
                    mEnabled = reader.nextBoolean();
                } else if ("debug".equals(key)) {
                    mDebug = reader.nextBoolean();
                } else if ("games".equals(key)) {
                    parseGames(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
        }
    }

    private void parseGames(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String packageName = reader.nextName();
            Map<String, String> gameProps = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                gameProps.put(reader.nextName(), reader.nextString());
            }
            reader.endObject();
            if (!gameProps.isEmpty()) {
                mGameConfigs.put(packageName, gameProps);
                if (mDebug) Log.d(TAG, "Loaded config for " + packageName
                        + ": " + gameProps.size() + " props");
            }
        }
        reader.endObject();
    }

    // -------------------------------------------------------------------------
    // Public spoof entry points
    // -------------------------------------------------------------------------

    /**
     * Apply spoofing for the given package.
     *
     * Checks JSON game-props first (source 1). If no entry is found there,
     * falls back to the per-app Settings.Secure map (source 2) when a Context
     * is supplied.
     *
     * @hide
     */
    public void spoofForPackage(String packageName, Context context) {
        if (packageName == null) return;

        // Source 1 — JSON game-props config
        if (mEnabled && mConfigLoaded) {
            Map<String, String> gameProps = mGameConfigs.get(packageName);
            if (gameProps != null && !gameProps.isEmpty()) {
                if (mDebug) Log.d(TAG, "Spoofing via game-props for: " + packageName);
                for (Map.Entry<String, String> entry : gameProps.entrySet()) {
                    spoofField(entry.getKey(), entry.getValue(), packageName);
                }
                return; // source 1 wins; skip per-app lookup
            }
        }

        // Source 2 — per-app Settings.Secure map (formerly PerAppsPropsUtils)
        if (context != null) {
            applyPerAppSpoof(packageName, context);
        }
    }

    /**
     * Convenience overload for callers that only have the JSON game-props path
     * (no Context available).
     *
     * @hide
     */
    public void spoofForPackage(String packageName) {
        spoofForPackage(packageName, null);
    }

    // -------------------------------------------------------------------------
    // Per-app spoof logic (source 2 — formerly PerAppsPropsUtils.setProps)
    // -------------------------------------------------------------------------

    private void applyPerAppSpoof(String packageName, Context context) {
        // Check master enable flag
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(), SETTING_PER_APPS_ENABLED, 1);
            if (enabled == 0) return;
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Could not read per-apps enabled flag: " + e.getMessage());
            return;
        }

        // Read active map: "pkg1:profileId1,pkg2:profileId2,..."
        String spoofedApps;
        try {
            spoofedApps = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_PER_APPS);
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Failed to read per-apps setting: " + e.getMessage());
            return;
        }

        if (TextUtils.isEmpty(spoofedApps)) return;

        // Find this package's assigned profile ID
        String profileId = null;
        for (String entry : spoofedApps.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length == 2 && packageName.equals(parts[0])) {
                profileId = parts[1];
                break;
            }
        }
        if (profileId == null) return;

        // Build combined profile map: built-ins + custom user profiles
        Map<String, Map<String, Object>> allProfiles = new HashMap<>(BUILTIN_PROFILES);
        loadCustomProfiles(context, allProfiles);

        Map<String, Object> props = allProfiles.get(profileId);
        if (props == null) {
            if (mDebug) Log.d(TAG, "Unknown profile id '" + profileId
                    + "' for package " + packageName);
            return;
        }

        if (mDebug) Log.d(TAG, "Per-app spoof: " + packageName + " → " + profileId);
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            Object v = prop.getValue();
            if (v != null) spoofField(prop.getKey(), v.toString(), packageName);
        }
    }

    /**
     * Reads custom profiles from Settings.Secure and merges them into {@code out}.
     * Any profile whose id collides with a built-in is skipped (built-ins win).
     */
    private void loadCustomProfiles(Context context, Map<String, Map<String, Object>> out) {
        String json;
        try {
            json = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_CUSTOM_PROFILES);
        } catch (Exception e) {
            return;
        }
        if (TextUtils.isEmpty(json)) return;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.getString("id");
                if (out.containsKey(id)) continue; // built-in takes priority

                Map<String, Object> props = new HashMap<>();
                props.put("BRAND",        obj.optString("brand",        ""));
                props.put("MANUFACTURER", obj.optString("manufacturer", ""));
                props.put("DEVICE",       obj.optString("device",       ""));
                props.put("MODEL",        obj.optString("model",        ""));
                String fp   = obj.optString("fingerprint", "");
                String prod = obj.optString("product",     "");
                if (!TextUtils.isEmpty(fp))   props.put("FINGERPRINT", fp);
                if (!TextUtils.isEmpty(prod)) props.put("PRODUCT",     prod);
                out.put(id, props);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse custom spoof profiles", e);
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private void spoofField(String fieldName, String value, String packageName) {
        if (value == null || value.isEmpty()) {
            if (mDebug) Log.d(TAG, fieldName + " is empty, skipping");
            return;
        }
        try {
            Field field = getDeclaredField(Build.class, fieldName);
            if (field == null) field = getDeclaredField(Build.VERSION.class, fieldName);
            if (field == null) {
                if (mDebug) Log.d(TAG, "Field not found: " + fieldName);
                return;
            }

            field.setAccessible(true);
            String oldValue = String.valueOf(field.get(null));
            if (value.equals(oldValue)) {
                if (mDebug) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
                return;
            }

            Class<?> type = field.getType();
            Object newValue;
            if (type == String.class)       newValue = value;
            else if (type == int.class)     newValue = Integer.parseInt(value);
            else if (type == long.class)    newValue = Long.parseLong(value);
            else if (type == boolean.class) newValue = Boolean.parseBoolean(value);
            else {
                Log.w(TAG, "Unsupported field type: " + type);
                return;
            }

            field.set(null, newValue);
            if (mDebug) Log.d(TAG, "[" + packageName + "][" + fieldName + "]: "
                    + oldValue + " → " + value);

        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof " + fieldName + " for " + packageName, e);
        }
    }

    private static Field getDeclaredField(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (unchanged public API)
    // -------------------------------------------------------------------------

    /** @hide */
    public boolean isEnabled() {
        return mEnabled && mConfigLoaded;
    }

    /** @hide */
    public boolean hasConfigForPackage(String packageName) {
        return mGameConfigs.containsKey(packageName);
    }

    /** @hide */
    public Map<String, Map<String, String>> getAllGameConfigs() {
        return new ConcurrentHashMap<>(mGameConfigs);
    }

    /** @hide */
    public boolean isConfigLoaded() {
        return mConfigLoaded;
    }
}
