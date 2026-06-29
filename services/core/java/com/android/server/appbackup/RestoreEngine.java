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
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.Session;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class RestoreEngine {

    private static final String TAG = "AppDataRestoreEngine";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String CE_SNAPSHOT_ROOT = "/data/misc_ce";
    private static final String DE_SNAPSHOT_ROOT = "/data/misc_de";

    private static final int TAR_BLOCK_SIZE = 512;
    private static final int TAR_NAME_OFFSET = 0;
    private static final int TAR_NAME_LEN = 100;
    private static final int TAR_SIZE_OFFSET = 124;
    private static final int TAR_SIZE_LEN = 12;
    private static final int TAR_TYPE_OFFSET = 156;
    private static final int TAR_LINKNAME_OFFSET = 157;
    private static final int TAR_LINKNAME_LEN = 100;

    private final Context mContext;
    private final PackageManager mPm;
    private final ActivityManager mAm;
    private final Installer mInstaller;
    private volatile boolean mCancelled = false;

    public RestoreEngine(@NonNull Context context, @NonNull Installer installer) {
        mContext = context;
        mPm = context.getPackageManager();
        mAm = context.getSystemService(ActivityManager.class);
        mInstaller = installer;
    }

    public void cancel() { mCancelled = true; }

    @NonNull
    public BackupResult restorePackage(@NonNull BackupRecord record, int userId,
            @Nullable char[] passphrase) {
        mCancelled = false;
        final String packageName = record.getPackageName();
        final String archiveId = record.getId();

        final File stagingRoot = new File("/data/system/app_backup_staging");
        stagingRoot.mkdirs();
        final File stagedVbak = new File(stagingRoot,
                "." + archiveId + ".restore" + BackupArchive.EXTENSION);
        stagedVbak.delete();

        try {
            final ParcelFileDescriptor pfd;
            try {
                pfd = mInstaller.openBackupArchive(userId, archiveId);
            } catch (InstallerException e) {
                return BackupResult.failure(BackupResult.ERROR_IO,
                        "Backup archive not available: " + e.getMessage());
            }
            if (pfd == null) {
                return BackupResult.failure(BackupResult.ERROR_IO,
                        "Cannot open backup archive: " + archiveId);
            }
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    OutputStream out = new FileOutputStream(stagedVbak)) {
                pipe(in, out);
            } catch (IOException e) {
                return BackupResult.failure(BackupResult.ERROR_IO,
                        "Cannot stage backup archive: " + e.getMessage());
            }

            final File archiveFile = stagedVbak;

            try {
                final org.json.JSONObject manifestJson = BackupArchive.readManifest(archiveFile);
                final BackupRecord storedRecord = BackupManifest.recordFromJson(manifestJson,
                        record.getBackupDir());
                if (!storedRecord.getId().equals(record.getId())
                        || !storedRecord.getPackageName().equals(packageName)) {
                    return BackupResult.failure(BackupResult.ERROR_CHECKSUM_MISMATCH,
                        "Manifest ID/package mismatch");
                }
            } catch (IOException e) {
                return BackupResult.failure(BackupResult.ERROR_IO,
                        "Cannot read backup header: " + e);
            }

            try { checkCancelled(); } catch (CancelledException e) {
                return BackupResult.cancelled();
            }

            final File extractDir = new File(stagingRoot, "." + record.getId() + ".restore");
            deleteRecursive(extractDir);
            if (!extractDir.mkdirs()) {
                return BackupResult.failure(BackupResult.ERROR_IO,
                        "Cannot create restore staging dir: " + extractDir);
            }
            try {
                final boolean trustedBackup;
                try {
                    BackupArchive.extract(archiveFile, extractDir, passphrase);
                    trustedBackup = BackupArchive.isEncrypted(archiveFile);
                } catch (BackupArchive.BadPassphraseException e) {
                    return BackupResult.failure(BackupResult.ERROR_DECRYPTION_FAILED,
                            "Cannot decrypt backup (wrong passphrase?)");
                } catch (IOException e) {
                    return BackupResult.failure(BackupResult.ERROR_IO,
                            "Failed to unpack backup: " + e.getMessage());
                }
                return doRestore(record, userId, extractDir, trustedBackup);
            } finally {
                deleteRecursive(extractDir);
            }
        } finally {
            stagedVbak.delete();
        }
    }

    @NonNull
    private BackupResult doRestore(@NonNull BackupRecord record, int userId,
            @NonNull File archiveDir, boolean trustedBackup) {
        final String packageName = record.getPackageName();

        try { checkCancelled(); } catch (CancelledException e) {
            return BackupResult.cancelled();
        }

        final BackupResult installResult =
                installApks(archiveDir, packageName, userId, trustedBackup);
        if (!installResult.isSuccess()) {
            return installResult;
        }

        try { checkCancelled(); } catch (CancelledException e) {
            return BackupResult.cancelled();
        }

        try {
            mAm.forceStopPackage(packageName);
        } catch (Exception e) {
            Slog.w(TAG, "forceStopPackage failed for " + packageName + ": " + e);
        }

        boolean partial = false;

        final int appId;
        final int restoredUid;
        final String seInfo;
        try {
            final PackageInfo installed = mPm.getPackageInfoAsUser(packageName, 0, userId);
            restoredUid = installed.applicationInfo.uid;
            appId = android.os.UserHandle.getAppId(restoredUid);
            seInfo = installed.applicationInfo.seInfo;
        } catch (PackageManager.NameNotFoundException e) {
            return BackupResult.failure(BackupResult.ERROR_PACKAGE_NOT_FOUND,
                    "Installed package missing after APK restore: " + packageName);
        }

        final File ceArchive = new File(archiveDir, "data_ce.tar");
        if (ceArchive.exists()) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(ceArchive,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                assertTarEntriesSafe(ceArchive, "CE", packageName);
                mInstaller.untarAppData(packageName, userId, Installer.FLAG_STORAGE_CE,
                        appId, seInfo, pfd);
            } catch (IOException | InstallerException e) {
                Slog.w(TAG, "CE data restore failed for " + packageName + ": " + e);
                partial = true;
            }
        }

        try { checkCancelled(); } catch (CancelledException e) {
            return BackupResult.cancelled();
        }

        final File deArchive = new File(archiveDir, "data_de.tar");
        if (deArchive.exists()) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(deArchive,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                assertTarEntriesSafe(deArchive, "DE", packageName);
                mInstaller.untarAppData(packageName, userId, Installer.FLAG_STORAGE_DE,
                        appId, seInfo, pfd);
            } catch (IOException | InstallerException e) {
                Slog.w(TAG, "DE data restore failed for " + packageName + ": " + e);
                partial = true;
            }
        }

        final File extArchive = new File(archiveDir, "data_ext.tar");
        if (extArchive.exists()) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(extArchive,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                assertTarEntriesSafe(extArchive, "external", packageName);
                mInstaller.untarAppDataExternal(packageName, userId, pfd);
            } catch (IOException | InstallerException e) {
                Slog.w(TAG, "External data restore failed for " + packageName + ": " + e);
                partial = true;
            }
        }

        try {
            AppMetadataBackup.restorePermissions(mContext, packageName, userId,
                    new File(archiveDir, AppMetadataBackup.FILE_PERMISSIONS));
            AppMetadataBackup.restoreAppOps(mContext, packageName, restoredUid,
                    new File(archiveDir, AppMetadataBackup.FILE_APPOPS), trustedBackup);
            AppMetadataBackup.restoreSsaid(userId, packageName, restoredUid,
                    new File(archiveDir, AppMetadataBackup.FILE_SSAID));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Metadata restore failed for " + packageName + ": " + e);
            partial = true;
        }

        return partial
                ? BackupResult.partial("Data restore completed with errors for " + packageName)
                : BackupResult.ok();
    }

    private BackupResult installApks(@NonNull File archiveDir,
            @NonNull String packageName, int userId, boolean allowDowngrade) {
        final PackageInstaller installer = mPm.getPackageInstaller();
        final SessionParams params = new SessionParams(SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        if (allowDowngrade) {
            params.installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
        }
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);

        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            try (Session session = installer.openSession(sessionId)) {
                final File baseApk = new File(archiveDir, "base.apk");
                if (!baseApk.exists()) {
                    Slog.i(TAG, "No base.apk in backup; assuming " + packageName
                            + " is already installed (data-only restore)");
                    installer.abandonSession(sessionId);
                    return BackupResult.ok();
                }
                writeApkToSession(session, baseApk, "base.apk");

                final File[] splits = archiveDir.listFiles(
                    f -> f.getName().startsWith("split_")
                        && f.getName().endsWith(".apk"));
                if (splits != null) {
                    for (File split : splits) {
                    writeApkToSession(session, split, split.getName());
                        checkCancelled();
                    }
                }

                final LocalIntentReceiver receiver = new LocalIntentReceiver();
                session.commit(receiver.getIntentSender());
                final Intent result = receiver.getResult();
                final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    installer.abandonSession(sessionId);
                    return BackupResult.failure(BackupResult.ERROR_INSTALL_FAILED,
                            "PackageInstaller returned status=" + status);
                }
            }
            return BackupResult.ok();
        } catch (CancelledException e) {
            if (sessionId != -1) installer.abandonSession(sessionId);
            return BackupResult.cancelled();
        } catch (IOException e) {
            if (sessionId != -1) installer.abandonSession(sessionId);
            return BackupResult.failure(BackupResult.ERROR_IO,
                    "APK install IO error: " + e.getMessage());
        }
    }

    private void writeApkToSession(@NonNull Session session,
            @NonNull File apk, @NonNull String name) throws IOException {
        try (InputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite(name, 0, apk.length())) {
            pipe(in, out);
            session.fsync(out);
        }
    }

    private void extractTar(@NonNull File archive, @NonNull File destDir) throws IOException {
        try (InputStream tarIn = new FileInputStream(archive)) {
            final byte[] header = new byte[TAR_BLOCK_SIZE];
            while (true) {
                checkCancelled();
                final int read = readFully(tarIn, header);
                if (read < TAR_BLOCK_SIZE) break;

                if (isZeroBlock(header)) {
                    readFully(tarIn, header);
                    break;
                }

                final String name = readString(header, TAR_NAME_OFFSET, TAR_NAME_LEN);
                if (name.isEmpty()) break;

                final long size = readOctal(header, TAR_SIZE_OFFSET, TAR_SIZE_LEN);
                final char type = (char) header[TAR_TYPE_OFFSET];

                final String safeName = sanitizePath(name);
                if (safeName == null) {
                    skipBytes(tarIn, align512(size));
                    continue;
                }

                final File dest = new File(destDir, safeName);

                if (type == '5') {
                    dest.mkdirs();
                } else if (type == '0' || type == '\0') {
                    dest.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(dest)) {
                        copyBytes(tarIn, out, size);
                    }
                    final long pad = align512(size) - size;
                    if (pad > 0) skipBytes(tarIn, pad);
                } else {
                    skipBytes(tarIn, align512(size));
                }
            }
        } catch (CancelledException e) {
            throw new IOException("Restore cancelled");
        }
    }

    private static int readFully(@NonNull InputStream in, @NonNull byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            final int n = in.read(buf, total, buf.length - total);
            if (n == -1) break;
            total += n;
        }
        return total;
    }

    private static void copyBytes(@NonNull InputStream in,
            @NonNull OutputStream out, long count) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        long remaining = count;
        while (remaining > 0) {
            final int toRead = (int) Math.min(remaining, buf.length);
            final int n = in.read(buf, 0, toRead);
            if (n == -1) throw new IOException("Unexpected end of archive");
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void skipBytes(@NonNull InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) break;
            remaining -= skipped;
        }
    }

    private static long align512(long size) {
        return (size + 511) & ~511L;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String readString(byte[] buf, int offset, int maxLen) {
        int end = offset;
        while (end < offset + maxLen && buf[end] != 0) end++;
        return new String(buf, offset, end - offset).trim();
    }

    private static long readOctal(byte[] buf, int offset, int len) {
        final String s = readString(buf, offset, len).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sanitizePath(String rawName) {
        String name = rawName;
        while (name.startsWith("/")) name = name.substring(1);
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) return null;
        }
        return name.isEmpty() ? null : name;
    }

    private static void assertTarEntriesSafe(@NonNull File archive, @NonNull String label,
            @NonNull String packageName) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
            final byte[] header = new byte[TAR_BLOCK_SIZE];
            String pendingName = null;
            String pendingLink = null;
            while (true) {
                if (readFully(in, header) != TAR_BLOCK_SIZE || isZeroBlock(header)) {
                    break;
                }
                final long size = readOctal(header, TAR_SIZE_OFFSET, TAR_SIZE_LEN);
                final char type = (char) (header[TAR_TYPE_OFFSET] & 0xFF);
                if (type == 'L' || type == 'K') {
                    final String longVal = readLongName(in, size);
                    if (type == 'L') {
                        pendingName = longVal;
                    } else {
                        pendingLink = longVal;
                    }
                    continue;
                }
                final String name = pendingName != null
                        ? pendingName
                        : readString(header, TAR_NAME_OFFSET, TAR_NAME_LEN);
                final String link = pendingLink != null
                        ? pendingLink
                        : readString(header, TAR_LINKNAME_OFFSET, TAR_LINKNAME_LEN);
                pendingName = null;
                pendingLink = null;
                if (isUnsafeTarPath(name)) {
                    throw new IOException("Unsafe path in " + label + " tar for "
                            + packageName + ": " + name);
                }
                if ((type == '1' || type == '2') && isUnsafeTarPath(link)) {
                    throw new IOException("Unsafe link target in " + label + " tar for "
                            + packageName + ": " + name + " -> " + link);
                }
                skipBytes(in, align512(size));
            }
        }
    }

    private static String readLongName(@NonNull InputStream in, long size)
            throws IOException {
        if (size < 0 || size > 64 * 1024) {
            throw new IOException("Implausible long-name record size: " + size);
        }
        final byte[] buf = new byte[(int) size];
        if (readFully(in, buf) != buf.length) {
            throw new IOException("Truncated long-name record");
        }
        skipBytes(in, align512(size) - size);
        int end = buf.length;
        while (end > 0 && buf[end - 1] == 0) end--;
        return new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isUnsafeTarPath(@Nullable String rawName) {
        if (rawName == null) return true;
        final String name = rawName.trim();
        if (name.isEmpty()) return false;
        if (name.startsWith("/")) return true;
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private static void deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static File getCeSnapshotDir(String packageName, int userId, int snapshotId) {
        return new File(CE_SNAPSHOT_ROOT + "/" + userId + "/rollback/"
                + snapshotId + "/" + packageName);
    }

    private static File getDeSnapshotDir(String packageName, int userId, int snapshotId) {
        return new File(DE_SNAPSHOT_ROOT + "/" + userId + "/rollback/"
                + snapshotId + "/" + packageName);
    }

    private static void pipe(@NonNull InputStream in, @NonNull OutputStream out)
            throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private void checkCancelled() throws CancelledException {
        if (mCancelled) throw new CancelledException();
    }

    static final class CancelledException extends RuntimeException {
        CancelledException() { super("Restore cancelled"); }
    }

    private void destroySnapshot(@NonNull String packageName, int userId, int snapshotId,
            int storageFlags) {
        try {
            mInstaller.destroyAppDataSnapshot(packageName, userId, snapshotId, storageFlags);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to destroy restore staging snapshot for " + packageName
                    + " snapshotId=" + snapshotId, e);
        }
    }

    private static boolean isDebugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(TAG, Log.VERBOSE);
    }

    private static final class LocalIntentReceiver {
        private final Object mLock = new Object();
        private Intent mResult;

        private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    IBinder whitelistToken, IIntentReceiver finishedReceiver,
                    String requiredPermission, Bundle options) {
                synchronized (mLock) {
                    mResult = intent;
                    mLock.notifyAll();
                }
            }
        };

        IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        Intent getResult() {
            synchronized (mLock) {
                while (mResult == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Intent().putExtra(PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE);
                    }
                }
                return mResult;
            }
        }
    }
}
