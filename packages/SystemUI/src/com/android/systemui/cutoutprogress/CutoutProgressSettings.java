/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.cutoutprogress;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

public final class CutoutProgressSettings {

    public static final String KEY_ENABLED = "cutout_progress_enabled";

    public static final String KEY_RING_COLOR_MODE = "cutout_progress_ring_color_mode";

    public static final String KEY_RING_COLOR = "cutout_progress_ring_color";

    public static final String KEY_ERROR_COLOR = "cutout_progress_error_color";

    public static final String KEY_FINISH_FLASH_COLOR = "cutout_progress_finish_flash_color";

    public static final String KEY_STROKE_WIDTH_DP10 = "cutout_progress_stroke_width_dp10";

    public static final String KEY_RING_GAP_X1000 = "cutout_progress_ring_gap_x1000";

    public static final String KEY_OPACITY = "cutout_progress_opacity";

    public static final String KEY_CLOCKWISE = "cutout_progress_clockwise";

    public static final String KEY_FINISH_STYLE = "cutout_progress_finish_style";

    public static final String KEY_FINISH_HOLD_MS = "cutout_progress_finish_hold_ms";

    public static final String KEY_FINISH_EXIT_MS = "cutout_progress_finish_exit_ms";

    public static final String KEY_FINISH_USE_FLASH = "cutout_progress_finish_use_flash";

    public static final String KEY_COMPLETION_PULSE = "cutout_progress_completion_pulse";

    public static final String KEY_AUTO_GEOMETRY = "cutout_progress_auto_geometry";

    public static final String KEY_PATH_MODE = "cutout_progress_path_mode";

    public static final String KEY_RING_SCALE_X_X1000 = "cutout_progress_ring_scale_x_x1000";

    public static final String KEY_RING_SCALE_Y_X1000 = "cutout_progress_ring_scale_y_x1000";

    public static final String KEY_RING_OFFSET_X_DP10 = "cutout_progress_ring_offset_x_dp10";

    public static final String KEY_RING_OFFSET_Y_DP10 = "cutout_progress_ring_offset_y_dp10";

    public static final String KEY_BG_RING_ENABLED = "cutout_progress_bg_ring_enabled";

    public static final String KEY_BG_RING_COLOR = "cutout_progress_bg_ring_color";

    public static final String KEY_BG_RING_OPACITY = "cutout_progress_bg_ring_opacity";

    public static final String KEY_MIN_VIS_ENABLED = "cutout_progress_min_vis_enabled";

    public static final String KEY_MIN_VIS_MS = "cutout_progress_min_vis_ms";

    public static final String KEY_SHOW_COUNT_BADGE = "cutout_progress_show_count_badge";

    public static final String KEY_BADGE_OFFSET_X_DP10 = "cutout_progress_badge_offset_x_dp10";

    public static final String KEY_BADGE_OFFSET_Y_DP10 = "cutout_progress_badge_offset_y_dp10";

    public static final String KEY_BADGE_TEXT_SIZE_SP10 = "cutout_progress_badge_text_size_sp10";

    public static final String KEY_PERCENT_ENABLED = "cutout_progress_percent_enabled";

    public static final String KEY_PERCENT_SIZE_SP10 = "cutout_progress_percent_size_sp10";

    public static final String KEY_PERCENT_BOLD = "cutout_progress_percent_bold";

    public static final String KEY_PERCENT_POSITION = "cutout_progress_percent_position";

    public static final String KEY_PERCENT_OFFSET_X = "cutout_progress_percent_offset_x";

    public static final String KEY_PERCENT_OFFSET_Y = "cutout_progress_percent_offset_y";

    public static final String KEY_FILENAME_ENABLED = "cutout_progress_filename_enabled";

    public static final String KEY_FILENAME_SIZE_SP10 = "cutout_progress_filename_size_sp10";

    public static final String KEY_FILENAME_BOLD = "cutout_progress_filename_bold";

    public static final String KEY_FILENAME_POSITION = "cutout_progress_filename_position";

    public static final String KEY_FILENAME_OFFSET_X = "cutout_progress_filename_offset_x";

    public static final String KEY_FILENAME_OFFSET_Y = "cutout_progress_filename_offset_y";

    public static final String KEY_FILENAME_MAX_CHARS = "cutout_progress_filename_max_chars";

    public static final String KEY_FILENAME_TRUNCATE = "cutout_progress_filename_truncate";

    public static final String KEY_PROGRESS_EASING = "cutout_progress_easing";

    public static final String KEY_CHARGING_RING_ENABLED = "cutout_progress_charging_ring_enabled";

    public static final String KEY_CHARGING_PULSE_ENABLED = "cutout_progress_charging_pulse_enabled";

