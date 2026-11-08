/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** Reader for unencrypted and default-guest Swift Backup SBA1 containers (header versions 1 and 2). */
final class SwiftBackupArchive implements Closeable {
    static final long MAX_BYTES = 1L << 40;
    private static final int MAX_INDEX = 16 << 20;
    static final int MAX_ENTRIES = 1024;

    static final class EncryptedException extends IOException {
        EncryptedException() {
            super("This Swift backup needs its original Google account or custom password,"
                    + " or uses unsupported encryption. Only guest backups without a custom"
                    + " password and unencrypted backups can be imported.");
        }
    }

    static final class Entry {
        final String name;
        final long offset;
        final long storedSize;
        final long tarSize;
        final long compressedSize;
        final int order;

        Entry(String name, long offset, long storedSize, long compressedSize, long tarSize, int order) {
            this.name = name;
            this.offset = offset;
            this.storedSize = storedSize;
            this.tarSize = tarSize;
            this.compressedSize = compressedSize;
            this.order = order;
        }
    }

    final long timestamp;
    final String metadata;
    final List<Entry> entries;
    private final File mFile;
    private final boolean mCompressed;
    private byte[] mKey;
    private byte[] mNonce;
    private int mChunkSize;

    SwiftBackupArchive(File file) throws IOException {
        this(file, null);
    }

