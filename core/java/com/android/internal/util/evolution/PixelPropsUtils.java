/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2022 StatiXOS
 *               2021-2022 crDroid Android Project
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.evolution.PixelDeviceRepository;
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

    private static final String PI_PP_TARGETS_KEY = "pi_pp_targets";
    private static final String PI_PP_MODEL_KEY   = "pi_pp_model";

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

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
    private static final Map<String, ArrayList<String>> propsToKeep;

    // Pixel device codename ("mustang" = Pixel 10 Pro XL) used as the GMS spoof target
    // for Mosey / Quick Share — Phenotype gates key on the exact model.
    private static final String MOSEY_PIXEL_CODENAME = "mustang";

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

    // DEFAULT_PP_TARGETS moved to PixelDeviceRepository.DEFAULT_PP_TARGETS — single
    // source of truth shared with PixelPropsSettings.

    private static final Set<String> customGoogleCameraPackages = new HashSet<>(Arrays.asList(
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite"
    ));

    private static volatile boolean sIsExcluded;
    private static volatile String sProcessName;

    private static final boolean sIsMainlineDevice = detectMainlinePixelDevice();

    private static volatile boolean sPhotosSpoofEnabled = true;
    private static volatile boolean sSnapchatSpoofEnabled = false;
    private static volatile boolean sPixelPropsSpoofEnabled = true;
    private static volatile boolean sInitialized = false;
    private static volatile Set<String> sPpTargets = null;
    private static volatile String sPpModel = null;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put(PACKAGE_SI, new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
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

    public static void init(Context context) {
        if (sInitialized || Process.isIsolated() || context == null) return;
        sInitialized = true;
        registerSpoofSettingsObserver(context);
    }

    private static void registerSpoofSettingsObserver(Context context) {
        final ContentResolver cr = context.getContentResolver();
        final Runnable refresh = () -> {
            try {
                sPhotosSpoofEnabled = Settings.Secure.getInt(
                        cr, Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
                sSnapchatSpoofEnabled = Settings.Secure.getInt(
                        cr, Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
                sPixelPropsSpoofEnabled = Settings.Secure.getInt(
                        cr, Settings.Secure.PI_PP_SPOOF, 1) == 1;
                final String rawTargets = Settings.Secure.getString(cr, PI_PP_TARGETS_KEY);
                sPpTargets = (rawTargets == null || rawTargets.isEmpty())
                        ? new ArraySet<>(PixelDeviceRepository.DEFAULT_PP_TARGETS)
                        : new ArraySet<>(Arrays.asList(rawTargets.split(",")));
                sPpModel = Settings.Secure.getString(cr, PI_PP_MODEL_KEY);
            } catch (Throwable t) {
                // Settings provider not ready yet; cache stays at safe defaults
            }
        };
        try {
            final android.database.ContentObserver observer =
                    new android.database.ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) { refresh.run(); }
            };
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PI_PHOTOS_SPOOF), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PI_SNAPCHAT_SPOOF), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PI_PP_SPOOF), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(PI_PP_TARGETS_KEY), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(PI_PP_MODEL_KEY), false, observer);
        } catch (Throwable t) {
            // Observer registration failed; cached defaults remain
        }
        refresh.run();
    }

    private static boolean isGoogleCameraPackage(String packageName) {
        return packageName.contains("GoogleCamera")
                || customGoogleCameraPackages.contains(packageName);
    }

    private static void applyAppSpecificProps(String packageName) {
        if (packageName.equals(PACKAGE_PHOTOS)) {
            if (sPhotosSpoofEnabled) {
                sPixelXLProps.forEach(PixelPropsUtils::setPropValue);
            }
            return;
        }

        if (packageName.equals(PACKAGE_SNAPCHAT)) {
            if (sSnapchatSpoofEnabled) {
                sPixelXLProps.forEach(PixelPropsUtils::setPropValue);
            }
        }
    }

    public static void setProps(Context context) {
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

        init(context);

        Map<String, Object> propsToChange = new HashMap<>();

        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        sIsExcluded = isGoogleCameraPackage(packageName);

        final Set<String> ppTargets;
        if (sPpTargets == null) {
            // Pre-assign sentinel so a partial failure doesn't leave sPpTargets null
            sPpTargets = new ArraySet<>(PixelDeviceRepository.DEFAULT_PP_TARGETS);
            try {
                final android.content.ContentResolver cr = context.getContentResolver();
                final String raw = Settings.Secure.getString(cr, PI_PP_TARGETS_KEY);
                if (raw != null && !raw.isEmpty()) {
                    sPpTargets = new ArraySet<>(Arrays.asList(raw.split(",")));
                }
                final String model = Settings.Secure.getString(cr, PI_PP_MODEL_KEY);
                if (model != null && !model.isEmpty()) sPpModel = model;
            } catch (Throwable t) {
                // sPpTargets already holds the safe default; no retry next call
            }
        }
        ppTargets = sPpTargets;

        if (!sIsExcluded
                && ppTargets.contains(packageName)
                && !sIsMainlineDevice
                && sPixelPropsSpoofEnabled) {

            // Resolve model profile from cache, falling back to hardcoded defaults
            final boolean isTablet = isDeviceTablet(context);
            Map<String, Object> resolvedProps = null;
            try {
                // GMS is pinned to the Mosey codename so Quick Share Phenotype gates
                final boolean moseySpoofEnabled = sPpTargets != null
                        && sPpTargets.contains("com.google.android.mosey");
                final String defaultCodename = isTablet
                        ? "tangorpro" : PixelDeviceRepository.getDefaultPhoneCodename();
                final String codename = (packageName.equals(PACKAGE_GMS) && moseySpoofEnabled)
                        ? MOSEY_PIXEL_CODENAME
                        : (sPpModel != null && !sPpModel.isEmpty()
                                ? sPpModel
                                : defaultCodename);
                final PixelDeviceRepository.PixelProfile profile =
                        PixelDeviceRepository.getProfileByCodename(
                                context, codename, isTablet);
                if (profile != null) {
                    resolvedProps = new HashMap<>();
                    resolvedProps.put("BRAND",       "google");
                    resolvedProps.put("MANUFACTURER","Google");
                    resolvedProps.put("BOARD",       profile.getDevice());
                    resolvedProps.put("DEVICE",      profile.getDevice());
                    resolvedProps.put("PRODUCT",     profile.getProduct());
                    resolvedProps.put("HARDWARE",    profile.getDevice());
                    resolvedProps.put("MODEL",       profile.getModel());
                    resolvedProps.put("ID",          profile.getBuildId());
                    resolvedProps.put("TYPE",        "user");
                    resolvedProps.put("TAGS",        "release-keys");
                    resolvedProps.put("FINGERPRINT", profile.getFingerprint());
                }
            } catch (Throwable t) {
                dlog("Profile resolve failed, using hardcoded fallback: " + t.getMessage());
            }

            if (resolvedProps == null) {
                // getProfileByCodename already falls back through cache → FALLBACK_PROFILES
                // internally, so this only triggers on a hard exception above.
                final PixelDeviceRepository.PixelProfile fallback =
                        PixelDeviceRepository.getProfileByCodename(
                                context,
                                isTablet ? "tangorpro" : PixelDeviceRepository.getDefaultPhoneCodename(),
                                isTablet);
                resolvedProps = new HashMap<>();
                if (fallback != null) {
                    resolvedProps.put("BRAND",       "google");
                    resolvedProps.put("MANUFACTURER","Google");
                    resolvedProps.put("BOARD",       fallback.getDevice());
                    resolvedProps.put("DEVICE",      fallback.getDevice());
                    resolvedProps.put("PRODUCT",     fallback.getProduct());
                    resolvedProps.put("HARDWARE",    fallback.getDevice());
                    resolvedProps.put("MODEL",       fallback.getModel());
                    resolvedProps.put("ID",          fallback.getBuildId());
                    resolvedProps.put("TYPE",        "user");
                    resolvedProps.put("TAGS",        "release-keys");
                    resolvedProps.put("FINGERPRINT", fallback.getFingerprint());
                }
            }

            propsToChange.putAll(resolvedProps);

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
        applyAppSpecificProps(packageName);
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

    private static boolean detectMainlinePixelDevice() {
        String model = SystemProperties.get("ro.product.model", "").trim();
        boolean isPixelSoC = "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer"));
        return isPixelSoC && MAINLINE_PIXEL_PATTERN.matcher(model).matches();
    }

    public static boolean isMainlinePixelDevice() {
        return sIsMainlineDevice;
    }

    public static boolean isTensorPixelDevice() {
        String model = SystemProperties.get("ro.product.model", "").trim();
        // Tensor devices are always Google SoC
        boolean isPixelSoC = "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer"));
        return isPixelSoC && TENSOR_PIXEL_PATTERN.matcher(model).matches();
    }

    public static boolean isSupportedPixelDevice() {
        String model = SystemProperties.get("ro.product.model", "").trim();
        return SUPPORTED_PIXEL_PATTERN.matcher(model).matches();
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
