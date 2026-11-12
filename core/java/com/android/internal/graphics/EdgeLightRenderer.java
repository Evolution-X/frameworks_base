/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.graphics;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.SweepGradient;

/**
 * Edge lighting geometry shared by SystemUI and the Settings preview. Motion is driven by a
 * caller-owned clock, so a notification pulse needs neither audio capture nor an overlay service.
 * All dimensions are relative to the supplied viewport; stroke width is supplied in pixels.
 * @hide
 */
public final class EdgeLightRenderer {
    private static final float TAU = (float) (Math.PI * 2);
    private static final String[] STYLES = {
            "default", "rounded", "chase_around", "mirror_rain", "corner_out", "fire_dance",
            "side_ripples", "flat_ripples", "flat_bars", "side_bars", "flat_streaks",
            "side_streaks", "side_flutter", "flat_flutter", "flat_waves", "side_waves",
            "waves_around", "lights_around", "bars_around", "side_braids", "side_lights",
            "serial_lights", "serials_around", "snow_particles", "bubble_particles",
            "dot_particles", "strings_around",
    };
    private static final int[] RAINBOW = {
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
            0xFF0000FF, 0xFF4B0082, 0xFF9400D3, 0xFFFF0000,
    };
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final Path mFrame = new Path();
    private final Path mClip = new Path();
    private final Path[] mStrokes = {new Path(), new Path(), new Path()};
    private final Path[] mFills = {new Path(), new Path(), new Path()};
    private final PathMeasure mPerimeter = new PathMeasure();
    private final PathMeasure[] mSides = {new PathMeasure(), new PathMeasure()};
    private final PathMeasure[] mFlats = {new PathMeasure(), new PathMeasure()};
    private final Path mSegment = new Path();
    private final float[] mPosition = new float[2];
    private final float[] mTangent = new float[2];
    private final float[] mRadii = new float[8];
    private final float[] mInsetRadii = new float[8];
    private final float[] mCorners = new float[4];
    private final float[] mLayerWidths = new float[3];
    private SweepGradient mRainbow;
    private String mStyle = "default";
    private String mEffect = "none";
    private float mWidth;
    private float mHeight;
    private float mStroke = 8;
    private float mInset;
    private float mLength = 0.35f;
    private float mSize = 0.3f;
    private float mSpread;
    private float mIntensity;
    private int mDensity = 50;
    private int mDirection;
    private int mColor = 0xFFFFFFFF;
    private boolean mUseRainbow;
    private boolean mGeometryDirty = true;

    public static String normalizeStyle(String style) {
        style = style == null ? null : style.trim();
        if ("frame".equalsIgnoreCase(style)) return "rounded";
        for (String candidate : STYLES) {
            if (candidate.equalsIgnoreCase(style)) return candidate;
        }
        return "default";
    }

    public static String normalizeEffect(String effect) {
        if ("breathing".equals(effect) || "wave".equals(effect) || "chase".equals(effect)
                || "sparkle".equals(effect) || "comet".equals(effect)) return effect;
        return "none";
    }

    public static boolean isDesign(String style) {
        String value = normalizeStyle(style);
        return !"default".equals(value) && !"rounded".equals(value);
    }

    public static boolean usesStrokeWidth(String style) {
        String value = normalizeStyle(style);
        return !"side_flutter".equals(value) && !"flat_flutter".equals(value)
                && !"flat_waves".equals(value) && !"side_waves".equals(value)
                && !"waves_around".equals(value);
    }

    /** Pulse spacing shared by the finite ROM pulse and the repeating Settings preview. */
    public static float pulseAlpha(float progress, int pulseCount) {
        int count = Math.max(1, Math.min(5, pulseCount));
        int segments = Math.max(3, count);
        float position = clamp(progress, 0, 1) * segments;
        int segment = Math.min(segments - 1, (int) position);
        boolean active = false;
        for (int i = 0; i < count; i++) {
            if ((int) ((i + 0.5f) * segments / count) == segment) active = true;
        }
        if (!active) return 0;
        float fraction = position - segment;
        return clamp(Math.min(fraction, 1 - fraction) / 0.2f, 0, 1);
    }

