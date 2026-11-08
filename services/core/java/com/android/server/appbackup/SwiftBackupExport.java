/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes Swift guest metadata with unencrypted SBA1 payloads. */
final class SwiftBackupExport {
    private SwiftBackupExport() {}

    static void write(BackupRecord record, File contents, File staging, OutputStream destination)
            throws IOException {
        final String pkg = record.getPackageName();
        SwiftBackupImport.packageName(new String[]{pkg + ".xml"});
        require(!record.isEncrypted(), "Create a backup without a passphrase before exporting to Swift");
        final String[] names = {"base.apk", "data_ce.tar", "data_de.tar", "data_ext.tar"};
        final int[] flags = {AppDataBackupRestoreManager.COMPONENT_APK,
                AppDataBackupRestoreManager.COMPONENT_CE_DATA,
                AppDataBackupRestoreManager.COMPONENT_DE_DATA,
                AppDataBackupRestoreManager.COMPONENT_EXTERNAL};
        for (int i = 0; i < names.length; i++) {
            final File file = new File(contents, names[i]);
            require((record.getComponents() & flags[i]) == 0 || file.isFile() && file.length() > 0,
                    "Backup is incomplete: missing " + names[i]);
        }
        final File[] apks = contents.listFiles(f -> f.getName().startsWith("split_")
                && f.getName().endsWith(".apk"));
        require(apks != null, "Cannot read backup contents");
        require(apks.length == 0 || new File(contents, "base.apk").isFile(),
                "Backup is incomplete: split APKs require base.apk");
        require(!new File(contents, "data_de.tar").exists()
                        || new File(contents, "data_ce.tar").exists(),
                "Swift cannot restore a DE-only backup. Create a backup including CE app data"
                        + " before exporting.");
        final File output = new File(staging, "swift");
        require(output.mkdir(), "Cannot create Swift export folder");
        try {
            final JSONObject metadata = new JSONObject();
            metadata.put("packageName", pkg);
            metadata.put("name", record.getLabel());
            metadata.put("versionName", record.getVersionName());
            metadata.put("versionCode", record.getVersionCode());
            metadata.put("dateBackup", record.getTimestampMs());
            metadata.put("keyVersion", 1);
            final File base = new File(contents, "base.apk");
            if (base.exists()) {
                Files.copy(base.toPath(), new File(output, pkg + ".app").toPath());
                details(metadata, "apk", base.length(), base.length(), record.getTimestampMs());
                final Map<String, File> splits = new LinkedHashMap<>();
                long bytes = 0;
                for (File apk : apks) {
                    final String name = apk.getName().substring("split_".length());
                    require(name.matches("[A-Za-z0-9_.-]+\\.apk"), "Unsupported split APK name");
                    final File tar = new File(staging, "export-" + splits.size() + ".tar");
                    SwiftBackupTar.apkForExport(apk, name, tar);
                    splits.put(name, tar);
                    bytes += apk.length();
                }
                if (!splits.isEmpty()) {
                    final File file = new File(output, pkg + ".splits");
                    archive(file, splits, "", record.getTimestampMs());
                    details(metadata, "splits", bytes, file.length(), 0);
                    for (File tar : splits.values()) tar.delete();
                }
            }
            final Map<String, File> data = new LinkedHashMap<>();
            final String[][] privateData = {{"data_ce.tar", "data"}, {"data_de.tar", "data_de"}};
            long dataBytes = 0;
            for (String[] part : privateData) {
                final File input = new File(contents, part[0]);
                if (!input.exists()) continue;
                final File tar = new File(staging, "export-" + part[0]);
                SwiftBackupTar.forExport(input, pkg, "", tar);
                data.put(part[1], tar);
                dataBytes += input.length();
            }
            if (!data.isEmpty()) {
                final JSONObject app = new JSONObject();
                app.put("kind", "swiftbackup.app-data");
                app.put("version", 1);
                app.put("packageName", pkg);
                app.put("appId", pkg.replace(".", ""));
                app.put("versionName", record.getVersionName());
                app.put("versionCode", record.getVersionCode());
                app.put("backupCache", true);
                app.put("includeDeviceProtectedData", data.containsKey("data_de"));
                app.put("compressionLevel", "NO_COMPRESSION");
                app.put("encrypted", false);
                app.put("dataSize", record.getCeDataSize());
                app.put("deDataSize", record.getDeDataSize());
                app.put("entries", new org.json.JSONArray(data.keySet()));
                final File file = new File(output, pkg + ".dat");
                archive(file, data, app.toString(), record.getTimestampMs());
                details(metadata, "data", dataBytes, file.length(), record.getTimestampMs());
                metadata.put("isDataEncrypted", false);
                for (File tar : data.values()) tar.delete();
            }
            final File external = new File(contents, "data_ext.tar");
            if (external.exists()) {
                final String[][] parts = {{"data", "extdat", "extData"},
                        {"obb", "exp", "exp"}, {"media", "med", "media"}};
                for (String[] part : parts) {
                    final File tar = new File(staging, "export-external.tar");
                    SwiftBackupTar.forExport(external, pkg, part[0], tar);

                    final File file = new File(output, pkg + "." + part[1]);
                    final Map<String, File> entries = new LinkedHashMap<>();
                    entries.put(pkg, tar);
                    archive(file, entries, "", record.getTimestampMs());
                    details(metadata, part[2], tar.length(), file.length(), record.getTimestampMs());
                    tar.delete();
                }
                metadata.put("isExtDataEncrypted", false);
                metadata.put("isMediaEncrypted", false);
                metadata.put("expansionBackupDate", record.getTimestampMs());
            }
            Files.writeString(new File(output, pkg + ".xml").toPath(),
                    SwiftBackupGuest.writeMetadata(metadata));
            try (ZipOutputStream zip = new ZipOutputStream(destination)) {
                final File[] files = output.listFiles();
                require(files != null && files.length > 1, "No exportable backup components");
                for (File file : files) {
                    zip.putNextEntry(new ZipEntry(file.getName()));
                    try (FileInputStream in = new FileInputStream(file)) {
                        SwiftBackupTar.copy(in, zip, file.length());
                    }
                    zip.closeEntry();
                }
            }
        } catch (JSONException e) {
            throw new IOException("Cannot create Swift metadata", e);
        }
    }

