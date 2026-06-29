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
import android.app.appbackup.AppBackupInfo;
import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.app.appbackup.IAppDataBackupService;
import android.app.appbackup.IBackupProgressCallback;
import android.app.appbackup.IRestoreProgressCallback;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDataBackupService extends SystemService {

    private static final String TAG = "AppDataBackupService";

    public static final String SERVICE_NAME = "app_data_backup";

    private static final int THREAD_POOL_SIZE = 2;
    private static final String RAW_MEDIA_ROOT = "/data/media";

    private final BinderService mBinderService = new BinderService();
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private final Installer mInstaller;

    private final Map<String, Object> mActiveEngines = new ConcurrentHashMap<>();

    public AppDataBackupService(@NonNull Context context) {
        super(context);
        mInstaller = new Installer(context);
    }

    @Override
    public void onStart() {
        mInstaller.onStart();
        publishBinderService(SERVICE_NAME, mBinderService);
        Slog.i(TAG, "AppDataBackupService started");
    }

    final class BinderService extends IAppDataBackupService.Stub {

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            enforceShellOrRoot();
            new AppDataBackupShellCommand(this).exec(
                    this,
                    in,
                    out,
                    err,
                    args,
                    callback,
                    resultReceiver);
        }

        private void enforceShellOrRoot() {
            final int uid = Binder.getCallingUid();
            if (uid != android.os.Process.SHELL_UID && uid != android.os.Process.ROOT_UID) {
                throw new SecurityException("Shell commands only available to shell/root");
            }
        }

        @Override
        public List<AppBackupInfo> getInstalledApps(int userId) {
            enforceBackupPermission();
            final long ident = Binder.clearCallingIdentity();
            try {
                return buildInstalledAppList(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public List<BackupRecord> getAvailableBackups(String backupDir, int userId) {
            enforceBackupPermission();
            final long ident = Binder.clearCallingIdentity();
            try {
                return scanBackupDirectory(resolveBackupDirectory(backupDir, userId), userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public String backupPackages(List<String> packageNames, String backupDir,
                boolean excludeCache, int userId, IBackupProgressCallback callback,
                String passphrase, int components, int keepVersions) {
            enforceBackupPermission();

            final String token = UUID.randomUUID().toString();
            final File destDir = resolveBackupDirectory(backupDir, userId);
            final char[] pass = toPassphrase(passphrase);
            final int comps = (components == 0)
                    ? AppDataBackupRestoreManager.COMPONENT_ALL : components;

            mExecutor.submit(() -> {
                final BackupEngine engine = new BackupEngine(
                        getContext(), getContext().getPackageManager(), mInstaller);
                mActiveEngines.put(token, engine);
                runBackup(engine, packageNames, destDir, excludeCache, comps, keepVersions,
                        userId, token, callback, pass);
                mActiveEngines.remove(token);
            });

            return token;
        }

        @Override
        public String restorePackages(List<String> backupIds, String backupDir,
                int userId, IRestoreProgressCallback callback, String passphrase) {
            enforceRestorePermission();

            final String token = UUID.randomUUID().toString();
            final File srcDir = resolveBackupDirectory(backupDir, userId);
            final char[] pass = toPassphrase(passphrase);

            mExecutor.submit(() -> {
                final RestoreEngine engine = new RestoreEngine(getContext(), mInstaller);
                mActiveEngines.put(token, engine);
                runRestore(engine, backupIds, srcDir, userId, token, callback, pass);
                mActiveEngines.remove(token);
            });

            return token;
        }

        @Override
        public void cancelOperation(String operationToken) {
            enforceAnyBackupPermission();
            final Object engine = mActiveEngines.get(operationToken);
            if (engine instanceof BackupEngine) {
                ((BackupEngine) engine).cancel();
            } else if (engine instanceof RestoreEngine) {
                ((RestoreEngine) engine).cancel();
            }
        }

        @Override
        public boolean deleteBackup(String backupId, String backupDir) {
            enforceBackupPermission();
            final int userId = android.os.UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                mInstaller.deleteBackupArchive(userId, backupId);
                return true;
            } catch (InstallerException e) {
                Slog.w(TAG, "deleteBackup failed for " + backupId, e);
                return false;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public BackupRecord getBackupRecord(String backupId, String backupDir) {
            enforceBackupPermission();
            final int userId = android.os.UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try (InputStream in = openArchiveStream(userId, backupId)) {
                return BackupManifest.recordFromJson(
                        BackupArchive.readManifest(in),
                        canonicalBackupDir(userId));
            } catch (IOException e) {
                Slog.w(TAG, "getBackupRecord failed", e);
                return null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean isEncryptionAvailable(int userId) {
            return true;
        }

        @Override
        public String verifyBackup(String backupId, String backupDir, int userId,
                String passphrase) {
            enforceBackupPermission();
            final long ident = Binder.clearCallingIdentity();
            try (InputStream in = openArchiveStream(userId, backupId)) {
                BackupArchive.verify(in, toPassphrase(passphrase));
                return null;
            } catch (BackupArchive.BadPassphraseException e) {
                return "Wrong passphrase or corrupt header";
            } catch (BackupArchive.IntegrityException e) {
                return e.getMessage();
            } catch (IOException e) {
                return "Verification failed: " + e.getMessage();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void runBackup(@NonNull BackupEngine engine,
            @NonNull List<String> packageNames,
            @NonNull File destDir,
            boolean excludeCache,
            int components,
            int keepVersions,
            int userId,
            @NonNull String token,
            IBackupProgressCallback callback,
            char[] passphrase) {

        final int total = packageNames.size();
        Slog.i(TAG, "Starting backup of " + total + " package(s) into " + destDir);
        notifyBackupStarted(callback, token, total);

        int successCount = 0;
        final PackageManager pm = getContext().getPackageManager();

        for (int i = 0; i < total; i++) {
            final String pkg = packageNames.get(i);
            notifyPackageBackupStarted(callback, token, pkg, i + 1, total);

            BackupResult result;
            try {
                final PackageInfo pi = pm.getPackageInfoAsUser(pkg,
                        PackageManager.GET_SHARED_LIBRARY_FILES
                        | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                    userId);
                final BackupRecord record = engine.backupPackage(pi, destDir, excludeCache,
                        components, userId, passphrase);
                if (record == null) {
                    result = BackupResult.cancelled();
                } else {
                    result = BackupResult.ok();
                    successCount++;
                    if (keepVersions > 0) {
                        pruneOldBackups(destDir, pkg, userId, keepVersions);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                result = BackupResult.failure(BackupResult.ERROR_PACKAGE_NOT_FOUND,
                        "Package not found: " + pkg);
            } catch (IOException e) {
                result = BackupResult.failure(BackupResult.ERROR_IO, e.getMessage());
            }

            notifyPackageBackupFinished(callback, token, pkg, result);

            if (result.getErrorCode() == BackupResult.ERROR_CANCELLED) break;
        }

        final BackupResult aggregate;
        if (successCount == total) {
            aggregate = BackupResult.ok();
        } else if (successCount > 0) {
            aggregate = BackupResult.partial(successCount + "/" + total + " packages succeeded");
        } else {
            aggregate = BackupResult.failure(BackupResult.ERROR_IO, "All packages failed");
        }

        Slog.i(TAG, "Backup finished for token=" + token + " result=" + aggregate);
        notifyBackupFinished(callback, token, aggregate);
    }

    private void runRestore(@NonNull RestoreEngine engine,
            @NonNull List<String> backupIds,
            @NonNull File backupDir,
            int userId,
            @NonNull String token,
            IRestoreProgressCallback callback,
            char[] passphrase) {

        final int total = backupIds.size();
    Slog.i(TAG, "Starting restore of " + total + " backup(s) from " + backupDir);
        notifyRestoreStarted(callback, token, total);

        int successCount = 0;

        for (int i = 0; i < total; i++) {
            final String backupId = backupIds.get(i);

            BackupRecord record;
            try (InputStream in = openArchiveStream(userId, backupId)) {
                record = BackupManifest.recordFromJson(
                        BackupArchive.readManifest(in),
                        canonicalBackupDir(userId));
            } catch (IOException e) {
                final BackupResult r = BackupResult.failure(BackupResult.ERROR_IO,
                        "Cannot read manifest for " + backupId + ": " + e);
                notifyPackageRestoreFinished(callback, token, backupId, r);
                continue;
            }

            notifyPackageRestoreStarted(callback, token, record.getPackageName(), i + 1, total);
            notifyPackageDataRestoring(callback, token, record.getPackageName());

            final BackupResult result = engine.restorePackage(record, userId, passphrase);
            notifyPackageRestoreFinished(callback, token, record.getPackageName(), result);

            if (result.isSuccess() || result.getStatus() == BackupResult.STATUS_PARTIAL) {
                successCount++;
            }
            if (result.getErrorCode() == BackupResult.ERROR_CANCELLED) break;
        }

        final BackupResult aggregate;
        if (successCount == total) {
            aggregate = BackupResult.ok();
        } else if (successCount > 0) {
            aggregate = BackupResult.partial(successCount + "/" + total + " packages restored");
        } else {
            aggregate = BackupResult.failure(BackupResult.ERROR_IO, "All restores failed");
        }

        Slog.i(TAG, "Restore finished for token=" + token + " result=" + aggregate);
        notifyRestoreFinished(callback, token, aggregate);
    }

    private List<AppBackupInfo> buildInstalledAppList(int userId) {
        final PackageManager pm = getContext().getPackageManager();
        final List<PackageInfo> packages = pm.getInstalledPackagesAsUser(
                PackageManager.GET_SHARED_LIBRARY_FILES, userId);

        final List<AppBackupInfo> result = new ArrayList<>();
        for (PackageInfo pi : packages) {
            final ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.sourceDir == null) continue;

            final long dataSize = estimateDataSize(pi.packageName, userId);
            final boolean hasDe = new File("/data/user_de/" + userId
                    + "/" + pi.packageName).exists();
            final String label;
            try {
                label = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) {
                continue;
            }

            result.add(new AppBackupInfo(
                    pi.packageName,
                    label,
                    pi.versionName,
                    pi.getLongVersionCode(),
                    ai.sourceDir,
                    ai.splitSourceDirs,
                    dataSize,
                    hasDe,
                    ai.uid,
                    userId));
        }
        return result;
    }

    private long estimateDataSize(String packageName, int userId) {
        final StorageStatsManager ssm =
                getContext().getSystemService(StorageStatsManager.class);
        if (ssm == null) return -1;
        try {
            final StorageStats stats = ssm.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, packageName, UserHandle.of(userId));
            return stats.getAppBytes() + stats.getDataBytes();
        } catch (PackageManager.NameNotFoundException | IOException e) {
            Slog.w(TAG, "queryStatsForPackage failed for " + packageName, e);
            return -1;
        }
    }

    private void pruneOldBackups(@NonNull File backupDir, @NonNull String packageName,
            int userId, int keep) {
        final List<BackupRecord> mine = new ArrayList<>();
        for (BackupRecord r : scanBackupDirectory(backupDir, userId)) {
            if (packageName.equals(r.getPackageName()) && r.getUserId() == userId) {
                mine.add(r);
            }
        }
        if (mine.size() <= keep) return;

        mine.sort((a, b) -> Long.compare(b.getTimestampMs(), a.getTimestampMs()));
        for (int i = keep; i < mine.size(); i++) {
            final String id = mine.get(i).getId();
            try {
                mInstaller.deleteBackupArchive(userId, id);
                Slog.i(TAG, "Pruned old backup: " + id);
            } catch (InstallerException e) {
                Slog.w(TAG, "Failed to prune old backup: " + id, e);
            }
        }
    }

    private List<BackupRecord> scanBackupDirectory(File backupDir, int userId) {
        if (userId < 0) return Collections.emptyList();
        final String[] ids;
        try {
            ids = mInstaller.listBackupArchives(userId);
        } catch (InstallerException e) {
            Slog.w(TAG, "listBackupArchives failed for user " + userId, e);
            return Collections.emptyList();
        }
        final List<BackupRecord> records = new ArrayList<>();
        for (String id : ids) {
            try (InputStream in = openArchiveStream(userId, id)) {
                final BackupRecord record = BackupManifest.recordFromJson(
                        BackupArchive.readManifest(in),
                        canonicalBackupDir(userId));
                if (record.getUserId() == userId) {
                    records.add(record);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Skipping malformed backup: " + id, e);
            }
        }
        return records;
    }

    private static void notifyBackupStarted(IBackupProgressCallback cb, String token, int total) {
        if (cb == null) return;
        try { cb.onBackupStarted(token, total); } catch (RemoteException ignored) {}
    }

    private static void notifyPackageBackupStarted(IBackupProgressCallback cb, String token,
            String pkg, int idx, int total) {
        if (cb == null) return;
        try { cb.onPackageBackupStarted(token, pkg, idx, total); } catch (RemoteException ignored) {}
    }

    private static void notifyPackageBackupFinished(IBackupProgressCallback cb, String token,
            String pkg, BackupResult result) {
        if (cb == null) return;
        try { cb.onPackageBackupFinished(token, pkg, result); } catch (RemoteException ignored) {}
    }

    private static void notifyBackupFinished(IBackupProgressCallback cb, String token,
            BackupResult result) {
        if (cb == null) return;
        try { cb.onBackupFinished(token, result); } catch (RemoteException ignored) {}
    }

    private static void notifyRestoreStarted(IRestoreProgressCallback cb, String token, int total) {
        if (cb == null) return;
        try { cb.onRestoreStarted(token, total); } catch (RemoteException ignored) {}
    }

    private static void notifyPackageRestoreStarted(IRestoreProgressCallback cb, String token,
            String pkg, int idx, int total) {
        if (cb == null) return;
        try { cb.onPackageRestoreStarted(token, pkg, idx, total); } catch (RemoteException ignored) {}
    }

    private static void notifyPackageDataRestoring(IRestoreProgressCallback cb, String token,
            String pkg) {
        if (cb == null) return;
        try { cb.onPackageDataRestoring(token, pkg); } catch (RemoteException ignored) {}
    }

    private static void notifyPackageRestoreFinished(IRestoreProgressCallback cb, String token,
            String pkg, BackupResult result) {
        if (cb == null) return;
        try { cb.onPackageRestoreFinished(token, pkg, result); } catch (RemoteException ignored) {}
    }

    private static void notifyRestoreFinished(IRestoreProgressCallback cb, String token,
            BackupResult result) {
        if (cb == null) return;
        try { cb.onRestoreFinished(token, result); } catch (RemoteException ignored) {}
    }

    private void enforceBackupPermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.APP_DATA_BACKUP,
                "Requires APP_DATA_BACKUP permission");
    }

    private void enforceRestorePermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.APP_DATA_RESTORE,
                "Requires APP_DATA_RESTORE permission");
    }

    private void enforceAnyBackupPermission() {
        try {
            enforceBackupPermission();
        } catch (SecurityException e) {
            enforceRestorePermission();
        }
    }

    private InputStream openArchiveStream(int userId, String archiveId) throws IOException {
        final ParcelFileDescriptor pfd;
        try {
            pfd = mInstaller.openBackupArchive(userId, archiveId);
        } catch (InstallerException e) {
            throw new IOException("installd openBackupArchive failed for " + archiveId, e);
        }
        if (pfd == null) {
            throw new IOException("Backup not found: " + archiveId);
        }
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    private static String canonicalBackupDir(int userId) {
        return RAW_MEDIA_ROOT + "/" + userId + "/AppDataBackup";
    }

    private static char[] toPassphrase(String passphrase) {
        return (passphrase == null || passphrase.isEmpty()) ? null : passphrase.toCharArray();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            final File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private static File resolveBackupDirectory(@NonNull String backupDir, int userId) {
        if (backupDir.startsWith("/sdcard/")) {
            return new File(RAW_MEDIA_ROOT + "/" + userId + backupDir.substring("/sdcard".length()));
        }
        if (backupDir.equals("/sdcard")) {
            return new File(RAW_MEDIA_ROOT + "/" + userId);
        }
        final String emulatedPrefix = "/storage/emulated/" + userId;
        if (backupDir.startsWith(emulatedPrefix)) {
            return new File(RAW_MEDIA_ROOT + "/" + userId
                    + backupDir.substring(emulatedPrefix.length()));
        }
        return new File(backupDir);
    }
}
