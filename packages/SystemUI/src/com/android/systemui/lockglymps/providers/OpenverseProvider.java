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

/**
 * Openverse provider - Creative Commons media with machine-readable attribution
 * (https://api.openverse.org/v1/images/).
 *
 * Verified live on 2026-11-17:
 *   - q, aspect_ratio=tall, license_type=all-cc, size=large, page_size, page
 *   - with size=large the sampled widths ranged 1099..3547 (all >= 720)
 *   - every result carries license, license_version, creator and
 *     foreign_landing_url, which we surface as attribution
 *
 * Anonymous quota is modest (20/min burst, 200/day), so this provider asks for
 * a single page of 20 candidates per change at most.
 */
public class OpenverseProvider implements GlympsProvider {

    static final String SEARCH_URL = "https://api.openverse.org/v1/images/";

    @Override
    public String id() {
        return "openverse";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Openverse";
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
        url.append("?license_type=all-cc&aspect_ratio=tall&size=large&page_size=20");
        url.append("&page=").append(Math.max(1, page));
        String topics = WallhavenProvider.sanitizeTopics(query.topics);
        if (!topics.isEmpty()) {
            url.append("&q=").append(android.net.Uri.encode(topics));
        } else {
            url.append("&q=nature");
        }

        try {
            JSONObject root = new JSONObject(GlympsHttp.fetchText(url.toString()));
            JSONArray results = root.optJSONArray("results");
            List<GlympsCandidate> out = new ArrayList<>();
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    int width = item.optInt("width");
                    int height = item.optInt("height");
                    String url2 = item.optString("url");
                    String externalId = item.optString("id");
                    if (url2.isEmpty() || externalId.isEmpty()) {
                        continue;
                    }
                    double ratio = (double) width / (double) height;
                    if (ratio < 0.5625 || ratio > 1.15 || width < 1080 || height < TARGET_HEIGHT) {
                        continue;
                    }
                    String creator = stripHtml(item.optString("creator"));
                    String license = ("CC " + item.optString("license") + " "
                            + item.optString("license_version")).trim();
                    out.add(new GlympsCandidate(id(), externalId, url2,
                            width, height, creator, license,
                            item.optString("foreign_landing_url")));
                }
            }
            return out;
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Openverse fetch failed: " + e.getMessage());
        }
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) throws GlympsException {
        try {
            return GlympsHttp.fetchBitmap(candidate.url);
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Openverse download failed: " + e.getMessage());
        }
    }

    static String stripHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }
}
