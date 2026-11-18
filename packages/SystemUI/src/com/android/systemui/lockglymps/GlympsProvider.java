/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.List;

/**
 * Contract for a Glymps wallpaper source provider.
 *
 * A provider describes where candidate wallpapers come from and how to download
 * them. The engine (LockGlympsService) never talks to a concrete source directly;
 * it always goes through this interface, so new sources are pluggable by
 * implementing one class and registering it in {@link GlympsProviders}.
 *
 * Design rules:
 *  - No API keys anywhere: every built-in provider works unauthenticated.
 *  - Required resolution is fixed at {@link #TARGET_WIDTH}x{@link #TARGET_HEIGHT};
 *    providers filter server-side where possible and the engine center-crops
 *    the decoded bitmap to exactly that size.
 *  - fetch() is cheap (metadata only) and may be cached; download() is heavy
 *    and runs on the engine's background executor.
 */
public interface GlympsProvider {

    /** Required wallpaper resolution (product decision v2.1): fixed 1080x1920. */
    int TARGET_WIDTH = 1080;
    int TARGET_HEIGHT = 1920;

    /** Stable identifier persisted in Settings.System (e.g. "wallhaven"). */
    String id();

    /** Localized display name shown in the Evolver source list. */
    CharSequence displayName(Context context);

    /** True when candidates come from the network (drives the Wi-Fi gate). */
    boolean isNetworkSource();

    /** True when the provider cannot produce candidates until user configures it. */
    boolean needsConfiguration(Context context);

    /**
     * Fetch one page of candidates. Must not perform heavy bitmap work.
     *
     * @param query taste parameters resolved for the current target
     * @param page 1-based page index; providers that are not paginated ignore it
     */
    List<GlympsCandidate> fetch(GlympsQuery query, int page) throws GlympsException;

    /** Download and decode the candidate image. Called on a background thread. */
    Bitmap download(GlympsCandidate candidate) throws GlympsException;

    /** One wallpaper candidate. Immutable. */
    final class GlympsCandidate {
        public final String providerId;
        /** Source-unique identifier used by the no-repeat bag. */
        public final String externalId;
        /** Direct image URL (http/https) or local file path (file:/). */
        public final String url;
        /** Known intrinsic size, 0 when unknown (local sources). */
        public final int width;
        public final int height;
        /** Human-readable credit, empty when the source has none. */
        public final String attribution;
        /** License label, empty when the source has none. */
        public final String license;
        /** Web page describing the wallpaper, empty when unavailable. */
        public final String sourcePage;

        public GlympsCandidate(String providerId, String externalId, String url,
                int width, int height, String attribution, String license, String sourcePage) {
            this.providerId = providerId;
            this.externalId = externalId;
            this.url = url;
            this.width = width;
            this.height = height;
            this.attribution = attribution == null ? "" : attribution;
            this.license = license == null ? "" : license;
            this.sourcePage = sourcePage == null ? "" : sourcePage;
        }

        /** Network candidates must already meet the target resolution. */
        public boolean fitsTarget() {
            return width <= 0 || height <= 0
                    || (width >= TARGET_WIDTH && height >= TARGET_HEIGHT);
        }
    }

    /** Taste parameters resolved for one target before calling fetch(). */
    final class GlympsQuery {
        /** User keywords (space/comma separated); empty = no filter. */
        public String topics = "";
        /** Provider-specific category flag, e.g. Wallhaven "110". Empty = provider default. */
        public String categories = "";
        /** Provider-specific dominant color, e.g. Wallhaven "000000". Empty = any. */
        public String color = "";
        /** Provider-specific preset/pack id (e.g. "toplist", "quality"). Empty = provider default. */
        public String preset = "";
        /** Stable seed for random listings (no-repeat across pages). */
        public String seed = "";
        /** Reserved for future key-based providers; always empty in this build. */
        public String apiKey = "";
    }

    /** Failure taxonomy used by the engine for backoff and fallback decisions. */
    class GlympsException extends Exception {
        public final boolean rateLimited;
        public final boolean retryable;

        public GlympsException(String message) {
            super(message);
            this.rateLimited = false;
            this.retryable = true;
        }

        public GlympsException(String message, boolean rateLimited, boolean retryable) {
            super(message);
            this.rateLimited = rateLimited;
            this.retryable = retryable;
        }
    }
}
