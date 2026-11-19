/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution.glass;

import android.annotation.ColorInt;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 *
 *
 * @hide
 */
public final class LiquidGlass {

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 shape;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float dispersion;\n"
            + "uniform float saturation;\n"
            + "uniform float rim;\n"
            + "uniform float innerShadow;\n"
            + "uniform float innerShadowWidth;\n"
            + "layout(color) uniform half4 tint;\n"
            + "\n"
            + "float sdf(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + r;\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "float2 sdfNormal(float2 p, float2 b, float r) {\n"
            + "  float2 w = abs(p) - (b - r);\n"
            + "  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "  float2 q = max(w, 0.0);\n"
            + "  float l = length(q);\n"
            + "  if (max(w.x, w.y) > 0.0 && l > 0.0) return s * q / l;\n"
            + "  return s * (w.x > w.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 center = (shape.xy + shape.zw) * 0.5;\n"
            + "  float2 halfSize = (shape.zw - shape.xy) * 0.5;\n"
            + "  float2 p = coord - center;\n"
            + "  float d = sdf(p, halfSize, radius);\n"
            + "  if (d > 1.0) return half4(0.0);\n"
            + "  float lensRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "  float2 n = sdfNormal(p, halfSize, lensRadius);\n"
            + "  float depth = 1.0 - clamp(-d / refractionHeight, 0.0, 1.0);\n"
            + "  float dome = 1.0 - sqrt(1.0 - depth * depth);\n"
            + "  float2 sampleCoord = coord - n * dome * refractionAmount;\n"
            + "  half4 color;\n"
            + "  if (dispersion > 0.0) {\n"
            + "    float2 spread = n * dome * dispersion;\n"
            + "    half4 g = content.eval(sampleCoord);\n"
            + "    color = half4(content.eval(sampleCoord + spread).r, g.g,\n"
            + "        content.eval(sampleCoord - spread).b, g.a);\n"
            + "  } else {\n"
            + "    color = content.eval(sampleCoord);\n"
            + "  }\n"
            + "  half lum = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));\n"
            + "  color.rgb = clamp(mix(half3(lum), color.rgb, saturation), 0.0, 1.0);\n"
            + "  color.rgb = mix(color.rgb, tint.rgb, tint.a);\n"
            + "  float edge = 1.0 - smoothstep(0.0, 1.5, -d);\n"
            + "  float light = abs(dot(n, float2(0.7071, -0.7071)));\n"
            + "  color.rgb += half3(edge * (0.35 + 0.65 * light) * rim);\n"
            + "  float inset = 1.0 - smoothstep(0.0, max(innerShadowWidth, 0.001), -d);\n"
            + "  color.rgb *= 1.0 - innerShadow * inset * inset;\n"
            + "  float alpha = 1.0 - smoothstep(-0.75, 0.75, d);\n"
            + "  return half4(clamp(color.rgb, 0.0, 1.0), 1.0) * alpha;\n"
            + "}\n";

    private final RuntimeShader mShader = new RuntimeShader(SHADER);

    {
        mShader.setFloatUniform("innerShadow", 0f);
        mShader.setFloatUniform("innerShadowWidth", 1f);
    }

    public LiquidGlass setInnerShadow(float width, float strength) {
        mShader.setFloatUniform("innerShadowWidth", Math.max(width, 0.001f));
        mShader.setFloatUniform("innerShadow", strength);
        return this;
    }
    private float mBlurRadius;

    public static final @ColorInt int TINT_LIGHT = 0x99F2F2F7;
    public static final @ColorInt int TINT_DARK = 0x991C1C1E;

    public LiquidGlass setShape(float left, float top, float right, float bottom, float radius) {
        mShader.setFloatUniform("shape", left, top, right, bottom);
        mShader.setFloatUniform("radius", radius);
        return this;
    }

    public LiquidGlass setLens(float height, float amount, float dispersion) {
        mShader.setFloatUniform("refractionHeight", Math.max(height, 1f));
        mShader.setFloatUniform("refractionAmount", amount);
        mShader.setFloatUniform("dispersion", dispersion);
        return this;
    }

    public LiquidGlass setLook(float blurRadius, float saturation, float rim, @ColorInt int tint) {
        mBlurRadius = blurRadius;
        mShader.setFloatUniform("saturation", saturation);
        mShader.setFloatUniform("rim", rim);
        mShader.setColorUniform("tint", tint);
        return this;
    }

    public RenderEffect build() {
        RenderEffect lens = RenderEffect.createRuntimeShaderEffect(mShader, "content");
        if (mBlurRadius > 0f) {
            lens = RenderEffect.createChainEffect(lens,
                    RenderEffect.createBlurEffect(mBlurRadius, mBlurRadius, Shader.TileMode.CLAMP));
        }
        return lens;
    }

    public void applyTo(View view) {
        view.setBackdropRenderEffect(build());
    }

    public static void removeFrom(View view) {
        view.setBackdropRenderEffect(null);
    }
}
