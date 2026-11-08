/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Converts Swift's package-rooted tar streams to the tar subset supported by installd. */
final class SwiftBackupTar {
    private static final long MAX_FILE_BYTES = SwiftBackupArchive.MAX_BYTES;
    private static final int MAX_ENTRIES = 100000;
    private static final int MAX_PATH_BYTES = 4095;

    private SwiftBackupTar() {}

    static void forExport(File tar, String packageName, String component, File destination)
            throws IOException {
        checkRestoreLimits(tar);
        final Set<String> names = new HashSet<>();
        try (TarArchiveInputStream in = open(tar);
                TarArchiveOutputStream out = openForExport(destination)) {
            final TarArchiveEntry root = new TarArchiveEntry(packageName + "/");
            root.setMode(0700);
            putExportEntry(out, packageName + "/", root);
            out.closeArchiveEntry();
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                validate(entry);
                String path = safePath(entry.getName());
                require(names.add(path) && names.size() <= MAX_ENTRIES,
                        "Too many or duplicate app-data entries");
                if (!component.isEmpty()) {
                    require(path.equals("data") || path.startsWith("data/")
                                    || path.equals("obb") || path.startsWith("obb/")
                                    || path.equals("media") || path.startsWith("media/"),
                            "Unsupported external app-data folder");
                    if (path.indexOf('/') < 0) {
                        require(entry.isDirectory(), "External app-data root is not a directory");
                    }
                    if (path.equals(component)) continue;
                    if (!path.startsWith(component + "/")) continue;
                    path = path.substring(component.length() + 1);
                }
                putExportEntry(out, packageName + "/" + path, entry);
                copy(in, out, entry.getSize());
                out.closeArchiveEntry();
            }
            out.finish();
        }
    }

    static void apkForExport(File apk, String name, File destination) throws IOException {
        final TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(apk.length());
        entry.setMode(0600);
        try (InputStream in = new FileInputStream(apk);
                TarArchiveOutputStream out = openForExport(destination)) {
            putExportEntry(out, name, entry);
            copy(in, out, apk.length());
            out.closeArchiveEntry();
            out.finish();
        }
    }

    private static TarArchiveOutputStream openForExport(File destination) throws IOException {
        final TarArchiveOutputStream out = new TarArchiveOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(destination)), "UTF-8");
        out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        return out;
    }

    private static void putExportEntry(TarArchiveOutputStream out, String name,
            TarArchiveEntry source) throws IOException {
        final TarArchiveEntry target = new TarArchiveEntry(
                source.isDirectory() && !name.endsWith("/") ? name + "/" : name);
        target.setSize(source.getSize());
        target.setMode(source.getMode() & 0777);
        target.setModTime(Math.max(0, source.getModTime().getTime()));
        out.putArchiveEntry(target);
    }

    static void appendData(File tar, String packageName, String prefix, OutputStream output)
            throws IOException {
        checkTar(tar, true);
        final Set<String> names = new HashSet<>();
        int written = 0;
        try (TarArchiveInputStream in = open(tar)) {
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                validate(entry);
                final String path = safePath(entry.getName());
                require(path.equals(packageName) || path.startsWith(packageName + "/"),
                        "Swift data contains a different app or an unsupported folder layout");
                require(names.add(path), "Duplicate Swift data entry");
                String relative = path.substring(packageName.length());
                if (relative.startsWith("/")) relative = relative.substring(1);
                if (relative.isEmpty()) {
                    require(entry.isDirectory(), "Swift package root is not a directory");
                    if (prefix.isEmpty()) continue;
                }
                require(++written <= MAX_ENTRIES, "Too many Swift data entries");
                final String target = prefix + relative;
                writeHeader(output, target, entry);
                copy(in, output, entry.getSize());
                pad(output, entry.getSize());
            }
        }
    }

    static void extractApk(File tar, String expectedName, File destination) throws IOException {
        checkTar(tar, true);
        try (TarArchiveInputStream in = open(tar);
                OutputStream out = new FileOutputStream(destination)) {
            final TarArchiveEntry entry = in.getNextTarEntry();
            require(entry != null, "Empty Swift split APK archive");
            validate(entry);
            require(entry.isFile() && safePath(entry.getName()).equals(expectedName),
                    "Swift split APK header does not match its payload");
            copy(in, out, entry.getSize());
            require(in.getNextTarEntry() == null, "Unexpected files in Swift split APK archive");
        }
    }

    private static TarArchiveInputStream open(File file) throws IOException {
        return new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(file)),
                "UTF-8");
    }

    private static void validate(TarArchiveEntry entry) throws IOException {
        require(entry.isCheckSumOK(), "Swift tar checksum mismatch");
        require(!entry.isSparse() && (entry.isFile() || entry.isDirectory())
                && !entry.isSymbolicLink() && !entry.isLink(),
                "Swift backup contains a link or special file that App Backup cannot restore");
        require(entry.getSize() >= 0 && entry.getSize() <= MAX_FILE_BYTES
                && (!entry.isDirectory() || entry.getSize() == 0),
                "Swift backup contains an invalid or oversized file");
    }

    static String safePath(String name) throws IOException {
        require(!name.isEmpty() && !name.startsWith("/") && name.indexOf('\\') < 0
                && name.indexOf('\0') < 0, "Unsafe path in Swift backup");
        while (name.startsWith("./")) name = name.substring(2);
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        require(!name.isEmpty(), "Empty path in Swift backup");
        for (String part : name.split("/", -1)) {
            require(!part.isEmpty() && !part.matches("\\.+"), "Unsafe path in Swift backup");
        }
        return name;
    }

    private static void writeHeader(OutputStream out, String name, TarArchiveEntry entry)
            throws IOException {
        if (entry.isDirectory() && !name.endsWith("/")) name += "/";
        checkRestorePath(name);
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        String leaf = name;
        String prefix = "";
        if (nameBytes.length > 100) {
            int slash = name.lastIndexOf('/', name.length() - 2);
            while (slash > 0 && name.substring(0, slash).getBytes(StandardCharsets.UTF_8).length > 155) {
                slash = name.lastIndexOf('/', slash - 1);
            }
            if (slash > 0 && name.substring(slash + 1).getBytes(StandardCharsets.UTF_8).length <= 100) {
                prefix = name.substring(0, slash);
                leaf = name.substring(slash + 1);
            } else {
                writeHeader(out, "././@LongLink", "", nameBytes.length + 1, 0, 0, (byte) 'L');
                out.write(nameBytes);
                out.write(0);
                pad(out, nameBytes.length + 1);
                leaf = "././@LongLink";
            }
        }
        writeHeader(out, leaf, prefix, entry.getSize(), entry.getMode() & 0777,
                Math.max(0, entry.getModTime().getTime() / 1000),
                (byte) (entry.isDirectory() ? '5' : '0'));
    }

    private static void writeHeader(OutputStream out, String leaf, String prefix, long size,
            int mode, long mtime, byte type) throws IOException {
        final byte[] header = new byte[512];
        putString(header, 0, 100, leaf);
        putString(header, 345, 155, prefix);
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, mtime);
        header[156] = type;
        putString(header, 257, 6, "ustar");
        putString(header, 263, 2, "00");
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int checksum = 0;
        for (byte b : header) checksum += b & 255;
        putOctal(header, 148, 7, checksum);
        header[155] = ' ';
        out.write(header);
    }

    private static void checkRestorePath(String name) throws IOException {
        final String path = safePath(name);
        require(name.getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_BYTES,
                "App-data path exceeds the filesystem limit");
        for (String part : path.split("/")) {
            require(part.getBytes(StandardCharsets.UTF_8).length <= 255,
                    "App-data filename exceeds the filesystem limit");
        }
    }

    private static void putString(byte[] header, int offset, int limit, String value)
            throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= limit, "Swift backup path is too long for App Backup");
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static void putOctal(byte[] header, int offset, int length, long value)
            throws IOException {
        final String number = Long.toOctalString(value);
        require(value >= 0, "Swift tar numeric field is out of range");
        if (number.length() >= length) {
            require(length > Long.BYTES, "Swift tar numeric field is out of range");
            for (int i = offset + length - 1; i > offset; i--) {
                header[i] = (byte) value;
                value >>>= 8;
            }
            header[offset] = (byte) 0x80;
            return;
        }
        for (int i = 0; i < length - 1; i++) header[offset + i] = '0';
        putString(header, offset + length - 1 - number.length(), number.length(), number);
    }

    static void finish(OutputStream out) throws IOException {
        out.write(new byte[1024]);
    }

    private static void pad(OutputStream out, long size) throws IOException {
        out.write(new byte[(int) ((512 - size % 512) % 512)]);
    }

    static void checkRestoreLimits(File file) throws IOException {
        require(checkTar(file, false) <= MAX_ENTRIES, "Too many app-data entries for App Backup");
    }

    private static int checkTar(File file, boolean allowMetadata) throws IOException {
        require(file.length() >= 1024 && file.length() % 512 == 0
                && file.length() <= SwiftBackupArchive.MAX_BYTES, "Truncated or oversized Swift tar");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            final byte[] header = new byte[512];
            int count = 0, recordCount = 0, consecutiveMetadata = 0;
            long metadataBytes = 0, globalMetadataBytes = 0;
            Long globalPaxSize = null, localPaxSize = null;
            String longName = null;
            while (in.getFilePointer() < in.length()) {
                in.readFully(header);
                boolean zero = true;
                for (byte b : header) if (b != 0) { zero = false; break; }
                if (zero) {
                    require(in.length() - in.getFilePointer() >= 512, "Missing Swift tar terminator");
                    while (in.getFilePointer() < in.length()) {
                        in.readFully(header);
                        for (byte b : header) require(b == 0, "Unexpected bytes after Swift tar end");
                    }
                    require(consecutiveMetadata == 0, "Swift tar metadata has no file entry");
                    return count;
                }
                require(++recordCount <= MAX_ENTRIES * 3, "Too many Swift tar records");
                require((header[124] & 0x80) == 0 || (header[124] & 0xff) == 0x80,
                        "Negative or overflowing Swift tar size");
                final TarArchiveEntry entry = new TarArchiveEntry(header);
                require(entry.isCheckSumOK(), "Swift tar checksum mismatch");
                final long dataOffset = in.getFilePointer();
                final boolean metadata = entry.isPaxHeader() || entry.isGlobalPaxHeader()
                        || entry.isGNULongNameEntry();
                if (metadata) {
                    if (!allowMetadata) {
                        require(entry.isGNULongNameEntry() && consecutiveMetadata == 0,
                                "App Backup requires converted tar metadata");
                        require(entry.getSize() > 1 && entry.getSize() <= MAX_PATH_BYTES + 1,
                                "Invalid app-data long-name size");
                        final byte[] bytes = new byte[(int) entry.getSize()];
                        in.readFully(bytes);
                        require(bytes[bytes.length - 1] == 0,
                                "Unterminated app-data long name");
                        longName = StandardCharsets.UTF_8.newDecoder()
                                .decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1)).toString();
                        checkRestorePath(longName);
                    }
                    metadataBytes += entry.getSize();
                    if (entry.isGlobalPaxHeader()) globalMetadataBytes += entry.getSize();
                    require(entry.getSize() >= 0 && metadataBytes <= (1 << 20)
                            && globalMetadataBytes <= (1 << 20)
                            && ++consecutiveMetadata <= 8, "Swift tar metadata is too large");
                    if (entry.isPaxHeader() || entry.isGlobalPaxHeader()) {
                        final byte[] records = new byte[(int) entry.getSize()];
                        in.readFully(records);
                        if (entry.isGlobalPaxHeader()) {
                            globalPaxSize = paxSize(records, globalPaxSize);
                        } else {
                            localPaxSize = paxSize(records, localPaxSize);
                        }
                    }
                } else {
                    ++count;
                    require(header[156] == 0 || header[156] == '0' || header[156] == '5',
                            "Swift backup contains an unsupported tar record");
                    safePath(entry.getName());
                    if (!allowMetadata) {
                        checkRestorePath(longName != null ? longName : entry.getName());
                    }
                    longName = null;
                    final Long size = localPaxSize != null ? localPaxSize : globalPaxSize;
                    if (size != null && size >= 0) entry.setSize(size);
                    validate(entry);
                    localPaxSize = null;
                    consecutiveMetadata = 0;
                    metadataBytes = 0;
                }
                final long size = entry.getSize();
                final long padded = size + (512 - size % 512) % 512;
                require(padded <= in.length() - dataOffset - 1024,
                        "Truncated Swift tar entry");
                in.seek(dataOffset + padded);
            }
            throw new IOException("Missing Swift tar terminator");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Swift tar header", e);
        }
    }

    private static Long paxSize(byte[] records, Long previous) throws IOException {
        int offset = 0;
        while (offset < records.length) {
            final int start = offset;
            int length = 0;
            while (offset < records.length && records[offset] >= '0' && records[offset] <= '9') {
                final int digit = records[offset++] - '0';
                require(length <= (records.length - digit) / 10, "Invalid Swift PAX record length");
                length = length * 10 + digit;
            }
            require(offset > start && offset < records.length && records[offset++] == ' '
                    && length > offset - start + 2 && length <= records.length - start,
                    "Invalid Swift PAX record length");
            final int end = start + length;
            require(records[end - 1] == '\n', "Invalid Swift PAX record terminator");
            final int keyStart = offset;
            while (offset < end - 1 && records[offset] != '=') offset++;
            require(offset > keyStart && offset < end - 1, "Invalid Swift PAX field");
            final String key = new String(records, keyStart, offset - keyStart, StandardCharsets.UTF_8);
            if (key.equals("size")) {
                final String value = new String(records, offset + 1, end - offset - 2,
                        StandardCharsets.UTF_8);
                require(value.isEmpty() || value.matches("[0-9]{1,19}"), "Invalid Swift PAX size");
                try {
                    previous = value.isEmpty() ? -1L : Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid Swift PAX size", e);
                }
                require(previous <= MAX_FILE_BYTES, "Swift PAX size exceeds the import limit");
            }
            offset = end;
        }
        return previous;
    }

    static void copy(InputStream in, OutputStream out, long size) throws IOException {
        final byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            final int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            require(count > 0, "Truncated Swift backup payload");
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }
}
