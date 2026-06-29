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

import android.app.appbackup.AppBackupInfo;
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.app.appbackup.IBackupProgressCallback;
import android.app.appbackup.IRestoreProgressCallback;

/**
 * @hide
 */
interface IAppDataBackupService {

    List<AppBackupInfo> getInstalledApps(int userId);

    List<BackupRecord> getAvailableBackups(String backupDir, int userId);

    String backupPackages(in List<String> packageNames,
                          String backupDir,
                          boolean excludeCache,
                          int userId,
                          IBackupProgressCallback callback,
                          String passphrase,
                          int components,
                          int keepVersions);

    String restorePackages(in List<String> backupIds,
                           String backupDir,
                           int userId,
                           IRestoreProgressCallback callback,
                           String passphrase);

    void cancelOperation(String operationToken);

    boolean deleteBackup(String backupId, String backupDir);

    BackupRecord getBackupRecord(String backupId, String backupDir);

    boolean isEncryptionAvailable(int userId);

    String verifyBackup(String backupId, String backupDir, int userId, String passphrase);
}
