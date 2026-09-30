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
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.pif.PlayIntegritySpoofService;
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
    private static final long REFRESH_STEP_DEADLINE_MS = TimeUnit.SECONDS.toMillis(20);
    // After the PIF config is cleared (reset to defaults, or deleted), give the
    // rest of a multi-key reset a moment to land, then refresh straight away
    // rather than leaving the device without a fingerprint until the next tick.
    private static final long REFRESH_AFTER_CLEAR_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    // Spoofing stays off until the user opens Settings for the first time after
    // setup. The flag is persisted, so later boots start spoofing straight away;
    // a clean flash wipes /data and the flag with it.
    private static final String ACTIVATED_KEY = "spoof_activated";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

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

    // Staging props for bionic's custom_rom_hide_get_prop_override(), which serves
    // them in place of the matching read-only props (ro.product.*, the *.build.id,
    // *.security_patch and *api_level keys) — see libc/bionic/custom_rom_hide.cpp.
    // ro.* props are immutable after boot, so we can't SystemProperties.set() them
    // directly; these mutable props are the handoff point instead. bionic only
    // applies them inside the DroidGuard process (com.google.android.gms.unstable),
    // so every other process keeps seeing the real values.
    private static final String PIF_PRODUCT_PROP_PREFIX = "persist.sys.pif.product.";
    private static final String[] PIF_PRODUCT_PROP_NAMES = {
            "manufacturer", "brand", "model", "device", "name",
    };
    private static final String PIF_BUILD_ID_PROP = "persist.sys.pif.build.id";
    private static final String PIF_SECURITY_PATCH_PROP = "persist.sys.pif.security_patch";
    private static final String PIF_API_LEVEL_PROP = "persist.sys.pif.api_level";
    private static final String PIF_BUILD_TYPE_PROP = "persist.sys.pif.build.type";
    private static final String PIF_BUILD_TAGS_PROP = "persist.sys.pif.build.tags";
    private static final String PIF_BUILD_DESCRIPTION_PROP = "persist.sys.pif.build.description";
    private static final String PIF_BUILD_FLAVOR_PROP = "persist.sys.pif.build.flavor";
    // Config entries the fixed props above don't cover. Slot i holds "name=value", or
    // "*suffix=value" for a leading-* wildcard; the count says how many slots are live.
    // bionic's custom_rom_hide.cpp reads these with the same names and limits.
    private static final String PIF_CUSTOM_COUNT_PROP = "persist.sys.pif.x.count";
    private static final String PIF_CUSTOM_SLOT_PREFIX = "persist.sys.pif.x.";
    private static final int PIF_CUSTOM_SLOT_MAX = 32;
    private static final int PROP_VALUE_MAX_CHARS = 91;

    // DEVICE_INITIAL_SDK_INT written into the PIF config by the auto-refresh. The
    // api_level staging prop is read back from the config, so they can't drift.
    private static final String PIF_DEVICE_INITIAL_SDK = "32";

    // Keybox and fingerprint work get their own executor: the fingerprint crawl
    // is many sequential requests, and a stuck one (cancel() can't interrupt
    // blocking URLConnection IO) must not hold up the keybox fetch behind it.
    private final ExecutorService mNetworkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mFingerprintExecutor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver mPackageAddedReceiver;
    private int mBootstrapRetries;
    // Nothing spoof-related runs until the user first opens Settings (after setup):
    // the config getters below return null, the refresh never starts and GMS is
    // left alone. Set once by enableSpoofing() and persisted in ACTIVATED_KEY.
    private volatile boolean mActivated;
    private TaskStackListener mTaskListener;
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

        mActivated = Settings.Secure.getIntForUser(
                mResolver, ACTIVATED_KEY, 0, UserHandle.USER_SYSTEM) != 0;
        if (mActivated) {
            seedDefaultTargetsIfUnset();
            stagePifPropOverrides(getCached(Settings.Secure.SPOOF_PIF_CONFIG));
        } else {
            Log.i(TAG, "Spoofing off until Settings is opened for the first time");
            registerSettingsWatcher();
        }

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

        if (mActivated) scheduleFirstRefresh();

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    private void scheduleFirstRefresh() {
        mHandler.postDelayed(this::performScheduledRefresh,
                isBootstrapPending() ? BOOTSTRAP_INITIAL_DELAY_MS : REFRESH_INTERVAL_MS);
    }

    /**
     * Watches for the first time the user brings Settings to the front. Only
     * counts once setup is finished: Setup Wizard opens Settings screens (lock
     * screen, fingerprint) and must not trigger the GMS restart.
     */
    private void registerSettingsWatcher() {
        mTaskListener = new TaskStackListener() {
            @Override
            public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                if (mActivated || taskInfo == null) return;
                final String pkg = taskInfo.topActivity != null
                        ? taskInfo.topActivity.getPackageName()
                        : (taskInfo.baseActivity != null
                                ? taskInfo.baseActivity.getPackageName() : null);
                if (!SETTINGS_PACKAGE.equals(pkg)) return;
                mHandler.post(() -> {
                    if (!mActivated && isDeviceProvisioned()) enableSpoofing();
                });
            }
        };
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to watch for Settings launch, spoofing stays off", e);
            mTaskListener = null;
        }
    }

    private void unregisterSettingsWatcher() {
        if (mTaskListener == null) return;
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister Settings watcher", e);
        }
        mTaskListener = null;
    }

    /**
     * Flips spoofing on, reloads the configs system_server's own PIF and TrickyStore
     * instances cached empty while off, starts the refresh schedule and restarts the
     * GMS family, whose processes were started without a config.
     */
    private void enableSpoofing() {
        if (mActivated) return;
        mActivated = true;
        Settings.Secure.putIntForUser(mResolver, ACTIVATED_KEY, 1, UserHandle.USER_SYSTEM);
        unregisterSettingsWatcher();
        Log.i(TAG, "Settings opened, enabling spoofing");
        PlayIntegritySpoofService.getInstance().loadConfig();
        TrickyStoreService.getInstance().initialize();
        seedDefaultTargetsIfUnset();
        stagePifPropOverrides(getCached(Settings.Secure.SPOOF_PIF_CONFIG));
        scheduleFirstRefresh();
        killGmsFamily();
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
     * to defaults, or the user deleted it), drop the staged PIF props so
     * bionic stops serving the old device identity for the overridden reads, and
     * stop the GMS family so it stops running on the old fingerprint, then
     * refresh shortly after so the device isn't left without a fingerprint until
     * the next hourly tick. A non-empty config is staged as it stands, so a
     * manual import or an Evolver edit reaches bionic the same way a refresh does.
     */
    private void onPifConfigChanged() {
        final String config = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        if (config != null && !config.trim().isEmpty()) {
            if (mActivated) stagePifPropOverrides(config);
            return;
        }

        clearPifPropOverrides();
        killGmsFamily();
        Log.i(TAG, "PIF config cleared, dropped staged PIF props");

        mHandler.removeCallbacks(mRefreshAfterClear);
        mHandler.postDelayed(mRefreshAfterClear, REFRESH_AFTER_CLEAR_DELAY_MS);
    }

    /**
     * Empties the staged PIF props. A property can't be deleted once set,
     * but bionic's custom_rom_hide_get_prop_override() only applies a staging
     * prop when it is non-empty, so an empty value restores the real identity.
     */
    private void clearPifPropOverrides() {
        for (String name : PIF_PRODUCT_PROP_NAMES) {
            setStagedProp(PIF_PRODUCT_PROP_PREFIX + name, null);
        }
        setStagedProp(PIF_BUILD_ID_PROP, null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, null);
        setStagedProp(PIF_API_LEVEL_PROP, null);
        setStagedProp(PIF_BUILD_TYPE_PROP, null);
        setStagedProp(PIF_BUILD_TAGS_PROP, null);
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP, null);
        stageCustomProps(null);
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
        if (!mActivated) return;
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
     * or sooner while a fresh install is still missing its keybox or PIF config.
     */
    private void performScheduledRefresh() {
        if (!mActivated) return;
        refreshNow();
        long delay = REFRESH_INTERVAL_MS;
        if (isBootstrapPending() && mBootstrapRetries < BOOTSTRAP_MAX_RETRIES) {
            mBootstrapRetries++;
            delay = BOOTSTRAP_RETRY_MS;
        }
        mHandler.postDelayed(this::performScheduledRefresh, delay);
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
        if (!mActivated) return;
        // Keybox first: it is one small request, while the fingerprint crawl is
        // the slow one and shouldn't be able to starve it.
        try {
            refreshKeyboxIfStale();
        } catch (Exception e) {
            Log.e(TAG, "Keybox refresh failed", e);
        }
        try {
            refreshPixelFingerprintIfStale();
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint refresh failed", e);
        }
    }

    /**
     * Runs [task] on a background executor with a hard wall-clock deadline, so
     * a stalled DNS/connect (not always caught by URLConnection's own timeouts)
     * can't wedge the shared HandlerThread that drives PACKAGE_ADDED handling
     * and the hourly reschedule.
     */
    private <T> T runBounded(ExecutorService pool, Callable<T> task) throws Exception {
        Future<T> future = pool.submit(task);
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

        PixelDeviceRepository.PixelProfile matched = runBounded(mFingerprintExecutor, () -> {
            List<PixelDeviceRepository.PixelProfile> profiles =
                    PixelDeviceRepository.getProfiles(mContext, true);
            String defaultCodename = PixelDeviceRepository.getDefaultPhoneCodename(profiles);
            return PixelDeviceRepository.getProfileByCodename(mContext, defaultCodename, false);
        });
        if (matched == null || !PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) {
            return;
        }
        if (matched.fingerprint.equals(existing.optString("FINGERPRINT", ""))) {
            // Nothing new. Without this, a fingerprint within the refetch window
            // (or with no canary metadata) was rewritten and GMS restarted every
            // day. Still start the cooldown so the crawl isn't repeated hourly.
            Settings.Secure.putLongForUser(
                    mResolver, LAST_AUTO_FETCH_KEY, System.currentTimeMillis(),
                    UserHandle.USER_SYSTEM);
            return;
        }

        // Start from the saved config so flags set elsewhere (spoofVendingFinger,
        // spoofProps, ...) survive the refresh; PlayIntegritySpoofService falls back
        // to weak defaults for any it doesn't find. Drop what is derived from, or
        // describes, the old fingerprint so it can't disagree with the new one.
        JSONObject toSave = new JSONObject(existing.toString());
        for (String stale : new String[] {"ID", "INCREMENTAL", "TYPE", "TAGS", "RELEASE",
                "_canary_month", "_canary_release_date"}) {
            toSave.remove(stale);
        }
        toSave.put("MANUFACTURER", capitalize(matched.brand));
        toSave.put("BRAND", matched.brand);
        toSave.put("MODEL", matched.model);
        toSave.put("PRODUCT", matched.product);
        toSave.put("DEVICE", matched.device);
        toSave.put("FINGERPRINT", matched.fingerprint);
        toSave.put("SECURITY_PATCH", matched.securityPatch);
        toSave.put("DEVICE_INITIAL_SDK_INT", PIF_DEVICE_INITIAL_SDK);
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

        stagePifPropOverrides(toSave.toString(2));
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

        String xml = runBounded(mNetworkExecutor, () -> {
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
        // Never touch GMS/GSF before spoofing is activated (first Settings launch),
        // so Setup Wizard is never disturbed.
        if (!mActivated) {
            Log.i(TAG, "Spoofing not activated, skipping GMS restart");
            return;
        }
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            for (String pkg : GMS_FAMILY) {
                am.forceStopPackage(pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart GMS family after refresh", e);
        }
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0
                && Settings.Secure.getIntForUser(mResolver,
                        Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }

    /**
     * Stages the spoofed product-identity, build id, security patch and initial
     * API level from the PIF config as mutable persist props for bionic's
     * custom_rom_hide_get_prop_override() to serve to DroidGuard in place of the
     * real read-only values — closes the gap where DroidGuard reading via
     * getprop (rather than the Build class) would see the real device.
     *
     * The config is the source of truth whichever path wrote it (auto-refresh,
     * manual import, Evolver). A field the config doesn't carry is staged empty,
     * which bionic treats as unset, so it never keeps a value from an older
     * config. Like PIFork, an explicit *.build.id, *.security_patch or
     * *api_level entry wins over the matching Build field; the build id falls
     * back to the one inside FINGERPRINT.
     *
     * spoofProps=0 turns the prop overrides off, as it does in PIFork: nothing
     * is staged, so bionic serves the real values. bionic can't read the config
     * itself, so this is where the switch has to be honored.
     */
    private void stagePifPropOverrides(String config) {
        final Map<String, String> fields = parsePifConfig(config);
        if (!isSpoofPropsEnabled(fields)) {
            clearPifPropOverrides();
            return;
        }
        // A config in PIF's default shape only has FINGERPRINT, so fall back to the
        // brand/product/device inside it, as PlayIntegritySpoofService does.
        final String[] head = fingerprintHead(fields.get("FINGERPRINT"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "manufacturer", fields.get("MANUFACTURER"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "brand", firstNonEmpty(
                fields.get("BRAND"), head != null ? head[0] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "model", fields.get("MODEL"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "device", firstNonEmpty(
                fields.get("DEVICE"), head != null ? head[2] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "name", firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null));
        final String[] tail = fingerprintTail(fields.get("FINGERPRINT"));
        final String buildId = firstNonEmpty(
                fields.get("*.build.id"), fields.get("ID"),
                buildIdFromFingerprint(fields.get("FINGERPRINT")));
        setStagedProp(PIF_BUILD_ID_PROP, buildId);
        // ro.*.build.type/tags for every partition and ro.build.description/flavor, so they
        // agree with the spoofed fingerprint. An unofficial ROM reports test-keys on the
        // partitions bionic doesn't pin, and its own description everywhere.
        final String type = firstNonEmpty(fields.get("*.build.type"), fields.get("TYPE"),
                tail != null ? tail[3] : null);
        final String tags = firstNonEmpty(fields.get("*.build.tags"), fields.get("TAGS"),
                tail != null ? tail[4] : null);
        setStagedProp(PIF_BUILD_TYPE_PROP, type);
        setStagedProp(PIF_BUILD_TAGS_PROP, tags);
        final String product = firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null);
        final String release = firstNonEmpty(
                fields.get("RELEASE"), tail != null ? tail[0] : null);
        final String incremental = firstNonEmpty(
                fields.get("INCREMENTAL"), tail != null ? tail[2] : null);
        final boolean haveDescription = product != null && type != null && release != null
                && buildId != null && incremental != null && tags != null;
        // Same layout as a stock build: product-type release id incremental tags
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, haveDescription
                ? product + "-" + type + " " + release + " " + buildId + " "
                        + incremental + " " + tags
                : null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP,
                product != null && type != null ? product + "-" + type : null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, firstNonEmpty(
                fields.get("*.security_patch"), fields.get("SECURITY_PATCH")));
        setStagedProp(PIF_API_LEVEL_PROP, firstNonEmpty(
                fields.get("*api_level"), fields.get("DEVICE_INITIAL_SDK_INT")));
        stageCustomProps(fields);
    }

    /**
     * Stages every other property entry of the config for DroidGuard, like PIFork's
     * jsonProps: a key containing '.' or '*' is a property name (exact, or a leading-*
     * suffix wildcard), not a Build field. Entries are sorted by key, which is the
     * order PIFork walks its wildcards in, and bionic serves an exact match before any
     * wildcard. The three wildcards handled above are skipped here.
     */
    private static void stageCustomProps(Map<String, String> fields) {
        final java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                if (key.indexOf('.') < 0 && key.indexOf('*') < 0) continue;
                if (key.equals("*") || key.startsWith("persist.sys.pif.")) continue;
                if (key.equals("*.build.id") || key.equals("*.security_patch")
                        || key.equals("*api_level") || key.equals("*.build.type")
                        || key.equals("*.build.tags")) continue;
                if (value == null || value.isEmpty()) continue;
                final String entry = key + "=" + value;
                if (entry.length() > PROP_VALUE_MAX_CHARS) {
                    Log.w(TAG, "PIF property " + key + " is too long to stage, skipping");
                    continue;
                }
                entries.put(key, entry);
            }
        }
        final int previous = Math.min(
                android.os.SystemProperties.getInt(PIF_CUSTOM_COUNT_PROP, 0),
                PIF_CUSTOM_SLOT_MAX);
        int count = 0;
        for (String entry : entries.values()) {
            if (count >= PIF_CUSTOM_SLOT_MAX) {
                Log.w(TAG, "More than " + PIF_CUSTOM_SLOT_MAX
                        + " custom PIF properties, ignoring the rest");
                break;
            }
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + count, entry);
            count++;
        }
        // Slots first when growing, count first when shrinking, so a reader never sees a
        // count that points at a stale or empty slot.
        setStagedProp(PIF_CUSTOM_COUNT_PROP, count > 0 ? Integer.toString(count) : null);
        for (int i = count; i < previous; i++) {
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + i, null);
        }
    }

    /**
     * Same rule as PlayIntegritySpoofService: on unless the config sets spoofProps
     * to something other than 1 or true.
     */
    private static boolean isSpoofPropsEnabled(Map<String, String> fields) {
        final String value = fields.get("spoofProps");
        return value == null || "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * Sets one staging prop, or empties it when there is no value. Skipped when
     * it already holds the value, since this runs on every config change and
     * persist props are written to disk.
     */
    private static void setStagedProp(String name, String value) {
        final String target = value != null ? value : "";
        try {
            if (target.equals(android.os.SystemProperties.get(name, ""))) return;
            android.os.SystemProperties.set(name, target);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stage " + name, e);
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** {brand, product, device} from brand/product/device:release/id/incremental:type/tags. */
    private static String[] fingerprintHead(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] head = fingerprint.substring(0, colon).split("/");
        return head.length == 3 ? head : null;
    }

    /** {release, id, incremental, type, tags} from brand/product/device:release/id/incremental:type/tags. */
    private static String[] fingerprintTail(String fingerprint) {
        if (fingerprint == null) return null;
        final int first = fingerprint.indexOf(':');
        final int second = first < 0 ? -1 : fingerprint.indexOf(':', first + 1);
        if (second < 0) return null;
        final String[] mid = fingerprint.substring(first + 1, second).split("/");
        final String[] end = fingerprint.substring(second + 1).split("/");
        if (mid.length != 3 || end.length != 2) return null;
        return new String[] {mid[0], mid[1], mid[2], end[0], end[1]};
    }

    /** Format: brand/product/device:release/id/incremental:type/tags */
    private static String buildIdFromFingerprint(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] rest = fingerprint.substring(colon + 1).split("/");
        return rest.length >= 4 ? rest[1] : null;
    }

    /**
     * Reads the flat key/value view of a PIF config, in either of the two formats
     * PlayIntegritySpoofService accepts: JSON, or PIF's key=value lines with
     * # comments.
     */
    private static Map<String, String> parsePifConfig(String config) {
        final Map<String, String> out = new java.util.HashMap<>();
        if (config == null || config.trim().isEmpty()) return out;
        try {
            if (config.trim().startsWith("{")) {
                final JSONObject json = new JSONObject(config);
                final java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!json.isNull(key)) out.put(key, json.get(key).toString());
                }
            } else {
                for (String line : config.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String value = line.substring(eq + 1);
                    final int comment = value.indexOf('#');
                    if (comment >= 0) value = value.substring(0, comment);
                    value = value.trim();
                    if (value.isEmpty()) continue; // the service skips these too
                    out.put(line.substring(0, eq).trim(), value);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read PIF config for staging", e);
        }
        return out;
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
        if (!mActivated) return null;
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    @Override
    public String getGamePropsConfig() {
        if (!mActivated) return null;
        return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
    }

    @Override
    public String getTrickyStoreTarget() {
        if (!mActivated) return null;
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    @Override
    public String getTrickyStoreKeyBox() {
        if (!mActivated) return null;
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    @Override
    public String getTrickyStorePatch() {
        if (!mActivated) return null;
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }
}
