/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Converts one Swift app/version folder into a native, unencrypted backup. */
final class SwiftBackupImport {
    private SwiftBackupImport() {}

    static String packageName(String[] names) throws IOException {
        require(names != null && names.length > 0 && names.length <= 10,
                "Select one Swift app backup version folder");
        String packageName = null;
        final java.util.Set<String> unique = new java.util.HashSet<>();
        for (String name : names) {
            require(name != null && name.length() <= 255 && name.matches(
                    "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\\.(app|splits|dat|extdat|med|exp|xml|libs)"),
                    "Unsupported Swift backup file");
            require(unique.add(name), "Duplicate Swift backup file");
            final String pkg = name.substring(0, name.lastIndexOf('.'));
            if (packageName == null) packageName = pkg;
            require(packageName.equals(pkg), "Select a backup folder containing only one app");
            require(!name.endsWith(".libs"), "Swift shared-library APK backups are not supported");
        }
        return packageName;
    }

    static BackupManifest convert(Map<String, File> inputs, String pkg, File output,
            PackageManager pm, int userId) throws IOException {
        try {
            JSONObject metadata = new JSONObject();
            final File xml = inputs.get(pkg + ".xml");
            if (xml != null) {
                require(xml.length() <= (1 << 20), "Swift metadata is too large");
                metadata = SwiftBackupGuest.readMetadata(Files.readString(xml.toPath()));
                require(pkg.equals(metadata.getString("packageName")), "Swift package mismatch");
                require(metadata.optLong("sharedLibsBackupSize", 0) <= 0,
                        "Swift shared-library APK backups are not supported");
                final String[][] expected = {{"apk", "app"}, {"splits", "splits"},
                        {"data", "dat"}, {"extData", "extdat"}, {"exp", "exp"}, {"media", "med"}};
                for (String[] part : expected) {
                    final long size = metadata.optLong(part[0] + "BackupSize", 0);
                    if (size <= 0) continue;
                    final File payload = inputs.get(pkg + "." + part[1]);
                    require(payload != null && payload.length() == size,
                            "Swift backup is incomplete or damaged: missing or wrong-sized ." + part[1]);
                }
            }
            final List<File> contents = new ArrayList<>();
            long apkBytes = 0, ceBytes = 0, deBytes = 0, extBytes = 0;
            int components = 0;
            long timestamp = metadata.optLong("dateBackup", 0);
            String label = metadata.optString("name", pkg);
            String versionName = metadata.optString("versionName", "");
            long versionCode = metadata.optLong("versionCode", 0);

            long unpackedBytes = inputs.containsKey(pkg + ".app")
                    ? inputs.get(pkg + ".app").length() : 0;
            for (Map.Entry<String, File> input : inputs.entrySet()) {
                if (input.getKey().endsWith(".app") || input.getKey().endsWith(".xml")) continue;
                try (SwiftBackupArchive archive = new SwiftBackupArchive(input.getValue(), pkg)) {
                    for (SwiftBackupArchive.Entry entry : archive.entries) {
                        require(entry.tarSize <= SwiftBackupArchive.MAX_BYTES - unpackedBytes,
                                "Swift backup exceeds the import size limit");
                        unpackedBytes += entry.tarSize;
                    }
                }
            }
            require(unpackedBytes <= SwiftBackupArchive.MAX_BYTES,
                    "Swift backup exceeds the import size limit");
            final File apk = inputs.get(pkg + ".app");
            if (apk != null) {
                validateApk(apk);
                final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
                require(info != null && pkg.equals(info.packageName), "Swift APK package mismatch");
                versionName = info.versionName != null ? info.versionName : "";
                versionCode = info.getLongVersionCode();
                final File base = new File(output, "base.apk");
                Files.copy(apk.toPath(), base.toPath());
                contents.add(base);
                apkBytes += base.length();
                components |= AppDataBackupRestoreManager.COMPONENT_APK;
            }
            final File splits = inputs.get(pkg + ".splits");
            require(splits == null || apk != null, "Swift split APKs require the base .app file");
            if (splits != null) {
                try (SwiftBackupArchive archive = new SwiftBackupArchive(splits, pkg)) {
                    for (SwiftBackupArchive.Entry entry : archive.entries) {
                        require(entry.name.matches("[A-Za-z0-9_.-]+\\.apk")
                                && !entry.name.equals("base.apk"), "Invalid Swift split APK name");
                        final File tar = new File(output, "payload.tar");
                        try {
                            archive.extract(entry, tar);
                            final File split = new File(output, "split_" + entry.name);
                            SwiftBackupTar.extractApk(tar, entry.name, split);
                            validateApk(split);
                            contents.add(split);
                            apkBytes += split.length();
                        } finally { tar.delete(); }
                    }
                    if (timestamp == 0) timestamp = archive.timestamp;
                }
            }
            final File data = inputs.get(pkg + ".dat");
            if (data != null) {
                try (SwiftBackupArchive archive = new SwiftBackupArchive(data, pkg)) {
                    if (!archive.metadata.isEmpty()) {
                        final JSONObject app = SwiftBackupGuest.parseMetadata(archive.metadata);
                        require("swiftbackup.app-data".equals(app.optString("kind"))
                                && app.optInt("version") == 1
                                && pkg.equals(app.optString("packageName")),
                                "Unsupported Swift app-data metadata");
                        if (app.has("entries")) {
                            final JSONArray declared = app.getJSONArray("entries");
                            final java.util.Set<String> expected = new java.util.HashSet<>();
                            for (int i = 0; i < declared.length(); i++) {
                                require(expected.add(declared.getString(i)),
                                        "Duplicate Swift app-data metadata entry");
                            }
                            require(expected.size() == archive.entries.size(),
                                    "Swift app-data metadata does not match its contents");
                            for (SwiftBackupArchive.Entry entry : archive.entries) {
                                require(expected.contains(entry.name),
                                        "Swift app-data metadata does not match its contents");
                            }
                        }
                        if (apk == null) {
                            versionName = app.optString("versionName", versionName);
                            versionCode = app.optLong("versionCode", versionCode);
                        }
                    }
                    for (SwiftBackupArchive.Entry entry : archive.entries) {
                        require(entry.name.equals("data") || entry.name.equals("data_de"),
                                "Unsupported Swift app-data component");
                        final boolean ce = entry.name.equals("data");
                        final File target = new File(output, ce ? "data_ce.tar" : "data_de.tar");
                        try (BufferedOutputStream out = new BufferedOutputStream(
                                new FileOutputStream(target))) {
                            append(archive, entry, pkg, "", output, out);
                            SwiftBackupTar.finish(out);
                        }
                        contents.add(target);
                        if (ce) {
                            ceBytes = target.length();
                            components |= AppDataBackupRestoreManager.COMPONENT_CE_DATA;
                        } else {
                            deBytes = target.length();
                            components |= AppDataBackupRestoreManager.COMPONENT_DE_DATA;
                        }
                    }
                    if (timestamp == 0) timestamp = archive.timestamp;
                }
            }
            final String[][] external = {{"extdat", "data/"}, {"exp", "obb/"}, {"med", "media/"}};
            final File ext = new File(output, "data_ext.tar");
            for (String[] part : external) {
                final File source = inputs.get(pkg + "." + part[0]);
                if (source == null) continue;
                try (SwiftBackupArchive archive = new SwiftBackupArchive(source, pkg);
                        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(ext, true))) {
                    require(archive.entries.size() == 1 && archive.entries.get(0).name.equals(pkg),
                            "Unsupported Swift external-data layout");
                    append(archive, archive.entries.get(0), pkg, part[1], output, out);
                    if (timestamp == 0) timestamp = archive.timestamp;
                }
            }
            if (ext.exists()) {
                try (FileOutputStream out = new FileOutputStream(ext, true)) { SwiftBackupTar.finish(out); }
                SwiftBackupTar.checkRestoreLimits(ext);
                extBytes = ext.length();
                contents.add(ext);
                components |= AppDataBackupRestoreManager.COMPONENT_EXTERNAL;
            }
            require(components != 0, "No supported backup payloads found in this Swift folder");
            require(label.length() <= 4096 && versionName.length() <= 4096,
                    "Swift app details are too large");
            require(apkBytes + ceBytes + deBytes + extBytes <= SwiftBackupArchive.MAX_BYTES,
                    "Swift backup exceeds the import size limit");
            if (timestamp <= 0) timestamp = System.currentTimeMillis();
            final String id = "swift_" + UUID.randomUUID();
            final BackupManifest manifest = new BackupManifest(id, pkg, label, versionName,
                    versionCode, timestamp, apkBytes, ceBytes, deBytes, extBytes, components,
                    false, BackupRecord.STATE_OK, "", userId);
            require(output.getUsableSpace() > unpackedBytes + (16 << 20),
                    "Not enough space to finish importing Swift backup");
            BackupArchive.write(new File(output, "import.vbak"), manifest.toJson(), contents, null);
            return manifest;
        } catch (JSONException e) {
            throw new IOException("Invalid Swift metadata", e);
        }
    }

    private static void append(SwiftBackupArchive archive, SwiftBackupArchive.Entry entry,
            String pkg, String prefix, File output, java.io.OutputStream out) throws IOException {
        final File tar = new File(output, "payload.tar");
        try {
            archive.extract(entry, tar);
            SwiftBackupTar.appendData(tar, pkg, prefix, out);
        } finally { tar.delete(); }
    }

    private static void validateApk(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            require(zip.getEntry("AndroidManifest.xml") != null, "Invalid Swift APK payload");
        }
    }
}
