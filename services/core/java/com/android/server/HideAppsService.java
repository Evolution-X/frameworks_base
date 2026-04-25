/*
 * Copyright (C) 2025 the AxionAOSP Project
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
package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.SystemService;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HideAppsService extends SystemService {
    private static final String TAG = "HideAppsService";

    private final Context mContext;
    private final Handler mHandler = new Handler();

    public HideAppsService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting HideAppsService");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiver(new PackageUninstallReceiver(), filter);
        }
    }

    private class PackageUninstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();
            if (packageName != null) {
                Slog.i(TAG, "Package uninstalled: " + packageName);
                removeFromHiddenLists(packageName);
            }
        }
    }

    private void removeFromHiddenLists(String packageName) {
        ContentResolver cr = mContext.getContentResolver();
        for (String key : new String[]{
                Settings.Secure.HIDE_APPLIST,
                Settings.Secure.HIDE_DEVELOPER_STATUS}) {
            String apps = Settings.Secure.getString(cr, key);
            if (apps == null || apps.isEmpty() || apps.equals(",")) continue;
            Set<String> appSet = new HashSet<>(Arrays.asList(apps.split(",")));
            if (appSet.remove(packageName)) {
                Slog.i(TAG, "Removing package due to reason: UNINSTALLED: " + packageName + " from " + key);
                Settings.Secure.putString(cr, key, String.join(",", appSet));
            }
        }
    }
}