    public void configure(String style, String effect, float strokeWidth, int length, int size,
            int density, int direction, float spread, float intensity) {
        mStyle = normalizeStyle(style);
        boolean design = isDesign(mStyle);
        mEffect = design ? "none" : normalizeEffect(effect);
        float stroke = Math.max(0.5f, strokeWidth);
        if (mStroke != stroke) mGeometryDirty = true;
        mStroke = stroke;
        mLength = design ? clamp(length / 100f, 0.05f, 1f) : 0.35f;
        mSize = design ? clamp(size / 100f, 0.05f, 1f) : 0.3f;
        mDensity = design ? Math.max(10, Math.min(100, density)) : 50;
        mDirection = design && direction == 1 ? 1 : 0;
        mSpread = clamp(spread, 0, 0.6f);
        mIntensity = clamp(intensity, 0, 1);
    }

    public void setColor(int color, boolean rainbow) {
        mColor = color;
        mUseRainbow = rainbow;
    }

    /** Radii are ordered top-left, top-right, bottom-right, bottom-left, as for Path. */
    public void setBounds(float width, float height, float[] radii) {
        boolean changed = mWidth != width || mHeight != height;
        mWidth = Math.max(0, width);
        mHeight = Math.max(0, height);
        float maxRadius = Math.min(mWidth, mHeight) / 2;
        for (int i = 0; i < 8; i++) {
            float radius = radii == null ? 0 : clamp(radii[i], 0, maxRadius);
            changed |= radius != mRadii[i];
            mRadii[i] = radius;
        }
        mGeometryDirty |= changed;
    }

