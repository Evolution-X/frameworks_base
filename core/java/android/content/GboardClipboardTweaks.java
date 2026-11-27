/*
 * Copyright (C) 2026 The Evolution X Project
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

package android.content;

import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.util.Locale;

/**
 * Runtime tweaks for Gboard's clipboard history.
 *
 * <p>Gboard enforces its own clipboard limits inside
 * {@code com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider}: the list
 * query carries a {@code timestamp >= ?} retention cutoff (about one hour) and a
 * {@code timestamp DESC limit 5} sort order. No AOSP setting, resource overlay or system property
 * changes them, so the only way to widen them without patching Gboard or hooking its code is to
 * rewrite the provider arguments as they pass through {@code ContentProvider.Transport}, which
 * runs inside Gboard's own process.
 *
 * <p>Everything here is a no-op unless the provider belongs to Gboard and the
 * {@link #SETTING_ENABLED} secure setting is on.
 *
 * @hide
 */
public final class GboardClipboardTweaks {
    private static final String TAG = "GboardClipboardTweaks";

    private static final String GBOARD_PACKAGE = "com.google.android.inputmethod.latin";

    /** Secure setting written by the Evolver switch. */
    public static final String SETTING_ENABLED = "gboard_clipboard_unlimited";

    private static final int MAX_CLIPS = 1000;

    private static final String SQL_LIMIT = "limit ";

    private GboardClipboardTweaks() {
    }

    /**
     * Rewrites Gboard's clipboard list query so the retention cutoff stops hiding old clips and
     * the row limit is raised to {@link #MAX_CLIPS}.
     *
     * @return the original bundle when no rewrite is needed
     */
    public static Bundle maybeRewriteQuery(Context context, Bundle queryArgs) {
        if (queryArgs == null || !isGboard(context)) {
            return queryArgs;
        }
        final String sortOrder = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
        if (sortOrder == null || !sortOrder.toLowerCase(Locale.ROOT).contains(SQL_LIMIT)) {
            return queryArgs;
        }
        if (!isEnabled(context)) {
            return queryArgs;
        }
        try {
            final Bundle rewritten = new Bundle(queryArgs);
            final String selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
            final String[] selectionArgs = queryArgs.getStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            final String[] withoutCutoff = dropRetentionCutoff(selection, selectionArgs);
            if (withoutCutoff != null) {
                rewritten.putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, withoutCutoff);
            }
            rewritten.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, raiseLimit(sortOrder));
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slog.d(TAG, "rewrote clipboard query: " + selection + " / " + sortOrder);
            }
            return rewritten;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to rewrite the clipboard query", e);
            return queryArgs;
        }
    }

    /**
     * Keeps Gboard from pruning clips while the toggle is on. Only timestamp based cleanup is
     * blocked; explicit user actions use a null selection or id based selections and still work.
     */
    public static boolean shouldBlockCleanupDelete(Context context, Bundle extras) {
        if (extras == null || !isGboard(context) || !isEnabled(context)) {
            return false;
        }
        final String selection = extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
        if (selection == null) {
            return false;
        }
        final String lower = selection.toLowerCase(Locale.ROOT);
        return lower.contains("timestamp") && lower.contains("<");
    }

    private static boolean isGboard(Context context) {
        return context != null && GBOARD_PACKAGE.equals(context.getPackageName());
    }

    private static boolean isEnabled(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), SETTING_ENABLED, 0) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Replaces the bound retention timestamp with {@code 0} so every stored clip matches.
     */
    private static String[] dropRetentionCutoff(String selection, String[] selectionArgs) {
        if (selection == null || selectionArgs == null) {
            return null;
        }
        final int cutoff = selection.indexOf("timestamp >=");
        if (cutoff < 0) {
            return null;
        }
        int index = 0;
        for (int i = 0; i < cutoff; i++) {
            if (selection.charAt(i) == '?') {
                index++;
            }
        }
        if (index >= selectionArgs.length) {
            return null;
        }
        final String[] rewritten = selectionArgs.clone();
        rewritten[index] = "0";
        return rewritten;
    }

    /**
     * Replaces the row count of a {@code ... limit N} sort order with {@link #MAX_CLIPS}.
     */
    private static String raiseLimit(String sortOrder) {
        final int limit = sortOrder.toLowerCase(Locale.ROOT).lastIndexOf(SQL_LIMIT);
        if (limit < 0) {
            return sortOrder;
        }
        final int valueStart = limit + SQL_LIMIT.length();
        int valueEnd = valueStart;
        while (valueEnd < sortOrder.length() && Character.isDigit(sortOrder.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return sortOrder;
        }
        try {
            if (Integer.parseInt(sortOrder.substring(valueStart, valueEnd)) >= MAX_CLIPS) {
                return sortOrder;
            }
        } catch (NumberFormatException e) {
            return sortOrder;
        }
        return sortOrder.substring(0, valueStart) + MAX_CLIPS + sortOrder.substring(valueEnd);
    }
}
