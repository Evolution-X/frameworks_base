/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;

import javax.inject.Inject;

public class WifiAutoOffTile extends ConnectivityAutoOffTile {

    public static final String TILE_SPEC = "wifi_auto_off";

    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    @Inject
    public WifiAutoOffTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
    }

    @Override
    protected String getSettingKey() {
        return WIFI_AUTO_OFF_TIMEOUT;
    }

    @Override
    protected String getRequiredFeature() {
        return PackageManager.FEATURE_WIFI;
    }

    @Override
    protected int getLabelResId() {
        return R.string.quick_settings_wifi_auto_off_label;
    }

    @Override
    protected int getIconResId() {
        return R.drawable.ic_qs_wifi_auto_off;
    }

    @Override
    protected int getAccessibilityOnResId() {
        return R.string.accessibility_quick_settings_wifi_auto_off_on;
    }

    @Override
    protected int getAccessibilityOffResId() {
        return R.string.accessibility_quick_settings_wifi_auto_off_off;
    }

    @Override
    protected Intent getSettingsIntent() {
        return WIFI_SETTINGS;
    }
}