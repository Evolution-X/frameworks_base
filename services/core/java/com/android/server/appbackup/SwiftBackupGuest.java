/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Swift's public, certificate-derived guest identity; never derives Google account keys. */
final class SwiftBackupGuest {

    private static final String GUEST_ID = "d58b0944415a4889d7f11aa95fbeca50";
    private static final byte[] ENTITY = bytes("SwiftBackup_Entity");

    private SwiftBackupGuest() {}

    static byte[] password(String packageName) {
        return bytes(murmur128(new StringBuilder(GUEST_ID).reverse().toString()
                + new StringBuilder(packageName.replace(".", "")).reverse()));
    }

    static JSONObject readMetadata(String text) throws IOException {
        try {
            if (text.startsWith("{")) return parseMetadata(text);
            final String[] parts = text.split(":::", -1);
            require(parts.length == 3 && parts[0].equals("v1"),
                    "Unsupported Swift metadata format");
            require(GUEST_ID.equals(decode(parts[1])),
                    "Swift metadata belongs to a different account");
            return parseMetadata(decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("This Swift backup uses a Google account or an unsupported"
                    + " guest identity, or its metadata is damaged", e);
        }
    }

    static JSONObject parseMetadata(String text) throws IOException {
        require(text.length() <= (1 << 20), "Swift metadata is too large");
        final JSONTokener input = new JSONTokener(text) {
            private int mDepth;

            @Override
            public Object nextValue() throws JSONException {
                if (++mDepth > 32) throw syntaxError("Swift metadata is nested too deeply");
                try {
                    return super.nextValue();
                } finally {
                    mDepth--;
                }
            }
        };
        try {
            final JSONObject result = new JSONObject(input);
            require(input.nextClean() == 0, "Unexpected bytes after Swift metadata");
            return result;
        } catch (JSONException e) {
            throw new IOException("Invalid Swift metadata", e);
        }
    }

    static String writeMetadata(JSONObject metadata) throws IOException {
        try {
            return "v1:::" + encode(GUEST_ID) + ":::" + encode(metadata.toString());
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot create Swift guest metadata", e);
        }
    }

    private static String decode(String encoded) throws GeneralSecurityException, IOException {
        final byte[] value = Base64.decode(encoded, Base64.DEFAULT);
        require(value.length >= 30 && value[0] == 1 && value[1] == 2,
                "Unsupported Swift metadata encryption");
        final Cipher cipher = metadataCipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(value, 2, 14));
        return new String(cipher.doFinal(value, 14, value.length - 14), StandardCharsets.UTF_8);
    }

    static void decodeSystemFile(java.io.File source, java.io.File destination) throws IOException {
        require(source.length() >= 30 && source.length() <= (64L << 20),
                "Swift system metadata is empty or exceeds the 64 MiB limit");
        try (java.io.InputStream in = new java.io.FileInputStream(source);
                java.io.OutputStream out = new java.io.FileOutputStream(destination)) {
            require(in.read() == 1 && in.read() == 2, "Unsupported Swift system metadata encryption");
            byte[] nonce = new byte[12];
            new java.io.DataInputStream(in).readFully(nonce);
            Cipher cipher = metadataCipher(Cipher.DECRYPT_MODE, nonce);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                byte[] clear = cipher.update(buffer, 0, count);
                if (clear != null) out.write(clear);
            }
            out.write(cipher.doFinal());
        } catch (GeneralSecurityException e) {
            destination.delete();
            throw new IOException("This Swift system backup belongs to another account or is damaged."
                    + " Only guest and unencrypted system backups can be imported.", e);
        }
    }

    private static String encode(String value) throws GeneralSecurityException {
        final byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        final byte[] encrypted = metadataCipher(Cipher.ENCRYPT_MODE, nonce).doFinal(bytes(value));
        return Base64.encodeToString(ByteBuffer.allocate(14 + encrypted.length)
                .put((byte) 1).put((byte) 2).put(nonce).put(encrypted).array(), Base64.NO_WRAP);
    }

    private static Cipher metadataCipher(int mode, byte[] nonce) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(bytes(GUEST_ID), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(new byte[]{1, 2});
        cipher.updateAAD(ENTITY);
        return cipher;
    }

    static byte[] sha256(byte[]... parts) throws IOException {
        try {
            final MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) hash.update(part);
            return hash.digest();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[]... parts) throws IOException {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) mac.update(part);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String murmur128(String value) {
        final byte[] data = bytes(value);
        final ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;
        long h1 = 0, h2 = 0;
        while (input.remaining() >= 16) {
            final long k1 = input.getLong(), k2 = input.getLong();
            h1 ^= Long.rotateLeft(k1 * c1, 31) * c2;
            h1 = (Long.rotateLeft(h1, 27) + h2) * 5 + 0x52dce729;
            h2 ^= Long.rotateLeft(k2 * c2, 33) * c1;
            h2 = (Long.rotateLeft(h2, 31) + h1) * 5 + 0x38495ab5;
        }
        long k1 = 0, k2 = 0;
        final int remaining = input.remaining();
        for (int i = 0; i < remaining; i++) {
            final long next = input.get() & 255L;
            if (i < 8) k1 |= next << (i * 8);
            else k2 |= next << ((i - 8) * 8);
        }
        if (remaining > 8) h2 ^= Long.rotateLeft(k2 * c2, 33) * c1;
        if (remaining > 0) h1 ^= Long.rotateLeft(k1 * c1, 31) * c2;
        h1 ^= data.length;
        h2 ^= data.length;
        h1 += h2;
        h2 += h1;
        h1 = mix(h1);
        h2 = mix(h2);
        h1 += h2;
        h2 += h1;
        return String.format(Locale.ROOT, "%016x%016x", h2, h1);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }
}
