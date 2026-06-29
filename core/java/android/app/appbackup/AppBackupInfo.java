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
public final class AppBackupInfo implements Parcelable {

    private final String mPackageName;
    private final String mLabel;
    private final String mVersionName;
    private final long mVersionCode;
    private final String mBaseApkPath;
    private final String[] mSplitApkPaths;
    private final long mDataSize;
    private final boolean mHasDeData;
    private final int mUid;
    private final int mUserId;

    public AppBackupInfo(String packageName, String label, String versionName,
            long versionCode, String baseApkPath, String[] splitApkPaths,
            long dataSize, boolean hasDeData, int uid, int userId) {
        mPackageName = packageName;
        mLabel = label;
        mVersionName = versionName;
        mVersionCode = versionCode;
        mBaseApkPath = baseApkPath;
        mSplitApkPaths = splitApkPaths;
        mDataSize = dataSize;
        mHasDeData = hasDeData;
        mUid = uid;
        mUserId = userId;
    }

    private AppBackupInfo(Parcel in) {
        mPackageName = in.readString();
        mLabel = in.readString();
        mVersionName = in.readString();
        mVersionCode = in.readLong();
        mBaseApkPath = in.readString();
        mSplitApkPaths = in.createStringArray();
        mDataSize = in.readLong();
        mHasDeData = in.readBoolean();
        mUid = in.readInt();
        mUserId = in.readInt();
    }

    public String getPackageName() { return mPackageName; }
    public String getLabel() { return mLabel; }
    public String getVersionName() { return mVersionName; }
    public long getVersionCode() { return mVersionCode; }
    public String getBaseApkPath() { return mBaseApkPath; }
    public String[] getSplitApkPaths() { return mSplitApkPaths; }
    public long getDataSize() { return mDataSize; }
    public boolean hasDeData() { return mHasDeData; }
    public int getUid() { return mUid; }
    public int getUserId() { return mUserId; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeString(mLabel);
        dest.writeString(mVersionName);
        dest.writeLong(mVersionCode);
        dest.writeString(mBaseApkPath);
        dest.writeStringArray(mSplitApkPaths);
        dest.writeLong(mDataSize);
        dest.writeBoolean(mHasDeData);
        dest.writeInt(mUid);
        dest.writeInt(mUserId);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Parcelable.Creator<AppBackupInfo> CREATOR =
            new Parcelable.Creator<AppBackupInfo>() {
                @Override
                public AppBackupInfo createFromParcel(Parcel in) {
                    return new AppBackupInfo(in);
                }

                @Override
                public AppBackupInfo[] newArray(int size) {
                    return new AppBackupInfo[size];
                }
            };

    @Override
    public String toString() {
        return "AppBackupInfo{pkg=" + mPackageName
                + ", v=" + mVersionName + "(" + mVersionCode + ")"
                + ", dataSize=" + mDataSize
                + ", userId=" + mUserId + "}";
    }
}