    private static void details(JSONObject json, String part, long size, long stored, long date)
            throws JSONException {
        json.put(part + "BackupSize", stored);
        json.put(part + "SizeMirrored", size);
        json.put(part + "SBVersionCodeRequired", 580);
        json.put(part + "SBVersionNameRequired", "v5.0.0");
        if (date > 0) json.put(part + "BackupDate", date);
    }

    static void archive(File output, Map<String, File> entries, String metadata, long timestamp)
            throws IOException {
        require(!entries.isEmpty() && entries.size() <= SwiftBackupArchive.MAX_ENTRIES,
                "Invalid Swift entry count");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(
                new FileOutputStream(output)))) {
            out.writeInt(0x53424131);
            out.writeShort(1);
            out.writeShort(96);
            out.writeInt(0);
            out.writeLong(timestamp);
            out.write(new byte[76]);
            long offset = 96;
            final ByteArrayOutputStream records = new ByteArrayOutputStream();
            final DataOutputStream index = new DataOutputStream(records);
            index.writeInt(0x53414931);
            index.writeShort(16);
            index.writeShort(1);
            index.writeInt(entries.size());
            final byte[] meta = SwiftBackupGuest.bytes(metadata);
            require(meta.length <= (1 << 20), "Swift metadata is too large");
            index.writeInt(meta.length);
            index.write(meta);
            for (Map.Entry<String, File> entry : entries.entrySet()) {
                final byte[] name = SwiftBackupGuest.bytes(entry.getKey());
                final File tar = entry.getValue();
                require(name.length > 0 && name.length <= 65535, "Invalid Swift entry name");
                final long payloadOffset = offset + 24 + name.length;
                require(tar.length() >= 1024 && tar.length() % 512 == 0
                        && tar.length() <= SwiftBackupArchive.MAX_BYTES - payloadOffset,
                        "Backup exceeds the Swift export size limit");
                out.writeInt(0x53414531);
                out.writeShort(24);
                out.writeShort(0);
                out.writeInt(1);
                out.writeShort(name.length);
                out.writeInt(0);
                out.write(new byte[6]);
                out.write(name);
                try (FileInputStream in = new FileInputStream(tar)) {
                    SwiftBackupTar.copy(in, out, tar.length());
                }
                index.writeLong(offset);
                index.writeLong(payloadOffset);
                index.writeLong(tar.length());
                index.writeLong(tar.length());
                index.writeLong(tar.length());
                index.writeInt(1);
                index.writeShort(name.length);
                index.writeInt(0);
                index.writeShort(0);
                index.write(new byte[32]);
                index.write(name);
                offset = payloadOffset + tar.length();
            }
            final byte[] bytes = records.toByteArray();
            require(offset + bytes.length + 32 <= SwiftBackupArchive.MAX_BYTES,
                    "Backup exceeds the Swift export size limit");
            out.write(bytes);
            final ByteArrayOutputStream footerBytes = new ByteArrayOutputStream();
            final DataOutputStream footer = new DataOutputStream(footerBytes);
            footer.writeInt(0x53414631);
            footer.writeShort(32);
            footer.writeShort(1);
            footer.writeLong(offset);
            footer.writeLong(bytes.length);
            footer.writeInt(crc(bytes));
            final byte[] tail = footerBytes.toByteArray();
            out.write(tail);
            out.writeInt(crc(tail));
        }
    }

    private static int crc(byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }
}
