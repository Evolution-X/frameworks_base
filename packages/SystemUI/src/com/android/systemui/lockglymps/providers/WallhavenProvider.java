/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps.providers;

import android.content.Context;
import android.graphics.Bitmap;

import com.android.systemui.lockglymps.GlympsHttp;
import com.android.systemui.lockglymps.GlympsProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Wallhaven provider (https://wallhaven.cc/help/api).
 *
 * The strongest unauthenticated wallpaper source: category flags, tags via q,
 * dominant color filter, minimum-resolution and portrait aspect ratios, and
 * random sorting with a stable seed (no repeats across pages).
 *
 * Only parameters verified live on 2026-11-17 are used:
 *   purity=100 (SFW only), categories=100|110|010|001|111,
 *   atleast=1080x1920, ratios=9x16,10x16,3x4, q=<topics>, colors=<hex>,
 *   sorting=random&seed=<seed>, page=<n>.
 * Rate limit: 45 requests/minute unauthenticated -> 429 surfaces as a
 * rate-limited GlympsException.
 */
public class WallhavenProvider implements GlympsProvider {

    static final String SEARCH_URL = "https://wallhaven.cc/api/v1/search";
    /** Verified portrait ratios that reach 1080x1920. */
    static final String PORTRAIT_RATIOS = "9x16,10x16,3x4";
    /** Verified dominant color palette (no # prefix). */
    static final String VALID_COLORS =
            "660000,990000,cc0000,cc3333,ea4c88,993399,663399,333399,0066cc,0099cc,"
            + "66cccc,77cc33,669900,336600,666600,999900,cccc33,ffff00,ffcc33,ff9900,"
            + "ff6600,cc6633,996633,663300,000000,999999,cccccc,ffffff,424153";

    @Override
    public String id() {
        return "wallhaven";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Wallhaven";
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
        StringBuilder url = new StringBuilder(SEARCH_URL);
        url.append("?purity=100");
        url.append("&categories=").append(sanitizeCategories(query.categories));
        url.append("&atleast=").append(TARGET_WIDTH).append('x').append(TARGET_HEIGHT);
        url.append("&ratios=").append(PORTRAIT_RATIOS);
        String topics = sanitizeTopics(query.topics);
        if (!topics.isEmpty()) {
            url.append("&q=").append(android.net.Uri.encode(topics));
        }
        String color = query.color.toLowerCase();
        if (VALID_COLORS.contains(color)) {
            url.append("&colors=").append(color);
        }
        url.append("&sorting=random&seed=").append(android.net.Uri.encode(query.seed));
        url.append("&page=").append(Math.max(1, page));

        try {
            JSONObject root = new JSONObject(GlympsHttp.fetchText(url.toString()));
            JSONArray data = root.optJSONArray("data");
            List<GlympsCandidate> out = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String path = item.optString("path");
                    int width = item.optInt("dimension_x");
                    int height = item.optInt("dimension_y");
                    String externalId = item.optString("id");
                    if (path.isEmpty() || externalId.isEmpty()) {
                        continue;
                    }
                    GlympsCandidate candidate = new GlympsCandidate(id(), externalId, path,
                            width, height,
                            "Wallhaven",
                            "Wallhaven community upload (SFW)",
                            "https://wallhaven.cc/w/" + externalId);
                    if (candidate.fitsTarget()) {
                        out.add(candidate);
                    }
                }
            }
            return out;
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Wallhaven fetch failed: " + e.getMessage());
        }
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) throws GlympsException {
        try {
            return GlympsHttp.fetchBitmap(candidate.url);
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Wallhaven download failed: " + e.getMessage());
        }
    }

    /** Accepts only the verified 3-digit flag form; anything else falls back to 110. */
    static String sanitizeCategories(String value) {
        if (value != null && value.length() == 3) {
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                char c = value.charAt(i);
                if (c != '0' && c != '1') {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return value;
            }
        }
        return "110";
    }

    /** Strips anything that could break the q syntax; allows letters, digits, spaces, - + . */
    static String sanitizeTopics(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '+' || c == '.') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }
}
