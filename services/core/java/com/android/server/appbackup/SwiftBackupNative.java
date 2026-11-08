/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import com.android.server.utils.LazyJniRegistrar;

import java.io.FileDescriptor;
import java.io.IOException;

final class SwiftBackupNative {
    static {
        LazyJniRegistrar.registerSwiftBackupNative();
    }

    private SwiftBackupNative() {}

    static native void decompress(FileDescriptor source, long offset, long compressedSize,
            FileDescriptor destination, long expectedSize) throws IOException;

    static native byte[] deriveKey(byte[] password, byte[] salt, int iterations,
            int memoryKiB, int lanes) throws IOException;

    static native byte[] decrypt(byte[] ciphertext, byte[] key, byte[] nonce, byte[] aad)
            throws IOException;
}
