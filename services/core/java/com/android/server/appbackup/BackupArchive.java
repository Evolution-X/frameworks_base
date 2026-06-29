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
import android.annotation.Nullable;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class BackupArchive {

    static final String EXTENSION = ".vbak";

    private static final byte[] MAGIC = "VBAKUP01".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;

    private static final String SCHEME_NONE = "none";
    private static final String SCHEME_AES = "AES-256-GCM";
    private static final String KDF = "PBKDF2WithHmacSHA256";

    private static final int KEY_BITS = 256;
    private static final int DEFAULT_ITERATIONS = 120000;
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int CHUNK = 64 * 1024;
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_HEADER = 8 * 1024 * 1024;
    private static final int MAX_FRAME = CHUNK + 1024;

    private static final String COMP_NONE = "none";
    private static final String COMP_DEFLATE = "deflate";
    private static final int DEFLATE_LEVEL = Deflater.DEFAULT_COMPRESSION;

    private BackupArchive() {}

    static final class BadPassphraseException extends IOException {
        BadPassphraseException(String message) { super(message); }
    }

    static final class IntegrityException extends IOException {
        IntegrityException(String message) { super(message); }
    }

    static void write(@NonNull File outFile, @NonNull JSONObject manifestJson,
            @NonNull List<File> entries, @Nullable char[] passphrase) throws IOException {
        final boolean encrypt = passphrase != null && passphrase.length > 0;
        final SecureRandom rng = new SecureRandom();

        byte[] salt = null;
        SecretKey key = null;
        final int iterations = DEFAULT_ITERATIONS;

        final JSONObject header = new JSONObject();
        try {
            header.put("formatVersion", FORMAT_VERSION);
            header.put("manifest", manifestJson);

            final JSONObject enc = new JSONObject();
            if (encrypt) {
                salt = new byte[SALT_LEN];
                rng.nextBytes(salt);
                try {
                    key = deriveKey(passphrase, salt, iterations);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Key derivation failed", e);
                }
                enc.put("scheme", SCHEME_AES);
                enc.put("kdf", KDF);
                enc.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
                enc.put("iterations", iterations);
                enc.put("keyBits", KEY_BITS);
                enc.put("chunkSize", CHUNK);
            } else {
                enc.put("scheme", SCHEME_NONE);
            }
            header.put("encryption", enc);

            final JSONArray arr = new JSONArray();
            for (File f : entries) {
                final JSONObject e = new JSONObject();
                e.put("name", f.getName());
                e.put("size", f.length());
                e.put("sha256", sha256OfFile(f));
                arr.put(e);
            }
            header.put("entries", arr);
            header.put("compression", COMP_DEFLATE);
        } catch (JSONException e) {
            throw new IOException("Failed to build archive header", e);
        }

        final byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        final File tmp = new File(outFile.getAbsolutePath() + ".tmp");

        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(tmp), BUFFER)) {
            raw.write(MAGIC);
            writeInt(raw, headerBytes.length);
            raw.write(headerBytes);

            final Deflater deflater = new Deflater(DEFLATE_LEVEL);
            final InputStream logical = concat(entries);
            final InputStream transport = new DeflaterInputStream(logical, deflater, BUFFER);
            try {
                if (!encrypt) {
                    pipe(transport, raw);
                } else {
                    writeEncrypted(raw, transport, key, rng);
                }
            } finally {
                transport.close();
                deflater.end();
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }

        if (!tmp.renameTo(outFile)) {
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new BufferedOutputStream(
                         new FileOutputStream(outFile), BUFFER)) {
                pipe(in, out);
            }
            tmp.delete();
            if (!outFile.exists()) {
                throw new IOException("Failed to finalize archive: " + outFile);
            }
        }
    }

    private static void writeEncrypted(@NonNull OutputStream raw, @NonNull InputStream src,
            @NonNull SecretKey key, @NonNull SecureRandom rng) throws IOException {
        final byte[] buf = new byte[CHUNK];
        int filled = 0;
        int n;
        while ((n = src.read(buf, filled, buf.length - filled)) != -1) {
            filled += n;
            if (filled == buf.length) {
                writeFrame(raw, key, rng, buf, filled);
                filled = 0;
            }
        }
        if (filled > 0) {
            writeFrame(raw, key, rng, buf, filled);
        }
    }

    private static void writeFrame(@NonNull OutputStream raw, @NonNull SecretKey key,
            @NonNull SecureRandom rng, @NonNull byte[] plain, int len) throws IOException {
        try {
            final byte[] nonce = new byte[NONCE_LEN];
            rng.nextBytes(nonce);
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            final byte[] ct = c.doFinal(plain, 0, len);
            writeInt(raw, ct.length);
            raw.write(nonce);
            raw.write(ct);
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed", e);
        }
    }

    @NonNull
    static JSONObject readManifest(@NonNull File inFile) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile))) {
            return readManifest(in);
        }
    }

    @NonNull
    static JSONObject readManifest(@NonNull InputStream in) throws IOException {
        final JSONObject header = readHeader(in);
        try {
            return header.getJSONObject("manifest");
        } catch (JSONException e) {
            throw new IOException("Malformed archive header", e);
        }
    }

    static void extract(@NonNull File inFile, @NonNull File destDir,
            @Nullable char[] passphrase) throws IOException {
        process(inFile, destDir, passphrase);
    }

    static void verify(@NonNull File inFile, @Nullable char[] passphrase) throws IOException {
        process(inFile, null, passphrase);
    }

    static void verify(@NonNull InputStream in, @Nullable char[] passphrase) throws IOException {
        process(in, null, passphrase);
    }

    static void extract(@NonNull InputStream in, @NonNull File destDir,
            @Nullable char[] passphrase) throws IOException {
        process(in, destDir, passphrase);
    }

    static boolean isEncrypted(@NonNull File inFile) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile))) {
            final JSONObject header = readHeader(in);
            final JSONObject enc = header.optJSONObject("encryption");
            final String scheme = (enc == null)
                    ? SCHEME_NONE : enc.optString("scheme", SCHEME_NONE);
            return SCHEME_AES.equals(scheme);
        }
    }

    private static void process(@NonNull File inFile, @Nullable File destDir,
            @Nullable char[] passphrase) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile))) {
            process(in, destDir, passphrase);
        }
    }

    private static void process(@NonNull InputStream rawIn, @Nullable File destDir,
            @Nullable char[] passphrase) throws IOException {
        final InputStream in = (rawIn instanceof BufferedInputStream)
                ? rawIn : new BufferedInputStream(rawIn);
        final JSONObject header = readHeader(in);

            final JSONObject enc;
            final JSONArray entries;
            final String scheme;
            final String compression;
            try {
                enc = header.getJSONObject("encryption");
                entries = header.getJSONArray("entries");
                scheme = enc.optString("scheme", SCHEME_NONE);
                compression = header.optString("compression", COMP_NONE);
            } catch (JSONException e) {
                throw new IOException("Malformed archive header", e);
            }

            if (destDir != null && !destDir.exists() && !destDir.mkdirs()) {
                throw new IOException("Cannot create extract dir: " + destDir);
            }

            InputStream payload;
            if (SCHEME_NONE.equals(scheme)) {
                payload = in;
            } else if (SCHEME_AES.equals(scheme)) {
                if (passphrase == null || passphrase.length == 0) {
                    throw new BadPassphraseException(
                            "Backup is encrypted; a passphrase is required");
                }
                final byte[] salt;
                final int iterations;
                try {
                    salt = Base64.decode(enc.getString("salt"), Base64.NO_WRAP);
                    iterations = enc.optInt("iterations", DEFAULT_ITERATIONS);
                } catch (JSONException e) {
                    throw new IOException("Malformed encryption header", e);
                }
                final SecretKey key;
                try {
                    key = deriveKey(passphrase, salt, iterations);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Key derivation failed", e);
                }
                payload = new DecryptingStream(in, key);
            } else {
                throw new IOException("Unsupported encryption scheme: " + scheme);
            }

            Inflater inflater = null;
            if (COMP_DEFLATE.equals(compression)) {
                inflater = new Inflater();
                payload = new InflaterInputStream(payload, inflater, BUFFER);
            } else if (!COMP_NONE.equals(compression)) {
                throw new IOException("Unsupported compression: " + compression);
            }

            try {
                for (int i = 0; i < entries.length(); i++) {
                    final JSONObject e = entries.getJSONObject(i);
                    final String name = sanitizeName(e.getString("name"));
                    final long size = e.getLong("size");
                    final String expected = e.optString("sha256", null);

                    final MessageDigest md;
                    try {
                        md = MessageDigest.getInstance("SHA-256");
                    } catch (GeneralSecurityException ex) {
                        throw new IOException("SHA-256 unavailable", ex);
                    }

                    OutputStream os = null;
                    if (destDir != null) {
                        os = new BufferedOutputStream(
                                new FileOutputStream(new File(destDir, name)), BUFFER);
                    }
                    try {
                        readEntry(payload, os, size, md);
                    } finally {
                        if (os != null) os.close();
                    }

                    if (expected != null && !expected.isEmpty()
                            && !expected.equalsIgnoreCase(toHex(md.digest()))) {
                        throw new IntegrityException(
                                "Integrity check failed for entry: " + name);
                    }
                }
            } catch (JSONException e) {
                throw new IOException("Malformed entry table", e);
            } finally {
                if (inflater != null) inflater.end();
            }
    }

    private static void readEntry(@NonNull InputStream payload, @Nullable OutputStream out,
            long size, @NonNull MessageDigest md) throws IOException {
        final byte[] buf = new byte[BUFFER];
        long remaining = size;
        while (remaining > 0) {
            final int toRead = (int) Math.min(remaining, buf.length);
            final int n = payload.read(buf, 0, toRead);
            if (n == -1) throw new IOException("Unexpected end of payload");
            md.update(buf, 0, n);
            if (out != null) out.write(buf, 0, n);
            remaining -= n;
        }
    }

    @NonNull
    private static JSONObject readHeader(@NonNull InputStream in) throws IOException {
        final byte[] magic = new byte[MAGIC.length];
        readFully(in, magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a VBAK archive");
        }
        final int hlen = readInt(in);
        if (hlen <= 0 || hlen > MAX_HEADER) {
            throw new IOException("Bad header length: " + hlen);
        }
        final byte[] hb = new byte[hlen];
        readFully(in, hb);
        try {
            return new JSONObject(new String(hb, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Malformed archive header", e);
        }
    }

    private static SecretKey deriveKey(@NonNull char[] passphrase, @NonNull byte[] salt,
            int iterations) throws GeneralSecurityException {
        final SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF);
        final PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            final byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String sanitizeName(@Nullable String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/")
                || name.contains("\\") || name.equals("..") || name.indexOf('\0') >= 0) {
            throw new IOException("Illegal entry name: " + name);
        }
        return name;
    }

    private static InputStream concat(@NonNull List<File> files) {
        final Iterator<File> it = files.iterator();
        return new SequenceInputStream(new Enumeration<InputStream>() {
            @Override public boolean hasMoreElements() { return it.hasNext(); }
            @Override public InputStream nextElement() {
                try {
                    return new FileInputStream(it.next());
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot open backup entry", e);
                }
            }
        });
    }

    private static String sha256OfFile(@NonNull File f) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[BUFFER];
            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(@NonNull byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void pipe(@NonNull InputStream in, @NonNull OutputStream out)
            throws IOException {
        final byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void writeInt(@NonNull OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static int readInt(@NonNull InputStream in) throws IOException {
        final byte[] b = new byte[4];
        readFully(in, b);
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static int tryReadInt(@NonNull InputStream in) throws IOException {
        final int b1 = in.read();
        if (b1 == -1) return -1;
        final int b2 = in.read();
        final int b3 = in.read();
        final int b4 = in.read();
        if ((b2 | b3 | b4) < 0) throw new IOException("Truncated frame length");
        return ((b1 & 0xff) << 24) | ((b2 & 0xff) << 16)
                | ((b3 & 0xff) << 8) | (b4 & 0xff);
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] buf)
            throws IOException {
        int off = 0;
        while (off < buf.length) {
            final int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("Unexpected end of archive");
            off += n;
        }
    }

    private static final class DecryptingStream extends InputStream {
        private final InputStream mIn;
        private final SecretKey mKey;
        private byte[] mBuf = new byte[0];
        private int mPos = 0;
        private boolean mEof = false;

        DecryptingStream(@NonNull InputStream in, @NonNull SecretKey key) {
            mIn = in;
            mKey = key;
        }

        @Override
        public int read() throws IOException {
            if (!ensure()) return -1;
            return mBuf[mPos++] & 0xff;
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!ensure()) return -1;
            final int n = Math.min(len, mBuf.length - mPos);
            System.arraycopy(mBuf, mPos, b, off, n);
            mPos += n;
            return n;
        }

        private boolean ensure() throws IOException {
            while (mPos >= mBuf.length) {
                if (mEof) return false;
                if (!nextFrame()) {
                    mEof = true;
                    return false;
                }
            }
            return true;
        }

        private boolean nextFrame() throws IOException {
            final int len = tryReadInt(mIn);
            if (len == -1) return false;
            if (len <= 0 || len > MAX_FRAME) {
                throw new IOException("Corrupt frame length: " + len);
            }
            final byte[] nonce = new byte[NONCE_LEN];
            readFully(mIn, nonce);
            final byte[] ct = new byte[len];
            readFully(mIn, ct);
            try {
                final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, mKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
                mBuf = c.doFinal(ct);
                mPos = 0;
            } catch (AEADBadTagException e) {
                throw new BadPassphraseException(
                        "Authentication failed (wrong passphrase or corrupt backup)");
            } catch (GeneralSecurityException e) {
                throw new IOException("Decryption failed", e);
            }
            return true;
        }
    }
}
