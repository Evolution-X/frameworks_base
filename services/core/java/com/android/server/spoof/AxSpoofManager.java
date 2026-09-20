/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.server.spoof;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.trickystore.TrickyStoreService;
import android.util.Base64;
import android.util.Log;

import com.android.internal.util.evolution.PixelDeviceRepository;
import com.android.server.NtServiceInjector;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.json.JSONObject;

public class AxSpoofManager implements IAxSpoofManager {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    private static final long REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    // A fresh install has no keybox or PIF config yet, so don't make it wait a
    // full hour for the first pass. Retry a few times in case the network isn't
    // up yet, then fall back to the hourly schedule.
    private static final long BOOTSTRAP_INITIAL_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long BOOTSTRAP_RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int BOOTSTRAP_MAX_RETRIES = 6;
    // Refreshing restarts GMS, so hold the scheduled pass until the setup wizard
    // is done rather than restart it under someone signing in. Re-check every
    // minute, and stop waiting after half an hour so a device with no setup
    // wizard still refreshes.
    private static final long SETUP_RECHECK_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long SETUP_WAIT_MAX_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long REFRESH_STEP_DEADLINE_MS = TimeUnit.SECONDS.toMillis(20);
    // After the PIF config is cleared (reset to defaults, or deleted), give the
    // rest of a multi-key reset a moment to land, then refresh straight away
    // rather than leaving the device without a fingerprint until the next tick.
    private static final long REFRESH_AFTER_CLEAR_DELAY_MS = TimeUnit.SECONDS.toMillis(5);

    private static final String PIF_ENABLED_KEY = "spoof_pif_enabled";
    private static final String TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled";
    private static final String LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch";
    private static final long AUTO_FETCH_COOLDOWN_MS = TimeUnit.DAYS.toMillis(1);
    private static final long REFETCH_WINDOW_DAYS = 15L;
    private static final Pattern PLAIN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String KEYBOX_SOURCE_KEY = "spoof_trickystore_keybox_source";
    private static final String KEYBOX_SOURCE_USER = "user";
    private static final String OFFICIAL_KEYBOX_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/keybox.xml";

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String[] GMS_FAMILY = {
            VENDING_PACKAGE,
            "com.google.android.gms.unstable",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.rkpdapp",
            "com.google.android.gsf",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
            "com.google.android.googlequicksearchbox",
    };

    // Staging props for bionic's custom_rom_hide_get_prop_override() to mirror
    // into ro.product.* reads — see libc/bionic/custom_rom_hide.cpp. ro.* props
    // are immutable after boot, so we can't SystemProperties.set() them directly;
    // this mutable staging prop is the handoff point instead. persist.sys.* is
    // labelled system_prop, which gmscore_app can read but ordinary app domains
    // cannot, so in practice the mirror reaches GMS/DroidGuard and leaves other
    // apps on the real values.
    private static final String PIF_PRODUCT_PROP_PREFIX = "persist.sys.pif.product.";
    private static final String[] PIF_PRODUCT_PROP_NAMES = {
            "manufacturer", "brand", "model", "device", "name",
    };

    private final ExecutorService mNetworkExecutor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver mPackageAddedReceiver;
    private int mBootstrapRetries;
    private long mStartElapsedMs;
    private final Runnable mRefreshAfterClear = this::refreshNow;

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private volatile boolean mReady = false;

    public AxSpoofManager() {
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        if (mContext == null) {
            Log.w(TAG, "Context unavailable, deferring init");
            return;
        }
        mResolver = mContext.getContentResolver();
        mStartElapsedMs = SystemClock.elapsedRealtime();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                refreshKey(last);
                if (Settings.Secure.SPOOF_PIF_CONFIG.equals(last)) {
                    onPifConfigChanged();
                }
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        seedDefaultTargetsIfUnset();

        mPackageAddedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // An update also broadcasts PACKAGE_ADDED; only new installs get targeted,
                // so an app the user removed from the list isn't re-added on every update.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                Uri data = intent.getData();
                String pkg = data != null ? data.getSchemeSpecificPart() : null;
                if (pkg != null) {
                    mHandler.post(() -> onPackageAdded(pkg));
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mPackageAddedReceiver, UserHandle.ALL, filter, null, mHandler);

        mHandler.postDelayed(this::performScheduledRefresh,
                isBootstrapPending() ? BOOTSTRAP_INITIAL_DELAY_MS : REFRESH_INTERVAL_MS);

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_SYSTEM);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    /**
     * Called after SPOOF_PIF_CONFIG changes. If the config was cleared (reset
     * to defaults, or the user deleted it), drop the staged product props so
     * bionic stops serving the old device identity for ro.product reads, and
     * stop the GMS family so it stops running on the old fingerprint, then
     * refresh shortly after so the device isn't left without a fingerprint until
     * the next hourly tick. A non-empty config is left to whichever refresh path
     * wrote it.
     */
    private void onPifConfigChanged() {
        final String config = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        if (config != null && !config.trim().isEmpty()) return;

        clearProductPropOverrides();
        killGmsFamily();
        Log.i(TAG, "PIF config cleared, dropped staged product props");

        mHandler.removeCallbacks(mRefreshAfterClear);
        mHandler.postDelayed(mRefreshAfterClear, REFRESH_AFTER_CLEAR_DELAY_MS);
    }

