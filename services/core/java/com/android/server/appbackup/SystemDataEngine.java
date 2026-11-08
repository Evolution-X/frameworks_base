/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_WIFI;
import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_MESSAGES;
import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.app.ActivityManager;
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class SystemDataEngine {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private final Context mContext;
    private final Installer mInstaller;
    private final int mUserId;
    private volatile boolean mCancelled;

    SystemDataEngine(Context context, Installer installer, int userId) {
        mContext = context.createContextAsUser(UserHandle.of(userId), 0);
        mInstaller = installer;
        mUserId = userId;
    }

    void cancel() { mCancelled = true; }

    BackupRecord backup(int type, char[] passphrase) throws IOException {
        SystemDataSchema.requireType(type);
        requireProviderUser(type);
        lock();
        File staging = null;
        try {
            check();
            staging = staging();
            File payload = new File(staging, SystemDataArchive.FILE_NAME);
            try (SystemDataArchive.Writer writer = new SystemDataArchive.Writer(payload, this::check)) {
                if (type == DATA_TYPE_WIFI) SystemWifiBackup.backup(mContext, writer);
                else new SystemProviderBackup(mContext, this::check).backup(type, writer);
                writer.finish();
            }
            try (SystemDataArchive archive = new SystemDataArchive(payload)) {
                archive.visit(row -> SystemDataSchema.validate(row, type, archive, this::check), this::check);
            }
            BackupManifest manifest = pack(type, mUserId, staging, passphrase);
            check();
            BackupRecord record = manifest.toBackupRecord("/data/media/" + mUserId + "/AppDataBackup");
            publish(staging, record.getId());
            return record;
        } catch (JSONException e) {
            throw new IOException("Cannot encode system backup", e);
        } finally {
            if (staging != null) delete(staging);
            LOCK.unlock();
        }
    }

    BackupResult restore(BackupRecord record, char[] passphrase) {
        File staging = null;
        boolean locked = false;
        int[] restored = {0};
        int[] failed = {0};
        String[] firstError = {null};
        boolean[] wifiSubmitted = {false};
        try {
            SystemDataSchema.requireType(record.getDataType());
            requireProviderUser(record.getDataType());
            lock();
            locked = true;
            check();
            require(record.getUserId() == mUserId, "System backup belongs to another user");
            staging = staging();
            try (SystemDataArchive archive = open(record, passphrase, staging)) {
                validate(archive, record.getDataType(), this::check);
                SystemProviderBackup provider = new SystemProviderBackup(mContext, this::check);
                archive.visit(row -> {
                    try {
                        if (record.getDataType() == DATA_TYPE_WIFI) {
                            wifiSubmitted[0] |= SystemWifiBackup.restore(mContext, row, archive, this::check);
                        } else {
                            provider.restore(row, archive);
                        }
                        restored[0]++;
                    } catch (CancelledException e) {
                        throw e;
                    } catch (IOException | RuntimeException e) {
                        check();
                        failed[0]++;
                        if (firstError[0] == null) firstError[0] = e.getMessage();
                    }
                }, this::check);
            }
            if (failed[0] > 0) {
                String message = failed[0] + " records could not be restored; "
                        + restored[0] + " restored or already present. " + firstError[0];
                return restored[0] > 0 ? BackupResult.partial(message)
                        : BackupResult.failure(BackupResult.ERROR_IO, message);
            }
            if (wifiSubmitted[0]) return BackupResult.ok(
                    "Wi-Fi restore was submitted to Android. Check saved networks in Wi-Fi settings.");
            return BackupResult.ok();
        } catch (CancelledException e) {
            return BackupResult.cancelled();
        } catch (BackupArchive.BadPassphraseException e) {
            return BackupResult.failure(BackupResult.ERROR_DECRYPTION_FAILED, "Wrong passphrase or damaged backup");
        } catch (IOException | RuntimeException e) {
            return restored[0] > 0 ? BackupResult.partial("Restore stopped after " + restored[0]
                    + " records; existing data has been kept. " + e.getMessage())
                    : BackupResult.failure(BackupResult.ERROR_IO, e.getMessage());
        } finally {
            if (staging != null) delete(staging);
            if (locked) LOCK.unlock();
        }
    }

    void verify(BackupRecord record, char[] passphrase) throws IOException {
        SystemDataSchema.requireType(record.getDataType());
        File staging = staging();
        try (SystemDataArchive archive = open(record, passphrase, staging)) {
            validate(archive, record.getDataType(), this::check);
        } finally { delete(staging); }
    }

    static void validate(SystemDataArchive archive, int type, SystemDataArchive.Check check)
            throws IOException {
        int[] platformWifi = {0};
        int count = archive.visit(row -> {
            SystemDataSchema.validate(row, type, archive, check);
            if (row.getString("kind").equals("wifi-platform")) platformWifi[0]++;
        }, check);
        require(platformWifi[0] == 0 || platformWifi[0] == 1 && count == 1,
                "Unexpected Wi-Fi backup records");
    }

    private SystemDataArchive open(BackupRecord record, char[] passphrase, File staging)
            throws IOException {
        check();
        File source = new File(staging, "source.vbak");
        final ParcelFileDescriptor fd;
        try { fd = mInstaller.openBackupArchive(mUserId, record.getId()); }
        catch (InstallerException e) { throw new IOException("Cannot open system backup", e); }
        require(fd != null, "Backup is unavailable");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                FileOutputStream out = new FileOutputStream(source)) {
            SystemDataArchive.hash(in, out, Math.min(SystemDataArchive.MAX_BYTES,
                    Math.max(0, staging.getUsableSpace() - (32 << 20)) / 2), this::check);
        }
        BackupRecord stored = BackupManifest.recordFromJson(BackupArchive.readManifest(source), "");
        require(record.getUserId() == mUserId && stored.getId().equals(record.getId())
                && stored.getUserId() == mUserId && stored.getDataType() == record.getDataType()
                && stored.getVersionCode() == 1
                && SystemDataSchema.identity(record.getDataType()).equals(stored.getPackageName()),
                "System backup identity or version mismatch");
        File contents = new File(staging, "contents");
        BackupArchive.extract(source, contents, passphrase, Math.min(SystemDataArchive.MAX_BYTES,
                Math.max(0, staging.getUsableSpace() - (16 << 20))));
        require(contents.list() != null && contents.list().length == 1,
                "Unexpected system backup contents");
        return new SystemDataArchive(new File(contents, SystemDataArchive.FILE_NAME));
    }

    static BackupManifest pack(int type, int userId, File staging, char[] passphrase) throws IOException {
        File payload = new File(staging, SystemDataArchive.FILE_NAME);
        BackupManifest manifest = new BackupManifest("system_" + UUID.randomUUID(),
                SystemDataSchema.identity(type), SystemDataSchema.label(type), "1", 1,
                System.currentTimeMillis(), 0, payload.length(), 0, 0, 0,
                passphrase != null && passphrase.length > 0, BackupRecord.STATE_OK, "", userId, type);
        BackupArchive.write(new File(staging, "import.vbak"), manifest.toJson(),
                Collections.singletonList(payload), passphrase);
        return manifest;
    }

    private void publish(File staging, String id) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(staging, "import.vbak"),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            mInstaller.publishBackupArchive(mUserId, id, fd);
        } catch (InstallerException e) {
            throw new IOException("Cannot save system backup", e);
        }
    }

    private void lock() throws IOException {
        try {
            while (!LOCK.tryLock(500, TimeUnit.MILLISECONDS)) check();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    private void check() throws IOException {
        if (mCancelled) throw new CancelledException();
        UserManager users = mContext.getSystemService(UserManager.class);
        require(ActivityManager.getCurrentUser() == mUserId && users != null
                && users.isUserUnlocked() && !users.isManagedProfile(),
                "Unlock and switch to the user whose system data is being backed up or restored");
    }

    private void requireProviderUser(int type) throws IOException {
        require(type != DATA_TYPE_MESSAGES || mUserId == UserHandle.USER_SYSTEM,
                "SMS and MMS backup and restore are available only for the device owner user");
    }

    static File staging() throws IOException {
        File root = new File("/data/system/app_backup_staging");
        require(root.isDirectory() || root.mkdirs(), "Cannot create system backup staging");
        return Files.createTempDirectory(root.toPath(), "system-").toFile();
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    static final class CancelledException extends IOException {}
}
