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
    private static final int CACHE_VERSION = 2;
    private static final String GOOGLE_URL = "https://developer.android.com";

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
        public final long fetchedAt;

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt) {
            this.codename     = codename;
            this.model        = model;
            this.brand        = brand;
            this.device       = device;
            this.product      = product;
            this.fingerprint  = fingerprint;
            this.buildId      = buildId;
            this.securityPatch = securityPatch;
            this.fetchedAt    = fetchedAt;
        }

        // Kotlin-style getters for compatibility with existing call sites
        public String getCodename()      { return codename; }
        public String getModel()         { return model; }
        public String getBrand()         { return brand; }
        public String getDevice()        { return device; }
        public String getProduct()       { return product; }
        public String getFingerprint()   { return fingerprint; }
        public String getBuildId()       { return buildId; }
        public String getSecurityPatch() { return securityPatch; }
        public long   getFetchedAt()     { return fetchedAt; }
    }

    // Must be updated when new Pixel codenames are released — devices not listed here
    // will be silently skipped during network profile fetch in fetchFromNetwork()
    public static final Set<String> KNOWN_CODENAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // Pixel 9 series
                    "tokay", "caiman", "komodo", "comet", "tegu",
                    // Pixel 10 series
                    "frankel", "blazer", "mustang", "rango", "stallion",
                    // Tablet
                    "tangorpro"
            )));

    public static final Map<String, String> DEVICE_MODEL_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("tangorpro", "Pixel Tablet");
        m.put("tokay",     "Pixel 9");
        m.put("caiman",    "Pixel 9 Pro");
        m.put("komodo",    "Pixel 9 Pro XL");
        m.put("comet",     "Pixel 9 Pro Fold");
        m.put("tegu",      "Pixel 9a");
        m.put("frankel",   "Pixel 10");
        m.put("blazer",    "Pixel 10 Pro");
        m.put("mustang",   "Pixel 10 Pro XL");
        m.put("rango",     "Pixel 10 Pro Fold");
        m.put("stallion",  "Pixel 10a");
        m.put("cubs",      "Pixel 11");
        m.put("grizzly",   "Pixel 11 Pro");
        m.put("kodiak",    "Pixel 11 Pro XL");
        m.put("yogi",      "Pixel 11 Pro Fold");
        m.put("formosan",  "Pixel 11a");
        m.put("galago",    "Pixel 12");
        m.put("sasquatch", "Pixel 12 Pro");
        m.put("silverback","Pixel 12 Pro XL");
        m.put("capuchin",  "Pixel 12 Pro Fold");
        DEVICE_MODEL_MAP = Collections.unmodifiableMap(m);
    }

    // Hardcoded fallback profiles — only used when network fails AND cache is empty
    public static final List<PixelProfile> FALLBACK_PROFILES;
    static {
        List<PixelProfile> f = new ArrayList<>();
        f.add(new PixelProfile("mustang",   "Pixel 10 Pro XL",   "google", "mustang",   "mustang",
                "google/mustang/mustang:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("rango",     "Pixel 10 Pro Fold", "google", "rango",     "rango",
                "google/rango/rango:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("blazer",    "Pixel 10 Pro",      "google", "blazer",    "blazer",
                "google/blazer/blazer:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("frankel",   "Pixel 10",          "google", "frankel",   "frankel",
                "google/frankel/frankel:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("stallion",  "Pixel 10a",         "google", "stallion",  "stallion",
                "google/stallion/stallion:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("komodo",    "Pixel 9 Pro XL",    "google", "komodo",    "komodo",
                "google/komodo/komodo:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("caiman",    "Pixel 9 Pro",       "google", "caiman",    "caiman",
                "google/caiman/caiman:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("comet",     "Pixel 9 Pro Fold",  "google", "comet",     "comet",
                "google/comet/comet:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("tokay",     "Pixel 9",           "google", "tokay",     "tokay",
                "google/tokay/tokay:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("tegu",      "Pixel 9a",          "google", "tegu",      "tegu",
                "google/tegu/tegu:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        f.add(new PixelProfile("tangorpro", "Pixel Tablet",      "google", "tangorpro", "tangorpro",
                "google/tangorpro/tangorpro:16/CP1A.260505.005/15081906:user/release-keys",
                "CP1A.260505.005", "2026-05-05", 0L));
        FALLBACK_PROFILES = Collections.unmodifiableList(f);
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
                return cached.isEmpty() ? FALLBACK_PROFILES : cached;
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
            String defaultCodename = isTablet ? "tangorpro" : "mustang";
            PixelProfile result = findByCodename(cached, codename);
            if (result == null) result = findByCodename(cached, defaultCodename);
            if (result == null) result = findByCodename(FALLBACK_PROFILES, codename);
            if (result == null) result = findByCodename(FALLBACK_PROFILES, defaultCodename);
            return result;
        } catch (Exception e) {
            Log.w(TAG, "getProfileByCodename failed, using fallback: " + e.getMessage());
            return findByCodename(FALLBACK_PROFILES, isTablet ? "tangorpro" : "mustang");
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
                Set<String> seenDevices = new HashSet<>();
                while (rm.find()) {
                    String device = rm.group(1).trim();
                    if (KNOWN_CODENAMES.contains(device) && seenDevices.add(device)) {
                        deviceCodenames.add(device);
                    }
                }
                if (deviceCodenames.isEmpty()) continue;

                // Step 4: get Flash Tool API key (same method as PIF canary fetch)
                String flashHtml = readUrl("https://flash.android.com");
                java.util.regex.Matcher km = java.util.regex.Pattern.compile(
                        "AIza[0-9A-Za-z_-]{35}")
                        .matcher(flashHtml);
                if (!km.find()) continue;
                String apiKey = km.group(0);

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
                            break;
                        }
                        if (id == null || incremental == null) continue;

                        // Derive security patch from canary ID month (e.g. "canary-202605")
                        String securityPatch = "2026-05-05"; // safe default
                        if (canaryId != null) {
                            java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                                    "canary-(\\d{4})(\\d{2})")
                                    .matcher(canaryId);
                            if (cm.find()) {
                                securityPatch = cm.group(1) + "-" + cm.group(2) + "-05";
                            }
                        }

                        String fingerprint = "google/" + product + "/" + device
                                + ":CANARY/" + id + "/" + incremental + ":user/release-keys";
                        String model = DEVICE_MODEL_MAP.containsKey(device)
                                ? DEVICE_MODEL_MAP.get(device) : device;

                        profiles.add(new PixelProfile(
                                device, model, "google", device, product,
                                fingerprint, id, securityPatch, now));
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

    private static String fetchPartialUrl(String url, int maxBytes) throws Exception {
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        InputStream input = conn.getInputStream();
        try {
            byte[] buf = new byte[512];
            StringBuilder sb = new StringBuilder();
            int total = 0;
            while (total < maxBytes) {
                int read = input.read(buf);
                if (read == -1) break;
                sb.append(new String(buf, 0, read, StandardCharsets.ISO_8859_1));
                total += read;
            }
            return sb.toString();
        } finally {
            input.close();
        }
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
}