    /**
     * Empties the staged product props. A property can't be deleted once set,
     * but bionic's custom_rom_hide_get_prop_override() only applies a staging
     * prop when it is non-empty, so an empty value restores the real identity.
     */
    private void clearProductPropOverrides() {
        try {
            for (String name : PIF_PRODUCT_PROP_NAMES) {
                android.os.SystemProperties.set(PIF_PRODUCT_PROP_PREFIX + name, "");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear staged product overrides", e);
        }
    }

    /**
     * Writes the default target list into a target setting that was never set,
     * so a fresh install attests GMS and friends without anyone opening Evolver
     * (TrickyStoreService ignores any package that isn't listed). Only a null
     * value is seeded: an empty string means the user cleared the list on
     * purpose and is left alone.
     */
    private void seedDefaultTargetsIfUnset() {
        String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);
        if (current != null) return;

        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                TrickyStoreService.DEFAULT_TARGET_LIST, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Seeded default TrickyStore targets");
    }

    /**
     * Adds a newly-installed package to the TrickyStore target list in AUTO
     * mode, unless it's already present or is a known Xposed/LSPosed manager
     * (attesting through a hooked process breaks STRONG). Mirrors AlwaysStrong's
     * inotify-driven auto-target, sourced from a live PACKAGE_ADDED broadcast
     * instead of a native watcher since this already runs in system_server.
     *
     * Does nothing while the list is empty: Evolver seeds the default targets
     * (GMS and friends) only when the list is empty, so writing a lone package
     * here would leave those defaults unseeded. A JSON list can't be appended
     * to as text either.
     */
    private void onPackageAdded(String pkg) {
        if (mResolver == null || TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) return;

        String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);
        String existing = current != null ? current : "";
        String trimmed = existing.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("{")) return;

        for (String raw : existing.split("\n")) {
            String line = raw.trim();
            String bare = line.endsWith("!") || line.endsWith("?") || line.endsWith("-")
                    ? line.substring(0, line.length() - 1).trim() : line;
            if (pkg.equals(bare)) return; // already targeted, in whatever mode
        }

        String updated = existing.endsWith("\n") ? existing + pkg : existing + "\n" + pkg;
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, updated, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Auto-targeted newly installed package: " + pkg);
    }

    /**
     * Native equivalent of AlwaysStrong's hourly background service: refreshes
     * the Pixel fingerprint and the official keybox without requiring the
     * Settings app to be opened. Each network step runs under a hard deadline
     * so a stalled connection can't stop future ticks from firing. Runs hourly,
     * or sooner while a fresh install is still missing its keybox or PIF config,
     * and waits for the setup wizard to finish first.
     */
    private void performScheduledRefresh() {
        if (isSetupPending()) {
            mHandler.postDelayed(this::performScheduledRefresh, SETUP_RECHECK_MS);
            return;
        }
        refreshNow();
        long delay = REFRESH_INTERVAL_MS;
        if (isBootstrapPending() && mBootstrapRetries < BOOTSTRAP_MAX_RETRIES) {
            mBootstrapRetries++;
            delay = BOOTSTRAP_RETRY_MS;
        }
        mHandler.postDelayed(this::performScheduledRefresh, delay);
    }

    /**
     * True while the setup wizard is still running, for up to SETUP_WAIT_MAX_MS
     * after this service started.
     */
    private boolean isSetupPending() {
        if (SystemClock.elapsedRealtime() - mStartElapsedMs >= SETUP_WAIT_MAX_MS) {
            return false;
        }
        boolean provisioned = Settings.Global.getInt(
                mResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        boolean setupComplete = Settings.Secure.getIntForUser(
                mResolver, Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
        return !provisioned || !setupComplete;
    }

    /**
     * True while an enabled feature still has nothing for the refresh to keep
     * current. Reads Settings directly rather than the cache, which is updated
     * from this same handler and would still be stale right after a refresh.
     */
    private boolean isBootstrapPending() {
        if (mResolver == null) return false;

        boolean pifMissing = isEmpty(Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.SPOOF_PIF_CONFIG, UserHandle.USER_SYSTEM))
                && Settings.System.getIntForUser(
                        mResolver, PIF_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;

        String keyboxSource = Settings.Secure.getStringForUser(
                mResolver, KEYBOX_SOURCE_KEY, UserHandle.USER_SYSTEM);
        boolean keyboxMissing = isEmpty(Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, UserHandle.USER_SYSTEM))
                && !KEYBOX_SOURCE_USER.equals(keyboxSource)
                && Settings.System.getIntForUser(
                        mResolver, TRICKYSTORE_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;

        return pifMissing || keyboxMissing;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** One fingerprint + keybox pass, without touching the hourly schedule. */
    private void refreshNow() {
        try {
            refreshPixelFingerprintIfStale();
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint refresh failed", e);
        }
        try {
            refreshKeyboxIfStale();
        } catch (Exception e) {
            Log.e(TAG, "Keybox refresh failed", e);
        }
    }

    /**
     * Runs [task] on a background executor with a hard wall-clock deadline, so
     * a stalled DNS/connect (not always caught by URLConnection's own timeouts)
     * can't wedge the shared HandlerThread that drives PACKAGE_ADDED handling
     * and the hourly reschedule.
     */
    private <T> T runBounded(Callable<T> task) throws Exception {
        Future<T> future = mNetworkExecutor.submit(task);
        try {
            return future.get(REFRESH_STEP_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void refreshPixelFingerprintIfStale() throws Exception {
        if (mResolver == null) return;
        if (Settings.System.getIntForUser(
                mResolver, PIF_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) == 0) {
            return;
        }

        long lastFetch = Settings.Secure.getLongForUser(
                mResolver, LAST_AUTO_FETCH_KEY, 0L, UserHandle.USER_SYSTEM);
        if (lastFetch > 0L
                && System.currentTimeMillis() - lastFetch < AUTO_FETCH_COOLDOWN_MS) {
            return;
        }

        String content = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        JSONObject existing = (content != null && !content.isEmpty())
                ? new JSONObject(content) : new JSONObject();
        if (existing.optBoolean("manually_imported", false)) return;

        String canaryMonth = existing.optString("_canary_month", "");
        String releaseDate = existing.has("_canary_release_date")
                ? existing.optString("_canary_release_date") : null;
        Long daysLeft = canaryMonth.isEmpty() ? null
                : PixelDeviceRepository.getDaysUntilExpiry(canaryMonth, releaseDate);
        if (daysLeft != null && daysLeft > REFETCH_WINDOW_DAYS) return;

        PixelDeviceRepository.PixelProfile matched = runBounded(() -> {
            List<PixelDeviceRepository.PixelProfile> profiles =
                    PixelDeviceRepository.getProfiles(mContext, true);
            String defaultCodename = PixelDeviceRepository.getDefaultPhoneCodename(profiles);
            return PixelDeviceRepository.getProfileByCodename(mContext, defaultCodename, false);
        });
        if (matched == null || !PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) {
            return;
        }

        JSONObject toSave = new JSONObject();
        toSave.put("MANUFACTURER", capitalize(matched.brand));
        toSave.put("BRAND", matched.brand);
        toSave.put("MODEL", matched.model);
        toSave.put("PRODUCT", matched.product);
        toSave.put("DEVICE", matched.device);
        toSave.put("FINGERPRINT", matched.fingerprint);
        toSave.put("SECURITY_PATCH", matched.securityPatch);
        toSave.put("DEVICE_INITIAL_SDK_INT", "32");
        toSave.put("manually_imported", false);
        // Same canary metadata Evolver writes, so the refetch window above can
        // work out how long this fingerprint has left instead of always seeing
        // "unknown" and refetching daily.
        if (matched.isCanary) {
            String newCanaryMonth = matched.securityPatch != null
                    && matched.securityPatch.length() >= 7
                    ? matched.securityPatch.substring(0, 7) : null;
            if (newCanaryMonth != null) toSave.put("_canary_month", newCanaryMonth);
            if (matched.releaseDate != null) {
                toSave.put("_canary_release_date", matched.releaseDate);
            }
        }

        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_PIF_CONFIG, toSave.toString(2),
                UserHandle.USER_SYSTEM);
        Settings.Secure.putLongForUser(
                mResolver, LAST_AUTO_FETCH_KEY, System.currentTimeMillis(), UserHandle.USER_SYSTEM);
        updatePatchIfSimple(matched.securityPatch);

        writeProductPropOverrides(matched);
        killGmsFamily();

        Log.i(TAG, "Auto-refreshed Pixel fingerprint: " + matched.model);
    }

    /**
     * Fetches the official keybox mirror and writes it to Settings.Secure if
     * different from what's cached. Deliberately does none of its own cert
     * parsing or revocation checking — TrickyStoreService.refreshKeyBox()
     * already re-validates and re-checks revocation on every read of
     * SPOOF_TRICKYSTORE_KEYBOX, so this only needs to fetch and dedupe.
     */
    private void refreshKeyboxIfStale() throws Exception {
        if (mResolver == null) return;
        if (Settings.System.getIntForUser(
                mResolver, TRICKYSTORE_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) == 0) {
            return;
        }

        String source = Settings.Secure.getStringForUser(
                mResolver, KEYBOX_SOURCE_KEY, UserHandle.USER_SYSTEM);
        if (KEYBOX_SOURCE_USER.equals(source)) {
            return; // user manages their own keybox — never overwrite it
        }

        String xml = runBounded(() -> {
            HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_KEYBOX_URL).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        });
        if (xml == null || xml.trim().isEmpty()) return;
        if (!looksLikeKeybox(xml)) {
            // A captive portal or error page can come back as a 200; never let
            // that replace a working keybox.
            Log.w(TAG, "Keybox mirror returned something that is not a keybox, keeping the current one");
            return;
        }

        // The stored value is Base64 (that's how Evolver writes it) while the
        // download is raw XML, so compare decoded XML with decoded XML.
        String currentXml = decodeKeyboxXml(getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX));
        if (currentXml != null && sha256(currentXml).equals(sha256(xml))) {
            return; // unchanged
        }

        String encoded = Base64.encodeToString(xml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, encoded, UserHandle.USER_SYSTEM);

        killGmsFamily();
        Log.i(TAG, "Auto-refreshed official keybox");
    }

    /** Keybox XML from a stored value that is either raw XML or Base64 of it; null if neither. */
    private static String decodeKeyboxXml(String payload) {
        if (payload == null) return null;
        String trimmed = payload.trim();
        if (trimmed.startsWith("<")) return trimmed;
        try {
            String xml = new String(Base64.decode(trimmed, Base64.DEFAULT),
                    StandardCharsets.UTF_8).trim();
            return xml.startsWith("<") ? xml : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean looksLikeKeybox(String xml) {
        String head = xml.substring(0, Math.min(xml.length(), 4096));
        return xml.trim().startsWith("<") && head.contains("Keybox");
    }

    /**
     * Keeps the TrickyStore attestation patch level in step with the fingerprint
     * that was just saved. With no patch set, attestation reports the device's
     * real patch while PIF spoofs the fingerprint's, and the two disagree. Same
     * rule as Evolver's updatePatchDateIfSimple: only touch an empty value or a
     * plain YYYY-MM-DD one, never a per-package block the user wrote.
     */
    private void updatePatchIfSimple(String patch) {
        if (patch == null || !PLAIN_DATE.matcher(patch).matches()) return;
        String existing = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, UserHandle.USER_SYSTEM);
        String trimmed = existing != null ? existing.trim() : "";
        if (!trimmed.isEmpty() && !PLAIN_DATE.matcher(trimmed).matches()) return;
        if (patch.equals(trimmed)) return;
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, patch, UserHandle.USER_SYSTEM);
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s; // fall back to raw comparison, still correct, just heavier
        }
    }

    private void killGmsFamily() {
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            for (String pkg : GMS_FAMILY) {
                am.forceStopPackage(pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart GMS family after refresh", e);
        }
    }

    /**
     * Stages the spoofed product-identity fields as mutable persist props for
     * bionic's custom_rom_hide_get_prop_override() to serve in place of the
     * real ro.product.* values — closes the gap where GMS/DroidGuard reading via
     * getprop (rather than the Build class) would see the real device.
     */
    private void writeProductPropOverrides(PixelDeviceRepository.PixelProfile matched) {
        try {
            android.os.SystemProperties.set(
                    PIF_PRODUCT_PROP_PREFIX + "manufacturer", capitalize(matched.brand));
            android.os.SystemProperties.set(
                    PIF_PRODUCT_PROP_PREFIX + "brand", matched.brand);
            android.os.SystemProperties.set(
                    PIF_PRODUCT_PROP_PREFIX + "model", matched.model);
            android.os.SystemProperties.set(
                    PIF_PRODUCT_PROP_PREFIX + "device", matched.device);
            android.os.SystemProperties.set(
                    PIF_PRODUCT_PROP_PREFIX + "name", matched.product);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stage ro.product.* overrides", e);
        }
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty()) ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    @Override
    public String getPifConfig() {
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    @Override
    public String getGamePropsConfig() {
        return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
    }

    @Override
    public String getTrickyStoreTarget() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    @Override
    public String getTrickyStoreKeyBox() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    @Override
    public String getTrickyStorePatch() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }
}
