/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

final class SystemDataArchive implements Closeable {
    static final String FILE_NAME = "system-data.zip";
    static final long MAX_BYTES = 8L << 30;
    static final int MAX_ROW = 4 << 20;
    static final int MAX_RECORDS = 1_000_000;
    static final long MAX_BLOB = 64L << 20;

    interface Check { void run() throws IOException; }
    interface Visitor { void accept(JSONObject row) throws IOException, JSONException; }

    private final ZipFile mZip;
    private long mValidatedBlobBytes;

    SystemDataArchive(File file) throws IOException {
        mZip = new ZipFile(file);
        try {
            require(mZip.size() >= 1 && mZip.size() <= 100_000, "Invalid system backup size");
            long bytes = 0;
            Set<String> names = new HashSet<>();
            var entries = mZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                require(names.add(name) && !entry.isDirectory()
                        && (name.equals("records.jsonl") || name.matches("blob-[a-f0-9-]{36}"))
                            && entry.getSize() >= 0 && entry.getCrc() >= 0
                            && entry.getSize() <= MAX_BYTES - bytes,
                        "Invalid system backup entry");
                if (!name.equals("records.jsonl")) {
                    require(entry.getSize() <= MAX_BLOB, "Attachment exceeds size limit");
                }
                bytes += entry.getSize();
            }
            require(names.contains("records.jsonl"), "Missing system backup records");
        } catch (IOException | RuntimeException e) {
            mZip.close();
            throw e;
        }
    }

    int visit(Visitor visitor, Check check) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                openEntry(mZip.getEntry("records.jsonl")), StandardCharsets.UTF_8.newDecoder()))) {
            int count = 0;
            String line;
            while ((line = readLine(reader)) != null) {
                check.run();
                require(++count <= MAX_RECORDS, "Too many system backup records");
                try {
                    visitor.accept(parse(line));
                } catch (JSONException | IllegalArgumentException e) {
                    throw new IOException("Malformed system backup record", e);
                }
            }
            return count;
        }
    }

    InputStream openBlob(String name) throws IOException {
        require(name.matches("blob-[a-f0-9-]{36}"), "Invalid attachment name");
        ZipEntry entry = mZip.getEntry(name);
        require(entry != null && entry.getSize() <= MAX_BLOB, "Missing attachment");
        return openEntry(entry);
    }

    private InputStream openEntry(ZipEntry entry) throws IOException {
        return new FilterInputStream(mZip.getInputStream(entry)) {
            private long mRead;
            private final CRC32 mCrc = new CRC32();
            @Override public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length);
                if (count == -1) {
                    require(mRead == entry.getSize() && mCrc.getValue() == entry.getCrc(),
                            "System backup ZIP checksum mismatch");
                } else {
                    require(count <= entry.getSize() - mRead, "System backup ZIP size mismatch");
                    mRead += count;
                    mCrc.update(bytes, offset, count);
                }
                return count;
            }
        };
    }

    void validateBlob(JSONObject part, Check check) throws IOException, JSONException {
        String name = part.getString("file");
        try (InputStream in = openBlob(name)) {
            long size = mZip.getEntry(name).getSize();
            require(size <= MAX_BYTES - mValidatedBlobBytes, "Restored attachments exceed size limit");
            mValidatedBlobBytes += size;
            require(hash(in, null, MAX_BLOB, check).equals(part.getString("sha256")),
                    "Attachment checksum mismatch");
        }
    }

    @Override
    public void close() throws IOException { mZip.close(); }

    static JSONObject parse(String text) throws IOException {
        require(text.length() <= MAX_ROW, "System backup record is too large");
        JSONTokener tokener = new JSONTokener(text) {
            private int mDepth;
            @Override
            public Object nextValue() throws JSONException {
                if (++mDepth > 16) throw syntaxError("Record nesting limit exceeded");
                try { return super.nextValue(); } finally { mDepth--; }
            }
        };
        try {
            JSONObject object = new JSONObject(tokener);
            require(tokener.nextClean() == 0, "Trailing data in system backup record");
            return object;
        } catch (JSONException e) {
            throw new IOException("Invalid system backup JSON", e);
        }
    }

    static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            require(line.length() < MAX_ROW, "System backup record is too large");
            line.append((char) c);
        }
        return c == -1 && line.length() == 0 ? null : line.toString();
    }

    static String hash(InputStream in, OutputStream out, long limit, Check check)
            throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            check.run();
            require(n <= limit - total, "System backup exceeds size limit");
            total += n;
            digest.update(buffer, 0, n);
            if (out != null) out.write(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 255) >>> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    static final class Writer implements Closeable {
        private final File mRecords;
        private final BufferedWriter mWriter;
        private final ZipOutputStream mZip;
        private final Check mCheck;
        private int mCount;
        private int mBlobs;
        private long mBytes;
        private long mRowBytes;

        Writer(File destination, Check check) throws IOException {
            mCheck = check;
            mRecords = File.createTempFile("records-", ".jsonl", destination.getParentFile());
            mWriter = Files.newBufferedWriter(mRecords.toPath(), StandardCharsets.UTF_8);
            try {
                mZip = new ZipOutputStream(new FileOutputStream(destination));
            } catch (IOException | RuntimeException e) {
                try { mWriter.close(); } finally { mRecords.delete(); }
                throw e;
            }
        }

        void row(JSONObject row) throws IOException {
            mCheck.run();
            String text = row.toString();
            require(text.length() <= MAX_ROW && ++mCount <= MAX_RECORDS,
                    "System backup record limit exceeded");
            long bytes = text.getBytes(StandardCharsets.UTF_8).length + 1L;
            require(bytes <= MAX_BYTES - mBytes - mRowBytes, "System backup exceeds size limit");
            mRowBytes += bytes;
            mWriter.write(text);
            mWriter.newLine();
        }

        JSONObject blob(InputStream in) throws IOException, JSONException {
            require(++mBlobs < 100_000, "Too many attachments");
            String name = "blob-" + UUID.randomUUID();
            mZip.putNextEntry(new ZipEntry(name));
            final long[] written = {0};
            OutputStream out = new OutputStream() {
                @Override public void write(int b) throws IOException {
                    mZip.write(b); written[0]++;
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    mZip.write(b, off, len); written[0] += len;
                }
            };
            String hash = hash(in, out, Math.min(MAX_BLOB, MAX_BYTES - mBytes - mRowBytes), mCheck);
            mBytes += written[0];
            mZip.closeEntry();
            return new JSONObject().put("file", name).put("sha256", hash);
        }

        void finish() throws IOException {
            mWriter.close();
            mZip.putNextEntry(new ZipEntry("records.jsonl"));
            try (InputStream in = new FileInputStream(mRecords)) {
                hash(in, mZip, MAX_BYTES - mBytes, mCheck);
            }
            mZip.closeEntry();
            mZip.finish();
        }

        int count() { return mCount; }

        @Override public void close() throws IOException {
            try { mWriter.close(); }
            finally {
                try { mZip.close(); }
                finally { mRecords.delete(); }
            }
        }
    }
}
