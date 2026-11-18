/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps.providers;

import android.content.Context;
import android.graphics.Bitmap;

import com.android.systemui.lockglymps.GlympsHttp;
import com.android.systemui.lockglymps.GlympsProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Custom URL list provider - compatible with v1's lock_glymps_custom_urls.
 *
 * The user-supplied comma-separated URL list is parsed into candidates with
 * unknown dimensions (accepted by fitsTarget) and downloaded through the
 * shared HTTP layer.
 */
public class CustomUrlsProvider implements GlympsProvider {

    private final List<String> mUrls = new ArrayList<>();
    private final Random mRandom = new Random();

    /** Replaces the current list (called by the engine on REFRESH_SETTINGS). */
    public void setUrls(List<String> urls) {
        mUrls.clear();
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url != null) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    mUrls.add(trimmed);
                }
            }
        }
    }

    public boolean hasUrls() {
        return !mUrls.isEmpty();
    }

    @Override
    public String id() {
        return "custom";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Custom URL list"; // replaced by resource string during Evolver wiring
    }

    @Override
    public boolean isNetworkSource() {
        return true;
    }

    @Override
    public boolean needsConfiguration(Context context) {
        return mUrls.isEmpty();
    }

    @Override
    public List<GlympsCandidate> fetch(GlympsQuery query, int page) {
        List<GlympsCandidate> out = new ArrayList<>();
        for (int i = 0; i < mUrls.size(); i++) {
            String url = mUrls.get(i);
            String externalId = Integer.toHexString(url.hashCode());
            out.add(new GlympsCandidate(id(), externalId, url, 0, 0,
                    "User-supplied URL", "", ""));
        }
        return out;
    }

    /** One random candidate, mirroring v1 behavior. */
    public GlympsCandidate pickRandom() {
        if (mUrls.isEmpty()) {
            return null;
        }
        String url = mUrls.get(mRandom.nextInt(mUrls.size()));
        return new GlympsCandidate(id(), Integer.toHexString(url.hashCode()), url, 0, 0,
                "User-supplied URL", "", "");
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) throws GlympsException {
        try {
            return GlympsHttp.fetchBitmap(candidate.url);
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Custom URL download failed: " + e.getMessage());
        }
    }
}
