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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
@SystemService(Context.APP_DATA_BACKUP_SERVICE)
public class AppDataBackupRestoreManager {

    private static final String TAG = "AppDataBackupMgr";

    public static final String SERVICE_NAME = "app_data_backup";

    public static final int COMPONENT_APK = 1;
    public static final int COMPONENT_CE_DATA = 1 << 1;
    public static final int COMPONENT_DE_DATA = 1 << 2;
    public static final int COMPONENT_EXTERNAL = 1 << 3;
    public static final int COMPONENT_ALL =
            COMPONENT_APK | COMPONENT_CE_DATA | COMPONENT_DE_DATA | COMPONENT_EXTERNAL;

    private final IAppDataBackupService mService;
    private final int mUserId;

    public AppDataBackupRestoreManager(Context context, IAppDataBackupService service) {
        mService = service;
        mUserId = context.getUserId();
    }

    private static IAppDataBackupService getService() {
        return IAppDataBackupService.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME));
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public List<AppBackupInfo> getInstalledApps() {
        try {
            List<AppBackupInfo> result = mService.getInstalledApps(mUserId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "getInstalledApps failed", e);
            return Collections.emptyList();
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public List<BackupRecord> getAvailableBackups(@NonNull String backupDir) {
        try {
            List<BackupRecord> result = mService.getAvailableBackups(backupDir, mUserId);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            Log.e(TAG, "getAvailableBackups failed", e);
            return Collections.emptyList();
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @NonNull
    public String backupPackages(@NonNull List<String> packageNames,
            @NonNull String backupDir,
            boolean excludeCache,
            @Nullable IBackupProgressCallback callback,
            @Nullable String passphrase,
            int components,
            int keepVersions) {
        try {
            return mService.backupPackages(packageNames, backupDir, excludeCache,
                    mUserId, callback, passphrase, components, keepVersions);
        } catch (RemoteException e) {
            Log.e(TAG, "backupPackages failed", e);
            return "";
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_RESTORE)
    @NonNull
    public String restorePackages(@NonNull List<String> backupIds,
            @NonNull String backupDir,
            @Nullable IRestoreProgressCallback callback,
            @Nullable String passphrase) {
        try {
            return mService.restorePackages(backupIds, backupDir, mUserId, callback, passphrase);
        } catch (RemoteException e) {
            Log.e(TAG, "restorePackages failed", e);
            return "";
        }
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.APP_DATA_BACKUP,
            android.Manifest.permission.APP_DATA_RESTORE
    })
    public void cancelOperation(@NonNull String operationToken) {
        try {
            mService.cancelOperation(operationToken);
        } catch (RemoteException e) {
            Log.e(TAG, "cancelOperation failed", e);
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    public boolean deleteBackup(@NonNull String backupId, @NonNull String backupDir) {
        try {
            return mService.deleteBackup(backupId, backupDir);
        } catch (RemoteException e) {
            Log.e(TAG, "deleteBackup failed", e);
            return false;
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @Nullable
    public BackupRecord getBackupRecord(@NonNull String backupId, @NonNull String backupDir) {
        try {
            return mService.getBackupRecord(backupId, backupDir);
        } catch (RemoteException e) {
            Log.e(TAG, "getBackupRecord failed", e);
            return null;
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    public boolean isEncryptionAvailable() {
        try {
            return mService.isEncryptionAvailable(mUserId);
        } catch (RemoteException e) {
            Log.e(TAG, "isEncryptionAvailable failed", e);
            return false;
        }
    }

    @RequiresPermission(android.Manifest.permission.APP_DATA_BACKUP)
    @Nullable
    public String verifyBackup(@NonNull String backupId, @NonNull String backupDir,
            @Nullable String passphrase) {
        try {
            return mService.verifyBackup(backupId, backupDir, mUserId, passphrase);
        } catch (RemoteException e) {
            Log.e(TAG, "verifyBackup failed", e);
            return "Verification failed: " + e.getMessage();
        }
    }
}
