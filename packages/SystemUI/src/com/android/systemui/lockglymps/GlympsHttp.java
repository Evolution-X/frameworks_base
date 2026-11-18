/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Small shared HTTP layer for all Glymps providers.
 *
 * - Fixed descriptive User-Agent (required by Wikimedia, good practice for all).
 * - Hard timeouts (15s connect / 15s read) like v1.
 * - HTTP 429 is surfaced as a rate-limited GlympsException so the engine can
 *   back off instead of hammering a source.
 */
public final class GlympsHttp {

    static final String USER_AGENT =
            "EvolutionX-Glymps (Android; +https://github.com/Evolution-X)";
    static final int CONNECT_TIMEOUT_MS = 15000;
    static final int READ_TIMEOUT_MS = 15000;

    private GlympsHttp() {
    }

    /** Opens a connection with the shared profile; caller owns the stream. */
    public static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setDoInput(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.connect();
        return connection;
    }

    /** Opens and validates the response, throwing typed exceptions on failure. */
    public static InputStream openChecked(String url) throws IOException, GlympsProvider.GlympsException {
        HttpURLConnection connection = open(url);
        int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {
            return connection.getInputStream();
        }
        connection.disconnect();
        if (code == 429) {
            throw new GlympsProvider.GlympsException("Rate limited: " + url, true, true);
        }
        throw new GlympsProvider.GlympsException("HTTP " + code + " for " + url, false, code >= 500);
    }

    /** Downloads a URL body as text (JSON endpoints). */
    public static String fetchText(String url) throws IOException, GlympsProvider.GlympsException {
        InputStream input = null;
        try {
            input = openChecked(url);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        }
    }

    /** Decodes a bitmap from a URL, disconnecting afterwards. */
    public static android.graphics.Bitmap fetchBitmap(String url)
            throws IOException, GlympsProvider.GlympsException {
        InputStream input = null;
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (code == 429) {
                    throw new GlympsProvider.GlympsException("Rate limited: " + url, true, true);
                }
                throw new GlympsProvider.GlympsException("HTTP " + code + " for " + url,
                        false, code >= 500);
            }
            input = connection.getInputStream();
            return android.graphics.BitmapFactory.decodeStream(input);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // ignored
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
