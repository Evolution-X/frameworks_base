package com.android.systemui.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.internal.util.evolution.Utils;
import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.util.settings.GlobalSettings;

import java.time.Duration;

import javax.inject.Inject;

@SysUISingleton
public final class EnhancedEstimatesImpl implements EnhancedEstimates {

    private static final String TAG = "EnhancedEstimatesImpl";

    private static final String TURBO_PACKAGE = "com.google.android.apps.turbo";
    private static final String TURBO_AUTHORITY = TURBO_PACKAGE + ".estimated_time_remaining";

    private static final Estimate EMPTY_ESTIMATE = new Estimate(
            EstimateKt.ESTIMATE_MILLIS_UNKNOWN,
            false,
            EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);

    private static final Duration DAY = Duration.ofDays(1L);
    private static final long HOUR = Duration.ofHours(1L).toMillis();
    private static final long THREE_HOURS = Duration.ofHours(3L).toMillis();
    private static final long FIFTEEN_MINUTES = Duration.ofMinutes(15L).toMillis();

    private final Context mContext;
    private final GlobalSettings mGlobalSettings;
    private final KeyValueListParser mParser;

    @Inject
    public EnhancedEstimatesImpl(
            Context context,
            GlobalSettings globalSettings) {
        mContext = context;
        mGlobalSettings = globalSettings;
        mParser = new KeyValueListParser(',');
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        final boolean isTurboInstalled = Utils.isPackageInstalled(
                mContext,
                TURBO_PACKAGE,
                false /* ignoreState */);
        if (!isTurboInstalled) return false;
        updateFlags();
        return mParser.getBoolean("hybrid_enabled", true);
    }

    @Override
    public Estimate getEstimate() {
        final Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(TURBO_AUTHORITY)
                .appendPath("time_remaining")
                .build();
        try (final Cursor query = mContext.getContentResolver().query(uri, null, null, null, null)) {
            if (query == null || !query.moveToFirst()) return EMPTY_ESTIMATE;
            try {
                long timeRemaining = -1L;
                final int usageColumnIndex = query.getColumnIndex("is_based_on_usage");
                final boolean isBasedOnUsage = usageColumnIndex != -1
                        && query.getInt(usageColumnIndex) != 0;
                final int batteryLifeColumnIndex = query.getColumnIndex("average_battery_life");
                if (batteryLifeColumnIndex != -1) {
                    final long averageBatteryLife = query.getLong(batteryLifeColumnIndex);
                    if (averageBatteryLife != -1L) {
                        final long duration = Duration.ofMillis(averageBatteryLife)
                                .compareTo(DAY) >= 0 ? HOUR : FIFTEEN_MINUTES;
                        timeRemaining = PowerUtil.roundTimeToNearestThreshold(
                                averageBatteryLife, duration);
                    }
                }
                final int estimateColumnIndex = query.getColumnIndex("battery_estimate");
                return new Estimate(
                        query.getLong(estimateColumnIndex),
                        isBasedOnUsage,
                        timeRemaining);
            } catch (Exception ex) {
                // Catch and release
            }
        } catch (Exception e) {
            Log.e(TAG, "Something went wrong when getting an estimate from Turbo", e);
        }
        return EMPTY_ESTIMATE;
    }

    @Override
    public long getLowWarningThreshold() {
        updateFlags();
        return mParser.getLong("low_threshold", THREE_HOURS);
    }

    @Override
    public long getSevereWarningThreshold() {
        updateFlags();
        return mParser.getLong("severe_threshold", HOUR);
    }

    @Override
    public boolean getLowWarningEnabled() {
        updateFlags();
        return mParser.getBoolean("low_warning_enabled", false);
    }

    private void updateFlags() {
        try {
            mParser.setString(mGlobalSettings.getString("hybrid_sysui_battery_warning_flags"));
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Bad hybrid sysui warning flags");
        }
    }
}
