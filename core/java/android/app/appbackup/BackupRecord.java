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

package android.app.appbackup;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public final class BackupRecord implements Parcelable {

    public static final int STATE_OK = 0;
    public static final int STATE_PARTIAL = 1;
    public static final int STATE_FAILED = 2;

    private final String mId;
    private final String mPackageName;
    private final String mLabel;
    private final String mVersionName;
    private final long mVersionCode;
    private final long mTimestampMs;
    private final long mApkSize;
    private final long mCeDataSize;
    private final long mDeDataSize;
    private final int mComponents;
    private final boolean mEncrypted;
    private final int mState;
    private final String mBackupDir;
    private final int mUserId;

    public BackupRecord(String id, String packageName, String label,
            String versionName, long versionCode, long timestampMs,
            long apkSize, long ceDataSize, long deDataSize,
            int components, boolean encrypted, int state, String backupDir, int userId) {
        mId = id;
        mPackageName = packageName;
        mLabel = label;
        mVersionName = versionName;
        mVersionCode = versionCode;
        mTimestampMs = timestampMs;
        mApkSize = apkSize;
        mCeDataSize = ceDataSize;
        mDeDataSize = deDataSize;
        mComponents = components;
        mEncrypted = encrypted;
        mState = state;
        mBackupDir = backupDir;
        mUserId = userId;
    }

    private BackupRecord(Parcel in) {
        mId = in.readString();
        mPackageName = in.readString();
        mLabel = in.readString();
        mVersionName = in.readString();
        mVersionCode = in.readLong();
        mTimestampMs = in.readLong();
        mApkSize = in.readLong();
        mCeDataSize = in.readLong();
        mDeDataSize = in.readLong();
        mComponents = in.readInt();
        mEncrypted = in.readBoolean();
        mState = in.readInt();
        mBackupDir = in.readString();
        mUserId = in.readInt();
    }

    public String getId() { return mId; }
    public String getPackageName() { return mPackageName; }
    public String getLabel() { return mLabel; }
    public String getVersionName() { return mVersionName; }
    public long getVersionCode() { return mVersionCode; }
    public long getTimestampMs() { return mTimestampMs; }
    public long getApkSize() { return mApkSize; }
    public long getCeDataSize() { return mCeDataSize; }
    public long getDeDataSize() { return mDeDataSize; }
    public long getTotalSize() { return mApkSize + mCeDataSize + mDeDataSize; }
    public int getComponents() { return mComponents; }
    public boolean isEncrypted() { return mEncrypted; }
    public int getState() { return mState; }
    public String getBackupDir() { return mBackupDir; }
    public int getUserId() { return mUserId; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mPackageName);
        dest.writeString(mLabel);
        dest.writeString(mVersionName);
        dest.writeLong(mVersionCode);
        dest.writeLong(mTimestampMs);
        dest.writeLong(mApkSize);
        dest.writeLong(mCeDataSize);
        dest.writeLong(mDeDataSize);
        dest.writeInt(mComponents);
        dest.writeBoolean(mEncrypted);
        dest.writeInt(mState);
        dest.writeString(mBackupDir);
        dest.writeInt(mUserId);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Parcelable.Creator<BackupRecord> CREATOR =
            new Parcelable.Creator<BackupRecord>() {
                @Override
                public BackupRecord createFromParcel(Parcel in) {
                    return new BackupRecord(in);
                }

                @Override
                public BackupRecord[] newArray(int size) {
                    return new BackupRecord[size];
                }
            };

    @Override
    public String toString() {
        return "BackupRecord{id=" + mId
                + ", pkg=" + mPackageName
                + ", ts=" + mTimestampMs
                + ", encrypted=" + mEncrypted
                + ", state=" + mState + "}";
    }
}
