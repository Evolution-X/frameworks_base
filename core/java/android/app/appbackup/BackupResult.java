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
public final class BackupResult implements Parcelable {

    public static final int STATUS_OK = 0;
    public static final int STATUS_PARTIAL = 1;
    public static final int STATUS_FAILED = 2;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_IO = 1;
    public static final int ERROR_PERMISSION_DENIED = 2;
    public static final int ERROR_CHECKSUM_MISMATCH = 3;
    public static final int ERROR_INSTALL_FAILED = 4;
    public static final int ERROR_PACKAGE_NOT_FOUND = 5;
    public static final int ERROR_STORAGE_FULL = 6;
    public static final int ERROR_ENCRYPTION_FAILED = 7;
    public static final int ERROR_DECRYPTION_FAILED = 8;
    public static final int ERROR_CANCELLED = 9;

    private final int mStatus;
    private final int mErrorCode;
    private final String mMessage;

    private BackupResult(int status, int errorCode, String message) {
        mStatus = status;
        mErrorCode = errorCode;
        mMessage = message;
    }

    private BackupResult(Parcel in) {
        mStatus = in.readInt();
        mErrorCode = in.readInt();
        mMessage = in.readString();
    }

    public static BackupResult ok() {
        return new BackupResult(STATUS_OK, ERROR_NONE, "OK");
    }

    public static BackupResult partial(String message) {
        return new BackupResult(STATUS_PARTIAL, ERROR_NONE, message);
    }

    public static BackupResult failure(int errorCode, String message) {
        return new BackupResult(STATUS_FAILED, errorCode, message);
    }

    public static BackupResult cancelled() {
        return new BackupResult(STATUS_FAILED, ERROR_CANCELLED, "Cancelled");
    }

    public int getStatus() { return mStatus; }
    public int getErrorCode() { return mErrorCode; }
    public String getMessage() { return mMessage; }
    public boolean isSuccess() { return mStatus == STATUS_OK; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeInt(mErrorCode);
        dest.writeString(mMessage);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Parcelable.Creator<BackupResult> CREATOR =
            new Parcelable.Creator<BackupResult>() {
                @Override
                public BackupResult createFromParcel(Parcel in) {
                    return new BackupResult(in);
                }

                @Override
                public BackupResult[] newArray(int size) {
                    return new BackupResult[size];
                }
            };

    @Override
    public String toString() {
        return "BackupResult{status=" + mStatus + ", error=" + mErrorCode
                + ", msg='" + mMessage + "'}";
    }
}
