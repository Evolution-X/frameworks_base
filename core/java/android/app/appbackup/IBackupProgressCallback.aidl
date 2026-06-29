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

import android.app.appbackup.BackupResult;

/**
 * @hide
 */
oneway interface IBackupProgressCallback {
    void onBackupStarted(String operationToken, int totalPackages);

    void onPackageBackupStarted(String operationToken, String packageName,
                                int currentIndex, int totalPackages);

    void onPackageBackupFinished(String operationToken, String packageName,
                                 in BackupResult result);

    void onBackupFinished(String operationToken, in BackupResult result);

    void onBackupCancelled(String operationToken);
}
