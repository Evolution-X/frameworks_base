/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps.providers;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.Html;

import com.android.systemui.lockglymps.GlympsHttp;
import com.android.systemui.lockglymps.GlympsProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wikimedia Commons provider - museum-grade photography with real categories
 * and declared licenses (https://commons.wikimedia.org/w/api.php).
 *
 * All parameters below were verified live on 2026-11-17:
 *   - generator=search + gsrsearch="filetype:bitmap incategory:<Category>"
 *   - categories: "Quality_images" and
 *     "Featured_pictures_on_Wikimedia_Commons" both return results
 *   - iiurlheight=1920 produces thumbwidth/thumbheight scaled by height
 *     (5280x3300 -> 3072x1920 was observed)
 *   - extmetadata.LicenseShortName carries the license label
 *   - gsroffset pages through results; descriptive User-Agent is required
 *
 * Candidate filter (portrait, tall enough to reach 1080x1920 after the
 * height-based thumb): original height >= 1920 and width/height >= 0.5625
 * and width/height <= 1.15. The engine center-crops to 1080x1920.
 */
public class CommonsProvider implements GlympsProvider {

    static final String API_URL = "https://commons.wikimedia.org/w/api.php";
    /** Verified category presets (mapped from GlympsQuery.preset). */
    static final String PRESET_QUALITY = "Quality_images";
    static final String PRESET_FEATURED = "Featured_pictures_on_Wikimedia_Commons";

    private final Random mRandom = new Random();

    @Override
    public String id() {
        return "commons";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Wikimedia Commons";
    }

    @Override
    public boolean isNetworkSource() {
        return true;
    }

    @Override
    public boolean needsConfiguration(Context context) {
        return false;
    }

    @Override
    public List<GlympsCandidate> fetch(GlympsQuery query, int page) throws GlympsException {
        String category = PRESET_QUALITY;
        if ("featured".equals(query.preset)) {
            category = PRESET_FEATURED;
        }
        String search = "filetype:bitmap incategory:" + category;
        String topics = WallhavenProvider.sanitizeTopics(query.topics);
        if (!topics.isEmpty()) {
            search += " " + topics.replace(" ", " ");
        }

        StringBuilder url = new StringBuilder(API_URL);
        url.append("?action=query&format=json&origin=*");
        url.append("&generator=search&gsrnamespace=6&gsrlimit=50");
        url.append("&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlheight=1920");
        url.append("&gsrsearch=").append(android.net.Uri.encode(search));
        // Random page start keeps results fresh without a random-sort API.
        int offset = page <= 1 ? mRandom.nextInt(400) : Math.max(0, page - 1) * 50;
        url.append("&gsroffset=").append(offset);

        try {
            JSONObject root = new JSONObject(GlympsHttp.fetchText(url.toString()));
            JSONObject pages = root.optJSONObject("query") == null
                    ? null : root.getJSONObject("query").optJSONObject("pages");
            List<GlympsCandidate> out = new ArrayList<>();
            if (pages != null) {
                JSONArray names = pages.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        JSONObject pageObj = pages.optJSONObject(names.optString(i));
                        if (pageObj == null) {
                            continue;
                        }
                        addCandidate(out, pageObj);
                    }
                }
            }
            return out;
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Commons fetch failed: " + e.getMessage());
        }
    }

    private void addCandidate(List<GlympsCandidate> out, JSONObject pageObj) {
        JSONObject info = pageObj.optJSONArray("imageinfo") == null
                ? null : pageObj.optJSONArray("imageinfo").optJSONObject(0);
        if (info == null) {
            return;
        }
        int origWidth = info.optInt("width");
        int origHeight = info.optInt("height");
        int thumbWidth = info.optInt("thumbwidth");
        int thumbHeight = info.optInt("thumbheight");
        String thumbUrl = info.optString("thumburl");
        String pageId = String.valueOf(pageObj.optLong("pageid", 0));
        if (thumbUrl.isEmpty() || "0".equals(pageId) || thumbHeight <= 0) {
            return;
        }
        if (origHeight < TARGET_HEIGHT) {
            return;
        }
        double ratio = (double) origWidth / (double) origHeight;
        if (ratio < 0.5625 || ratio > 1.15) {
            return;
        }
        String title = pageObj.optString("title");
        if (title.startsWith("File:")) {
            title = title.substring("File:".length());
        }
        String license = licenseLabel(info);
        GlympsCandidate candidate = new GlympsCandidate(id(), pageId, thumbUrl,
                thumbWidth, thumbHeight,
                Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString(),
                license,
                info.optString("descriptionurl"));
        if (candidate.fitsTarget()) {
            out.add(candidate);
        }
    }

    static String licenseLabel(JSONObject info) {
        try {
            JSONObject meta = info.optJSONObject("extmetadata");
            if (meta != null && meta.optJSONObject("LicenseShortName") != null) {
                String raw = meta.getJSONObject("LicenseShortName").optString("value");
                if (!raw.isEmpty()) {
                    return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString();
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return "Wikimedia Commons (see file page)";
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) throws GlympsException {
        try {
            return GlympsHttp.fetchBitmap(candidate.url);
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Commons download failed: " + e.getMessage());
        }
    }
}
