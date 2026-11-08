/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

#include <nativehelper/JNIHelp.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <argon2.h>
#include <aegis.h>
#include <stdint.h>
#include <zstd.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <memory>
#include <vector>

namespace android {
namespace {
void secureWipe(void* data, size_t size) {
    volatile uint8_t* bytes = static_cast<volatile uint8_t*>(data);
    while (size > 0) {
        *bytes++ = 0;
        --size;
    }
}

void fail(JNIEnv* env, const char* message) {
    jniThrowException(env, "java/io/IOException", message);
}

void decompress(JNIEnv* env, jclass, jobject source, jlong offset, jlong size,
                jobject destination, jlong expected) {
    const int input = jniGetFDFromFileDescriptor(env, source);
    const int output = jniGetFDFromFileDescriptor(env, destination);
    constexpr jlong maxBytes = jlong{1} << 40;
    if (input < 0 || output < 0 || offset < 0 || size <= 0 || expected <= 0 ||
        size > maxBytes || expected > maxBytes || offset > maxBytes - size) {
        fail(env, "Invalid Swift decompression bounds");
        return;
    }
    std::unique_ptr<ZSTD_DCtx, decltype(&ZSTD_freeDCtx)> ctx(ZSTD_createDCtx(), ZSTD_freeDCtx);
    if (!ctx || ZSTD_isError(ZSTD_DCtx_setParameter(ctx.get(), ZSTD_d_windowLogMax, 27))) {
        fail(env, "Cannot initialize Swift decompression");
        return;
    }
    std::array<char, 65536> inBuffer{}, outBuffer{};
    jlong written = 0;
    size_t result = 1;
    while (size > 0) {
        const ssize_t count = TEMP_FAILURE_RETRY(pread64(input, inBuffer.data(),
                std::min<jlong>(size, inBuffer.size()), offset));
        if (count <= 0) { fail(env, "Truncated Swift compressed payload"); return; }
        size -= count;
        offset += count;
        ZSTD_inBuffer in{inBuffer.data(), static_cast<size_t>(count), 0};
        do {
            ZSTD_outBuffer out{outBuffer.data(), outBuffer.size(), 0};
            const size_t previous = in.pos;
            result = ZSTD_decompressStream(ctx.get(), &out, &in);
            if (ZSTD_isError(result) || out.pos > static_cast<uint64_t>(expected - written)) {
                fail(env, "Invalid Swift compressed payload or size mismatch"); return;
            }
            size_t done = 0;
            while (done < out.pos) {
                const ssize_t n = TEMP_FAILURE_RETRY(write(output, outBuffer.data() + done,
                                                          out.pos - done));
                if (n <= 0) { fail(env, "Cannot write Swift decompressed payload"); return; }
                done += n;
            }
            written += out.pos;
            if (result == 0 && in.pos == in.size) break;
            if (in.pos == previous && out.pos == 0) break;
            if (in.pos == in.size && out.pos < out.size) break;
        } while (true);
    }
    if (result != 0 || written != expected) fail(env, "Truncated Swift zstd frame or size mismatch");
}

jbyteArray deriveKey(JNIEnv* env, jclass, jbyteArray password, jbyteArray salt,
                     jint iterations, jint memoryKiB, jint lanes) {
    ScopedByteArrayRO pass(env, password), saltBytes(env, salt);
    if (!pass.get() || !saltBytes.get()) return nullptr;
    if (pass.size() != 32 || saltBytes.size() != 16 || iterations < 1 || iterations > 10 ||
        memoryKiB < 16384 || memoryKiB > 65536 || lanes < 1 || lanes > 8) {
        fail(env, "Unsupported Swift guest key parameters"); return nullptr;
    }
    std::array<uint8_t, 32> key{};
    if (argon2id_hash_raw(iterations, memoryKiB, lanes, pass.get(), pass.size(),
                         saltBytes.get(), saltBytes.size(), key.data(), key.size()) != ARGON2_OK) {
        secureWipe(key.data(), key.size());
        fail(env, "Swift guest key derivation failed"); return nullptr;
    }
    jbyteArray result = env->NewByteArray(key.size());
    if (result) env->SetByteArrayRegion(result, 0, key.size(),
                                        reinterpret_cast<const jbyte*>(key.data()));
    secureWipe(key.data(), key.size());
    return result;
}

jbyteArray decrypt(JNIEnv* env, jclass, jbyteArray ciphertext, jbyteArray key,
                   jbyteArray nonce, jbyteArray aad) {
    ScopedByteArrayRO enc(env, ciphertext), k(env, key), n(env, nonce), ad(env, aad);
    if (!enc.get() || !k.get() || !n.get() || !ad.get()) return nullptr;
    if (enc.size() < 16 || enc.size() > (1 << 20) + 16 || k.size() != 32 || n.size() != 32 ||
        ad.size() > 65536 + 128) {
        fail(env, "Invalid Swift encrypted chunk"); return nullptr;
    }
    const size_t size = enc.size() - 16;
    std::vector<uint8_t> plain(size);
    if (aegis256_decrypt_detached(plain.data(), reinterpret_cast<const uint8_t*>(enc.get()), size,
            reinterpret_cast<const uint8_t*>(enc.get()) + size, 16,
            reinterpret_cast<const uint8_t*>(ad.get()), ad.size(),
            reinterpret_cast<const uint8_t*>(n.get()), reinterpret_cast<const uint8_t*>(k.get()))
            != 0) {
        secureWipe(plain.data(), plain.size());
        fail(env, "Swift payload authentication failed"); return nullptr;
    }
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size,
                                        reinterpret_cast<const jbyte*>(plain.data()));
    secureWipe(plain.data(), plain.size());
    return result;
}
}

int register_android_server_appbackup_SwiftBackupNative(JNIEnv* env) {
    static const JNINativeMethod methods[] = {
        {"decompress", "(Ljava/io/FileDescriptor;JJLjava/io/FileDescriptor;J)V",
         reinterpret_cast<void*>(decompress)},
        {"deriveKey", "([B[BIII)[B", reinterpret_cast<void*>(deriveKey)},
        {"decrypt", "([B[B[B[B)[B", reinterpret_cast<void*>(decrypt)},
    };
    return jniRegisterNativeMethods(env, "com/android/server/appbackup/SwiftBackupNative",
                                    methods, NELEM(methods));
}
}
