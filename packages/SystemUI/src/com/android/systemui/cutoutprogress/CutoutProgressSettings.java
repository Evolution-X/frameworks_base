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

    private static final boolean DEF_ENABLED = false;
    private static final int DEF_RING_COLOR = 0xFF2196F3;
    private static final int DEF_ERROR_COLOR = 0xFFF44336;
    private static final int DEF_FINISH_FLASH_COLOR = Color.WHITE;
    private static final float DEF_STROKE_DP = 2.0f;
    private static final float DEF_RING_GAP = 1.155f;
    private static final int DEF_OPACITY = 90;
    private static final boolean DEF_CLOCKWISE = true;
    private static final int DEF_FINISH_STYLE = 0;
    private static final int DEF_FINISH_HOLD_MS = 500;
    private static final int DEF_FINISH_EXIT_MS = 500;
    private static final boolean DEF_FINISH_USE_FLASH = true;
    private static final boolean DEF_COMPLETION_PULSE = true;
    private static final boolean DEF_PATH_MODE = false;
    private static final float DEF_RING_SCALE = 1.0f;
    private static final float DEF_RING_OFFSET = 0.0f;
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
    private ContentObserver mObserver;
    private Runnable mCallback;

    public CutoutProgressSettings(ContentResolver cr, Handler handler) {
        mCr = cr;
        mHandler = handler;
    }

    public void observe(Runnable onChange) {
        mCallback = onChange;
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (mCallback != null) mCallback.run();
            }
        };
        Uri base = Settings.Secure.getUriFor("cutout_progress_enabled").buildUpon()
                .path("").build();
        mCr.registerContentObserver(
                Settings.Secure.CONTENT_URI, true, mObserver);
    }

    public void stopObserving() {
        if (mObserver != null) {
            mCr.unregisterContentObserver(mObserver);
            mObserver = null;
        }
    }

    public boolean isEnabled() {
        return getInt(KEY_ENABLED, DEF_ENABLED ? 1 : 0) != 0;
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
        return getInt(KEY_STROKE_WIDTH_DP10, (int)(DEF_STROKE_DP * 10)) / 10f;
    }

    public float getRingGap() {
        return getInt(KEY_RING_GAP_X1000, (int)(DEF_RING_GAP * 1000)) / 1000f;
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
        return getInt(KEY_FINISH_HOLD_MS, DEF_FINISH_HOLD_MS);
    }

    public int getFinishExitMs() {
        return getInt(KEY_FINISH_EXIT_MS, DEF_FINISH_EXIT_MS);
    }

    public boolean isFinishUseFlash() {
        return getInt(KEY_FINISH_USE_FLASH, DEF_FINISH_USE_FLASH ? 1 : 0) != 0;
    }

    public boolean isCompletionPulse() {
        return getInt(KEY_COMPLETION_PULSE, DEF_COMPLETION_PULSE ? 1 : 0) != 0;
    }

    public boolean isPathMode() {
        return getInt(KEY_PATH_MODE, DEF_PATH_MODE ? 1 : 0) != 0;
    }

    public float getRingScaleX() {
        return getInt(KEY_RING_SCALE_X_X1000, (int)(DEF_RING_SCALE * 1000)) / 1000f;
    }

    public float getRingScaleY() {
        return getInt(KEY_RING_SCALE_Y_X1000, (int)(DEF_RING_SCALE * 1000)) / 1000f;
    }

    public float getRingOffsetXDp() {
        return getInt(KEY_RING_OFFSET_X_DP10, (int)(DEF_RING_OFFSET * 10)) / 10f;
    }

    public float getRingOffsetYDp() {
        return getInt(KEY_RING_OFFSET_Y_DP10, (int)(DEF_RING_OFFSET * 10)) / 10f;
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
        return getInt(KEY_MIN_VIS_MS, DEF_MIN_VIS_MS);
    }

    public boolean isShowCountBadge() {
        return getInt(KEY_SHOW_COUNT_BADGE, DEF_SHOW_COUNT_BADGE ? 1 : 0) != 0;
    }

    public float getBadgeOffsetXDp() {
        return getInt(KEY_BADGE_OFFSET_X_DP10, (int)(DEF_BADGE_OFFSET * 10)) / 10f;
    }

    public float getBadgeOffsetYDp() {
        return getInt(KEY_BADGE_OFFSET_Y_DP10, (int)(DEF_BADGE_OFFSET * 10)) / 10f;
    }

    public float getBadgeTextSizeSp() {
        return getInt(KEY_BADGE_TEXT_SIZE_SP10, (int)(DEF_BADGE_TEXT_SP * 10)) / 10f;
    }

    public boolean isPercentEnabled() {
        return getInt(KEY_PERCENT_ENABLED, DEF_PERCENT_ENABLED ? 1 : 0) != 0;
    }

    public float getPercentTextSizeSp() {
        return getInt(KEY_PERCENT_SIZE_SP10, (int)(DEF_PERCENT_SP * 10)) / 10f;
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
        return getInt(KEY_PERCENT_OFFSET_X, 0) / 10f;
    }

    public float getPercentOffsetYDp() {
        return getInt(KEY_PERCENT_OFFSET_Y, 0) / 10f;
    }

    public boolean isFilenameEnabled() {
        return getInt(KEY_FILENAME_ENABLED, DEF_FILENAME_ENABLED ? 1 : 0) != 0;
    }

    public float getFilenameTextSizeSp() {
        return getInt(KEY_FILENAME_SIZE_SP10, (int)(DEF_FILENAME_SP * 10)) / 10f;
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
        return getInt(KEY_FILENAME_OFFSET_X, 0) / 10f;
    }

    public float getFilenameOffsetYDp() {
        return getInt(KEY_FILENAME_OFFSET_Y, 0) / 10f;
    }

    public int getFilenameMaxChars() {
        return getInt(KEY_FILENAME_MAX_CHARS, DEF_FILENAME_MAX_CHARS);
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

    public void setEnabled(boolean value) {
        putInt(KEY_ENABLED, value ? 1 : 0);
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

    private int getInt(String key, int def) {
        return Settings.Secure.getInt(mCr, key, def);
    }

    private void putInt(String key, int value) {
        Settings.Secure.putInt(mCr, key, value);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