    public static final String KEY_BATTERY_INDICATOR_ENABLED = "cutout_progress_battery_indicator_enabled";

    public static final String KEY_MUSIC_RING_ENABLED = "cutout_progress_music_enabled";

    public static final String KEY_MUSIC_COLOR_MODE = "cutout_progress_music_color_mode";

    public static final String KEY_MUSIC_CUSTOM_COLOR = "cutout_progress_music_custom_color";

    public static final String KEY_MUSIC_OPACITY = "cutout_progress_music_opacity";

    public static final String KEY_MUSIC_STROKE_WIDTH_DP10 = "cutout_progress_music_stroke_dp10";

    public static final String KEY_MUSIC_SHOW_ON_AOD = "cutout_progress_music_aod";

    public static final String KEY_MUSIC_CLOCKWISE = "cutout_progress_music_clockwise";

    public static final String KEY_DOWNLOAD_PRESENTATION =
            "cutout_progress_download_presentation";

    public static final String KEY_MUSIC_PRESENTATION =
            "cutout_progress_music_presentation";

    public static final String KEY_PRIMARY_PRIORITY =
            "cutout_progress_primary_priority";

    public static final String KEY_MULTI_RING_SPACING_DP10 =
            "cutout_progress_multi_ring_spacing_dp10";

    public static final String KEY_MUSIC_WAVE_ENABLED =
            "cutout_progress_music_wave_enabled";

    public static final String KEY_MUSIC_WAVE_AMPLITUDE_DP10 =
            "cutout_progress_music_wave_amplitude_dp10";

    public static final String KEY_MUSIC_WAVE_DENSITY =
            "cutout_progress_music_wave_density";

    public static final String KEY_MUSIC_WAVE_SPEED =
            "cutout_progress_music_wave_speed";

    public static final String KEY_TIMER_ENABLED =
            "cutout_progress_timer_enabled";
    public static final String KEY_TIMER_PRESENTATION =
            "cutout_progress_timer_presentation";
    public static final String KEY_TIMER_COLOR_MODE =
            "cutout_progress_timer_color_mode";
    public static final String KEY_TIMER_CUSTOM_COLOR =
            "cutout_progress_timer_custom_color";
    public static final String KEY_TIMER_OPACITY =
            "cutout_progress_timer_opacity";
    public static final String KEY_TIMER_STROKE_WIDTH_DP10 =
            "cutout_progress_timer_stroke_dp10";
    public static final String KEY_TIMER_CLOCKWISE =
            "cutout_progress_timer_clockwise";
    public static final String KEY_TIMER_FLAME_ENABLED =
            "cutout_progress_timer_flame_enabled";
    public static final String KEY_TIMER_FLAME_COLOR =
            "cutout_progress_timer_flame_color";
    public static final String KEY_TIMER_FLAME_SIZE_DP10 =
            "cutout_progress_timer_flame_size_dp10";

    public static final String KEY_AURORA_ENABLED =
            "cutout_progress_aurora_enabled";
    public static final String KEY_AURORA_CALLS =
            "cutout_progress_aurora_calls";
    public static final String KEY_AURORA_MUSIC =
            "cutout_progress_aurora_music";
    public static final String KEY_AURORA_RECORDING =
            "cutout_progress_aurora_recording";
    public static final String KEY_AURORA_NOTIFICATIONS =
            "cutout_progress_aurora_notifications";
    public static final String KEY_AURORA_COLOR_MODE =
            "cutout_progress_aurora_color_mode";
    public static final String KEY_AURORA_CUSTOM_COLOR =
            "cutout_progress_aurora_custom_color";
    public static final String KEY_AURORA_NOTIFICATION_COLOR_MODE =
            "cutout_progress_aurora_notification_color_mode";
    public static final String KEY_AURORA_SPREAD_DP10 =
            "cutout_progress_aurora_spread_dp10";
    public static final String KEY_AURORA_OPACITY =
            "cutout_progress_aurora_opacity";
    public static final String KEY_AURORA_SPEED =
            "cutout_progress_aurora_speed";
    public static final String KEY_AURORA_NOTIFICATION_DURATION_MS =
            "cutout_progress_aurora_notification_duration_ms";

    public static final String KEY_GLOW_ENABLED = "cutout_progress_glow_enabled";

    public static final String KEY_GLOW_RADIUS_DP10 = "cutout_progress_glow_radius_dp10";

