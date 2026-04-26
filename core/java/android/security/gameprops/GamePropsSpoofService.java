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
import android.os.Process;
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
    private static final String SETTING_PER_APPS         = "per_apps_device_spoof";
    /** Master enable flag for per-app spoofing (int, default 1). */
    private static final String SETTING_PER_APPS_ENABLED = "per_apps_device_spoof_enabled";
    /** JSON array of custom user-defined profiles. */
    private static final String SETTING_CUSTOM_PROFILES  = "custom_spoof_profiles";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static GamePropsSpoofService sInstance;

    private GamePropsSpoofService() {}

    /** @hide */
    public static GamePropsSpoofService getInstance() {
        GamePropsSpoofService instance;
        synchronized (GamePropsSpoofService.class) {
            if (sInstance == null) {
                sInstance = new GamePropsSpoofService();
            }
            instance = sInstance;
        }
        if (!instance.mConfigLoaded) {
            instance.loadConfig();
        }
        return instance;
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
     * Built-in named profiles. Keys match the profile IDs used by
     * UserSelectedAppSpoofSettings / PerAppsPropsUtils.
     *
     * @hide
     */
    public static final Map<String, Map<String, Object>> BUILTIN_PROFILES;

    static {
        BUILTIN_PROFILES = new HashMap<>();
        Object[][] entries = {
            // key          brand          model               manufacturer    device             fingerprint                                                                                                product
            { "BS4C",     "Black Shark", "2SM-X706B",         "Xiaomi",       "2SM-X706B",       "BlackShark/PRS-H0/Black Shark 4:13/TQ3A.230805.001/20230315:user/release-keys",                       "2SM-X706B"   },
            { "F5",       "POCO",        "23049PCD8G",        "Xiaomi",       "marble",          "POCO/marble_global/marble:15/AQ3A.250226.002/OS3.0.3.0.VMRMIXM:user/release-keys",                    "marble"      },
            { "S25U",     "samsung",     "SM-S938B",          "samsung",      "pa3q",            "samsung/pa3qxxx/pa3q:15/AP3A.240905.015.A2/S938BXXU9CZDP:user/release-keys",                          "pa3qxxx"     },
            { "GZF5",     "samsung",     "SM-F9460",          "samsung",      "q2q",             "samsung/q2qzh/q2q:15/UP1A.231005.007/F946BXXU1BWK4:user/release-keys",                                "q2qxxx"    },
            { "HMV2R",    "HONOR",       "VER-N49DP",         "HONOR",        "VER",             "HONOR/VER-N49DP/VER:13/ENG.20240918.123456:user/release-keys",                                        "VER-N49DP"   },
            { "LY70023",  "Lenovo",      "Lenovo TB-9707F",   "lenovo",       "TB-9707F",        "Lenovo/TB-9707F_PRC/TB-9707F:13/TKQ1.221013.002/15.0.342_231018:user/release-keys",                   "TB-9707F"    },
            { "MI11TP",   "Xiaomi",      "2107113SG",         "Xiaomi",       "vili",            "Xiaomi/vili_global/vili:14/UKQ1.231207.002/V816.0.22.0.UKDMIXM:user/release-keys",                    "vili"        },
            { "MI13",     "Xiaomi",      "2211133G",          "Xiaomi",       "fuxi",            "Xiaomi/fuxi_global/fuxi:14/UKQ1.230804.001/V816.0.3.0.UMCMIXM:user/release-keys",                     "fuxi"        },
            { "MI13P",    "Xiaomi",      "2210132G",          "Xiaomi",       "nuwa",            "Xiaomi/nuwa_global/nuwa:13/TKQ1.221114.001/OS2.0.100.0.VMBMIXM:user/release-keys",                    "nuwa"        },
            { "MI14P",    "Xiaomi",      "23116PN5BC",        "Xiaomi",       "shennong",        "Xiaomi/shennong/shennong:15/AQ3A.240627.003/OS2.0.202.0.VNBCNXM:user/release-keys",                   "shennong"    },
            { "OP12",     "OnePlus",     "CPH2581",           "OnePlus",      "OP594DL1",        "OnePlus/OP594DL1/OP594DL1:14/UKQ1.230917.001/1702951307528:user/release-keys",                        "OP594DL1"    },
            { "OP13",     "OnePlus",     "PJZ110",            "OnePlus",      "OP5D0DL1",        "OnePlus/PJZ110/OP5D0DL1:15/AP3A.240617.008/V.1bd19a1-1-2:user/release-keys",                          "PJZ110"      },
            { "PXL10PXL", "google",      "Pixel 10 Pro XL",   "Google",       "mustang",         "google/mustang/mustang:16/CP1A.260505.005/15081906:user/release-keys",                                "mustang"     },
            { "RM9P",     "nubia",       "NX769J",            "ZTE",          "NX769J",          "nubia/NX769J/NX769J:14/UKQ1.230917.001/20240813.173312:user/release-keys",                            "NX769J"      },
            { "RM10P",    "nubia",       "NX789J",            "ZTE",          "NX789J",          "nubia/NX789J-UN/NX789J:15/AQ3A.240812.002/20241212.194919:user/release-keys",                         "NX789J"      },
            { "RM15P5G",  "realme",      "RMX5101",           "realme",       "RE60B4L1",        "realme/RMX5101IN/RE60B4L1:15/AP3A.240617.008/V.R4T2.26cec0e-80bb4e-80b757:user/release-keys",         "RMX5101"     },
            { "ROG9P",    "asus",        "ASUSAI2501",        "asus",         "ASUSAI2501",      "asus/WWAI2501/ASUSAI2501:15/AQ3A.240829.003/35.1810.1810.346-0:user/release-keys",                    "ASUSAI2501"  },
        };
        for (Object[] e : entries) {
            BUILTIN_PROFILES.put(
                (String) e[0],
                profile((String) e[1], (String) e[2], (String) e[3],
                        (String) e[4], (String) e[5], (String) e[6])
            );
        }
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
        // Reset volatile flags first so concurrent readers see a consistent
        // "not loaded" state while we rebuild.
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
            // Parse into a fresh local map; swap atomically so concurrent
            // readers never see a half-cleared ConcurrentHashMap.
            parseJson(content);
            mConfigLoaded = true;
            Log.i(TAG, "Game props config loaded, games=" + mGameConfigs.size()
                    + ", enabled=" + mEnabled);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse game props config", e);
        }
    }

    private void parseJson(String content) {
        boolean enabled = false;
        boolean debug   = false;
        final Map<String, Map<String, String>> newConfigs = new HashMap<>();

        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("enabled".equals(key)) {
                    enabled = reader.nextBoolean();
                } else if ("debug".equals(key)) {
                    debug = reader.nextBoolean();
                } else if ("games".equals(key)) {
                    parseGames(reader, newConfigs);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
            return;
        }

        mEnabled = enabled;
        mDebug   = debug;
        mGameConfigs.clear();
        mGameConfigs.putAll(newConfigs);
    }

    private void parseGames(JsonReader reader, Map<String, Map<String, String>> out)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String packageName = reader.nextName();
            Map<String, String> gameProps = new HashMap<>();
            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    gameProps.put(reader.nextName(), reader.nextString());
                }
                reader.endObject();
            } catch (Exception e) {
                Log.w(TAG, "Skipping malformed game-props entry for " + packageName, e);
                continue;
            }
            if (!gameProps.isEmpty()) {
                out.put(packageName, gameProps);
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
     * Apply game-props spoofing (source 1) for the given package.
     * Per-app spoofing (source 2) is handled separately via
     * applyPerAppSpoofFromContext() at Instrumentation time.
     *
     * @hide
     */
    public void spoofForPackage(String packageName) {
        if (packageName == null) return;

        if (mEnabled && mConfigLoaded) {
            Map<String, String> gameProps = mGameConfigs.get(packageName);
            if (gameProps != null && !gameProps.isEmpty()) {
                if (mDebug) Log.d(TAG, "Spoofing via game-props for: " + packageName);
                for (Map.Entry<String, String> entry : gameProps.entrySet()) {
                    spoofField(entry.getKey(), entry.getValue(), packageName);
                }
            }
        }
    }

    /**
     * Called from Instrumentation.newApplication once the app Context is fully
     * ready and the ContentResolver can safely reach SettingsProvider.
     * Skipped for isolated processes and for packages already handled by the
     * JSON game-props source.
     *
     * @hide
     */
    public void applyPerAppSpoofFromContext(Context context) {
        if (context == null) return;
        if (Process.isIsolated()) return;
        String packageName = context.getPackageName();
        if (TextUtils.isEmpty(packageName)) return;
        // Source 1 already applied — don't double-spoof
        if (mEnabled && mConfigLoaded && mGameConfigs.containsKey(packageName)) return;
        applyPerAppSpoof(packageName, context);
    }

    // -------------------------------------------------------------------------
    // Per-app spoof logic (source 2 — formerly PerAppsPropsUtils.setProps)
    // -------------------------------------------------------------------------

    private void applyPerAppSpoof(String packageName, Context context) {
        if (TextUtils.isEmpty(packageName) || context == null) return;

        // Check master enable flag
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(), SETTING_PER_APPS_ENABLED, 1);
            if (enabled == 0) return;
        } catch (Throwable e) {
            if (mDebug) Log.d(TAG, "Could not read per-apps enabled flag: " + e.getMessage());
            return;
        }

        // Read active map: "pkg1:profileId1,pkg2:profileId2,..."
        String spoofedApps;
        try {
            spoofedApps = Settings.Secure.getString(
                    context.getContentResolver(), SETTING_PER_APPS);
        } catch (Throwable e) {
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
        } catch (Throwable e) {
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
            Object rawOld = field.get(null);
            String oldValue = (rawOld != null) ? rawOld.toString() : "";
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
