/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PixelDeviceRepository {

    private static final String TAG = "PixelDeviceRepository";
    public static final String CACHE_KEY = "pi_pixel_device_cache";
    private static final long CACHE_TTL = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final int CACHE_VERSION = 3;
    private static final String GOOGLE_URL = "https://developer.android.com";

    // Default codenames used when no explicit user selection or generation-based
    // preference is available. Single source of truth — PixelPropsUtils and
    // PixelPropsSettings.kt read these instead of hardcoding their own copies.
    public static final String DEFAULT_PHONE_CODENAME  = "mustang";
    public static final String DEFAULT_TABLET_CODENAME = "tangorpro";

    private static final java.util.regex.Pattern MODEL_GEN_PATTERN =
            java.util.regex.Pattern.compile("Pixel (\\d+)");

    private static volatile List<PixelProfile> sMemoryCache = null;
    private static final Object sFetchLock = new Object();

    public static final class PixelProfile {
        public final String codename;
        public final String model;
        public final String brand;
        public final String device;
        public final String product;
        public final String fingerprint;
        public final String buildId;
        public final String securityPatch;
        public final String canaryMonth;
        public final String releaseDate;
        public final String factoryImageUrl;
        public final long fetchedAt;

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, String canaryMonth, String releaseDate,
                String factoryImageUrl, long fetchedAt) {
            this.codename        = codename;
            this.model           = model;
            this.brand           = brand;
            this.device          = device;
            this.product         = product;
            this.fingerprint     = fingerprint;
            this.buildId         = buildId;
            this.securityPatch   = securityPatch;
            this.canaryMonth     = canaryMonth;
            this.releaseDate     = releaseDate;
            this.factoryImageUrl = factoryImageUrl;
            this.fetchedAt       = fetchedAt;
        }

        // Kotlin-style getters for compatibility with existing call sites
        public String getCodename()        { return codename; }
        public String getModel()           { return model; }
        public String getBrand()           { return brand; }
        public String getDevice()          { return device; }
        public String getProduct()         { return product; }
        public String getFingerprint()     { return fingerprint; }
        public String getBuildId()         { return buildId; }
        public String getSecurityPatch()   { return securityPatch; }
        public String getCanaryMonth()     { return canaryMonth; }
        public String getReleaseDate()     { return releaseDate; }
        public String getFactoryImageUrl() { return factoryImageUrl; }
        public long   getFetchedAt()       { return fetchedAt; }
    }

    private PixelDeviceRepository() {}

    /**
     * Returns cached profiles if fresh, otherwise fetches from network.
     * Must be called from a background thread.
     * Falls back to hardcoded profiles if network fails and cache is empty.
     */
    public static List<PixelProfile> getProfiles(Context context, boolean forceRefresh) {
        synchronized (sFetchLock) {
            List<PixelProfile> cached = readCache(context);
            long fetchedAt = cached.isEmpty() ? 0L : cached.get(0).fetchedAt;
            boolean stale = cached.isEmpty() ||
                    (System.currentTimeMillis() - fetchedAt) > CACHE_TTL;

            if (!forceRefresh && !stale) return cached;

            List<PixelProfile> fresh = Collections.emptyList();
            try {
                fresh = fetchFromNetwork();
            } catch (Exception e) {
                Log.w(TAG, "Network fetch failed: " + e.getMessage());
            }

            if (!fresh.isEmpty()) {
                writeCache(context, fresh);
                return fresh;
            } else {
                // No stale hardcoded fallback: an empty cache plus a failed fetch just
                // means no profiles are available yet. Callers (setProps(), the settings
                // UI) already treat "no profile" as "skip spoofing" rather than apply a
                // guessed/stale device, so returning what little we have is safe.
                return cached;
            }
        }
    }

    public static List<PixelProfile> getProfiles(Context context) {
        return getProfiles(context, false);
    }

    /**
     * Returns a single profile by codename from cache.
     * Falls back to mustang (mobile) or tangorpro (tablet) if not found.
     * Safe to call from any thread.
     */
    public static PixelProfile getProfileByCodename(Context context, String codename,
            boolean isTablet) {
        try {
            List<PixelProfile> cached = sMemoryCache;
            if (cached == null) {
                cached = readCache(context);
                sMemoryCache = cached;
            }
            String defaultCodename = isTablet ? DEFAULT_TABLET_CODENAME : DEFAULT_PHONE_CODENAME;
            PixelProfile result = findByCodename(cached, codename);
            if (result == null) result = findByCodename(cached, defaultCodename);
            // No hardcoded fallback: if the cache has nothing for either codename yet,
            // setProps() treats a null profile as "skip spoofing this call" instead of
            // applying a guessed fingerprint.
            return result;
        } catch (Exception e) {
            Log.w(TAG, "getProfileByCodename failed: " + e.getMessage());
            return null;
        }
    }

    private static PixelProfile findByCodename(List<PixelProfile> list, String codename) {
        for (PixelProfile p : list) {
            if (p.codename.equals(codename)) return p;
        }
        return null;
    }

    public static List<PixelProfile> readCache(Context context) {
        try {
            String json = Settings.Secure.getString(context.getContentResolver(), CACHE_KEY);
            if (json == null || json.isEmpty()) return Collections.emptyList();
            JSONArray arr = new JSONArray(json);
            List<PixelProfile> result = new ArrayList<>();
            if (arr.length() == 0) return Collections.emptyList();
            if (arr.getJSONObject(0).optInt("cacheVersion", 1) != CACHE_VERSION)
                return Collections.emptyList();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    result.add(new PixelProfile(
                            o.getString("codename"),
                            o.getString("model"),
                            o.getString("brand"),
                            o.getString("device"),
                            o.getString("product"),
                            o.getString("fingerprint"),
                            o.getString("buildId"),
                            o.getString("securityPatch"),
                            o.optString("canaryMonth", ""),
                            o.optString("releaseDate", ""),
                            o.optString("factoryImageUrl", ""),
                            o.getLong("fetchedAt")
                    ));
                } catch (Exception ignored) {}
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Cache read failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static void writeCache(Context context, List<PixelProfile> profiles) {
        sMemoryCache = profiles;
        try {
            JSONArray arr = new JSONArray();
            for (PixelProfile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("cacheVersion",  CACHE_VERSION);
                o.put("codename",      p.codename);
                o.put("model",         p.model);
                o.put("brand",         p.brand);
                o.put("device",        p.device);
                o.put("product",       p.product);
                o.put("fingerprint",   p.fingerprint);
                o.put("buildId",       p.buildId);
                o.put("securityPatch", p.securityPatch);
                o.put("canaryMonth",   p.canaryMonth);
                o.put("releaseDate",   p.releaseDate);
                o.put("factoryImageUrl", p.factoryImageUrl);
                o.put("fetchedAt",     p.fetchedAt);
                arr.put(o);
            }
            String json = arr.toString();
            if (json.length() > 7500) {
                Log.w(TAG, "Cache JSON too large (" + json.length() + " chars), trimming to newest entries");
                // Drop oldest entries (tail of list) until it fits
                while (arr.length() > 1) {
                    arr.remove(arr.length() - 1);
                    json = arr.toString();
                    if (json.length() <= 7500) break;
                }
            }
            Settings.Secure.putString(context.getContentResolver(), CACHE_KEY, json);
        } catch (Exception e) {
            Log.w(TAG, "Cache write failed: " + e.getMessage());
        }
    }

    private static List<PixelProfile> fetchFromNetwork() throws Exception {
        // Step 1: find all known Android versions
        String versionsHtml = readUrl(GOOGLE_URL + "/about/versions");
        List<Integer> knownVersions = new ArrayList<>();
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                "https://developer\\.android\\.com/about/versions/(\\d+)")
                .matcher(versionsHtml);
        Set<Integer> seen = new HashSet<>();
        while (vm.find()) {
            int v = Integer.parseInt(vm.group(1));
            if (seen.add(v)) knownVersions.add(v);
        }
        Collections.sort(knownVersions, Collections.reverseOrder());

        if (knownVersions.isEmpty()) return Collections.emptyList();

        // Also try maxVersion+1 in case a new release isn't linked yet
        List<Integer> versions = new ArrayList<>();
        versions.add(knownVersions.get(0) + 1);
        versions.addAll(knownVersions);

        // Step 2: find the latest QPR download-ota page (mirrors PIF's canary device fetch)
        java.util.regex.Pattern qprPattern = java.util.regex.Pattern.compile(
                "href=\"(/about/versions/\\d+/qpr(\\d+)/download-ota)\"");
        java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                "<tr id=\"([^\"]+)\">\\s*<td[^>]*>([^<]+)</td>",
                java.util.regex.Pattern.DOTALL);

        for (int version : versions) {
            try {
                String latestHtml = readUrl(GOOGLE_URL + "/about/versions/" + version);
                java.util.regex.Matcher qm = qprPattern.matcher(latestHtml);

                String bestQprPath = null;
                int bestQpr = -1;
                while (qm.find()) {
                    int qprNum = Integer.parseInt(qm.group(2));
                    if (qprNum > bestQpr) {
                        bestQpr = qprNum;
                        bestQprPath = qm.group(1);
                    }
                }
                if (bestQprPath == null) continue;

                // Step 3: extract device codenames from QPR table rows
                String qprHtml = readUrl(GOOGLE_URL + bestQprPath);
                java.util.regex.Matcher rm = rowPattern.matcher(qprHtml);
                List<String> deviceCodenames = new ArrayList<>();
                Map<String, String> deviceModels = new HashMap<>();
                Set<String> seenDevices = new HashSet<>();
                while (rm.find()) {
                    String device = rm.group(1).trim();
                    if (seenDevices.add(device)) {
                        deviceCodenames.add(device);
                        deviceModels.put(device, rm.group(2).trim());
                    }
                }
                if (deviceCodenames.isEmpty()) continue;

                // Step 4: get Flash Tool API key
                String apiKey = fetchFlashToolApiKey();
                if (apiKey == null || apiKey.isEmpty()) continue;

                // Step 5: for each device, hit the Flash Tool API and extract canary fingerprint
                long now = System.currentTimeMillis();
                List<PixelProfile> profiles = new ArrayList<>();

                for (String device : deviceCodenames) {
                    String product = device + "_beta";
                    try {
                        URLConnection conn = new URL(
                                "https://content-flashstation-pa.googleapis.com/v1/builds"
                                + "?product=" + product + "&key=" + apiKey)
                                .openConnection();
                        conn.setRequestProperty("Referer", "https://flash.android.com");
                        conn.setRequestProperty("X-Goog-Api-Key", apiKey);
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        String buildsJson = new String(
                                conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                        org.json.JSONObject root = new org.json.JSONObject(buildsJson);
                        org.json.JSONArray buildsArray = root.optJSONArray("flashstationBuild");
                        if (buildsArray == null) continue;

                        String id = null;
                        String incremental = null;
                        String canaryId = null;
                        String factoryImageUrl = null;

                        for (int i = buildsArray.length() - 1; i >= 0; i--) {
                            org.json.JSONObject b = buildsArray.optJSONObject(i);
                            if (b == null) continue;
                            org.json.JSONObject meta = b.optJSONObject("previewMetadata");
                            if (meta == null || !meta.optBoolean("canary")) continue;
                            String rc = b.optString("releaseCandidateName");
                            String bid = b.optString("buildId");
                            if (rc.isEmpty() || bid.isEmpty()) continue;
                            id = rc;
                            incremental = bid;
                            String mid = meta.optString("id");
                            if (mid.contains("canary-")) canaryId = mid;
                            String fiu = b.optString("factoryImageDownloadUrl");
                            if (fiu.isEmpty()) fiu = meta.optString("factoryImageDownloadUrl");
                            if (!fiu.isEmpty()) factoryImageUrl = fiu;
                            break;
                        }
                        if (id == null || incremental == null) continue;

                        // Derive security patch from canary ID month (e.g. "canary-202605")
                        String canaryMonth = "";
                        if (canaryId != null) {
                            java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                                    "canary-(\\d{4})(\\d{2})")
                                    .matcher(canaryId);
                            if (cm.find()) {
                                canaryMonth = cm.group(1) + "-" + cm.group(2);
                            }
                        }
                        String securityPatch = canaryMonth.isEmpty()
                                ? "2026-05-05" : canaryMonth + "-05"; // safe default

                        String releaseDate = factoryImageUrl != null
                                ? fetchLastModifiedDate(factoryImageUrl) : null;

                        String fingerprint = "google/" + product + "/" + device
                                + ":CANARY/" + id + "/" + incremental + ":user/release-keys";
                        if (!isValidFingerprint(fingerprint)) continue;

                        // Model name comes straight from Google's own QPR table row —
                        // no hardcoded codename→model map to keep updated per release.
                        String scrapedModel = deviceModels.get(device);
                        String model = (scrapedModel != null && !scrapedModel.isEmpty())
                                ? scrapedModel : device;

                        profiles.add(new PixelProfile(
                                device, model, "google", device, product,
                                fingerprint, id, securityPatch, canaryMonth,
                                releaseDate == null ? "" : releaseDate,
                                factoryImageUrl == null ? "" : factoryImageUrl, now));
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to fetch canary build for " + device + ": " + e.getMessage());
                    }
                }

                if (!profiles.isEmpty()) return profiles;
            } catch (Exception e) {
                // try next version
            }
        }
        return Collections.emptyList();
    }

    private static String readUrl(String url) throws Exception {
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        InputStream input = conn.getInputStream();
        try {
            byte[] bytes = input.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    /**
     * Extracts the Flash Tool API key from flash.android.com. Shared by both the
     * canary profile scrape above and PlayIntegrityFix's device picker so the two
     * never fetch or parse it differently.
     */
    public static String fetchFlashToolApiKey() {
        try {
            String flashHtml = readUrl("https://flash.android.com");
            java.util.regex.Matcher km = java.util.regex.Pattern.compile(
                    "AIza[0-9A-Za-z_-]{35}")
                    .matcher(flashHtml);
            return km.find() ? km.group(0) : null;
        } catch (Exception e) {
            Log.w(TAG, "Flash Tool API key fetch failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * HEAD-requests a factory image to read its Last-Modified header, giving a
     * precise canary release date instead of assuming the 1st of the canary month.
     * Returns a yyyy-MM-dd string, or null if it can't be determined.
     */
    public static String fetchLastModifiedDate(String url) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            String lastModified = conn.getHeaderField("Last-Modified");
            conn.disconnect();
            if (lastModified == null) return null;
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            java.util.Date parsed = in.parse(lastModified);
            if (parsed == null) return null;
            return new java.text.SimpleDateFormat(
                    "yyyy-MM-dd", java.util.Locale.US).format(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates that a fingerprint string matches the expected Android format:
     * brand/product/device:VERSION/ID/INCREMENTAL:TYPE/KEYS
     */
    public static boolean isValidFingerprint(String fp) {
        return fp != null && java.util.regex.Pattern.compile(
                "^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/[^:]+:[^/]+/[^:]+$")
                .matcher(fp).matches();
    }

    public static java.util.Date parsePatchDate(String patch) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .parse(patch);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the number of days elapsed since the given YYYY-MM-DD security patch
     * date, or null if the date cannot be parsed.
     */
    public static Long getPatchAgeDays(String patch) {
        java.util.Date parsed = parsePatchDate(patch);
        if (parsed == null) return null;
        return (System.currentTimeMillis() - parsed.getTime()) / (1000L * 60 * 60 * 24);
    }

    /**
     * Given a canary month string (YYYY-MM), estimates the expiry date as ~6 weeks
     * from the 1st of that month (or from [releaseDate] when known) and returns a
     * human-readable string: "expires YYYY-MM-DD" or "expired YYYY-MM-DD" if past.
     */
    public static String getCanaryExpiryString(String canaryMonth, String releaseDate) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd", java.util.Locale.US);
            java.util.Date base = sdf.parse(
                    releaseDate != null && !releaseDate.isEmpty()
                            ? releaseDate : canaryMonth + "-01");
            if (base == null) return null;
            java.util.Date expiry = new java.util.Date(base.getTime() + 42L * 24 * 60 * 60 * 1000);
            String expiryStr = sdf.format(expiry);
            return expiry.before(new java.util.Date())
                    ? "expired " + expiryStr : "expires " + expiryStr;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Picks the best default profile: highest-generation flagship ("Pro XL" for
     * phones, or the tablet) available in [profiles], falling back to lower-tier
     * variants of that same generation, then to DEFAULT_PHONE_CODENAME /
     * DEFAULT_TABLET_CODENAME if nothing from the network is usable yet.
     */
    public static PixelProfile getPreferredDefaultProfile(List<PixelProfile> profiles, boolean isTablet) {
        if (isTablet) {
            return findByCodename(profiles, DEFAULT_TABLET_CODENAME);
        }
        int bestGen = -1;
        PixelProfile best = null;
        for (PixelProfile p : profiles) {
            java.util.regex.Matcher m = MODEL_GEN_PATTERN.matcher(p.model);
            if (!m.find()) continue;
            int gen = Integer.parseInt(m.group(1));
            boolean isFlagship = p.model.contains("Pro XL");
            if (gen > bestGen || (gen == bestGen && isFlagship)) {
                bestGen = gen;
                best = p;
            }
        }
        if (best != null) return best;
        return findByCodename(profiles, DEFAULT_PHONE_CODENAME);
    }

    public static String getPreferredDefaultCodename(List<PixelProfile> profiles, boolean isTablet) {
        PixelProfile p = getPreferredDefaultProfile(profiles, isTablet);
        return p != null ? p.codename : (isTablet ? DEFAULT_TABLET_CODENAME : DEFAULT_PHONE_CODENAME);
    }
}
