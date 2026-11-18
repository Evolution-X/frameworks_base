/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps.providers;

import android.content.Context;
import android.graphics.Bitmap;

import com.android.systemui.lockglymps.GlympsHttp;
import com.android.systemui.lockglymps.GlympsProvider;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Picsum provider - the zero-configuration default (compatible with v1).
 *
 * Uses the seeded form /seed/<seed>/1080/1920 so every candidate has a stable,
 * trackable identity for the no-repeat bag, and always returns the required
 * target resolution.
 */
public class PicsumProvider implements GlympsProvider {

    private static final String SEED_CHARS = "abcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String id() {
        return "picsum";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Lorem Picsum";
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
    public List<GlympsCandidate> fetch(GlympsQuery query, int page) {
        List<GlympsCandidate> out = new ArrayList<>();
        String seed = randomSeed();
        out.add(new GlympsCandidate(id(), seed,
                "https://picsum.photos/seed/" + seed + "/" + TARGET_WIDTH + "/" + TARGET_HEIGHT,
                TARGET_WIDTH, TARGET_HEIGHT,
                "Lorem Picsum", "Picsum images (Unsplash-sourced)", ""));
        return out;
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) throws GlympsException {
        try {
            return GlympsHttp.fetchBitmap(candidate.url);
        } catch (GlympsException e) {
            throw e;
        } catch (Exception e) {
            throw new GlympsException("Picsum download failed: " + e.getMessage());
        }
    }

    static String randomSeed() {
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            out.append(SEED_CHARS.charAt(RANDOM.nextInt(SEED_CHARS.length())));
        }
        return out.toString();
    }
}
