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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.appbackup.BackupResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.os.BackgroundThread;

import java.util.function.Consumer;

final class BackupNotificationHelper {

    private static final String CHANNEL_ID = "app_data_backup";
    private static final String CHANNEL_NAME = "App backup & restore";
    private static final String ACTION_CANCEL = "com.android.server.appbackup.action.CANCEL";
    private static final String EXTRA_TOKEN = "com.android.server.appbackup.extra.TOKEN";
    private static final int NOTIFICATION_ID = 1;

    private final Context mContext;
    private final Consumer<String> mCancelFn;
    private volatile NotificationManager mNm;
    private volatile boolean mChannelCreated;

    BackupNotificationHelper(Context context, Consumer<String> cancelFn) {
        mContext = context;
        mCancelFn = cancelFn;
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final String token = intent.getStringExtra(EXTRA_TOKEN);
                if (!TextUtils.isEmpty(token)) {
                    mCancelFn.accept(token);
                }
            }
        }, new IntentFilter(ACTION_CANCEL), null, BackgroundThread.getHandler(),
                Context.RECEIVER_NOT_EXPORTED);
    }

    void onOperationStarted(String token, int userId, boolean backup, int total) {
        final Notification.Builder b = baseBuilder(userId,
                backup ? "Backing up apps" : "Restoring apps")
                .setSmallIcon(backup ? android.R.drawable.stat_sys_upload
                        : android.R.drawable.stat_sys_download)
                .setContentText("Preparing " + total + (total == 1 ? " app" : " apps"))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        addCancelAction(b, token);
        post(token, userId, b.build());
    }

    void onPackageProgress(String token, int userId, boolean backup, String label,
            int index, int total) {
        final Notification.Builder b = baseBuilder(userId,
                backup ? "Backing up apps" : "Restoring apps")
                .setSmallIcon(backup ? android.R.drawable.stat_sys_upload
                        : android.R.drawable.stat_sys_download)
                .setContentText(label + " (" + index + "/" + total + ")")
                .setProgress(total, Math.max(0, index - 1), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        addCancelAction(b, token);
        post(token, userId, b.build());
    }

    void onOperationFinished(String token, int userId, boolean backup, BackupResult result) {
        final String title;
        if (result.isSuccess()) {
            title = backup ? "Backup complete" : "Restore complete";
        } else if (result.getStatus() == BackupResult.STATUS_PARTIAL) {
            title = backup ? "Backup partially complete" : "Restore partially complete";
        } else {
            title = backup ? "Backup failed" : "Restore failed";
        }
        final Notification.Builder b = baseBuilder(userId, title)
                .setSmallIcon(result.isSuccess()
                        ? (backup ? android.R.drawable.stat_sys_upload_done
                                : android.R.drawable.stat_sys_download_done)
                        : android.R.drawable.stat_notify_error)
                .setAutoCancel(true);
        final String msg = result.getMessage();
        if (!TextUtils.isEmpty(msg) && !"OK".equals(msg)) {
            b.setContentText(msg);
        }
        post(token, userId, b.build());
    }

    void onOperationCancelled(String token, int userId, boolean backup) {
        final Notification.Builder b = baseBuilder(userId,
                backup ? "Backup cancelled" : "Restore cancelled")
                .setSmallIcon(backup ? android.R.drawable.stat_sys_upload_done
                        : android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true);
        post(token, userId, b.build());
    }

    private Notification.Builder baseBuilder(int userId, String title) {
        return new Notification.Builder(mContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentIntent(contentIntent(userId))
                .setLocalOnly(true)
                .setShowWhen(false);
    }

    private void addCancelAction(Notification.Builder b, String token) {
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(mContext,
                        android.R.drawable.ic_menu_close_clear_cancel),
                "Cancel", cancelIntent(token)).build());
    }

    private PendingIntent cancelIntent(String token) {
        final Intent intent = new Intent(ACTION_CANCEL)
                .setPackage("android")
                .putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(mContext, token.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent contentIntent(int userId) {
        final Intent intent = new Intent("android.settings.APP_DATA_BACKUP_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivityAsUser(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.of(userId));
    }

    private void post(String token, int userId, Notification n) {
        final NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);
        nm.notifyAsUser(token, NOTIFICATION_ID, n, UserHandle.of(userId));
    }

    private NotificationManager nm() {
        NotificationManager nm = mNm;
        if (nm == null) {
            nm = mContext.getSystemService(NotificationManager.class);
            mNm = nm;
        }
        return nm;
    }

    private void ensureChannel(NotificationManager nm) {
        if (mChannelCreated) return;
        synchronized (this) {
            if (mChannelCreated) return;
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
            mChannelCreated = true;
        }
    }
}