    public static final int RING_COLOR_MODE_ACCENT = 0;
    public static final int RING_COLOR_MODE_RAINBOW = 1;
    public static final int RING_COLOR_MODE_CUSTOM = 2;
    public static final int PRESENTATION_PRIMARY = 0;
    public static final int PRESENTATION_INDEPENDENT = 1;
    public static final int PRESENTATION_DISABLED = 2;
    public static final int PRIMARY_PRIORITY_DOWNLOAD = 0;
    public static final int PRIMARY_PRIORITY_MUSIC = 1;
    public static final int PRIMARY_PRIORITY_TIMER = 2;

    public static final int AURORA_COLOR_MODE_SPECTRUM = 0;
    public static final int AURORA_COLOR_MODE_SOURCE = 1;
    public static final int AURORA_COLOR_MODE_CUSTOM = 2;
    public static final int AURORA_NOTIFICATION_COLOR_NOTIFICATION = 0;
    public static final int AURORA_NOTIFICATION_COLOR_EFFECT = 1;
    private static final boolean DEF_ENABLED = false;
    private static final int DEF_RING_COLOR_MODE = RING_COLOR_MODE_ACCENT;
    private static final int DEF_RING_COLOR = 0xFF2196F3;
    private static final int DEF_ERROR_COLOR = 0xFFF44336;
    private static final int DEF_FINISH_FLASH_COLOR = Color.WHITE;
    private static final float DEF_STROKE_DP = 2.0f;
    private static final float DEF_RING_GAP = 1.160f;
    private static final int DEF_OPACITY = 90;
    private static final boolean DEF_CLOCKWISE = true;
    private static final int DEF_FINISH_STYLE = 0;
    private static final int DEF_FINISH_HOLD_MS = 500;
    private static final int DEF_FINISH_EXIT_MS = 500;
    private static final boolean DEF_FINISH_USE_FLASH = true;
    private static final boolean DEF_COMPLETION_PULSE = true;
    private static final boolean DEF_AUTO_GEOMETRY = true;
    private static final boolean DEF_PATH_MODE = true;
    private static final float DEF_RING_SCALE_X = 1.05f;
    private static final float DEF_RING_SCALE_Y = 0.60f;
    private static final float DEF_RING_OFFSET_X = 0.0f;
    private static final float DEF_RING_OFFSET_Y = 1.5f;
    private static final boolean DEF_BG_RING_ENABLED = true;
    private static final int DEF_BG_RING_COLOR = 0xFF808080;
    private static final int DEF_BG_RING_OPACITY = 30;
    private static final boolean DEF_MIN_VIS_ENABLED = true;
    private static final int DEF_MIN_VIS_MS = 500;
    private static final boolean DEF_SHOW_COUNT_BADGE = false;
    private static final float DEF_BADGE_OFFSET = 0.0f;
    private static final float DEF_BADGE_TEXT_SP = 10.0f;
    private static final boolean DEF_PERCENT_ENABLED = false;
    private static final float DEF_PERCENT_SP = 8.0f;
    private static final boolean DEF_PERCENT_BOLD = true;
    private static final int DEF_PERCENT_POSITION = 0;
    private static final boolean DEF_FILENAME_ENABLED = false;
    private static final float DEF_FILENAME_SP = 7.0f;
    private static final boolean DEF_FILENAME_BOLD = false;
    private static final int DEF_FILENAME_POSITION  = 4;
    private static final int DEF_FILENAME_MAX_CHARS = 20;
    private static final int DEF_FILENAME_TRUNCATE = 0;
    private static final int DEF_EASING = 0;
    private static final boolean DEF_CHARGING_RING_ENABLED = true;
    private static final boolean DEF_CHARGING_PULSE_ENABLED = true;
    private static final boolean DEF_BATTERY_INDICATOR_ENABLED = false;
    public static final int MUSIC_COLOR_MODE_ALBUM_ICON = 0;
    public static final int MUSIC_COLOR_MODE_ACCENT = 1;
    public static final int MUSIC_COLOR_MODE_ALBUM_ART = 2;
    public static final int MUSIC_COLOR_MODE_CUSTOM = 3;
    private static final boolean DEF_MUSIC_RING_ENABLED = false;
    private static final int DEF_MUSIC_COLOR_MODE = MusicRingColorManager.MODE_ALBUM_ICON;
    private static final int DEF_MUSIC_CUSTOM_COLOR = 0xFF9C27B0;
    private static final int DEF_MUSIC_OPACITY = 85;
    private static final float DEF_MUSIC_STROKE_DP = 2.0f;
    private static final boolean DEF_MUSIC_SHOW_ON_AOD = false;
    private static final boolean DEF_MUSIC_CLOCKWISE = true;
    private static final int DEF_DOWNLOAD_PRESENTATION = PRESENTATION_PRIMARY;
    private static final int DEF_MUSIC_PRESENTATION = PRESENTATION_PRIMARY;
    private static final int DEF_PRIMARY_PRIORITY = PRIMARY_PRIORITY_DOWNLOAD;
    private static final float DEF_MULTI_RING_SPACING_DP = 5.0f;
    private static final boolean DEF_MUSIC_WAVE_ENABLED = false;
    private static final float DEF_MUSIC_WAVE_AMPLITUDE_DP = 2.5f;
    private static final int DEF_MUSIC_WAVE_DENSITY = 48;
    private static final int DEF_MUSIC_WAVE_SPEED = 100;

