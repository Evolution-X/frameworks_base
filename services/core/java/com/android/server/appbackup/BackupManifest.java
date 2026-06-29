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
import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

class BackupManifest {

    private static final String TAG = "AppDataBackupManifest";

    private static final String KEY_ID           = "id";
    private static final String KEY_PACKAGE      = "packageName";
    private static final String KEY_LABEL        = "label";
    private static final String KEY_VERSION_NAME = "versionName";
    private static final String KEY_VERSION_CODE = "versionCode";
    private static final String KEY_TIMESTAMP    = "timestampMs";
    private static final String KEY_APK_SIZE     = "apkSize";
    private static final String KEY_CE_SIZE      = "ceDataSize";
    private static final String KEY_DE_SIZE      = "deDataSize";
    private static final String KEY_EXT_SIZE     = "extDataSize";
    private static final String KEY_COMPONENTS   = "components";
    private static final String KEY_ENCRYPTED    = "encrypted";
    private static final String KEY_STATE        = "state";
    private static final String KEY_CHECKSUM     = "checksum";
    private static final String KEY_USER_ID      = "userId";

    private final String mId;
    private final String mPackageName;
    private final String mLabel;
    private final String mVersionName;
    private final long mVersionCode;
    private final long mTimestampMs;
    private final long mApkSize;
    private final long mCeDataSize;
    private final long mDeDataSize;
    private final long mExtDataSize;
    private final int mComponents;
    private final boolean mEncrypted;
    private final int mState;
    private final String mChecksum;
    private final int mUserId;

    BackupManifest(String id, String packageName, String label, String versionName,
            long versionCode, long timestampMs, long apkSize, long ceDataSize,
            long deDataSize, long extDataSize, int components, boolean encrypted, int state,
            String checksum, int userId) {
        mId = id;
        mPackageName = packageName;
        mLabel = label;
        mVersionName = versionName;
        mVersionCode = versionCode;
        mTimestampMs = timestampMs;
        mApkSize = apkSize;
        mCeDataSize = ceDataSize;
        mDeDataSize = deDataSize;
        mExtDataSize = extDataSize;
        mComponents = components;
        mEncrypted = encrypted;
        mState = state;
        mChecksum = checksum;
        mUserId = userId;
    }

    void writeTo(@NonNull File dest) throws IOException {
        try {
            final JSONObject json = new JSONObject();
            json.put(KEY_ID, mId);
            json.put(KEY_PACKAGE, mPackageName);
            json.put(KEY_LABEL, mLabel);
            json.put(KEY_VERSION_NAME, mVersionName);
            json.put(KEY_VERSION_CODE, mVersionCode);
            json.put(KEY_TIMESTAMP, mTimestampMs);
            json.put(KEY_APK_SIZE, mApkSize);
            json.put(KEY_CE_SIZE, mCeDataSize);
            json.put(KEY_DE_SIZE, mDeDataSize);
            json.put(KEY_EXT_SIZE, mExtDataSize);
            json.put(KEY_COMPONENTS, mComponents);
            json.put(KEY_ENCRYPTED, mEncrypted);
            json.put(KEY_STATE, mState);
            json.put(KEY_CHECKSUM, mChecksum);
            json.put(KEY_USER_ID, mUserId);

            try (FileWriter writer = new FileWriter(dest)) {
                writer.write(json.toString(2));
            }
        } catch (JSONException e) {
            throw new IOException("Failed to write manifest JSON", e);
        }
    }

