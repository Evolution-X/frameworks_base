/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2022 StatiXOS
 *               2021-2022 crDroid Android Project
 *               2019-2026 Evolution X
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

import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.evolution.Utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * @hide
 */
public final class PixelPropsUtils {

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_NEXUS_LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String PACKAGE_PHOTOS = "com.google.android.apps.photos";
    private static final String PACKAGE_SI = "com.google.android.settings.intelligence";
    private static final String PACKAGE_SNAPCHAT = "com.snapchat.android";

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String sDeviceModel =
            SystemProperties.get("ro.product.model", Build.MODEL);
    private static final String sDeviceFingerprint =
            SystemProperties.get("ro.product.fingerprint", Build.FINGERPRINT);

    private static final Map<String, Object> sPixelXLProps = Map.of(
            "BRAND", "google",
            "MANUFACTURER", "Google",
            "DEVICE", "marlin",
            "PRODUCT", "marlin",
            "HARDWARE", "marlin",
            "ID", "QP1A.191005.007.A3",
            "MODEL", "Pixel XL",
            "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Map<String, Object> propsToChangeGeneric;
    private static final Map<String, Object> propsToChangeRecentPixel;
    private static final Map<String, Object> propsToChangePixelTablet;
    private static final Map<String, ArrayList<String>> propsToKeep;

    private static volatile Set<String> mLauncherPkgs;
    private static volatile Set<String> mExemptedUidPkgs;

    // Tensor devices: Pixel 6 and above
    private static final Pattern TENSOR_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([6-9]|[1-9][0-9])[a-zA-Z ]*)$");