    private static final boolean DEF_TIMER_ENABLED = false;
    private static final int DEF_TIMER_PRESENTATION = PRESENTATION_PRIMARY;
    private static final int DEF_TIMER_COLOR_MODE = RING_COLOR_MODE_ACCENT;
    private static final int DEF_TIMER_CUSTOM_COLOR = 0xFFFF8A00;
    private static final int DEF_TIMER_OPACITY = 95;
    private static final float DEF_TIMER_STROKE_DP = 2.0f;
    private static final boolean DEF_TIMER_CLOCKWISE = true;
    private static final boolean DEF_TIMER_FLAME_ENABLED = true;
    private static final int DEF_TIMER_FLAME_COLOR = 0xFFFF6D00;
    private static final float DEF_TIMER_FLAME_SIZE_DP = 3.5f;

    private static final boolean DEF_AURORA_ENABLED = false;
    private static final boolean DEF_AURORA_CALLS = true;
    private static final boolean DEF_AURORA_MUSIC = true;
    private static final boolean DEF_AURORA_RECORDING = true;
    private static final boolean DEF_AURORA_NOTIFICATIONS = true;
    private static final int DEF_AURORA_COLOR_MODE = AURORA_COLOR_MODE_SPECTRUM;
    private static final int DEF_AURORA_CUSTOM_COLOR = 0xFF7C4DFF;
    private static final int DEF_AURORA_NOTIFICATION_COLOR_MODE =
            AURORA_NOTIFICATION_COLOR_NOTIFICATION;
    private static final float DEF_AURORA_SPREAD_DP = 8.0f;
    private static final int DEF_AURORA_OPACITY = 85;
    private static final int DEF_AURORA_SPEED = 100;
    private static final int DEF_AURORA_NOTIFICATION_DURATION_MS = 2500;

    private static final boolean DEF_GLOW_ENABLED = false;
    private static final float DEF_GLOW_RADIUS_DP = 4.0f;

    static final String[] POSITION_NAMES = {
            "right", "left", "top", "bottom",
            "top_right", "top_left", "bottom_right", "bottom_left"
    };

    static final String[] FINISH_STYLE_NAMES = { "pop", "segmented", "snap" };

    static final String[] EASING_NAMES = {
            "linear", "accelerate", "decelerate", "ease_in_out"
    };

    static final String[] TRUNCATE_MODE_NAMES = { "middle", "start", "end" };

    private final ContentResolver mCr;
    private final Handler mHandler;
    private int mUserId;
    private ContentObserver mObserver;
    private Runnable mCallback;

    public CutoutProgressSettings(ContentResolver cr, Handler handler, int userId) {
        mCr = cr;
        mHandler = handler;
        mUserId = userId;
    }

    public void observe(Runnable onChange) {
        mCallback = onChange;
        registerObserverForCurrentUser();
    }

    public void setUserId(int userId) {
        if (mUserId == userId) return;
        mUserId = userId;
        if (mObserver != null) {
            mCr.unregisterContentObserver(mObserver);
            mObserver = null;
            registerObserverForCurrentUser();
        }
    }

