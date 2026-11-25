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

package android.security.trickystore;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.JsonReader;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class TrickyStoreService {
    private static final String TAG = "TrickyStoreService";

    private static TrickyStoreService sInstance;

    private static final class FetchResult {
        final boolean success;
        final String value;

        FetchResult(boolean success, String value) {
            this.success = success;
            this.value = value;
        }
    }

    private static final class PatchConfig {
        final CustomPatchLevel global;
        final Map<String, CustomPatchLevel> byPackage;

        PatchConfig(CustomPatchLevel global, Map<String, CustomPatchLevel> byPackage) {
            this.global = global;
            this.byPackage = Collections.unmodifiableMap(new HashMap<>(byPackage));
        }
    }

    private static final class PatchConfigBuilder {
        CustomPatchLevel global;
        final Map<String, CustomPatchLevel> byPackage = new HashMap<>();

        PatchConfig build() {
            return new PatchConfig(global, byPackage);
        }
    }

    // Kill switch for the background keybox revocation check (network, 24h cooldown,
    // logging-only: it never changes keybox state). Evolver runs its own check.
    private static final boolean ENABLE_REVOCATION_CHECK = true;

    // Publish package policy atomically. An attestation cannot see half a refresh.
    private volatile Map<String, Mode> mPackageModes = Collections.emptyMap();

    private volatile Boolean mTeeBroken = null;
    // A "healthy" result is cached for the process lifetime; a "broken" one is
    // re-checked after this long, so one transient keystore failure (early boot,
    // exhausted RKP key pool) doesn't force GENERATE mode until the next reboot.
    private static final long TEE_RECHECK_COOLDOWN_MS = 5L * 60 * 1000L;
    private volatile long mTeeCheckedAtMs = 0L;
    private volatile long mLastRevocationCheckMs = 0L;
    private volatile long mLastTargetsRefreshMs = 0L;
    private static final long TARGETS_REFRESH_COOLDOWN_MS = 5_000L;
    // Most attestations call into keybox and patch configuration repeatedly. These
    // cooldowns avoid synchronous ActivityManager Binder calls on every request.
    // Explicit refreshKeyBox()/refreshPatchLevel() still bypass the cooldown.
    private static final long CONFIG_REFRESH_COOLDOWN_MS = 5_000L;
    private final Object mKeyboxRefreshLock = new Object();
    private final Object mPatchRefreshLock = new Object();
    private volatile long mLastKeyboxRefreshElapsedMs = 0L;
    private volatile long mLastPatchRefreshElapsedMs = 0L;
    private static final long REVOCATION_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1000L;
    private static final long CACHE_TRUST_WINDOW_MS = 7L * 24 * 60 * 60 * 1000L;
    private static final java.io.File REVOCATION_CACHE_FILE =
        new java.io.File("/data/system/trickystore/revocation_cache.json");
    private static final java.io.File REVOCATION_CHECKED_FILE =
        new java.io.File("/data/system/trickystore/revocation_checked_at");
    private final Object mRevocationLock = new Object();
    private volatile PatchConfig mPatchConfig =
            new PatchConfig(null, Collections.emptyMap());
    // Attesting through a hooked process breaks STRONG — always skipped
    // regardless of what mode the target list has for them, and never
    // auto-targeted by AxSpoofManager. Shared so the two can't drift apart.
    public static final java.util.Set<String> XPOSED_PACKAGES = java.util.Set.of(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "io.va.exposed",
            "com.topjohnwu.lsplant.manager",
            "me.weishu.exposed"
    );
    // Apps that don't run integrity checks against the bootloader, so targeting them only
    // adds work: root managers and tools, and a few general Google and utility apps. Taken
    // from Tricky Addon's "unnecessary apps" list as shipped in Duck-ToolBox. Not applied
    // to an existing target list, only offered by Evolver's picker and skipped when
    // AxSpoofManager auto-targets a new install. Xposed managers are in XPOSED_PACKAGES.
    public static final java.util.Set<String> UNNECESSARY_PACKAGES = java.util.Set.of(
            // Root managers
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "com.sukisu.ultra",
            "me.bmax.apatch",
            "me.garfieldhan.apatch.next",
            // Root related tools
            "com.termux",
            "bin.mt.plus",
            "bin.mt.plus.canary",
            "xzr.konabess",
            "io.github.a13e300.ksuwebui",
            "moe.shizuku.privileged.api",
            "com.dergoogler.mmrl",
            "com.dergoogler.mmrl.wx",
            // General apps
            "org.swiftapps.swiftbackup",
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.google.android.keep",
            "com.google.android.apps.maps",
            "com.google.earth",
            "com.google.android.apps.translate",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "org.zwanoo.android.speedtest"
    );
    // Further packages AxSpoofManager skips when auto-targeting a new install: app stores,
    // root and system tools and proxy clients, none of which run integrity checks. Compiled
    // from the exclude lists of Targeter and Tricky Addon Enhanced. Like UNNECESSARY_PACKAGES
    // this never touches an existing target list or Evolver's picker.
    public static final java.util.Set<String> AUTO_TARGET_EXCLUDED_PACKAGES = java.util.Set.of(
            // App stores
            "com.looker.droidify",
            "com.aurora.store",
            // Root and system tools
            "io.github.muntashirakon.AppManager",
            "io.github.muntashirakon.AppManager.debug",
            "com.tsng.hidemyapplist",
            "org.frknkrc44.hma_oss",
            "eu.darken.sdmse",
            "me.gm.cleaner",
            "com.speedsoftware.rootexplorer",
            "com.mixplorer",
            "com.merxury.blocker",
            "rikka.appops",
            "com.piriform.ccleaner",
            "io.github.vvb2060.magisk.lite",
            "com.resukisu.resukisu",
            // Proxy clients
            "com.v2ray.ang",
            "com.github.shadowsocks",
            "com.github.metacubex.clash.meta",
            "com.github.kr328.clash.monet"
    );
    // Vendor prefixes covering whole families of such packages (F-Droid and its basic
    // flavour, LSPosed and LSPatch).
    private static final String[] AUTO_TARGET_EXCLUDED_PREFIXES = {
            "org.fdroid.",
            "org.lsposed.",
            "io.github.lsposed."
    };

    /**
     * True if a newly installed package should not be added to the target list on its own:
     * Xposed managers, {@link #UNNECESSARY_PACKAGES} and {@link #AUTO_TARGET_EXCLUDED_PACKAGES},
     * or anything under {@link #AUTO_TARGET_EXCLUDED_PREFIXES}.
     */
    public static boolean isAutoTargetExcluded(String pkg) {
        if (pkg == null) return true;
        if (XPOSED_PACKAGES.contains(pkg) || UNNECESSARY_PACKAGES.contains(pkg)
                || AUTO_TARGET_EXCLUDED_PACKAGES.contains(pkg)) {
            return true;
        }
        for (String prefix : AUTO_TARGET_EXCLUDED_PREFIXES) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }
    // Default TrickyStore targets, in the same syntax as SPOOF_TRICKYSTORE_TARGET:
    // "pkg" is AUTO, "pkg?" leaf hack, "pkg!" cert generation, "pkg-" skip.
    // AxSpoofManager writes this into a target setting that was never set, so a
    // fresh install attests GMS and friends without anyone opening Evolver, and
    // Evolver's reset and app picker read the same list rather than keeping a copy.
    public static final String DEFAULT_TARGET_LIST = String.join("\n",
            "android",
            // GMS and friends, AUTO mode
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.google.android.contactkeys",
            "com.google.android.ims",
            "com.google.android.safetycore",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
            // Cert generation
            "com.revolut.revolut!",
            // Key attestation checkers, leaf hack
            "io.github.qwq233.keyattestation?",
            "io.github.vvb2060.keyattestation?",
            "io.github.vvb2060.mahoshojo?",
            "icu.nullptr.nativetest?",
            "com.reveny.nativecheck?",
            "com.zhenxi.hunter?",
            "com.android.nativetest?",
            "io.liankong.riskdetector?",
            "luna.safe.luna?",
            "com.eltavine.duckdetector?",
            "com.rem01gaming.disclosure?",
            "wu.keyChain.test?",
            "com.kikyps.crackme?",
            "com.chunqiunativecheck?"
    );
    private volatile String mLastKeyboxFingerprint = null;

    private final KeyBoxManager mKeyBoxManager;

    /** @hide */
    public enum Mode {
        AUTO, LEAF_HACK, GENERATE, SKIP
    }

    /** @hide */
    public static class CustomPatchLevel {
        public final String system;
        public final String vendor;
        public final String boot;
        public final String all;

        public CustomPatchLevel(String system, String vendor, String boot, String all) {
            this.system = system;
            this.vendor = vendor;
            this.boot = boot;
            this.all = all;
        }
    }

    private TrickyStoreService() {
        mKeyBoxManager = new KeyBoxManager();
    }

    public static synchronized TrickyStoreService getInstance() {
        if (sInstance == null) {
            sInstance = new TrickyStoreService();
            sInstance.initialize();
        }
        return sInstance;
    }

    public void initialize() {
        refreshTargets();
        refreshKeyBox();
        refreshPatchLevel();
        Log.i(TAG, "TrickyStoreService initialized");
    }

    private FetchResult fetchFromAms(Fetcher fetcher) {
        try {
            IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Log.w(TAG, "ActivityManager not ready, retaining last trickystore configuration");
                return new FetchResult(false, null);
            }
            return new FetchResult(true, fetcher.fetch(am));
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch trickystore config; retaining existing values", e);
            return new FetchResult(false, null);
        }
    }

    private interface Fetcher {
        String fetch(IActivityManager am) throws RemoteException;
    }

    public synchronized void refreshTargets() {
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStoreTarget());
        if (!fetched.success) return;
        if (fetched.value == null || fetched.value.trim().isEmpty()) {
            mPackageModes = Collections.emptyMap();
            return;
        }
        String content = fetched.value.trim();
        try {
            Map<String, Mode> parsed = content.startsWith("[")
                    ? parseTargetsJson(content) : parseTargetsText(content);
            mPackageModes = Collections.unmodifiableMap(parsed);
            Log.i(TAG, "Updated target policies: " + parsed.size());
        } catch (Exception e) {
            Log.e(TAG, "Invalid target policies; retaining previous configuration", e);
        }
    }

    private Map<String, Mode> parseTargetsText(String content) {
        Map<String, Mode> parsed = new HashMap<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.endsWith("!")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.GENERATE);
            } else if (line.endsWith("?")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.LEAF_HACK);
            } else if (line.endsWith("-")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.SKIP);
            } else {
                parsed.put(line, Mode.AUTO);
            }
        }
        return parsed;
    }

    private Map<String, Mode> parseTargetsJson(String content) throws IOException {
        Map<String, Mode> parsed = new HashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                String pkg = null;
                String modeStr = "AUTO";
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if ("package".equals(key)) {
                        pkg = reader.nextString();
                    } else if ("mode".equals(key)) {
                        modeStr = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (pkg == null) continue;
                Mode mode;
                try {
                    mode = Mode.valueOf(modeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    mode = Mode.AUTO;
                }
                if (!pkg.trim().isEmpty()) parsed.put(pkg.trim(), mode);
            }
            reader.endArray();
        }
        return parsed;
    }

    public void refreshKeyBox() {
        synchronized (mKeyboxRefreshLock) {
            mLastKeyboxRefreshElapsedMs = SystemClock.elapsedRealtime();
            refreshKeyBoxLocked();
        }
    }

    private void refreshKeyBoxLocked() {
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStoreKeyBox());
        if (!fetched.success) return;
        String raw = fetched.value;
        if (raw == null || raw.trim().isEmpty()) {
            // A successful empty response means the config was explicitly deleted.
            mKeyBoxManager.clear();
            mLastKeyboxFingerprint = null;
            return;
        }
        String fingerprint = sha256(raw);
        if (fingerprint.equals(mLastKeyboxFingerprint)) {
            return;
        }
        String xml = decodeKeybox(raw);
        if (xml == null) {
            Log.e(TAG, "Keybox payload not recognised as XML or base64-encoded XML");
            return;
        }
        try {
            if (!isValidKeyboxXml(xml)) {
                mLastKeyboxFingerprint = null;
                Log.e(TAG, "Keybox XML failed structural validation (missing keys or identifier)");
                return;
            }
            if (ENABLE_REVOCATION_CHECK) {
                checkKeyboxRevocation(xml);
            }
            if (!mKeyBoxManager.parseKeybox(xml)) {
                // Rejected as incomplete: the previous keyboxes stay loaded. Don't record
                // this payload as applied, so the next read looks at it again.
                mLastKeyboxFingerprint = null;
                return;
            }
            if (mKeyBoxManager.hasKeyboxes()) {
                mLastKeyboxFingerprint = fingerprint;
                Log.i(TAG, "Keybox updated successfully");
            } else {
                mLastKeyboxFingerprint = null;
                Log.e(TAG, "Keybox parse produced no usable entries");
            }
        } catch (Exception e) {
            mLastKeyboxFingerprint = null;
            Log.e(TAG, "Failed to update keybox", e);
        }
    }

    // KeyBoxManager accepts algorithm="ec", single quotes and extra attributes, so the
    // structural pre-check has to as well.
    private static final java.util.regex.Pattern KEY_ECDSA = java.util.regex.Pattern.compile(
            "<Key\\s[^>]*?algorithm\\s*=\\s*[\"'](?:ecdsa|ec)[\"']",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern KEY_RSA = java.util.regex.Pattern.compile(
            "<Key\\s[^>]*?algorithm\\s*=\\s*[\"']rsa[\"']",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean isValidKeyboxXml(String xml) {
        String problem = keyboxProblem(xml);
        if (problem != null) {
            Log.e(TAG, "Keybox validation failed: " + problem);
            return false;
        }
        if (!KEY_ECDSA.matcher(xml).find()) Log.w(TAG, "Keybox warning: missing ECDSA key block");
        if (!KEY_RSA.matcher(xml).find())   Log.w(TAG, "Keybox warning: missing RSA key block");
        return true;
    }

    /**
     * Structural check of a keybox document before it is parsed.
     *
     * @return null if acceptable, otherwise a short reason
     */
    public static String keyboxProblem(String xml) {
        if (xml == null) return "Keybox is empty";
        if (!KEY_ECDSA.matcher(xml).find() && !KEY_RSA.matcher(xml).find()) {
            return "No ECDSA or RSA key block";
        }
        if (!xml.contains("<serial>") && !xml.contains("DeviceID")) {
            return "No serial or DeviceID";
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return AttestationUtils.bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the keybox XML text of a payload that is either the XML itself or its base64
     * encoding, or null if it is neither.
     */
    public static String decodeKeybox(String payload) {
        String trimmed = payload.trim();
        if (trimmed.startsWith("<")) {
            return trimmed;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            String asXml = new String(decoded, StandardCharsets.UTF_8).trim();
            if (asXml.startsWith("<")) {
                return asXml;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public void refreshPatchLevel() {
        synchronized (mPatchRefreshLock) {
            mLastPatchRefreshElapsedMs = SystemClock.elapsedRealtime();
            refreshPatchLevelLocked();
        }
    }

    private void refreshPatchLevelLocked() {
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStorePatch());
        if (!fetched.success) return;
        String content = fetched.value;
        if (content == null || content.trim().isEmpty()) {
            mPatchConfig = new PatchConfig(null, Collections.emptyMap());
            return;
        }
        try {
            PatchConfigBuilder next = new PatchConfigBuilder();
            String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                parsePatchJson(trimmed, next);
            } else {
                parsePatchText(trimmed, next);
            }
            mPatchConfig = next.build();
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch levels; retaining previous configuration", e);
        }
    }

    private void parsePatchText(String content, PatchConfigBuilder next) {
        String currentPackage = null;
        String system = null, vendor = null, boot = null, all = null;

        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                flushPatchSection(next, currentPackage, system, vendor, boot, all);
                currentPackage = line.substring(1, line.length() - 1).trim();
                system = vendor = boot = all = null;
                continue;
            }

            int idx = line.indexOf('=');
            if (idx > 0) {
                String key = line.substring(0, idx).trim().toLowerCase();
                String value = line.substring(idx + 1).trim();
                switch (key) {
                    case "system": system = value; break;
                    case "vendor": vendor = value; break;
                    case "boot":   boot   = value; break;
                    case "all":    all    = value; break;
                }
            } else {
                all = line;
            }
        }
        flushPatchSection(next, currentPackage, system, vendor, boot, all);
    }

    // YYYY-MM[-DD] where any field may be the literal YYYY / MM / DD placeholder.
    private static final java.util.regex.Pattern PATCH_TEMPLATE = java.util.regex.Pattern
            .compile("^(YYYY|\\d{4})-(MM|\\d{2})(?:-(DD|\\d{2}))?$");

    /**
     * Resolves the "today" keyword and YYYY/MM/DD placeholders against the current UTC
     * date. A fixed day is clamped to the length of a placeholder-resolved month. Anything
     * that isn't a template, including plain dates, is returned unchanged.
     */
    public static String resolvePatchTemplate(String value) {
        if (value == null) return null;
        String v = value.trim();
        java.util.Calendar cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        int nowYear = cal.get(java.util.Calendar.YEAR);
        int nowMonth = cal.get(java.util.Calendar.MONTH) + 1;
        int nowDay = cal.get(java.util.Calendar.DAY_OF_MONTH);
        if (v.equalsIgnoreCase("today")) {
            return String.format(java.util.Locale.US, "%04d-%02d-%02d", nowYear, nowMonth, nowDay);
        }
        java.util.regex.Matcher m = PATCH_TEMPLATE.matcher(v);
        if (!m.matches()) return value;
        boolean yearTemplate = "YYYY".equals(m.group(1));
        boolean monthTemplate = "MM".equals(m.group(2));
        boolean dayTemplate = "DD".equals(m.group(3));
        if (!yearTemplate && !monthTemplate && !dayTemplate) return value;

        int year = yearTemplate ? nowYear : Integer.parseInt(m.group(1));
        int month = monthTemplate ? nowMonth : Integer.parseInt(m.group(2));
        if (m.group(3) == null) {
            return String.format(java.util.Locale.US, "%04d-%02d", year, month);
        }
        int day = dayTemplate ? nowDay : Integer.parseInt(m.group(3));
        if ((yearTemplate || monthTemplate) && month >= 1 && month <= 12) {
            day = Math.min(day, java.time.YearMonth.of(year, month).lengthOfMonth());
        }
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day);
    }

    /**
     * Whether [value] is acceptable as a patch level setting: "no", "prop", a date, or a
     * template that resolves to a date (see {@link #resolvePatchTemplate}).
     */
    public static boolean isValidPatchValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.equalsIgnoreCase("no") || v.equalsIgnoreCase("prop")) return true;
        return AttestationUtils.isValidPatchLevel(resolvePatchTemplate(v));
    }

    private void flushPatchSection(PatchConfigBuilder next,
            String pkg, String system, String vendor, String boot, String all) {
        if (system == null && vendor == null && boot == null && all == null) return;
        String resolvedAll    = resolvePatchTemplate(all);
        String resolvedSystem = resolvePatchTemplate(system);
        String resolvedVendor = resolvePatchTemplate(vendor);
        String resolvedBoot   = resolvePatchTemplate(boot);
        CustomPatchLevel level = new CustomPatchLevel(
            resolvedSystem != null ? resolvedSystem : resolvedAll,
            resolvedVendor != null ? resolvedVendor : resolvedAll,
            resolvedBoot   != null ? resolvedBoot   : resolvedAll,
            resolvedAll
        );
        if (pkg == null) {
            next.global = level;
        } else {
            next.byPackage.put(pkg, level);
        }
    }

    private void parsePatchJson(String content, PatchConfigBuilder next) throws IOException {
        String system = null, vendor = null, boot = null, all = null;
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                switch (key) {
                    case "system": system = reader.nextString(); break;
                    case "vendor": vendor = reader.nextString(); break;
                    case "boot":   boot   = reader.nextString(); break;
                    case "all":    all    = reader.nextString(); break;
                    case "packages":
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String pkg = reader.nextName();
                            String ps = null, pv = null, pb = null, pa = null;
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String pk = reader.nextName();
                                switch (pk) {
                                    case "system": ps = reader.nextString(); break;
                                    case "vendor": pv = reader.nextString(); break;
                                    case "boot":   pb = reader.nextString(); break;
                                    case "all":    pa = reader.nextString(); break;
                                    default: reader.skipValue(); break;
                                }
                            }
                            reader.endObject();
                            flushPatchSection(next, pkg, ps, pv, pb, pa);
                        }
                        reader.endObject();
                        break;
                    default: reader.skipValue(); break;
                }
            }
            reader.endObject();
        }
        flushPatchSection(next, null, system, vendor, boot, all);
    }

    private boolean isTeeStatusFresh() {
        Boolean cached = mTeeBroken;
        return cached != null && (!cached
                || System.currentTimeMillis() - mTeeCheckedAtMs < TEE_RECHECK_COOLDOWN_MS);
    }

    private void ensureTeeStatus() {
        if (isTeeStatusFresh()) return;
        synchronized (this) {
            if (isTeeStatusFresh()) return;
            boolean broken = checkTeeBroken();
            mTeeBroken = broken;
            mTeeCheckedAtMs = System.currentTimeMillis();
            AttestationUtils.setTeeBroken(broken);
        }
    }

    public void checkRevocationIfDue() {
        if (!ENABLE_REVOCATION_CHECK || Process.myUid() != Process.SYSTEM_UID) return;
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStoreKeyBox());
        String xml = fetched.success && fetched.value != null ? decodeKeybox(fetched.value) : null;
        if (xml != null) checkKeyboxRevocation(xml);
    }

    private void checkKeyboxRevocation(String xml) {
        if (Process.myUid() != Process.SYSTEM_UID) {
            if (REVOCATION_CACHE_FILE.exists()) checkCachedRevocation(extractCertSerials(xml));
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (mRevocationLock) {
            if (mLastRevocationCheckMs == 0L) mLastRevocationCheckMs = readRevocationCheckedAt();
            long since = now - mLastRevocationCheckMs;
            if (since >= 0 && since < REVOCATION_CHECK_COOLDOWN_MS) {
                Log.d(TAG, "Skipping revocation check — ran within 24h");
                return;
            }
            mLastRevocationCheckMs = now;
        }
        new Thread(() -> {
            writeRevocationCheckedAt(now);
            try {
                List<String> serials = extractCertSerials(xml);
                if (serials.isEmpty()) return;
                java.net.URL url = new java.net.URL(
                        "https://android.googleapis.com/attestation/status?encrypted=0");
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                    checkCachedRevocation(serials);
                    return;
                }
                String body = new String(
                        conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                org.json.JSONObject entries =
                        new org.json.JSONObject(body).optJSONObject("entries");
                if (entries == null) return;
                for (String serial : serials) {
                    org.json.JSONObject entry = entries.optJSONObject(serial);
                    if (entry == null) continue;
                    String status = entry.optString("status", "").toUpperCase(java.util.Locale.US);
                    if ("REVOKED".equals(status) || "SUSPENDED".equals(status)) {
                        Log.w(TAG, "Keybox serial " + serial + " is " + status +
                                " — attestation may fail");
                        writeCachedRevokedSerial(serial);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Keybox revocation check failed, trying offline cache", e);
                try {
                    checkCachedRevocation(extractCertSerials(xml));
                } catch (Exception inner) {
                    Log.w(TAG, "Offline revocation cache check also failed", inner);
                }
            }
        }, "TrickyStore-RevocationCheck").start();
    }

    private static long readRevocationCheckedAt() {
        try {
            return Long.parseLong(new String(
                    java.nio.file.Files.readAllBytes(REVOCATION_CHECKED_FILE.toPath()),
                    StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void writeRevocationCheckedAt(long time) {
        try {
            REVOCATION_CHECKED_FILE.getParentFile().mkdirs();
            java.nio.file.Files.write(REVOCATION_CHECKED_FILE.toPath(),
                    Long.toString(time).getBytes(StandardCharsets.UTF_8));
            android.system.Os.chmod(REVOCATION_CHECKED_FILE.getAbsolutePath(), 0600);
        } catch (Exception e) {
            Log.w(TAG, "Failed to write revocation check time", e);
        }
    }

    /**
     * Writes [serial] to a small private cache file so a later network
     * failure can still flag it. Downgrade-only: this cache is only ever
     * consulted to log a warning, never to assert a keybox is safe.
     */
    private void writeCachedRevokedSerial(String serial) {
        try {
            REVOCATION_CACHE_FILE.getParentFile().mkdirs();
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("serial", serial);
            obj.put("cachedAt", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(REVOCATION_CACHE_FILE)) {
                fw.write(obj.toString());
            }
            android.system.Os.chmod(REVOCATION_CACHE_FILE.getAbsolutePath(), 0600);
        } catch (Exception e) {
            Log.w(TAG, "Failed to write revocation cache", e);
        }
    }

    /**
     * Logging-only fallback used when the live revocation fetch fails.
     * Reads the cached bad serial, if any, and logs a warning if it matches
     * one of the current keybox's serials and the cache is still within the
     * trust window. Never mutates keybox state — that decision stays with
     * the Settings UI layer.
     */
    private void checkCachedRevocation(List<String> serials) {
        if (!REVOCATION_CACHE_FILE.exists()) return;
        try {
            String content = new String(
                java.nio.file.Files.readAllBytes(REVOCATION_CACHE_FILE.toPath()),
                StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(content);
            long cachedAt = obj.optLong("cachedAt", 0L);
            if (cachedAt == 0L ||
                System.currentTimeMillis() - cachedAt > CACHE_TRUST_WINDOW_MS) {
                return;
            }
            String cachedSerial = obj.optString("serial", "");
            if (!cachedSerial.isEmpty() && serials.contains(cachedSerial)) {
                Log.w(TAG, "Keybox serial " + cachedSerial +
                        " was cached as revoked/suspended (offline fallback,"
                        + " live check unavailable)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read revocation cache", e);
        }
    }

    private List<String> extractCertSerials(String xml) {
        List<String> serials = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----");
        java.util.regex.Matcher m = p.matcher(xml);
        java.security.cert.CertificateFactory factory;
        try {
            factory = java.security.cert.CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            return serials;
        }
        while (m.find()) {
            try {
                byte[] der = Base64.getDecoder().decode(
                        m.group(1).replaceAll("\\s", ""));
                java.security.cert.X509Certificate cert =
                        (java.security.cert.X509Certificate)
                        factory.generateCertificate(
                                new java.io.ByteArrayInputStream(der));
                // Google's revocation list spells 128-bit serials as lowercase hex without
                // leading zeros and the old 64-bit ones as decimal, so look up both.
                serials.add(cert.getSerialNumber().toString(16));
                serials.add(cert.getSerialNumber().toString());
            } catch (Exception ignored) {}
        }
        return serials;
    }

    private boolean checkTeeBroken() {
        final String alias = "TrickyStoreTeeCheck";
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[16])
                    // A targeted request attests device ids. A challenge-only key
                    // can succeed on this KeyMint and cache a healthy TEE, after
                    // which the real request dies with CANNOT_ATTEST_IDS.
                    .setDevicePropertiesAttestationIncluded(true);
            kpg.initialize(builder.build());
            kpg.generateKeyPair();
            Log.i(TAG, "Attested key generation probe successful");
            return false;
        } catch (Exception e) {
            // A failed probe is not proof of a damaged TEE. Unsupported attestation
            // or unavailable provisioning may produce the same failure.
            Log.w(TAG, "Attested key generation probe unavailable", e);
            return true;
        } finally {
            if (ks != null) {
                try {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete temporary TEE check alias", e);
                }
            }
        }
    }

    public boolean needHack(int callingUid, String[] packages) {
        if (packages == null) return false;
        maybeRefreshTargets();
        for (String pkg : packages) {
            if (XPOSED_PACKAGES.contains(pkg)) continue;
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.SKIP) continue;
            if (mode == Mode.LEAF_HACK) return true;
            // Only AUTO needs to know the TEE state. Probing it costs a real
            // attested key generation, so don't do it for untargeted apps.
            if (mode == Mode.AUTO && !isTeeBroken()) return true;
        }
        return false;
    }

    public boolean needGenerate(int callingUid, String[] packages) {
        if (packages == null) return false;
        maybeRefreshTargets();
        for (String pkg : packages) {
            if (XPOSED_PACKAGES.contains(pkg)) continue;
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.SKIP) continue;
            if (mode == Mode.GENERATE) return hasKeyboxes();
            if (mode == Mode.AUTO && hasKeyboxes() && isTeeBroken()) return true;
        }
        return false;
    }

    /**
     * True for any non-skip target. Unlike {@link #needHack} and
     * {@link #needGenerate}, this does not consult the TEE probe.
     */
    public boolean isAttestationTarget(int callingUid, String[] packages) {
        if (packages == null) return false;
        maybeRefreshTargets();
        for (String pkg : packages) {
            if (XPOSED_PACKAGES.contains(pkg)) continue;
            Mode mode = mPackageModes.get(pkg);
            if (mode != null && mode != Mode.SKIP) return true;
        }
        return false;
    }

    private void maybeRefreshTargets() {
        long now = System.currentTimeMillis();
        if (now - mLastTargetsRefreshMs >= TARGETS_REFRESH_COOLDOWN_MS) {
            mLastTargetsRefreshMs = now;
            refreshTargets();
        }
    }

    private void maybeRefreshKeyBox() {
        long now = SystemClock.elapsedRealtime();
        if (mLastKeyboxRefreshElapsedMs != 0L
                && now - mLastKeyboxRefreshElapsedMs < CONFIG_REFRESH_COOLDOWN_MS) {
            return;
        }
        synchronized (mKeyboxRefreshLock) {
            now = SystemClock.elapsedRealtime();
            if (mLastKeyboxRefreshElapsedMs == 0L
                    || now - mLastKeyboxRefreshElapsedMs >= CONFIG_REFRESH_COOLDOWN_MS) {
                mLastKeyboxRefreshElapsedMs = now;
                refreshKeyBoxLocked();
            }
        }
    }

    private void maybeRefreshPatchLevel() {
        long now = SystemClock.elapsedRealtime();
        if (mLastPatchRefreshElapsedMs != 0L
                && now - mLastPatchRefreshElapsedMs < CONFIG_REFRESH_COOLDOWN_MS) {
            return;
        }
        synchronized (mPatchRefreshLock) {
            now = SystemClock.elapsedRealtime();
            if (mLastPatchRefreshElapsedMs == 0L
                    || now - mLastPatchRefreshElapsedMs >= CONFIG_REFRESH_COOLDOWN_MS) {
                mLastPatchRefreshElapsedMs = now;
                refreshPatchLevelLocked();
            }
        }
    }

    public KeyBoxManager getKeyBoxManager() {
        maybeRefreshKeyBox();
        return mKeyBoxManager;
    }

    public CustomPatchLevel getCustomPatchLevel() {
        maybeRefreshPatchLevel();
        return mPatchConfig.global;
    }

    public CustomPatchLevel getCustomPatchLevelForPackage(String[] packages) {
        maybeRefreshPatchLevel();
        PatchConfig config = mPatchConfig;
        if (packages != null) {
            for (String pkg : packages) {
                CustomPatchLevel level = config.byPackage.get(pkg);
                if (level != null) return level;
            }
        }
        return config.global;
    }

    /**
     * Returns true if the given patch date string is more than 12 months old.
     * Accepts YYYY-MM-DD only; unparsable or null input is treated as not stale
     * (we don't want to warn on a value we can't understand).
     */
    private static final long PATCH_STALE_THRESHOLD_MS = 365L * 24 * 60 * 60 * 1000L;

    public static boolean isPatchLevelStale(String patchDate) {
        if (patchDate == null || patchDate.isEmpty()) return false;
        try {
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setLenient(false);
            long patchMs = sdf.parse(patchDate.trim()).getTime();
            return System.currentTimeMillis() - patchMs > PATCH_STALE_THRESHOLD_MS;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasKeyboxes() {
        maybeRefreshKeyBox();
        return mKeyBoxManager.hasKeyboxes();
    }

    /**
     * Returns whether the TEE is broken, forcing the check if it hasn't run yet.
     * Safe to call from any thread; the underlying check is synchronized.
     */
    public boolean isTeeBroken() {
        ensureTeeStatus();
        return Boolean.TRUE.equals(mTeeBroken);
    }

    /**
     * KeyMint could not produce an attestation certificate. That is either
     * ATTESTATION_KEYS_NOT_PROVISIONED or CANNOT_ATTEST_IDS for the spoofed
     * device ids. A healthy probe result is cached for the life of this
     * process, which would leave AUTO targets on leaf hack with nothing to
     * rewrite. Remember that so the following calls generate a chain instead.
     */
    public void noteAttestationKeysMissing() {
        mTeeBroken = Boolean.TRUE;
        mTeeCheckedAtMs = System.currentTimeMillis();
        AttestationUtils.setTeeBroken(true);
        Log.w(TAG, "KeyMint cannot attest; using certificate generation");
    }
}