    // Mainline (first-party SoC) devices: Pixel 8 and above
    private static final Pattern MAINLINE_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([89]|[1-9][0-9])([a-zA-Z].*)?)$");

    // Any supported Pixel: Pixel 3 and above (covers full GMS support window + current)
    private static final Pattern SUPPORTED_PIXEL_PATTERN =
            Pattern.compile("^Pixel ([3-9]|[1-9][0-9])([a-zA-Z ].*)?$");

    // Packages to Spoof as the most recent Pixel device
    private static final Set<String> packagesToChangeRecentPixel = new HashSet<>(Arrays.asList(
            "com.amazon.avod.thirdpartyclient",
            "com.android.chrome",
            "com.breel.wallpapers20",
            "com.disney.disneyplus",
            "com.google.android.aicore",
            "com.google.android.apps.accessibility.magnifier",
            "com.google.android.apps.aiwallpapers",
            "com.google.android.apps.bard",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.emojiwallpaper",
            "com.google.android.apps.pixel.agent",
            "com.google.android.apps.pixel.creativeassistant",
            "com.google.android.apps.pixel.nowplaying",
            "com.google.android.apps.pixel.psi",
            "com.google.android.apps.pixel.subzero",
            "com.google.android.apps.pixel.support",
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.subscriptions.red",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.apps.weather",
            "com.google.android.googlequicksearchbox",
            "com.google.android.pcs",
            "com.google.android.wallpaper.effects",
            "com.google.pixel.livewallpaper",
            "com.microsoft.android.smsorganizer",
            "com.nhs.online.nhsonline",
            "com.nothing.smartcenter",
            "com.realme.link",
            "in.startv.hotstar",
            "jp.id_credit_sp2.android"
    ));

    private static final Set<String> customGoogleCameraPackages = new HashSet<>(Arrays.asList(
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite"
    ));

    private static volatile boolean sIsExcluded;
    private static volatile String sProcessName;

    private static final boolean sIsCustomForkBuild = detectCustomFork();

    private static boolean detectCustomFork() {
        char[] k = new char[]{'d','e','v','o','l','u','t','i','o','n'};
        String needle = new String(k);

        String[] props = {
            SystemProperties.get("ro.build.display.id", ""),
            SystemProperties.get("ro.modversion", ""),
            SystemProperties.get("ro.evolution.version", ""),
            SystemProperties.get("ro.build.flavor", "")
        };

        for (String p : props) {
            if (p != null && p.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCustomForkBuild() {
        return sIsCustomForkBuild;
    }

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put(PACKAGE_SI, new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangeRecentPixel = new HashMap<>();
        propsToChangeRecentPixel.put("BRAND", "google");
        propsToChangeRecentPixel.put("BOARD", "mustang");
        propsToChangeRecentPixel.put("MANUFACTURER", "Google");
        propsToChangeRecentPixel.put("DEVICE", "mustang");
        propsToChangeRecentPixel.put("PRODUCT", "mustang");
        propsToChangeRecentPixel.put("HARDWARE", "mustang");
        propsToChangeRecentPixel.put("MODEL", "Pixel 10 Pro XL");
        propsToChangeRecentPixel.put("ID", "CP1A.260505.005");
        propsToChangeRecentPixel.put("FINGERPRINT", "google/mustang/mustang:16/CP1A.260505.005/15081906:user/release-keys");
        propsToChangePixelTablet = new HashMap<>();
        propsToChangePixelTablet.put("BRAND", "google");
        propsToChangePixelTablet.put("BOARD", "tangorpro");
        propsToChangePixelTablet.put("MANUFACTURER", "Google");
        propsToChangePixelTablet.put("DEVICE", "tangorpro");
        propsToChangePixelTablet.put("PRODUCT", "tangorpro");
        propsToChangePixelTablet.put("HARDWARE", "tangorpro");
        propsToChangePixelTablet.put("MODEL", "Pixel Tablet");
        propsToChangePixelTablet.put("ID", "CP1A.260505.005");
        propsToChangePixelTablet.put("FINGERPRINT", "google/tangorpro/tangorpro:16/CP1A.260505.005/15081906:user/release-keys");
    }

    public static String getBuildID(String fingerprint) {
        Pattern pattern = Pattern.compile("([A-Za-z0-9]+\\.\\d+\\.\\d+\\.\\w+)");
        Matcher matcher = pattern.matcher(fingerprint);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    public static String getDeviceName(String fingerprint) {
        String[] parts = fingerprint.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "";
    }

    private static boolean isGoogleCameraPackage(String packageName) {
        return packageName.contains("GoogleCamera")
                || customGoogleCameraPackages.contains(packageName);
    }

    private static void applyAppSpecificProps(Context context, String packageName) {
        if (context == null) return;
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return;

        if (packageName.equals(PACKAGE_PHOTOS)) {
            boolean enabled = true;
            try {
                enabled = Settings.Secure.getInt(
                        resolver,
                        Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
            } catch (Throwable ignored) {}

            if (enabled) {
                sPixelXLProps.forEach(PixelPropsUtils::setPropValue);
            }
            return;
        }

        if (packageName.equals(PACKAGE_SNAPCHAT)) {
            boolean enabled = false;
            try {
                enabled = Settings.Secure.getInt(
                        resolver,
                        Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
            } catch (Throwable ignored) {}

            if (enabled) {
                sPixelXLProps.forEach(PixelPropsUtils::setPropValue);
            }
        }
    }

    public static void setProps(Context context) {
        if (sIsCustomForkBuild) {
            if (DEBUG) Log.d(TAG, "Custom fork detected → disabling prop spoofing");
            return;
        }

        if (Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "Skipping setProps in isolated process");
            return;
        }

        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (packageName == null || processName == null || packageName.isEmpty()) {
            return;
        }

        sProcessName = processName;

        Map<String, Object> propsToChange = new HashMap<>();

        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        sIsExcluded = isGoogleCameraPackage(packageName);

        boolean isMainlineDevice = isMainlinePixelDevice();
        boolean isPixelPropsEnabled = getSecureIntSafe(context, Settings.Secure.PI_PP_SPOOF, 1) == 1;

        if (!sIsExcluded
                && packagesToChangeRecentPixel.contains(packageName)
                && !isMainlineDevice
                && isPixelPropsEnabled) {

            if (isDeviceTablet(context)) {
                propsToChange.putAll(propsToChangePixelTablet);
            } else {
                propsToChange.putAll(propsToChangeRecentPixel);
            }

            dlog("Defining props for: " + packageName);
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                if (propsToKeep.containsKey(packageName) && propsToKeep.get(packageName).contains(key)) {
                    dlog("Not defining " + key + " prop for: " + packageName);
                    continue;
                }
                dlog("Defining " + key + " prop for: " + packageName);
                setPropValue(key, value);
            }
        }

        // Set proper indexing fingerprint
        if (packageName.equals(PACKAGE_SI)) {
            setPropValue("FINGERPRINT", String.valueOf(Build.TIME));
            return;
        }
        if (packageName.equals(PACKAGE_ARCORE)) {
            setPropValue("FINGERPRINT", sDeviceFingerprint);
            return;
        }
        applyAppSpecificProps(context, packageName);
    }

    private static boolean isDeviceTablet(Context context) {
        if (context == null) {
            return false;
        }
        Configuration config = context.getResources().getConfiguration();
        if (config == null) return false;
        return config.smallestScreenWidthDp >= 600;
    }

    public static void setPropValue(String key, Object value) {
        try {
            Field field = getBuildClassField(key);
            if (field != null) {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    if (value instanceof String) {
                        field.set(null, Integer.parseInt((String) value));
                    } else if (value instanceof Integer) {
                        field.set(null, (Integer) value);
                    }
                } else if (field.getType() == long.class) {
                    if (value instanceof String) {
                        field.set(null, Long.parseLong((String) value));
                    } else if (value instanceof Long) {
                        field.set(null, (Long) value);
                    }
                } else {
                    field.set(null, value.toString());
                }
                field.setAccessible(false);
                dlog("Set prop " + key + " to " + value);
            } else {
                Log.e(TAG, "Field " + key + " not found in Build or Build.VERSION classes");
            }
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static Field getBuildClassField(String key) throws NoSuchFieldException {
        try {
            Field field = Build.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.class");
            return field;
        } catch (NoSuchFieldException e) {
            Field field = Build.VERSION.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.VERSION.class");
            return field;
        }
    }

    private static String[] getStringArrayResSafely(int resId) {
        String[] strArr = Resources.getSystem().getStringArray(resId);
        if (strArr == null) strArr = new String[0];
        return strArr;
    }

    public static boolean isPackageGoogle(String pkg) {
        return pkg != null && pkg.toLowerCase().contains("google");
    }

    private static Set<String> getLauncherPkgs() {
        synchronized (PixelPropsUtils.class) {
            if (mLauncherPkgs == null || mLauncherPkgs.isEmpty()) {
                mLauncherPkgs =
                        new HashSet<>(
                                Arrays.asList(
                                        getStringArrayResSafely(R.array.config_launcherPackages)));
            }
            return mLauncherPkgs;
        }
    }

    private static Set<String> getExemptedUidPkgs() {
        synchronized (PixelPropsUtils.class) {
            if (mExemptedUidPkgs == null || mExemptedUidPkgs.isEmpty()) {
                mExemptedUidPkgs = new HashSet<>();
                mExemptedUidPkgs.add(PACKAGE_GMS);
                mExemptedUidPkgs.addAll(getLauncherPkgs());
            }
            return mExemptedUidPkgs;
        }
    }

    public static boolean isNexusLauncher(Context context) {
        try {
            return PACKAGE_NEXUS_LAUNCHER.equals(
                    context.getPackageManager().getNameForUid(android.os.Binder.getCallingUid()));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSystemLauncher(Context context) {
        try {
            return isSystemLauncherInternal(
                    context.getPackageManager().getNameForUid(android.os.Binder.getCallingUid()));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSystemLauncher(int callingUid) {
        try {
            return isSystemLauncherInternal(
                    ActivityThread.getPackageManager().getNameForUid(callingUid));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSystemLauncherInternal(String callerPackage) {
        return getLauncherPkgs().contains(callerPackage);
    }

    public static boolean shouldBypassTaskPermission(int callingUid) {
        for (String pkg : getExemptedUidPkgs()) {
            try {
                ApplicationInfo appInfo =
                        ActivityThread.getPackageManager()
                                .getApplicationInfo(pkg, 0, UserHandle.getUserId(callingUid));
                if (appInfo.uid == callingUid) {
                    return true;
                }
            } catch (Exception e) {
                dlog("shouldBypassTaskPermission: failed to get appInfo for uid " + callingUid + ": " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean shouldBypassManageActivityTaskPermission(Context context) {
        final int callingUid = Binder.getCallingUid();
        return isSystemLauncher(callingUid)
                || isPackageGoogle(context.getPackageManager().getNameForUid(callingUid));
    }

    public static boolean shouldBypassMonitorInputPermission(Context context) {
        final int callingUid = Binder.getCallingUid();
        return shouldBypassTaskPermission(callingUid)
                || isPackageGoogle(context.getPackageManager().getNameForUid(callingUid));
    }

    // Whitelist of package names to bypass FGS type validation
    public static boolean shouldBypassFGSValidation(String packageName) {
        if (Arrays.asList(getStringArrayResSafely(R.array.config_fgsTypeValidationBypassPackages))
                .contains(packageName)) {
            dlog("shouldBypassFGSValidation: "
                    + "Bypassing FGS type validation for whitelisted app: "
                    + packageName);
            return true;
        }
        return false;
    }

    // Whitelist of package names to bypass alarm manager validation
    public static boolean shouldBypassAlarmManagerValidation(String packageName) {
        if (Arrays.asList(
                        getStringArrayResSafely(
                                R.array.config_alarmManagerValidationBypassPackages))
                .contains(packageName)) {
            dlog("shouldBypassAlarmManagerValidation: "
                    + "Bypassing alarm manager validation for whitelisted app: "
                    + packageName);
            return true;
        }
        return false;
    }

    // Whitelist of package names to bypass broadcast receiver validation
    public static boolean shouldBypassBroadcastReceiverValidation(String packageName) {
        if (Arrays.asList(
                        getStringArrayResSafely(
                                R.array.config_broadcastReceiverValidationBypassPackages))
                .contains(packageName)) {
            dlog("shouldBypassBroadcastReceiverValidation: "
                    + "Bypassing broadcast receiver validation for whitelisted app: "
                    + packageName);
            return true;
        }
        return false;
    }

    private static int getSecureIntSafe(Context context, String key, int def) {
        try {
            if (context == null) return def;
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) return def;
            return Settings.Secure.getInt(resolver, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean isMainlinePixelDevice() {
        String model = SystemProperties.get("ro.product.model", "");
        boolean isPixelSoC = "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer"));
        return isPixelSoC && MAINLINE_PIXEL_PATTERN.matcher(model.trim()).matches();
    }

    public static boolean isTensorPixelDevice() {
        String model = SystemProperties.get("ro.product.model", "");
        // Tensor devices are always Google SoC
        boolean isPixelSoC = "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer"));
        return isPixelSoC && TENSOR_PIXEL_PATTERN.matcher(model.trim()).matches();
    }

    public static boolean isSupportedPixelDevice() {
        String model = SystemProperties.get("ro.product.model", "").trim();
        return SUPPORTED_PIXEL_PATTERN.matcher(model).matches();
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
