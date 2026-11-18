/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps;

import android.content.Context;

import com.android.systemui.lockglymps.providers.CommonsProvider;
import com.android.systemui.lockglymps.providers.CustomUrlsProvider;
import com.android.systemui.lockglymps.providers.LocalFolderProvider;
import com.android.systemui.lockglymps.providers.OpenverseProvider;
import com.android.systemui.lockglymps.providers.PicsumProvider;
import com.android.systemui.lockglymps.providers.WallhavenProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all built-in Glymps providers (zero-key portfolio).
 *
 * Wallhaven / Wikimedia Commons / Openverse were verified live on 2026-11-17
 * (see ar-work/glymps2/docs/PHASE0_DECISIONS.md). Picsum keeps v1
 * compatibility as the zero-configuration default, together with the local
 * folder and custom URL list sources from v1.
 */
public final class GlympsProviders {

    public static final String DEFAULT_ID = "picsum";
    public static final String WALLHAVEN = "wallhaven";
    public static final String COMMONS = "commons";
    public static final String OPENVERSE = "openverse";
    public static final String PICSUM = "picsum";
    public static final String LOCAL = "local";
    public static final String CUSTOM = "custom";

    private static final Map<String, GlympsProvider> sProviders = new LinkedHashMap<>();

    static {
        register(new WallhavenProvider());
        register(new CommonsProvider());
        register(new OpenverseProvider());
        register(new PicsumProvider());
        register(new LocalFolderProvider());
        register(new CustomUrlsProvider());
    }

    private GlympsProviders() {
    }

    static void register(GlympsProvider provider) {
        sProviders.put(provider.id(), provider);
    }

    /** Resolves a stored id; unknown or empty ids fall back to the default. */
    public static GlympsProvider get(String id) {
        if (id != null && sProviders.containsKey(id)) {
            return sProviders.get(id);
        }
        return sProviders.get(DEFAULT_ID);
    }

    /** True when the id is a known provider. */
    public static boolean isKnown(String id) {
        return id != null && sProviders.containsKey(id);
    }

    /** All provider ids in registration order (drives Evolver list entries). */
    public static List<String> ids() {
        return new ArrayList<>(sProviders.keySet());
    }

    /**
     * Fallback display name for logs/state. The Evolver UI resolves localized
     * names from its own arrays keyed by provider id.
     */
    public static CharSequence displayName(Context context, String id) {
        return get(id).displayName(context);
    }
}