    /** One cycle is two seconds at normal speed. No per-frame randomness or retained particles. */
    public void draw(Canvas canvas, float cycle) {
        if (mWidth <= 1 || mHeight <= 1) return;
        if (mGeometryDirty) rebuildGeometry();
        float phase = wrap(mDirection == 1 ? -cycle : cycle);
        float breath = "breathing".equals(mEffect) ? 0.7f + 0.6f * pulse(phase) : 1;
        for (int i = 0; i < 3; i++) {
            mStrokes[i].rewind();
            mFills[i].rewind();
            mLayerWidths[i] = mStroke * breath;
        }
        buildDesign(phase);
        int save = canvas.save();
        canvas.clipPath(mClip);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(mColor);
        if (mUseRainbow) {
            mMatrix.setRotate(phase * 360, mWidth / 2, mHeight / 2);
            mRainbow.setLocalMatrix(mMatrix);
            mPaint.setShader(mRainbow);
        } else {
            mPaint.setShader(null);
        }
        float opacity = "breathing".equals(mEffect) ? 0.6f + 0.4f * pulse(phase) : 1;
        if (mSpread > 0 && mIntensity > 0) {
            float spreadPx = Math.min(mWidth, mHeight) * mSpread * 0.3f;
            for (int pass = 4; pass >= 1; pass--) {
                mPaint.setStyle(Paint.Style.STROKE);
                for (int layer = 2; layer >= 0; layer--) {
                    mPaint.setStrokeWidth(mLayerWidths[layer] + spreadPx * pass / 2);
                    mPaint.setAlpha((int) (255 * mIntensity * opacity * (5 - pass) * 0.025f));
                    canvas.drawPath(mStrokes[layer], mPaint);
                    canvas.drawPath(mFills[layer], mPaint);
                }
            }
        }
        for (int layer = 2; layer >= 0; layer--) {
            mPaint.setAlpha((int) (255 * opacity * (1 - layer * 0.25f)));
            mPaint.setStrokeWidth(mLayerWidths[layer]);
            mPaint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(mStrokes[layer], mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(mFills[layer], mPaint);
        }
        canvas.restoreToCount(save);
    }

    private void rebuildGeometry() {
        float half = Math.min(mStroke / 2, Math.min(mWidth, mHeight) / 4);
        mInset = half;
        mClip.rewind();
        mClip.addRoundRect(0, 0, mWidth, mHeight, mRadii, Path.Direction.CW);
        for (int i = 0; i < 8; i++) mInsetRadii[i] = Math.max(0, mRadii[i] - half);
        mFrame.rewind();
        mFrame.addRoundRect(half, half, mWidth - half, mHeight - half,
                mInsetRadii, Path.Direction.CW);
        mPerimeter.setPath(mFrame, true);
        Path path = mSegment;
        for (int edge = 0; edge < 2; edge++) {
            float x = edge == 0 ? half : mWidth - half;
            path.rewind();
            path.moveTo(x, Math.max(half, mRadii[edge * 2]));
            path.lineTo(x, mHeight - Math.max(half, mRadii[6 - edge * 2]));
            mSides[edge].setPath(path, false);
            float y = edge == 0 ? half : mHeight - half;
            path.rewind();
            path.moveTo(Math.max(half, mRadii[edge == 0 ? 0 : 6]), y);
            path.lineTo(mWidth - Math.max(half, mRadii[edge == 0 ? 2 : 4]), y);
            mFlats[edge].setPath(path, false);
        }
        for (int corner = 0; corner < 4; corner++) {
            float best = Float.MAX_VALUE;
            float targetX = corner == 0 || corner == 3 ? 0 : mWidth;
            float targetY = corner < 2 ? 0 : mHeight;
            for (int i = 0; i < 256; i++) {
                float distance = i * mPerimeter.getLength() / 256;
                mPerimeter.getPosTan(distance, mPosition, null);
                float dx = targetX - mPosition[0];
                float dy = targetY - mPosition[1];
                float score = dx * dx + dy * dy;
                if (score < best) {
                    best = score;
                    mCorners[corner] = distance;
                }
            }
        }
        mRainbow = new SweepGradient(mWidth / 2, mHeight / 2, RAINBOW, null);
        mGeometryDirty = false;
    }

    private void buildDesign(float phase) {
        switch (mStyle) {
            case "default": case "rounded": buildLegacy(phase); break;
            case "chase_around": buildChase(phase); break;
            case "mirror_rain": buildRain(phase, false); break;
            case "fire_dance": buildRain(phase, true); break;
            case "corner_out": buildCorners(phase); break;
            case "side_ripples": buildRipples(phase, false); break;
            case "flat_ripples": buildRipples(phase, true); break;
            case "flat_bars": buildBars(phase, 0); break;
            case "side_bars": buildBars(phase, 1); break;
            case "bars_around": buildBars(phase, 2); break;
            case "flat_streaks": buildStreaks(phase, true); break;
            case "side_streaks": buildStreaks(phase, false); break;
            case "flat_flutter": buildFlutter(phase, true); break;
            case "side_flutter": buildFlutter(phase, false); break;
            case "flat_waves": buildWaves(phase, 0); break;
            case "side_waves": buildWaves(phase, 1); break;
            case "waves_around": buildWaves(phase, 2); break;
            case "strings_around": buildWaves(phase, 3); break;
            case "side_braids": buildWaves(phase, 4); break;
            case "lights_around": buildLights(phase, 0); break;
            case "side_lights": buildLights(phase, 1); break;
            case "serial_lights": buildLights(phase, 2); break;
            case "serials_around": buildLights(phase, 3); break;
            case "snow_particles": buildParticles(phase, 0); break;
            case "bubble_particles": buildParticles(phase, 1); break;
            case "dot_particles": buildParticles(phase, 2); break;
            default: break;
        }
    }

    private void buildLegacy(float phase) {
        boolean frame = "rounded".equals(mStyle);
        switch (mEffect) {
            case "wave": buildWaves(phase, frame ? 3 : 4); return;
            case "sparkle":
                addBase(frame, 2);
                for (int i = 0; i < 24; i++) {
                    float p = wrap(phase + hash(i));
                    point(frame ? mPerimeter : mSides[i % 2], hash(i + 43), 0);
                    circle(mFills[i % 3], mPosition[0], mPosition[1],
                            mStroke * (0.3f + pulse(p)));
                }
                return;
            case "chase": case "comet":
                addBase(frame, 2);
                if (frame) buildChase(phase);
                else buildStreaks(phase, false);
                return;
            default: addBase(frame, 0);
        }
    }

    private void addBase(boolean frame, int layer) {
        if (frame) mStrokes[layer].addPath(mFrame);
        else for (PathMeasure side : mSides) segment(side, 0, 1, mStrokes[layer], false);
    }

    private void buildChase(float phase) {
        int count = "comet".equals(mEffect) ? 1 : 2 + mDensity / 50;
        for (int i = 0; i < count; i++) {
            float head = wrap(phase + i / (float) count);
            for (int layer = 2; layer >= 0; layer--) {
                float tail = mLength * 0.35f * (layer + 1) / 3;
                segment(mPerimeter, head - tail, head, mStrokes[layer], true);
            }
        }
    }

    private void buildCorners(float phase) {
        float perimeter = mPerimeter.getLength();
        int count = 1 + mDensity / 25;
        for (int i = 0; i < count; i++) {
            float p = wrap(phase + i / (float) count);
            float offset = p * 0.12f;
            for (float corner : mCorners) {
                float start = corner / perimeter;
                float length = mLength * 0.12f * (1 - p * 0.5f);
                segment(mPerimeter, start + offset, start + offset + length,
                        mStrokes[i % 3], true);
                segment(mPerimeter, start - offset - length, start - offset,
                        mStrokes[i % 3], true);
            }
        }
    }

    private void buildRain(float phase, boolean fire) {
        int count = 3 + mDensity / 12;
        for (int i = 0; i < count; i++) {
            float p = wrap((fire ? -phase : phase) + i / (float) count);
            float extent = mLength * (fire ? 0.22f : 0.3f);
            for (int edge = 0; edge < 2; edge++) {
                segment(mSides[edge], p - extent, p, mStrokes[i % 3], false);
                if (fire) {
                    point(mSides[edge], p, (edge == 0 ? -1 : 1)
                            * amplitude() * 0.2f * pulse(p + hash(i)));
                    circle(mFills[i % 3], mPosition[0], mPosition[1], mStroke * 0.5f);
                }
            }
        }
    }

    private void buildRipples(float phase, boolean flat) {
        PathMeasure[] edges = flat ? mFlats : mSides;
        int count = 2 + mDensity / 20;
        for (int i = 0; i < count; i++) {
            float p = wrap(phase + i / (float) count);
            float length = mLength * 0.2f * (1 - p * 0.5f);
            for (PathMeasure edge : edges) {
                segment(edge, 0.5f + p * 0.5f - length, 0.5f + p * 0.5f,
                        mStrokes[i % 3], false);
                segment(edge, 0.5f - p * 0.5f, 0.5f - p * 0.5f + length,
                        mStrokes[i % 3], false);
            }
        }
    }

    private void buildBars(float phase, int orientation) {
        int count = 8 + mDensity / 2;
        if (orientation == 2) count *= 2;
        int edges = orientation == 2 ? 1 : 2;
        for (int edge = 0; edge < edges; edge++) {
            PathMeasure measure = orientation == 2 ? mPerimeter
                    : orientation == 0 ? mFlats[edge] : mSides[edge];
            for (int i = 0; i < count; i++) {
                float t = (i + 0.5f) / count;
                float depth = amplitude() * (0.15f + 0.85f * signal(t, phase));
                point(measure, t, 0);
                float nx = -mTangent[1];
                float ny = mTangent[0];
                if (orientation == 0) ny = edge == 0 ? 1 : -1;
                if (orientation == 1) nx = edge == 0 ? 1 : -1;
                Path path = mStrokes[i % 3];
                path.moveTo(mPosition[0], mPosition[1]);
                path.lineTo(mPosition[0] + nx * depth, mPosition[1] + ny * depth);
            }
        }
    }

    private void buildStreaks(float phase, boolean flat) {
        int count = "comet".equals(mEffect) ? 1 : 2 + mDensity / 20;
        for (int edge = 0; edge < 2; edge++) {
            PathMeasure measure = flat ? mFlats[edge] : mSides[edge];
            for (int i = 0; i < count; i++) {
                float head = wrap(phase + i / (float) count);
                if (edge == 1) head = 1 - head;
                for (int layer = 2; layer >= 0; layer--) {
                    float tail = mLength * 0.4f * (layer + 1) / 3;
                    segment(measure, head - tail, head, mStrokes[layer], false);
                }
            }
        }
    }

    private void buildFlutter(float phase, boolean flat) {
        for (int edge = 0; edge < 2; edge++) {
            PathMeasure measure = flat ? mFlats[edge] : mSides[edge];
            for (int layer = 2; layer >= 0; layer--) {
                Path path = mFills[layer];
                outerPoint(measure, 0, flat ? 0 : 1, edge);
                path.moveTo(mPosition[0], mPosition[1]);
                for (int i = 0; i <= 64; i++) {
                    float t = i / 64f;
                    float envelope = (float) Math.sin(Math.PI * t);
                    float depth = amplitude() * envelope * (0.4f + 0.6f
                            * pulse(t * (1 + mDensity / 50f) - phase + layer * 0.15f));
                    outerPoint(measure, t, flat ? 0 : 1, edge);
                    if (flat) mPosition[1] += (edge == 0 ? 1 : -1) * depth;
                    else mPosition[0] += (edge == 0 ? 1 : -1) * depth;
                    path.lineTo(mPosition[0], mPosition[1]);
                }
                outerPoint(measure, 1, flat ? 0 : 1, edge);
                path.lineTo(mPosition[0], mPosition[1]);
                path.close();
            }
        }
    }

    private void buildWaves(float phase, int orientation) {
        boolean around = orientation == 2 || orientation == 3;
        boolean filled = orientation < 3;
        int edges = around ? 1 : 2;
        int samples = around ? 192 : 80;
        for (int edge = 0; edge < edges; edge++) {
            PathMeasure measure = around ? mPerimeter
                    : orientation == 0 ? mFlats[edge] : mSides[edge];
            for (int layer = 2; layer >= 0; layer--) {
                Path path = filled ? mFills[layer] : mStrokes[layer];
                float start = around && filled ? wrap(phase + layer * 0.1f) : 0;
                float span = around && filled ? 0.5f + mLength * 0.4f : 1;
                if (filled) outerPoint(measure, start, orientation, edge);
                else point(measure, start, 0);
                if (filled) path.moveTo(mPosition[0], mPosition[1]);
                for (int i = 0; i <= samples; i++) {
                    float t = i / (float) samples;
                    float cycles = 2 + mDensity / 20f;
                    float wave;
                    if (around && !filled) {
                        int lower = (int) cycles;
                        float mix = cycles - lower;
                        wave = (1 - mix) * pulse(t * lower - phase + layer / 3f)
                                + mix * pulse(t * (lower + 1) - phase + layer / 3f);
                    } else {
                        wave = pulse(t * cycles - phase + layer / 3f);
                    }
                    float depth = amplitude() * wave * (0.4f + layer * 0.25f);
                    if (!around) depth *= (float) Math.sin(Math.PI * t);
                    float fraction = around ? wrap(start + t * span) : t;
                    if (filled) outerPoint(measure, fraction, orientation, edge);
                    else point(measure, fraction, 0);
                    if (around) {
                        mPosition[0] -= mTangent[1] * depth;
                        mPosition[1] += mTangent[0] * depth;
                    } else if (orientation == 0) {
                        mPosition[1] += (edge == 0 ? 1 : -1) * depth;
                    } else {
                        mPosition[0] += (edge == 0 ? 1 : -1) * depth;
                    }
                    if (i == 0 && !filled) path.moveTo(mPosition[0], mPosition[1]);
                    else path.lineTo(mPosition[0], mPosition[1]);
                }
                if (filled) {
                    for (int i = samples; i >= 0; i--) {
                        float t = start + i * span / samples;
                        outerPoint(measure, around ? wrap(t) : Math.min(1, t),
                                orientation, edge);
                        path.lineTo(mPosition[0], mPosition[1]);
                    }
                    path.close();
                } else if (around) path.close();
            }
        }
    }

    private void buildLights(float phase, int kind) {
        if (kind == 0) {
            mStrokes[2].addPath(mFrame);
            buildChase(phase);
            return;
        }
        if (kind == 1) {
            float depth = amplitude() * (0.3f + 0.7f * pulse(phase));
            float extent = mHeight * mLength * 0.5f;
            for (int edge = 0; edge < 2; edge++) {
                float center = mHeight * (0.5f + 0.2f * (float) Math.sin(TAU * phase));
                Path path = mStrokes[edge];
                float x = edge == 0 ? 0 : mWidth;
                path.moveTo(x, center - extent);
                path.cubicTo(x + (edge == 0 ? depth : -depth), center - extent,
                        x + (edge == 0 ? depth : -depth), center + extent,
                        x, center + extent);
            }
            return;
        }
        int count = 12 + mDensity / 2;
        int edges = kind == 3 ? 1 : 2;
        for (int edge = 0; edge < edges; edge++) {
            PathMeasure measure = kind == 3 ? mPerimeter : mSides[edge];
            for (int i = 0; i < count; i++) {
                float t = (i + 0.5f) / count;
                if (kind == 3) t = wrap(t + phase);
                point(measure, t, 0);
                float radius = Math.min(mStroke * (0.3f + mSize * 2 * signal(t, phase)),
                        measure.getLength() / count * 0.4f);
                circle(mFills[i % 3], mPosition[0], mPosition[1], radius);
            }
        }
    }

    private void buildParticles(float phase, int kind) {
        int count = 12 + mDensity / 2;
        float depth = Math.min(mWidth, mHeight) * (0.04f + mLength * 0.18f);
        for (int i = 0; i < count; i++) {
            float age = wrap(phase + hash(i));
            float radius = mStroke * (0.15f + mSize * (kind == 1 ? 2.5f : 0.8f));
            radius *= (0.4f + 0.6f * hash(i + 91)) * (0.2f + 0.8f * pulse(age));
            if (kind == 2) {
                point(mPerimeter, hash(i + 19), depth * age);
            } else {
                float band = depth * hash(i + 19);
                mPosition[0] = i % 2 == 0 ? band + radius : mWidth - band - radius;
                mPosition[0] += (float) Math.sin(TAU * (age + hash(i + 7)))
                        * depth * 0.15f;
                mPosition[1] = (kind == 0 ? age : 1 - age) * mHeight;
            }
            circle(kind == 1 ? mStrokes[i % 3] : mFills[i % 3],
                    mPosition[0], mPosition[1], radius);
        }
    }

    private void point(PathMeasure measure, float fraction, float inward) {
        measure.getPosTan(clamp(fraction, 0, 1) * measure.getLength(), mPosition, mTangent);
        mPosition[0] -= mTangent[1] * inward;
        mPosition[1] += mTangent[0] * inward;
    }

    private void outerPoint(PathMeasure measure, float fraction, int orientation, int edge) {
        float offset = orientation == 0 ? (edge == 0 ? -mInset : mInset)
                : orientation == 1 ? (edge == 0 ? mInset : -mInset) : -mInset;
        point(measure, fraction, offset);
    }

    private void segment(PathMeasure measure, float start, float end, Path out, boolean closed) {
        float length = measure.getLength();
        if (length <= 0 || end <= start) return;
        if (!closed) {
            start = clamp(start, 0, 1);
            end = clamp(end, 0, 1);
            measure.getSegment(start * length, end * length, out, true);
            return;
        }
        float span = Math.min(1, end - start);
        start = wrap(start);
        end = start + span;
        measure.getSegment(start * length, Math.min(1, end) * length, out, true);
        if (end > 1) measure.getSegment(0, (end - 1) * length, out, true);
    }

    private float amplitude() {
        return Math.min(mWidth, mHeight) * (0.01f + mSize * 0.12f);
    }

    private static void circle(Path path, float x, float y, float radius) {
        path.addCircle(x, y, Math.max(0.2f, radius), Path.Direction.CW);
    }

    private static float signal(float t, float phase) {
        return 0.55f * pulse(t * 3 - phase) + 0.3f * pulse(t * 7 + phase * 2)
                + 0.15f * pulse(t * 13 - phase * 3);
    }

    private static float pulse(float phase) {
        return (float) (0.5 - 0.5 * Math.cos(TAU * phase));
    }

    private static float hash(int seed) {
        return wrap((float) Math.sin(seed * 127.1 + 311.7) * 43758.5453f);
    }

    private static float wrap(float value) {
        return value - (float) Math.floor(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
