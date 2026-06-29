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
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class BackupEngine {

    private static final String TAG = "AppDataBackupEngine";

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Context mContext;
    private final PackageManager mPm;
    private final Installer mInstaller;
    private final ActivityManager mActivityManager;
    private volatile boolean mCancelled = false;

    public BackupEngine(@NonNull Context context, @NonNull PackageManager pm,
            @NonNull Installer installer) {
        mContext = context;
        mPm = pm;
        mInstaller = installer;
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    public void cancel() {
        mCancelled = true;
    }

    @Nullable
    public BackupRecord backupPackage(@NonNull PackageInfo packageInfo,
            @NonNull File backupDir,
            boolean excludeCache,
            int components,
            int userId,
            @Nullable char[] passphrase) throws IOException {

        mCancelled = false;
        final String packageName = packageInfo.packageName;
        final long timestampMs = System.currentTimeMillis();
        final String backupId = packageName + "_" + timestampMs;

        final File stagingRoot = new File("/data/system/app_backup_staging");
        final File tmpDir     = new File(stagingRoot, backupId + ".tmp");
        final File stagedVbak = new File(stagingRoot, backupId + BackupArchive.EXTENSION);

        stagingRoot.mkdirs();
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs()) {
            throw new IOException("Cannot create temp backup dir: " + tmpDir);
        }

        long apkSize = 0;
        long ceDataSize = 0;
        long deDataSize = 0;
        long extDataSize = 0;
        boolean partial = false;

        final boolean doApk = (components & AppDataBackupRestoreManager.COMPONENT_APK) != 0;
        final boolean doCe = (components & AppDataBackupRestoreManager.COMPONENT_CE_DATA) != 0;
        final boolean doDe = (components & AppDataBackupRestoreManager.COMPONENT_DE_DATA) != 0;
        final boolean doExt = (components & AppDataBackupRestoreManager.COMPONENT_EXTERNAL) != 0;

        try {
            stopPackage(packageName);

            if (doApk) {
                final String baseApkPath = packageInfo.applicationInfo.sourceDir;
                if (baseApkPath != null) {
                    apkSize += archiveFile(new File(baseApkPath), new File(tmpDir, "base.apk"));
                }

                final String[] splitSourceDirs = packageInfo.applicationInfo.splitSourceDirs;
                if (splitSourceDirs != null) {
                    for (String splitPath : splitSourceDirs) {
                        final File splitFile = new File(splitPath);
                        final String splitName = "split_" + splitFile.getName();
                        apkSize += archiveFile(splitFile, new File(tmpDir, splitName));
                        checkCancelled();
                    }
                }
            }

            if (doCe) {
                try {
                    final File ceArchive = new File(tmpDir, "data_ce.tar");
                    ceDataSize = tarAppDataViaInstalld(packageName, userId,
                            Installer.FLAG_STORAGE_CE, ceArchive, excludeCache);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to archive CE data for " + packageName + ": " + e);
                    partial = true;
                }
            }

            checkCancelled();

            if (doDe) {
                try {
                    final File deArchive = new File(tmpDir, "data_de.tar");
                    deDataSize = tarAppDataViaInstalld(packageName, userId,
                            Installer.FLAG_STORAGE_DE, deArchive, excludeCache);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to archive DE data for " + packageName + ": " + e);
                    partial = true;
                }
            }

            checkCancelled();

            if (doExt) {
                try {
                    final File extArchive = new File(tmpDir, "data_ext.tar");
                    extDataSize = tarAppDataViaInstalld(packageName, userId,
                            Installer.FLAG_STORAGE_EXTERNAL, extArchive, excludeCache);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to archive external data for " + packageName + ": " + e);
                    partial = true;
                }
            }

            checkCancelled();

            final int appUid = packageInfo.applicationInfo != null
                    ? packageInfo.applicationInfo.uid : -1;
            try {
                if (!AppMetadataBackup.backupPermissions(mContext, packageName, userId,
                        new File(tmpDir, AppMetadataBackup.FILE_PERMISSIONS))) {
                    partial = true;
                }
                if (!AppMetadataBackup.backupAppOps(mContext, packageName, appUid,
                        new File(tmpDir, AppMetadataBackup.FILE_APPOPS))) {
                    partial = true;
                }
                AppMetadataBackup.backupSsaid(userId, packageName,
                        new File(tmpDir, AppMetadataBackup.FILE_SSAID));
            } catch (RuntimeException e) {
                Slog.w(TAG, "Metadata backup failed for " + packageName + ": " + e);
                partial = true;
            }

            checkCancelled();

            final boolean encrypted = passphrase != null && passphrase.length > 0;
            final String checksum = checksumDirectory(tmpDir);
            final BackupManifest manifest = new BackupManifest(
                    backupId, packageName,
                    getAppLabel(packageName),
                    packageInfo.versionName,
                    getLongVersionCode(packageInfo),
                    timestampMs, apkSize, ceDataSize, deDataSize, extDataSize,
                    components,
                    encrypted,
                    partial ? BackupRecord.STATE_PARTIAL : BackupRecord.STATE_OK,
                    checksum, userId);

            final java.util.List<File> entryFiles = new java.util.ArrayList<>();
            final File[] packed = tmpDir.listFiles();
            if (packed != null) {
                java.util.Arrays.sort(packed,
                        (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : packed) {
                    if (f.isFile()) entryFiles.add(f);
                }
            }

            BackupArchive.write(stagedVbak, manifest.toJson(), entryFiles, passphrase);
            deleteRecursive(tmpDir);

            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    stagedVbak, ParcelFileDescriptor.MODE_READ_ONLY)) {
                mInstaller.publishBackupArchive(userId, backupId, pfd);
            } catch (InstallerException e) {
                throw new IOException("publishBackupArchive failed for " + packageName, e);
            } finally {
                stagedVbak.delete();
            }

            return manifest.toBackupRecord(backupDir.getAbsolutePath());

        } catch (CancelledException e) {
            Slog.i(TAG, "Backup of " + packageName + " cancelled");
            deleteRecursive(tmpDir);
            stagedVbak.delete();
            return null;
        } catch (IOException | RuntimeException e) {
            deleteRecursive(tmpDir);
            stagedVbak.delete();
            throw e;
        }
    }

    private long archiveFile(@NonNull File src, @NonNull File dest) throws IOException {
        if (!src.exists()) {
            Slog.w(TAG, "APK not found: " + src);
            return 0;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
            pipe(in, out);
        }
        return dest.length();
    }

    private long tarAppDataViaInstalld(@NonNull String packageName, int userId,
            int storageFlag, @NonNull File dest, boolean excludeCache) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(dest,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_TRUNCATE)) {
            mInstaller.tarAppData(packageName, userId, storageFlag, pfd, excludeCache);
        } catch (InstallerException e) {
            throw new IOException("tarAppData failed for " + packageName
                    + " (storageFlag=" + storageFlag + ")", e);
        }
        final long size = dest.length();
        if (size <= 1024L) {
            dest.delete();
            return 0;
        }
        return size;
    }

    private void pipe(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            checkCancelled();
        }
    }

    private String checksumDirectory(@NonNull File dir) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().equals("manifest.json")) continue;
                    md.update(f.getName().getBytes());
                    md.update(longToBytes(f.length()));
                }
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] longToBytes(long v) {
        return new byte[]{
                (byte) (v >> 56), (byte) (v >> 48), (byte) (v >> 40), (byte) (v >> 32),
                (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v
        };
    }

    private static String bytesToHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRecursive(@NonNull File f) {
        if (f.isDirectory()) {
            final File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private String getAppLabel(String packageName) {
        try {
            return mPm.getApplicationLabel(
                    mPm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static long getLongVersionCode(PackageInfo pi) {
        return pi.getLongVersionCode();
    }

    private void stopPackage(@NonNull String packageName) {
        if (mActivityManager == null) {
            return;
        }
        try {
            mActivityManager.forceStopPackage(packageName);
            if (isDebugEnabled()) {
                Slog.d(TAG, "force-stopped " + packageName + " before backup");
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to stop package before backup: " + packageName, e);
        }
    }

    private static boolean isDebugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE);
    }

    private void checkCancelled() throws CancelledException {
        if (mCancelled) throw new CancelledException();
    }

    static final class CancelledException extends RuntimeException {
        CancelledException() { super("Backup cancelled"); }
    }
}