    @NonNull
    static BackupRecord readFrom(@NonNull File manifestFile, @NonNull String backupDir)
            throws IOException {
        final char[] buf = new char[(int) manifestFile.length()];
        try (FileReader reader = new FileReader(manifestFile)) {
            reader.read(buf);
        }
        try {
            final JSONObject json = new JSONObject(new String(buf));
            return new BackupRecord(
                    json.getString(KEY_ID),
                    json.getString(KEY_PACKAGE),
                    json.optString(KEY_LABEL, json.getString(KEY_PACKAGE)),
                    json.optString(KEY_VERSION_NAME, ""),
                    json.optLong(KEY_VERSION_CODE, 0),
                    json.getLong(KEY_TIMESTAMP),
                    json.optLong(KEY_APK_SIZE, 0),
                    json.optLong(KEY_CE_SIZE, 0),
                    json.optLong(KEY_DE_SIZE, 0),
                    resolveComponents(json),
                    json.optBoolean(KEY_ENCRYPTED, false),
                    json.optInt(KEY_STATE, BackupRecord.STATE_OK),
                    backupDir,
                    json.optInt(KEY_USER_ID, 0));
        } catch (JSONException e) {
            throw new IOException("Malformed manifest: " + manifestFile, e);
        }
    }

    @NonNull
    JSONObject toJson() throws IOException {
        try {
            final JSONObject json = new JSONObject();
            json.put(KEY_ID, mId);
            json.put(KEY_PACKAGE, mPackageName);
            json.put(KEY_LABEL, mLabel);
            json.put(KEY_VERSION_NAME, mVersionName);
            json.put(KEY_VERSION_CODE, mVersionCode);
            json.put(KEY_TIMESTAMP, mTimestampMs);
            json.put(KEY_APK_SIZE, mApkSize);
            json.put(KEY_CE_SIZE, mCeDataSize);
            json.put(KEY_DE_SIZE, mDeDataSize);
            json.put(KEY_EXT_SIZE, mExtDataSize);
            json.put(KEY_COMPONENTS, mComponents);
            json.put(KEY_ENCRYPTED, mEncrypted);
            json.put(KEY_STATE, mState);
            json.put(KEY_CHECKSUM, mChecksum);
            json.put(KEY_USER_ID, mUserId);
            return json;
        } catch (JSONException e) {
            throw new IOException("Failed to build manifest JSON", e);
        }
    }

    @NonNull
    static BackupRecord recordFromJson(@NonNull JSONObject json, @NonNull String backupDir)
            throws IOException {
        try {
            return new BackupRecord(
                    json.getString(KEY_ID),
                    json.getString(KEY_PACKAGE),
                    json.optString(KEY_LABEL, json.getString(KEY_PACKAGE)),
                    json.optString(KEY_VERSION_NAME, ""),
                    json.optLong(KEY_VERSION_CODE, 0),
                    json.getLong(KEY_TIMESTAMP),
                    json.optLong(KEY_APK_SIZE, 0),
                    json.optLong(KEY_CE_SIZE, 0),
                    json.optLong(KEY_DE_SIZE, 0),
                    resolveComponents(json),
                    json.optBoolean(KEY_ENCRYPTED, false),
                    json.optInt(KEY_STATE, BackupRecord.STATE_OK),
                    backupDir,
                    json.optInt(KEY_USER_ID, 0));
        } catch (JSONException e) {
            throw new IOException("Malformed manifest JSON", e);
        }
    }

    @NonNull
    BackupRecord toBackupRecord(@NonNull String backupDir) {
        return new BackupRecord(mId, mPackageName, mLabel, mVersionName, mVersionCode,
                mTimestampMs, mApkSize, mCeDataSize, mDeDataSize, mComponents, mEncrypted, mState,
                backupDir, mUserId);
    }

    private static int resolveComponents(@NonNull JSONObject json) {
        final int stored = json.optInt(KEY_COMPONENTS, -1);
        if (stored >= 0) {
            return stored;
        }
        int inferred = 0;
        if (json.optLong(KEY_APK_SIZE, 0) > 0) inferred |= AppDataBackupRestoreManager.COMPONENT_APK;
        if (json.optLong(KEY_CE_SIZE, 0) > 0) inferred |= AppDataBackupRestoreManager.COMPONENT_CE_DATA;
        if (json.optLong(KEY_DE_SIZE, 0) > 0) inferred |= AppDataBackupRestoreManager.COMPONENT_DE_DATA;
        if (json.optLong(KEY_EXT_SIZE, 0) > 0) inferred |= AppDataBackupRestoreManager.COMPONENT_EXTERNAL;
        return inferred;
    }

    String getChecksum() { return mChecksum; }
}
