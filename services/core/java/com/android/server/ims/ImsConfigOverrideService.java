/*
 * Copyright (C) 2025 AxionOS
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

package com.android.server.ims;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Slog;

import com.android.server.SystemService;

public final class ImsConfigOverrideService extends SystemService {
    private static final String TAG = "ImsConfigOverrideService";
    private final Context mContext;

    public ImsConfigOverrideService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Service started");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            Slog.i(TAG, "Boot completed — applying IMS CarrierConfig overrides");
            overrideConfig();
        }
    }

    private void overrideConfig() {
        CarrierConfigManager cm = mContext.getSystemService(CarrierConfigManager.class);
        SubscriptionManager sm = mContext.getSystemService(SubscriptionManager.class);
        if (cm == null || sm == null) {
            Slog.w(TAG, "CarrierConfigManager or SubscriptionManager is null — skipping override");
            return;
        }

        PersistableBundle values = getConfig();
        int[] subIds = sm.getActiveSubscriptionIdList();
        if (subIds == null || subIds.length == 0) {
            Slog.w(TAG, "No active subscriptions — nothing to override");
            return;
        }

        for (int subId : subIds) {
            try {
                cm.overrideConfig(subId, values);
                Slog.i(TAG, "Applied CarrierConfig override for subId=" + subId);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to override config for subId=" + subId, e);
            }
        }
    }

    private static PersistableBundle getConfig() {
        PersistableBundle b = new PersistableBundle();

        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true);

        b.putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, true);
        b.putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, 6);

        b.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
        b.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false);

        b.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true);
        b.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true);
        b.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                new int[]{
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
                });
        b.putIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                new int[]{-128, -118, -108, -98});

        return b;
    }
}