    private void registerObserverForCurrentUser() {
        if (mObserver != null) {
            mCr.unregisterContentObserver(mObserver);
        }
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                String key = uri != null ? uri.getLastPathSegment() : null;
                if (key != null && !key.startsWith("cutout_progress_")) return;
                if (mCallback != null) mCallback.run();
            }
        };
        mCr.registerContentObserver(
                Settings.Secure.CONTENT_URI, true, mObserver, mUserId);
    }

    public void stopObserving() {
        if (mObserver != null) {
            mCr.unregisterContentObserver(mObserver);
            mObserver = null;
        }
        mCallback = null;
    }

    public boolean isEnabled() {
        return getInt(KEY_ENABLED, DEF_ENABLED ? 1 : 0) != 0;
    }

    public int getRingColorMode() {
        return clamp(getInt(KEY_RING_COLOR_MODE, DEF_RING_COLOR_MODE),
                RING_COLOR_MODE_ACCENT, RING_COLOR_MODE_CUSTOM);
    }

    public int getRingColor() {
        return getInt(KEY_RING_COLOR, DEF_RING_COLOR);
    }

    public int getErrorColor() {
        return getInt(KEY_ERROR_COLOR, DEF_ERROR_COLOR);
    }

    public int getFinishFlashColor() {
        return getInt(KEY_FINISH_FLASH_COLOR, DEF_FINISH_FLASH_COLOR);
    }

    public float getStrokeWidthDp() {
        return clamp(getInt(KEY_STROKE_WIDTH_DP10, (int)(DEF_STROKE_DP * 10)), 5, 80) / 10f;
    }

    public float getRingGap() {
        return clamp(getInt(KEY_RING_GAP_X1000, (int)(DEF_RING_GAP * 1000)), 1000, 2000) / 1000f;
    }

    public int getOpacity() {
        return clamp(getInt(KEY_OPACITY, DEF_OPACITY), 0, 100);
    }

    public boolean isClockwise() {
        return getInt(KEY_CLOCKWISE, DEF_CLOCKWISE ? 1 : 0) != 0;
    }

    public String getFinishStyle() {
        int idx = clamp(getInt(KEY_FINISH_STYLE, DEF_FINISH_STYLE), 0,
                FINISH_STYLE_NAMES.length - 1);
        return FINISH_STYLE_NAMES[idx];
    }

    public int getFinishHoldMs() {
        return clamp(getInt(KEY_FINISH_HOLD_MS, DEF_FINISH_HOLD_MS), 0, 2000);
    }

    public int getFinishExitMs() {
        return clamp(getInt(KEY_FINISH_EXIT_MS, DEF_FINISH_EXIT_MS), 0, 2000);
    }

    public boolean isFinishUseFlash() {
        return getInt(KEY_FINISH_USE_FLASH, DEF_FINISH_USE_FLASH ? 1 : 0) != 0;
    }

    public boolean isCompletionPulse() {
        return getInt(KEY_COMPLETION_PULSE, DEF_COMPLETION_PULSE ? 1 : 0) != 0;
    }

    public boolean isAutoGeometryEnabled() {
        return getInt(KEY_AUTO_GEOMETRY, DEF_AUTO_GEOMETRY ? 1 : 0) != 0;
    }

    public boolean isPathMode() {
        return getInt(KEY_PATH_MODE, DEF_PATH_MODE ? 1 : 0) != 0;
    }

    public float getRingScaleX() {
        return clamp(getInt(KEY_RING_SCALE_X_X1000, (int)(DEF_RING_SCALE_X * 1000)), 500, 3000) / 1000f;
    }

    public float getRingScaleY() {
        return clamp(getInt(KEY_RING_SCALE_Y_X1000, (int)(DEF_RING_SCALE_Y * 1000)), 500, 3000) / 1000f;
    }

    public float getRingOffsetXDp() {
        return clamp(getInt(KEY_RING_OFFSET_X_DP10, (int)(DEF_RING_OFFSET_X * 10)), -200, 200) / 10f;
    }

    public float getRingOffsetYDp() {
        return clamp(getInt(KEY_RING_OFFSET_Y_DP10, (int)(DEF_RING_OFFSET_Y * 10)), -200, 200) / 10f;
    }

    public boolean isBgRingEnabled() {
        return getInt(KEY_BG_RING_ENABLED, DEF_BG_RING_ENABLED ? 1 : 0) != 0;
    }

    public int getBgRingColor() {
        return getInt(KEY_BG_RING_COLOR, DEF_BG_RING_COLOR);
    }

    public int getBgRingOpacity() {
        return clamp(getInt(KEY_BG_RING_OPACITY, DEF_BG_RING_OPACITY), 0, 100);
    }

    public boolean isMinVisEnabled() {
        return getInt(KEY_MIN_VIS_ENABLED, DEF_MIN_VIS_ENABLED ? 1 : 0) != 0;
    }

    public int getMinVisMs() {
        return clamp(getInt(KEY_MIN_VIS_MS, DEF_MIN_VIS_MS), 100, 3000);
    }

    public boolean isShowCountBadge() {
        return getInt(KEY_SHOW_COUNT_BADGE, DEF_SHOW_COUNT_BADGE ? 1 : 0) != 0;
    }

    public float getBadgeOffsetXDp() {
        return clamp(getInt(KEY_BADGE_OFFSET_X_DP10, (int)(DEF_BADGE_OFFSET * 10)), -100, 100) / 10f;
    }

    public float getBadgeOffsetYDp() {
        return clamp(getInt(KEY_BADGE_OFFSET_Y_DP10, (int)(DEF_BADGE_OFFSET * 10)), -100, 100) / 10f;
    }

    public float getBadgeTextSizeSp() {
        return clamp(getInt(KEY_BADGE_TEXT_SIZE_SP10, (int)(DEF_BADGE_TEXT_SP * 10)), 60, 180) / 10f;
    }

    public boolean isPercentEnabled() {
        return getInt(KEY_PERCENT_ENABLED, DEF_PERCENT_ENABLED ? 1 : 0) != 0;
    }

    public float getPercentTextSizeSp() {
        return clamp(getInt(KEY_PERCENT_SIZE_SP10, (int)(DEF_PERCENT_SP * 10)), 60, 200) / 10f;
    }

    public boolean isPercentBold() {
        return getInt(KEY_PERCENT_BOLD, DEF_PERCENT_BOLD ? 1 : 0) != 0;
    }

    public String getPercentPosition() {
        int idx = clamp(getInt(KEY_PERCENT_POSITION, DEF_PERCENT_POSITION), 0,
                POSITION_NAMES.length - 1);
        return POSITION_NAMES[idx];
    }

    public float getPercentOffsetXDp() {
        return clamp(getInt(KEY_PERCENT_OFFSET_X, 0), -200, 200) / 10f;
    }

    public float getPercentOffsetYDp() {
        return clamp(getInt(KEY_PERCENT_OFFSET_Y, 0), -200, 200) / 10f;
    }

    public boolean isFilenameEnabled() {
        return getInt(KEY_FILENAME_ENABLED, DEF_FILENAME_ENABLED ? 1 : 0) != 0;
    }

    public float getFilenameTextSizeSp() {
        return clamp(getInt(KEY_FILENAME_SIZE_SP10, (int)(DEF_FILENAME_SP * 10)), 50, 180) / 10f;
    }

    public boolean isFilenameBold() {
        return getInt(KEY_FILENAME_BOLD, DEF_FILENAME_BOLD ? 1 : 0) != 0;
    }

    public String getFilenamePosition() {
        int idx = clamp(getInt(KEY_FILENAME_POSITION, DEF_FILENAME_POSITION), 0,
                POSITION_NAMES.length - 1);
        return POSITION_NAMES[idx];
    }

    public float getFilenameOffsetXDp() {
        return clamp(getInt(KEY_FILENAME_OFFSET_X, 0), -200, 200) / 10f;
    }

    public float getFilenameOffsetYDp() {
        return clamp(getInt(KEY_FILENAME_OFFSET_Y, 0), -200, 200) / 10f;
    }

    public int getFilenameMaxChars() {
        return clamp(getInt(KEY_FILENAME_MAX_CHARS, DEF_FILENAME_MAX_CHARS), 5, 60);
    }

    public String getFilenameTruncateMode() {
        int idx = clamp(getInt(KEY_FILENAME_TRUNCATE, DEF_FILENAME_TRUNCATE), 0,
                TRUNCATE_MODE_NAMES.length - 1);
        return TRUNCATE_MODE_NAMES[idx];
    }

    public String getProgressEasing() {
        int idx = clamp(getInt(KEY_PROGRESS_EASING, DEF_EASING), 0,
                EASING_NAMES.length - 1);
        return EASING_NAMES[idx];
    }

    public boolean isChargingRingEnabled() {
        return getInt(KEY_CHARGING_RING_ENABLED, DEF_CHARGING_RING_ENABLED ? 1 : 0) != 0;
    }

    public boolean isChargingPulseEnabled() {
        return getInt(KEY_CHARGING_PULSE_ENABLED, DEF_CHARGING_PULSE_ENABLED ? 1 : 0) != 0;
    }

    public boolean isBatteryIndicatorEnabled() {
        return getInt(KEY_BATTERY_INDICATOR_ENABLED, DEF_BATTERY_INDICATOR_ENABLED ? 1 : 0) != 0;
    }

    public boolean isMusicRingEnabled() {
        return getInt(KEY_MUSIC_RING_ENABLED, DEF_MUSIC_RING_ENABLED ? 1 : 0) != 0;
    }

    public int getMusicColorMode() {
        return clamp(getInt(KEY_MUSIC_COLOR_MODE, DEF_MUSIC_COLOR_MODE),
                MUSIC_COLOR_MODE_ALBUM_ICON, MUSIC_COLOR_MODE_CUSTOM);
    }

    public int getMusicCustomColor() {
        return getInt(KEY_MUSIC_CUSTOM_COLOR, DEF_MUSIC_CUSTOM_COLOR);
    }

    public int getMusicOpacity() {
        return clamp(getInt(KEY_MUSIC_OPACITY, DEF_MUSIC_OPACITY), 0, 100);
    }

    public float getMusicStrokeWidthDp() {
        return clamp(getInt(KEY_MUSIC_STROKE_WIDTH_DP10, (int)(DEF_MUSIC_STROKE_DP * 10)), 5, 80) / 10f;
    }

    public boolean isMusicShowOnAod() {
        return getInt(KEY_MUSIC_SHOW_ON_AOD, DEF_MUSIC_SHOW_ON_AOD ? 1 : 0) != 0;
    }

    public boolean isMusicClockwise() {
        return getInt(KEY_MUSIC_CLOCKWISE, DEF_MUSIC_CLOCKWISE ? 1 : 0) != 0;
    }

    public int getDownloadPresentation() {
        return clamp(getInt(KEY_DOWNLOAD_PRESENTATION, DEF_DOWNLOAD_PRESENTATION),
                PRESENTATION_PRIMARY, PRESENTATION_DISABLED);
    }

    public int getMusicPresentation() {
        return clamp(getInt(KEY_MUSIC_PRESENTATION, DEF_MUSIC_PRESENTATION),
                PRESENTATION_PRIMARY, PRESENTATION_DISABLED);
    }

    public int getPrimaryPriority() {
        return clamp(getInt(KEY_PRIMARY_PRIORITY, DEF_PRIMARY_PRIORITY),
                PRIMARY_PRIORITY_DOWNLOAD, PRIMARY_PRIORITY_TIMER);
    }

    public float getMultiRingSpacingDp() {
        return clamp(getInt(KEY_MULTI_RING_SPACING_DP10,
                (int)(DEF_MULTI_RING_SPACING_DP * 10)), 10, 120) / 10f;
    }

    public boolean isMusicWaveEnabled() {
        return getInt(KEY_MUSIC_WAVE_ENABLED, DEF_MUSIC_WAVE_ENABLED ? 1 : 0) != 0;
    }

    public float getMusicWaveAmplitudeDp() {
        return clamp(getInt(KEY_MUSIC_WAVE_AMPLITUDE_DP10,
                (int)(DEF_MUSIC_WAVE_AMPLITUDE_DP * 10)), 5, 80) / 10f;
    }

    public int getMusicWaveDensity() {
        return clamp(getInt(KEY_MUSIC_WAVE_DENSITY, DEF_MUSIC_WAVE_DENSITY), 16, 96);
    }

    public int getMusicWaveSpeed() {
        return clamp(getInt(KEY_MUSIC_WAVE_SPEED, DEF_MUSIC_WAVE_SPEED), 25, 250);
    }

    public boolean isTimerEnabled() {
        return getInt(KEY_TIMER_ENABLED, DEF_TIMER_ENABLED ? 1 : 0) != 0;
    }

    public int getTimerPresentation() {
        return clamp(getInt(KEY_TIMER_PRESENTATION, DEF_TIMER_PRESENTATION),
                PRESENTATION_PRIMARY, PRESENTATION_DISABLED);
    }

    public int getTimerColorMode() {
        return clamp(getInt(KEY_TIMER_COLOR_MODE, DEF_TIMER_COLOR_MODE),
                RING_COLOR_MODE_ACCENT, RING_COLOR_MODE_CUSTOM);
    }

    public int getTimerCustomColor() {
        return getInt(KEY_TIMER_CUSTOM_COLOR, DEF_TIMER_CUSTOM_COLOR);
    }

    public int getTimerOpacity() {
        return clamp(getInt(KEY_TIMER_OPACITY, DEF_TIMER_OPACITY), 0, 100);
    }

    public float getTimerStrokeWidthDp() {
        return clamp(getInt(KEY_TIMER_STROKE_WIDTH_DP10,
                (int) (DEF_TIMER_STROKE_DP * 10)), 5, 80) / 10f;
    }

    public boolean isTimerClockwise() {
        return getInt(KEY_TIMER_CLOCKWISE, DEF_TIMER_CLOCKWISE ? 1 : 0) != 0;
    }

    public boolean isTimerFlameEnabled() {
        return getInt(KEY_TIMER_FLAME_ENABLED, DEF_TIMER_FLAME_ENABLED ? 1 : 0) != 0;
    }

    public int getTimerFlameColor() {
        return getInt(KEY_TIMER_FLAME_COLOR, DEF_TIMER_FLAME_COLOR);
    }

    public float getTimerFlameSizeDp() {
        return clamp(getInt(KEY_TIMER_FLAME_SIZE_DP10,
                (int) (DEF_TIMER_FLAME_SIZE_DP * 10)), 10, 100) / 10f;
    }

    public boolean isAuroraEnabled() {
        return getInt(KEY_AURORA_ENABLED, DEF_AURORA_ENABLED ? 1 : 0) != 0;
    }

    public boolean isAuroraCallsEnabled() {
        return getInt(KEY_AURORA_CALLS, DEF_AURORA_CALLS ? 1 : 0) != 0;
    }

    public boolean isAuroraMusicEnabled() {
        return getInt(KEY_AURORA_MUSIC, DEF_AURORA_MUSIC ? 1 : 0) != 0;
    }

    public boolean isAuroraRecordingEnabled() {
        return getInt(KEY_AURORA_RECORDING, DEF_AURORA_RECORDING ? 1 : 0) != 0;
    }

    public boolean isAuroraNotificationsEnabled() {
        return getInt(KEY_AURORA_NOTIFICATIONS, DEF_AURORA_NOTIFICATIONS ? 1 : 0) != 0;
    }

    public int getAuroraColorMode() {
        return clamp(getInt(KEY_AURORA_COLOR_MODE, DEF_AURORA_COLOR_MODE),
                AURORA_COLOR_MODE_SPECTRUM, AURORA_COLOR_MODE_CUSTOM);
    }

    public int getAuroraCustomColor() {
        return getInt(KEY_AURORA_CUSTOM_COLOR, DEF_AURORA_CUSTOM_COLOR);
    }

    public int getAuroraNotificationColorMode() {
        return clamp(getInt(KEY_AURORA_NOTIFICATION_COLOR_MODE,
                DEF_AURORA_NOTIFICATION_COLOR_MODE),
                AURORA_NOTIFICATION_COLOR_NOTIFICATION, AURORA_NOTIFICATION_COLOR_EFFECT);
    }

    public float getAuroraSpreadDp() {
        return clamp(getInt(KEY_AURORA_SPREAD_DP10,
                (int) (DEF_AURORA_SPREAD_DP * 10)), 20, 200) / 10f;
    }

    public int getAuroraOpacity() {
        return clamp(getInt(KEY_AURORA_OPACITY, DEF_AURORA_OPACITY), 10, 100);
    }

    public int getAuroraSpeed() {
        return clamp(getInt(KEY_AURORA_SPEED, DEF_AURORA_SPEED), 25, 250);
    }

    public int getAuroraNotificationDurationMs() {
        return clamp(getInt(KEY_AURORA_NOTIFICATION_DURATION_MS,
                DEF_AURORA_NOTIFICATION_DURATION_MS), 500, 8000);
    }

    public boolean isGlowEnabled() {
        return getInt(KEY_GLOW_ENABLED, DEF_GLOW_ENABLED ? 1 : 0) != 0;
    }

    public float getGlowRadiusDp() {
        return clamp(getInt(KEY_GLOW_RADIUS_DP10, (int)(DEF_GLOW_RADIUS_DP * 10)), 10, 150) / 10f;
    }

    public void setBatteryIndicatorEnabled(boolean value) {
        putInt(KEY_BATTERY_INDICATOR_ENABLED, value ? 1 : 0);
    }

    public void setEnabled(boolean value) {
        putInt(KEY_ENABLED, value ? 1 : 0);
    }

    public void setRingColorMode(int mode) {
        putInt(KEY_RING_COLOR_MODE, clamp(mode, RING_COLOR_MODE_ACCENT, RING_COLOR_MODE_CUSTOM));
    }

    public void setRingColor(int argb) {
        putInt(KEY_RING_COLOR, argb);
    }

    public void setOpacity(int opacity) {
        putInt(KEY_OPACITY, clamp(opacity, 0, 100));
    }

    public void setClockwise(boolean cw) {
        putInt(KEY_CLOCKWISE, cw ? 1 : 0);
    }

    public void setFinishStyle(int styleIndex) {
        putInt(KEY_FINISH_STYLE, clamp(styleIndex, 0, FINISH_STYLE_NAMES.length - 1));
    }

    public void setMusicRingEnabled(boolean v) {
        putInt(KEY_MUSIC_RING_ENABLED, v ? 1 : 0);
    }

    public void setMusicColorMode(int mode) {
        putInt(KEY_MUSIC_COLOR_MODE,
               clamp(mode, MUSIC_COLOR_MODE_ALBUM_ICON, MUSIC_COLOR_MODE_CUSTOM));
    }

    public void setMusicCustomColor(int argb) {
        putInt(KEY_MUSIC_CUSTOM_COLOR, argb);
    }

    public void setMusicOpacity(int pct) {
        putInt(KEY_MUSIC_OPACITY, clamp(pct, 0, 100));
    }

    public void setMusicClockwise(boolean cw) {
        putInt(KEY_MUSIC_CLOCKWISE, cw ? 1 : 0);
    }

    private int getInt(String key, int def) {
        return Settings.Secure.getIntForUser(mCr, key, def, mUserId);
    }

    private void putInt(String key, int value) {
        Settings.Secure.putIntForUser(mCr, key, value, mUserId);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