    SwiftBackupArchive(File file, String packageName) throws IOException {
        mFile = file;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            final long length = in.length();
            require(length >= 128 && length <= MAX_BYTES, "Invalid Swift archive size");
            require(in.readInt() == 0x53424131,
                    "Unsupported Swift archive format; expected an SBA1 backup");
            final int version = in.readUnsignedShort();
            require(version == 1 || version == 2, "Unsupported Swift archive version");
            final int headerSize = in.readUnsignedShort();
            require(headerSize == (version == 1 ? 96 : 144), "Invalid Swift header size");
            final int flags = in.readInt();
            require((flags & ~3) == 0, "Unsupported Swift archive flags");
            timestamp = in.readLong();
            require(timestamp >= 0, "Invalid Swift backup date");
            final int compression = in.readUnsignedShort();
            final int level = in.readUnsignedShort();
            final int encryption = in.readUnsignedShort();
            final boolean encrypted = encryption != 0;
            require(encrypted == ((flags & 2) != 0), "Inconsistent Swift encryption header");
            if (encrypted && (encryption != 4 || packageName == null)) {
                throw new EncryptedException();
            }
            require(compression == 0 || compression == 1, "Unsupported Swift compression");
            mCompressed = compression == 1;
            require(mCompressed == ((flags & 1) != 0)
                    && (mCompressed ? level >= 1 && level <= 22 : level == 0),
                    "Inconsistent Swift compression header");
            final byte[] header = new byte[headerSize];
            in.seek(0);
            in.readFully(header);
            if (encrypted) {
                final ByteBuffer crypto = ByteBuffer.wrap(header);
                crypto.position(26);
                require(crypto.getShort() == 1, "Unsupported Swift key derivation");
                final int iterations = crypto.getShort() & 65535;
                require(crypto.getShort() == 16 && crypto.getShort() == 16
                        && crypto.getShort() == 16, "Invalid Swift encryption parameters");
                final byte[] check = Arrays.copyOfRange(header, 36, 52);
                final byte[] salt = Arrays.copyOfRange(header, 52, 68);
                mNonce = Arrays.copyOfRange(header, 68, 84);
                crypto.position(84);
                require(crypto.getInt() == 0, "Unsupported Swift encryption flags");
                mChunkSize = crypto.getInt();
                require(mChunkSize >= 4096 && mChunkSize <= (1 << 20), "Invalid Swift chunk size");
                require(version != 1 || iterations == 3, "Invalid Swift v1 key derivation");
                final int memory = version == 1 ? 16384 : crypto.getInt();
                final int lanes = version == 1 ? 1 : crypto.getShort() & 65535;
                if (version == 2) {
                    require(crypto.getShort() == 1 && crypto.getShort() == 32
                            && crypto.getShort() == 1, "Unsupported Swift index authentication");
                    in.seek(136);
                    requireZero(in, 8);
                } else {
                    in.seek(92);
                    requireZero(in, 4);
                }
                final byte[] password = SwiftBackupGuest.password(packageName);
                try {
                    mKey = SwiftBackupNative.deriveKey(password, salt, iterations, memory, lanes);
                } finally {
                    Arrays.fill(password, (byte) 0);
                }
                final byte[] expected = Arrays.copyOf(SwiftBackupGuest.sha256(
                        SwiftBackupGuest.bytes("SBA1-AEGIS256-key-check-v1"), salt, mNonce, mKey), 16);
                if (!MessageDigest.isEqual(check, expected)) throw new EncryptedException();
            } else {
                in.seek(26);
                requireZero(in, headerSize - 26);
            }

            in.seek(length - 32);
            final byte[] footer = new byte[32];
            in.readFully(footer);
            final ByteBuffer foot = ByteBuffer.wrap(footer);
            require(foot.getInt() == 0x53414631 && foot.getShort() == 32
                    && foot.getShort() == version, "Invalid Swift footer");
            final long indexOffset = foot.getLong();
            final long indexSize = foot.getLong();
            final int indexCrc = foot.getInt();
            require(foot.getInt() == crc32(footer, 28), "Swift footer checksum mismatch");
            require(indexOffset >= headerSize && indexSize >= 16 && indexSize <= MAX_INDEX
                    && indexOffset == length - 32 - indexSize, "Invalid Swift index bounds");
            final byte[] index = new byte[(int) indexSize];
            in.seek(indexOffset);
            in.readFully(index);
            require(indexCrc == crc32(index, index.length), "Swift index checksum mismatch");
            if (encrypted && version == 2) {
                final byte[] mac = Arrays.copyOfRange(header, 104, 136);
                Arrays.fill(header, 104, 136, (byte) 0);
                final byte[] macKey = SwiftBackupGuest.sha256(
                        SwiftBackupGuest.bytes("SBA2-index-mac-key-v1"),
                        Arrays.copyOfRange(header, 52, 68), mNonce, mKey);
                try {
                    require(MessageDigest.isEqual(mac, SwiftBackupGuest.hmac(macKey,
                            SwiftBackupGuest.bytes("SBA2-index-metadata-mac-v1"), header,
                            Arrays.copyOfRange(footer, 8, 28), index)),
                            "Swift index authentication failed");
                } finally {
                    Arrays.fill(macKey, (byte) 0);
                }
            }

            final List<Entry> parsed = new ArrayList<>();
            final Set<String> names = new HashSet<>();
            try (DataInputStream records = new DataInputStream(new ByteArrayInputStream(index))) {
                require(records.readInt() == 0x53414931 && records.readUnsignedShort() == 16,
                        "Invalid Swift index header");
                final int indexVersion = records.readUnsignedShort();
                require(indexVersion == 1 || indexVersion == 2, "Unsupported Swift index version");
                final int count = records.readInt();
                final int metadataSize = records.readInt();
                require(count > 0 && count <= MAX_ENTRIES
                        && metadataSize >= 0 && metadataSize <= (1 << 20)
                        && metadataSize + (long) count * (indexVersion == 1 ? 85 : 109)
                                <= records.available(), "Invalid Swift index record sizes");
                metadata = readUtf8(records, metadataSize);
                long nextOffset = headerSize;
                long totalTarSize = 0;
                for (int i = 0; i < count; i++) {
                    final long headerOffset = records.readLong();
                    final long offset = records.readLong();
                    final long storedSize = records.readLong();
                    final long compressedSize = records.readLong();
                    final long tarSize = records.readLong();
                    final int entryFlags = records.readInt();
                    final int nameSize = records.readUnsignedShort();
                    require(records.readInt() == 0 && records.readUnsignedShort() == 0,
                            "Unsupported Swift entry metadata");
                    requireZero(records, 32);
                    if (indexVersion == 2) {
                        final long members = records.readLong();
                        final long files = records.readLong();
                        final long dirs = records.readLong();
                        require(members >= 0 && files >= 0 && dirs >= 0
                                && files <= members && dirs <= members - files,
                                "Invalid Swift member counts");
                    }
                    final String name = readUtf8(records, nameSize);
                    require(!name.isEmpty() && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                            && !name.equals(".") && !name.equals("..") && names.add(name),
                            "Invalid or duplicate Swift entry name");
                    require(entryFlags == ((mCompressed ? 3 : 1) | (encrypted ? 20 : 0)), "Invalid Swift entry flags");
                    require(headerOffset == nextOffset && offset == headerOffset + 24 + nameSize
                            && offset <= indexOffset && storedSize > 0
                            && storedSize <= indexOffset - offset && compressedSize > 0
                            && compressedSize <= storedSize
                            && storedSize == compressedSize + (encrypted
                                    ? ((compressedSize - 1) / mChunkSize + 1) * 16 : 0)
                            && tarSize >= 1024 && tarSize % 512 == 0
                            && tarSize <= MAX_BYTES - totalTarSize
                            && (mCompressed || tarSize == compressedSize), "Invalid Swift entry bounds");
                    in.seek(headerOffset);
                    require(in.readInt() == 0x53414531 && in.readUnsignedShort() == 24
                            && in.readUnsignedShort() == 0 && in.readInt() == entryFlags
                            && in.readUnsignedShort() == nameSize && in.readInt() == 0,
                            "Swift entry header/index mismatch");
                    requireZero(in, 6);
                    require(name.equals(readUtf8(in, nameSize)), "Swift entry name mismatch");
                    parsed.add(new Entry(name, offset, storedSize, compressedSize, tarSize, i));
                    nextOffset = offset + storedSize;
                    totalTarSize += tarSize;
                }
                require(nextOffset == indexOffset && records.available() == 0,
                        "Unexpected bytes in Swift archive");
            }
            entries = Collections.unmodifiableList(parsed);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    void extract(Entry entry, File destination) throws IOException {
        require(entries.contains(entry), "Unknown Swift entry");
        require(destination.getParentFile().getUsableSpace()
                        > Math.max(entry.tarSize * 2, entry.tarSize + entry.compressedSize)
                                + (16 << 20),
                "Not enough space to import Swift backup");
        final File decrypted = new File(destination.getPath() + ".compressed");
        try {
            File payload = mFile;
            long offset = entry.offset;
            long size = entry.storedSize;
            if (mKey != null) {
                decryptEntry(entry, decrypted);
                payload = decrypted;
                offset = 0;
                size = entry.compressedSize;
            }
            try (RandomAccessFile in = new RandomAccessFile(payload, "r");
                    FileOutputStream out = new FileOutputStream(destination)) {
                if (mCompressed) {
                    SwiftBackupNative.decompress(in.getFD(), offset, size, out.getFD(), entry.tarSize);
                } else {
                    in.seek(offset);
                    final byte[] buffer = new byte[65536];
                    while (size > 0) {
                        final int count = (int) Math.min(buffer.length, size);
                        in.readFully(buffer, 0, count);
                        out.write(buffer, 0, count);
                        size -= count;
                    }
                }
            }
        } catch (IOException e) {
            destination.delete();
            throw e;
        } finally {
            decrypted.delete();
        }
    }

    private void decryptEntry(Entry entry, File output) throws IOException {
        final byte[] name = SwiftBackupGuest.bytes(entry.name);
        try (RandomAccessFile in = new RandomAccessFile(mFile, "r");
                FileOutputStream out = new FileOutputStream(output)) {
            in.seek(entry.offset);
            long remaining = entry.compressedSize;
            long chunk = 0;
            while (remaining > 0) {
                final int size = (int) Math.min(remaining, mChunkSize);
                final byte[] common = ByteBuffer.allocate(20 + name.length)
                        .putLong(entry.order).putInt(name.length).put(name).putLong(chunk++).array();
                final byte[] nonce = SwiftBackupGuest.sha256(
                        SwiftBackupGuest.bytes("SBA1-AEGIS256-entry-chunk-nonce-v1"), mNonce, common);
                final byte[] label = SwiftBackupGuest.bytes("SBA1-AEGIS256-entry-chunk-aad-v1");
                final byte[] aad = ByteBuffer.allocate(label.length + common.length + 8)
                        .put(label).put(common).putLong(size).array();
                final byte[] ciphertext = new byte[size + 16];
                in.readFully(ciphertext);
                final byte[] plain = SwiftBackupNative.decrypt(ciphertext, mKey, nonce, aad);
                try { out.write(plain); } finally { Arrays.fill(plain, (byte) 0); }
                remaining -= size;
            }
        }
    }

    @Override
    public void close() {
        if (mKey != null) Arrays.fill(mKey, (byte) 0);
    }

    private static String readUtf8(DataInput input, int size) throws IOException {
        final byte[] bytes = new byte[size];
        input.readFully(bytes);
        try {
            final String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                    .toString();
            require(value.indexOf('\0') < 0, "NUL in Swift metadata or entry name");
            return value;
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 in Swift archive", e);
        }
    }

    private static void requireZero(DataInput input, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            require(input.readByte() == 0, "Unsupported Swift header fields");
        }
    }

    private static int crc32(byte[] bytes, int size) {
        final CRC32 crc = new CRC32();
        crc.update(bytes, 0, size);
        return (int) crc.getValue();
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
