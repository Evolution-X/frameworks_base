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

package com.android.server.power;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class SleepModeIdleController {

    private static final String TAG = "SleepModeIdleController";
    
    private static final long REAPPLY_INTERVAL_MS = 20 * 60 * 1000; // 20 min

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> mBucketSnapshot = new ArrayMap<>();

    private boolean mDeepSleepActive = false;

    SleepModeIdleController(Context context) {
        mContext = context;
    }

    public void setDeepSleepEnabled(boolean enabled) {
        if (enabled == mDeepSleepActive) return;

        mDeepSleepActive = enabled;

        if (enabled) {
            snapshotBuckets();
            applyRestrictions();
            scheduleReapply();
        } else {
            restoreBuckets();
            mHandler.removeCallbacks(mReapplyRunnable);
        }
    }

    private void applyRestrictions() {
        final UsageStatsManager usm = mContext.getSystemService(UsageStatsManager.class);
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);

        final List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        final Set<String> foregroundPkgs = new ArraySet<>();

        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    for (String pkg : proc.pkgList) {
                        foregroundPkgs.add(pkg);
                    }
                }
            }
        }

        final List<ApplicationInfo> apps = mContext.getPackageManager()
                .getInstalledApplications(PackageManager.MATCH_ALL);

        for (ApplicationInfo app : apps) {
            final String pkg = app.packageName;

            if (shouldSkipApp(app, pkg, foregroundPkgs)) {
                continue;
            }

            try {
                usm.setAppStandbyBucket(pkg, UsageStatsManager.STANDBY_BUCKET_RESTRICTED);
            } catch (Exception ignored) {}
        }
    }

    private boolean shouldSkipApp(ApplicationInfo app, String pkg, Set<String> foregroundPkgs) {
        // Real foreground only (user actively using)
        if (foregroundPkgs.contains(pkg)) {
            return true;
        }

        // System apps
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            return true;
        }

        // Critical user apps
        if (isCriticalApp(pkg)) {
            return true;
        }

        return false;
    }

    private boolean isCriticalApp(String pkg) {
        return pkg.equals(getDefaultDialer())
            || pkg.equals(getDefaultSms())
            || pkg.equals(getDefaultAlarm());
    }

    private String getDefaultDialer() {
        TelecomManager tm = mContext.getSystemService(TelecomManager.class);
        return tm != null ? tm.getDefaultDialerPackage() : null;
    }

    private String getDefaultSms() {
        return Telephony.Sms.getDefaultSmsPackage(mContext);
    }

    private String getDefaultAlarm() {
        return Settings.Secure.getString(
            mContext.getContentResolver(),
            "default_alarm_app"
        );
    }

    private void snapshotBuckets() {
        mBucketSnapshot.clear();

        UsageStatsManager usm = mContext.getSystemService(UsageStatsManager.class);
        PackageManager pm = mContext.getPackageManager();

        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
            try {
                int bucket = usm.getAppStandbyBucket(app.packageName);
                mBucketSnapshot.put(app.packageName, bucket);
            } catch (Exception ignored) {}
        }
    }

    private void restoreBuckets() {
        UsageStatsManager usm = mContext.getSystemService(UsageStatsManager.class);

        for (Map.Entry<String, Integer> entry : mBucketSnapshot.entrySet()) {
            try {
                usm.setAppStandbyBucket(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {}
        }

        mBucketSnapshot.clear();
    }

    private final Runnable mReapplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mDeepSleepActive) return;

            applyRestrictions();
            mHandler.postDelayed(this, REAPPLY_INTERVAL_MS);
        }
    };

    private void scheduleReapply() {
        mHandler.removeCallbacks(mReapplyRunnable);
        mHandler.postDelayed(mReapplyRunnable, REAPPLY_INTERVAL_MS);
    }
}
