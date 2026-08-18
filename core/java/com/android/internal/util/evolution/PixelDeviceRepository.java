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
        // Precise factory image publish date (yyyy-MM-dd), from the image's
        // Last-Modified header. Null when unavailable — callers fall back to
        // estimating from the canary month instead.
        public final String releaseDate;

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt) {
            this(codename, model, brand, device, product, fingerprint, buildId,
                    securityPatch, fetchedAt, null);
        }

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt, String releaseDate) {
            this.codename     = codename;
            this.model        = model;
            this.brand        = brand;
            this.device       = device;
            this.product      = product;
            this.fingerprint  = fingerprint;
            this.buildId      = buildId;
            this.securityPatch = securityPatch;
            this.fetchedAt    = fetchedAt;
            this.releaseDate  = releaseDate;
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
        public String getReleaseDate()   { return releaseDate; }

        /** This profile's canary month (YYYY-MM), derived from securityPatch. */
        public String getCanaryMonth() {
            return securityPatch != null && securityPatch.length() >= 7
                    ? securityPatch.substring(0, 7) : null;
        }
    }

    public static final Map<String, String> DEVICE_MODEL_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("oriole",    "Pixel 6");
        m.put("raven",     "Pixel 6 Pro");
        m.put("bluejay",   "Pixel 6a");
        m.put("panther",   "Pixel 7");
        m.put("cheetah",   "Pixel 7 Pro");
        m.put("lynx",      "Pixel 7a");
        m.put("felix",     "Pixel Fold");
        m.put("shiba",     "Pixel 8");
        m.put("husky",     "Pixel 8 Pro");
        m.put("akita",     "Pixel 8a");
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

    // Must be updated when a codename's *tier* (Pro XL vs Pro vs base vs a-series) is
    // confirmed — used only to pick the default codename, never to filter what the
    // network fetch returns. Newest-first; unranked/unknown codenames are simply not
    // eligible to become the default until added here.
    public static final List<String> GENERATION_ORDER = Collections.unmodifiableList(
            Arrays.asList(
                    // Pixel 10 series (current default tier: Pro XL)
                    "mustang", "rango", "blazer", "frankel", "stallion",
                    // Pixel 9 series
                    "komodo", "caiman", "comet", "tokay", "tegu",
                    // Tablet
                    "tangorpro"
            ));

    // Shared default spoof target packages — single source of truth used by both
    // PixelPropsUtils (runtime) and PixelPropsSettings (UI), rather than three
    // separately-maintained copies.
    public static final Set<String> DEFAULT_PP_TARGETS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.amazon.avod.thirdpartyclient",
                    "com.android.chrome",
                    "com.breel.wallpapers20",
                    "com.disney.disneyplus",
                    "com.google.android.aicore",
                    "com.google.android.apps.accessibility.magnifier",
                    "com.google.android.apps.aiwallpapers",
                    "com.google.android.apps.bard",
                    "com.google.android.apps.customization.pixel",
                    "com.google.android.apps.emojiwallpaper",
                    "com.google.android.apps.pixel.agent",
                    "com.google.android.apps.pixel.creativeassistant",
                    "com.google.android.apps.pixel.nowplaying",
                    "com.google.android.apps.pixel.psi",
                    "com.google.android.apps.pixel.subzero",
                    "com.google.android.apps.pixel.support",
                    "com.google.android.apps.privacy.wildlife",
                    "com.google.android.apps.subscriptions.red",
                    "com.google.android.apps.wallpaper",
                    "com.google.android.apps.wallpaper.pixel",
                    "com.google.android.apps.weather",
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.pcs",
                    "com.google.android.wallpaper.effects",
                    "com.google.pixel.livewallpaper",
                    "com.microsoft.android.smsorganizer",
                    "com.nhs.online.nhsonline",
                    "com.nothing.smartcenter",
                    "com.realme.link",
                    "in.startv.hotstar",
                    "jp.id_credit_sp2.android"
            )));

    /**
     * Returns the newest ranked codename present in [available], per GENERATION_ORDER.
     * Unranked/unrecognized codenames (e.g. a not-yet-confirmed Pixel 11 tier) are
     * never auto-selected as default — add them to GENERATION_ORDER once confirmed.
     */
    public static String getDefaultPhoneCodename(List<PixelProfile> available) {
        Set<String> present = new HashSet<>();
        for (PixelProfile p : available) present.add(p.codename);
        for (String c : GENERATION_ORDER) {
            if (present.contains(c)) return c;
        }
        return "mustang";
    }

    public static String getDefaultPhoneCodename() {
        return getDefaultPhoneCodename(FALLBACK_PROFILES);
    }

    // Hardcoded fallback profiles — only used when network fails AND cache is empty
    public static final List<PixelProfile> FALLBACK_PROFILES;
    static {
        List<PixelProfile> f = new ArrayList<>();
        f.add(new PixelProfile("mustang",   "Pixel 10 Pro XL",   "google", "mustang",   "mustang",
                "google/mustang/mustang:17/CP2A.260805.005/15828068:user/release-keys",
                "CP2A.260805.005", "2026-08-05", 0L));
        f.add(new PixelProfile("tangorpro", "Pixel Tablet",      "google", "tangorpro", "tangorpro",
                "google/tangorpro/tangorpro:17/CP2A.260705.006/15641320:user/release-keys",
                "CP2A.260705.005", "2026-07-05", 0L));
        FALLBACK_PROFILES = Collections.unmodifiableList(f);
    }

    /**
     * True if both timestamps fall in the same calendar year+month, using the
     * device's default timezone. Used to gate re-fetching to once per month
     * rather than a rolling window, matching canary's actual release cadence.
     */
    private static boolean isSameMonth(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance();
        ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.MONTH) == cb.get(java.util.Calendar.MONTH);
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
            // Canary profiles turn over roughly once a month; re-fetching within the
            // same calendar month just re-downloads the same builds. Only treat the
            // cache as stale once the wall-clock month has actually rolled over.
            boolean stale = cached.isEmpty() || !isSameMonth(fetchedAt, System.currentTimeMillis());

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
     * Validates that a fingerprint string matches the expected Android format:
     * brand/product/device:VERSION/ID/INCREMENTAL:TYPE/KEYS
     */
    public static boolean isValidFingerprint(String fp) {
        if (fp == null) return false;
        return fp.matches("^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/[^:]+:[^/]+/[^:]+$");
    }

    /**
     * Returns days elapsed since a YYYY-MM-DD security patch date, or null if
     * unparseable.
     */
    public static Long getPatchAgeDays(String patch) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = sdf.parse(patch);
            if (d == null) return null;
            return (System.currentTimeMillis() - d.getTime()) / (1000L * 60 * 60 * 24);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns days remaining until a canary profile's estimated expiry (~6 weeks
     * from its release date, falling back to the 1st of its canary month), or
     * null if unparseable. Negative once expired.
     */
    public static Long getDaysUntilExpiry(String canaryMonth, String releaseDate) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String base = (releaseDate != null && !releaseDate.isEmpty())
                    ? releaseDate : canaryMonth + "-01";
            java.util.Date parsed = sdf.parse(base);
            if (parsed == null) return null;
            long expiry = parsed.getTime() + 42L * 24 * 60 * 60 * 1000;
            return (expiry - System.currentTimeMillis()) / (1000L * 60 * 60 * 24);
        } catch (Exception e) {
            return null;
        }
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
                            o.getLong("fetchedAt"),
                            o.optString("releaseDate", null)
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
                if (p.releaseDate != null) o.put("releaseDate", p.releaseDate);
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

                // Step 3: extract device codenames from QPR table rows. No known-codename
                // filter here — an unlisted device (e.g. an unconfirmed Pixel 11 tier) is
                // still picked up the moment Flash Tool/Google publish it.
                String qprHtml = readUrl(GOOGLE_URL + bestQprPath);
                java.util.regex.Matcher rm = rowPattern.matcher(qprHtml);
                // codename -> friendly model name straight from the page table,
                // e.g. "bluejay" -> "Pixel 6a". Preferred over DEVICE_MODEL_MAP
                // since it's always current; the map is only a fallback for the
                // rare case a row's name cell is empty.
                Map<String, String> scrapedModelNames = new HashMap<>();
                List<String> deviceCodenames = new ArrayList<>();
                Set<String> seenDevices = new HashSet<>();
                while (rm.find()) {
                    String device = rm.group(1).trim();
                    String modelName = rm.group(2).trim();
                    if (seenDevices.add(device)) {
                        deviceCodenames.add(device);
                        if (!modelName.isEmpty()) {
                            scrapedModelNames.put(device, modelName);
                        }
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
                            String fiUrl = b.optString("factoryImageDownloadUrl");
                            if (fiUrl.isEmpty()) fiUrl = meta.optString("factoryImageDownloadUrl");
                            if (!fiUrl.isEmpty()) factoryImageUrl = fiUrl;
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

                        // Precise release date from the factory image itself — this is what
                        // lets expiry tracking be maximally accurate rather than assuming the
                        // 1st of the canary month.
                        String releaseDate = fetchLastModifiedDate(factoryImageUrl);

                        String fingerprint = "google/" + product + "/" + device
                                + ":CANARY/" + id + "/" + incremental + ":user/release-keys";
                        String model = scrapedModelNames.getOrDefault(device,
                                DEVICE_MODEL_MAP.getOrDefault(
                                        device, "Unknown Pixel (" + device + ")"));

                        profiles.add(new PixelProfile(
                                device, model, "google", device, product,
                                fingerprint, id, securityPatch, now, releaseDate));
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

    /**
     * HEAD-requests a factory image URL to read its Last-Modified header,
     * giving a precise release date instead of assuming the 1st of the
     * canary month. Returns a yyyy-MM-dd string, or null if unavailable.
     */
    private static String fetchLastModifiedDate(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            String lastModified = conn.getHeaderField("Last-Modified");
            conn.disconnect();
            if (lastModified == null) return null;
            java.text.SimpleDateFormat httpFmt = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            java.util.Date parsed = httpFmt.parse(lastModified);
            if (parsed == null) return null;
            return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(parsed);
        } catch (Exception e) {
            return null;
        }
    }
}
