/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <nativehelper/JNIHelp.h>

#include "jni.h"

namespace android {

// Forward declared per-class registration methods.
int register_android_server_app_GameManagerService(JNIEnv* env);
int register_android_server_connectivity_Vpn(JNIEnv* env);
int register_android_server_tv_TvUinputBridge(JNIEnv* env);
int register_android_server_appbackup_SwiftBackupNative(JNIEnv* env);

namespace {

// TODO(b/)375264322: Remove these trampoline methods after finalizing the
// registrar implementation. Instead, just update the called methods to take a
// class arg, and hand those methods to jniRegisterNativeMethods directly.

void registerGameManagerService(JNIEnv* env, jclass) {
    register_android_server_app_GameManagerService(env);
}

void registerTvUinputBridge(JNIEnv* env, jclass) {
    register_android_server_tv_TvUinputBridge(env);
}

void registerVpn(JNIEnv* env, jclass) {
    register_android_server_connectivity_Vpn(env);
}

void registerSwiftBackupNative(JNIEnv* env, jclass) {
    register_android_server_appbackup_SwiftBackupNative(env);
}

static const JNINativeMethod sJniRegistrarMethods[] = {
        {"registerSwiftBackupNative", "()V", (void*)registerSwiftBackupNative},
        {"registerGameManagerService", "()V", (void*)registerGameManagerService},
        {"registerTvUinputBridge", "()V", (void*)registerTvUinputBridge},
        {"registerVpn", "()V", (void*)registerVpn},
};

} // namespace

int register_android_server_utils_LazyJniRegistrar(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/utils/LazyJniRegistrar",
                                    sJniRegistrarMethods, NELEM(sJniRegistrarMethods));
}

} // namespace android
