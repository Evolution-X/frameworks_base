/*
 * Copyright (C) 2026 VoltageOS
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

package com.android.server.appbackup;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AppMetadataBackup {

    private static final String TAG = "AppDataBackupMeta";

    static final String FILE_PERMISSIONS = "permissions.json";
    static final String FILE_APPOPS = "appops.json";
    static final String FILE_SSAID = "ssaid.json";

    private static final String KEY_PACKAGE = "package";
    private static final String KEY_UID = "uid";
    private static final String KEY_PERMISSIONS = "permissions";
    private static final String KEY_NAME = "name";
    private static final String KEY_GRANTED = "granted";
    private static final String KEY_OPS = "ops";
    private static final String KEY_OP = "op";
    private static final String KEY_MODE = "mode";
    private static final String KEY_VALUE = "value";

    private AppMetadataBackup() {}

    private static final java.util.Set<String> RESTORE_BLOCKED_OPS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    AppOpsManager.OPSTR_WRITE_SETTINGS,
                    AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES,
                    AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE,
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    AppOpsManager.OPSTR_ACCESS_NOTIFICATIONS,
                    AppOpsManager.OPSTR_SCHEDULE_EXACT_ALARM,
                    AppOpsManager.OPSTR_INTERACT_ACROSS_PROFILES,
                    AppOpsManager.OPSTR_MANAGE_MEDIA,
                    AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND,
                    AppOpsManager.OPSTR_RUN_IN_BACKGROUND));

    private static boolean isDebug() {
        return android.util.Log.isLoggable(TAG, android.util.Log.DEBUG);
    }

    static boolean backupPermissions(@NonNull Context context, @NonNull String packageName,
            int userId, @NonNull File outFile) {
        final PackageManager pm = context.getPackageManager();
        try {
            final PackageInfo pi = pm.getPackageInfoAsUser(
                    packageName, PackageManager.GET_PERMISSIONS, userId);
            final JSONArray perms = new JSONArray();
            final String[] requested = pi.requestedPermissions;
            final int[] flags = pi.requestedPermissionsFlags;
            if (requested != null) {
                for (int i = 0; i < requested.length; i++) {
                    final String perm = requested[i];
                    if (!isRuntimePermission(pm, perm)) {
                        continue;
                    }
                    final boolean granted = flags != null && i < flags.length
                            && (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    final JSONObject o = new JSONObject();
                    o.put(KEY_NAME, perm);
                    o.put(KEY_GRANTED, granted);
                    perms.put(o);
                }
            }
            final JSONObject root = new JSONObject();
            root.put(KEY_PACKAGE, packageName);
            root.put(KEY_PERMISSIONS, perms);
            writeJson(outFile, root);
            return true;
        } catch (PackageManager.NameNotFoundException | IOException | JSONException e) {
            Slog.w(TAG, "backupPermissions failed for " + packageName + ": " + e);
            return false;
        }
    }

    static void restorePermissions(@NonNull Context context, @NonNull String packageName,
            int userId, @NonNull File inFile) {
        if (!inFile.exists()) {
            return;
        }
        final PackageManager pm = context.getPackageManager();
        final UserHandle user = UserHandle.of(userId);
        final java.util.Set<String> declared = new java.util.HashSet<>();
        try {
            final PackageInfo pi = pm.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (String rp : pi.requestedPermissions) {
                    declared.add(rp);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        final JSONObject root = readJson(inFile);
        if (root == null) {
            return;
        }
        final JSONArray perms = root.optJSONArray(KEY_PERMISSIONS);
        if (perms == null) {
            return;
        }
        for (int i = 0; i < perms.length(); i++) {
            final JSONObject o = perms.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String perm = o.optString(KEY_NAME, null);
            if (perm == null) {
                continue;
            }
            if (!declared.contains(perm)) {
                continue;
            }
            final boolean granted = o.optBoolean(KEY_GRANTED, false);
            if (!isRuntimePermission(pm, perm)) {
                continue;
            }
            try {
                if (granted) {
                    pm.grantRuntimePermission(packageName, perm, user);
                } else {
                    pm.revokeRuntimePermission(packageName, perm, user);
                }
            } catch (RuntimeException e) {
                if (isDebug()) {
                    Slog.d(TAG, "restore perm " + perm + " for " + packageName
                            + " failed: " + e);
                }
            }
        }
    }

    private static boolean isRuntimePermission(@NonNull PackageManager pm, String perm) {
        if (perm == null) {
            return false;
        }
        try {
            final PermissionInfo info = pm.getPermissionInfo(perm, 0);
            return info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean backupAppOps(@NonNull Context context, @NonNull String packageName,
            int uid, @NonNull File outFile) {
        if (uid < 0) {
            return false;
        }
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            return false;
        }
        try {
            final JSONArray ops = new JSONArray();
            final List<AppOpsManager.PackageOps> pkgOps =
                    appOps.getOpsForPackage(uid, packageName, (String[]) null);
            if (pkgOps != null) {
                for (AppOpsManager.PackageOps po : pkgOps) {
                    if (po == null || po.getOps() == null) {
                        continue;
                    }
                    for (AppOpsManager.OpEntry oe : po.getOps()) {
                        final String opStr = oe.getOpStr();
                        if (opStr == null) {
                            continue;
                        }
                        final JSONObject o = new JSONObject();
                        o.put(KEY_OP, opStr);
                        o.put(KEY_MODE, oe.getMode());
                        ops.put(o);
                    }
                }
            }
            final JSONObject root = new JSONObject();
            root.put(KEY_PACKAGE, packageName);
            root.put(KEY_UID, uid);
            root.put(KEY_OPS, ops);
            writeJson(outFile, root);
            return true;
        } catch (RuntimeException | IOException | JSONException e) {
            Slog.w(TAG, "backupAppOps failed for " + packageName + ": " + e);
            return false;
        }
    }

    static void restoreAppOps(@NonNull Context context, @NonNull String packageName,
            int uid, @NonNull File inFile, boolean trusted) {
        if (!inFile.exists() || uid < 0) {
            return;
        }
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            return;
        }
        final JSONObject root = readJson(inFile);
        if (root == null) {
            return;
        }
        final JSONArray ops = root.optJSONArray(KEY_OPS);
        if (ops == null) {
            return;
        }
        for (int i = 0; i < ops.length(); i++) {
            final JSONObject o = ops.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String opStr = o.optString(KEY_OP, null);
            if (opStr == null) {
                continue;
            }
            if (!trusted && RESTORE_BLOCKED_OPS.contains(opStr)) {
                if (isDebug()) {
                    Slog.d(TAG, "Skipping special-access op " + opStr + " for "
                            + packageName + " from untrusted backup");
                }
                continue;
            }
            final int mode = o.optInt(KEY_MODE, AppOpsManager.MODE_DEFAULT);
            final int code;
            try {
                code = AppOpsManager.strOpToOp(opStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            try {
                appOps.setMode(code, uid, packageName, mode);
            } catch (IllegalArgumentException | SecurityException e) {
                try {
                    appOps.setUidMode(code, uid, mode);
                } catch (RuntimeException e2) {
                    if (isDebug()) {
                        Slog.d(TAG, "restore op " + opStr + " for " + packageName
                                + " failed: " + e2);
                    }
                }
            } catch (RuntimeException e) {
                if (isDebug()) {
                    Slog.d(TAG, "restore op " + opStr + " for " + packageName
                            + " failed: " + e);
                }
            }
        }
    }

    private static File ssaidFile(int userId) {
        return new File("/data/system/users/" + userId + "/settings_ssaid.xml");
    }

    static boolean backupSsaid(int userId, @NonNull String packageName, @NonNull File outFile) {
        final File ssaid = ssaidFile(userId);
        if (!ssaid.exists()) {
            return false;
        }
        try {
            final List<Map<String, String>> settings = parseSsaid(ssaid);
            String value = null;
            for (Map<String, String> s : settings) {
                if (packageName.equals(s.get("package"))) {
                    value = s.get("value");
                    break;
                }
            }
            if (value == null || value.isEmpty()) {
                return false;
            }
            final JSONObject root = new JSONObject();
            root.put(KEY_PACKAGE, packageName);
            root.put(KEY_VALUE, value);
            writeJson(outFile, root);
            return true;
        } catch (IOException | XmlPullParserException | JSONException e) {
            Slog.w(TAG, "backupSsaid failed for " + packageName + ": " + e);
            return false;
        }
    }

    static void restoreSsaid(int userId, @NonNull String packageName, int newUid,
            @NonNull File inFile) {
        if (!inFile.exists() || newUid < 0) {
            return;
        }
        final JSONObject root = readJson(inFile);
        if (root == null) {
            return;
        }
        final String value = root.optString(KEY_VALUE, null);
        if (value == null || value.isEmpty()) {
            return;
        }
        final File ssaid = ssaidFile(userId);
        if (!ssaid.exists()) {
            return;
        }
        try {
            final List<Map<String, String>> settings = parseSsaid(ssaid);
            boolean found = false;
            int maxId = 0;
            for (Map<String, String> s : settings) {
                final String idStr = s.get("id");
                if (idStr != null) {
                    try {
                        maxId = Math.max(maxId, Integer.parseInt(idStr));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (packageName.equals(s.get("package"))) {
                    s.put("value", value);
                    s.put("name", Integer.toString(newUid));
                    found = true;
                }
            }
            if (!found) {
                final Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", Integer.toString(maxId + 1));
                entry.put("name", Integer.toString(newUid));
                entry.put("value", value);
                entry.put("package", packageName);
                entry.put("defaultValue", value);
                entry.put("defaultSysSet", "false");
                settings.add(entry);
            }
            writeSsaid(ssaid, settings);
            Slog.i(TAG, "Restored SSAID for " + packageName
                    + " (effective after settings reload / reboot)");
        } catch (IOException | XmlPullParserException e) {
            Slog.w(TAG, "restoreSsaid failed for " + packageName + ": " + e);
        }
    }

    private static List<Map<String, String>> parseSsaid(@NonNull File file)
            throws IOException, XmlPullParserException {
        final List<Map<String, String>> settings = new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            final XmlPullParser parser = Xml.resolvePullParser(in);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                if (!"setting".equals(parser.getName())) {
                    continue;
                }
                final Map<String, String> attrs = new LinkedHashMap<>();
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    attrs.put(parser.getAttributeName(i), parser.getAttributeValue(i));
                }
                settings.add(attrs);
            }
        }
        return settings;
    }

    private static void writeSsaid(@NonNull File file,
            @NonNull List<Map<String, String>> settings) throws IOException {
        final AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            final XmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.startDocument(null, Boolean.TRUE);
            serializer.startTag(null, "settings");
            serializer.attribute(null, "version", "1");
            for (Map<String, String> s : settings) {
                serializer.startTag(null, "setting");
                for (Map.Entry<String, String> e : s.entrySet()) {
                    if (e.getValue() != null) {
                        serializer.attribute(null, e.getKey(), e.getValue());
                    }
                }
                serializer.endTag(null, "setting");
            }
            serializer.endTag(null, "settings");
            serializer.endDocument();
            atomic.finishWrite(out);
            out = null;
        } finally {
            if (out != null) {
                atomic.failWrite(out);
            }
        }
    }

    private static void writeJson(@NonNull File dest, @NonNull JSONObject json)
            throws IOException {
        try (FileWriter writer = new FileWriter(dest)) {
            writer.write(json.toString(2));
        } catch (JSONException e) {
            throw new IOException("Failed to serialize " + dest.getName(), e);
        }
    }

    private static JSONObject readJson(@NonNull File src) {
        try {
            final char[] buf = new char[(int) src.length()];
            try (FileReader reader = new FileReader(src)) {
                final int n = reader.read(buf);
                if (n <= 0) {
                    return null;
                }
                return new JSONObject(new String(buf, 0, n));
            }
        } catch (IOException | JSONException e) {
            Slog.w(TAG, "Cannot read " + src + ": " + e);
            return null;
        }
    }
}
