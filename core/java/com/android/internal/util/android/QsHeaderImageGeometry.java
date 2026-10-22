/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.android;

public final class QsHeaderImageGeometry {
    private QsHeaderImageGeometry() {}

    public static void calculate(float imageWidth, float imageHeight, float width, float height,
            float zoom, float positionX, float positionY, float[] result) {
        float scale = Math.max(width / imageWidth, height / imageHeight)
                * clamp(zoom, 1f, 4f, 1f);
        result[0] = scale;
        result[1] = -(imageWidth * scale - width) * clamp(positionX, 0f, 1f, 0.5f);
        result[2] = -(imageHeight * scale - height) * clamp(positionY, 0f, 1f, 0.5f);
    }

    public static float clamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
