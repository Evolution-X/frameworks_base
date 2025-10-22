/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wallpapers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.DisplayMetrics;
import android.util.Log;
import java.io.ByteArrayOutputStream;

public class WallpaperUtils {

    private static final String TAG = "WallpaperUtils";

    public static Bitmap resizeAndCompress(Bitmap bitmap, Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        context.getDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;
        float maxScale = 1.10f;
        int targetWidth = Math.round(screenWidth * maxScale);
        int targetHeight = Math.round(screenHeight * maxScale);
        if (bitmap.getWidth() != targetWidth || bitmap.getHeight() != targetHeight) {
            bitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
    }

    public static Bitmap getDimmedBitmap(Bitmap bitmap, int dimLevel) {
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        float dimFactor = 1 - (Math.max(0, Math.min(dimLevel, 100)) / 100f);
        Bitmap dimmedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dimmedBitmap);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setScale(dimFactor, dimFactor, dimFactor, 1.0f);
        ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(colorFilter);
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return dimmedBitmap;
    }

    public static Bitmap getBlurredBitmap(Bitmap bitmap, int radius, Context context) {
        RenderScript rs = RenderScript.create(context);
        float scaleFactor = 0.25f;
        int scaledWidth = Math.round(bitmap.getWidth() * scaleFactor);
        int scaledHeight = Math.round(bitmap.getHeight() * scaleFactor);
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
        Bitmap outputBitmap = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        ScriptIntrinsicBlur blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        float blurRadius = Math.min(radius, 25);
        int passes = Math.max(1, radius / 25);
        Allocation input = Allocation.createFromBitmap(rs, scaledBitmap);
        Allocation output = Allocation.createFromBitmap(rs, outputBitmap);
        blurScript.setRadius(blurRadius);
        for (int i = 0; i < passes; i++) {
            blurScript.setInput(input);
            blurScript.forEach(output);
            output.copyTo(outputBitmap);
            input.copyFrom(outputBitmap);
        }
        input.destroy();
        output.destroy();
        blurScript.destroy();
        rs.destroy();
        return Bitmap.createScaledBitmap(outputBitmap, bitmap.getWidth(), bitmap.getHeight(), true);
    }

    public static Bitmap getAtmosphereEffect(Bitmap bitmap, Context context) {
        try {
            RenderScript rs = RenderScript.create(context);
            Bitmap currentBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            final int iterations = 20;
            
            for (int i = 0; i < iterations; i++) {
                Allocation input = Allocation.createFromBitmap(rs, currentBitmap);
                Allocation output = Allocation.createTyped(rs, input.getType());
                ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
                script.setRadius(25f);
                script.setInput(input);
                script.forEach(output);
                output.copyTo(currentBitmap);
                input.destroy();
                output.destroy();
                script.destroy();
            }
            
            rs.destroy();
            return currentBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error applying atmosphere effect", e);
            return bitmap;
        }
    }

    public static Bitmap getChromaticAberrationEffect(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            int[] resultPixels = new int[width * height];
            float aberrationStrength = 3.0f;
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int redX = (int) (x + Math.sin(y * 0.01) * aberrationStrength);
                    int greenX = x;
                    int blueX = (int) (x - Math.sin(y * 0.01) * aberrationStrength);
                    
                    redX = Math.max(0, Math.min(width - 1, redX));
                    blueX = Math.max(0, Math.min(width - 1, blueX));
                    
                    int redPixel = pixels[y * width + redX];
                    int greenPixel = pixels[y * width + greenX];
                    int bluePixel = pixels[y * width + blueX];
                    
                    int r = (redPixel >> 16) & 0xFF;
                    int g = (greenPixel >> 8) & 0xFF;
                    int b = bluePixel & 0xFF;
                    int a = (pixels[y * width + x] >> 24) & 0xFF;
                    
                    resultPixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            result.setPixels(resultPixels, 0, width, 0, 0, width, height);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying chromatic aberration effect", e);
            return bitmap;
        }
    }

    public static Bitmap getVignetteEffect(Bitmap bitmap, float intensity) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(bitmap, 0, 0, null);
            
            Paint paint = new Paint();
            paint.setDither(true);
            RadialGradient gradient = new RadialGradient(
                width / 2f, height / 2f,
                Math.max(width, height) * 0.7f,
                0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP
            );
            paint.setShader(gradient);
            paint.setAlpha((int) (intensity * 255));
            canvas.drawRect(0, 0, width, height, paint);
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying vignette effect", e);
            return bitmap;
        }
    }

    public static Bitmap getPixelationEffect(Bitmap bitmap, int pixelSize) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            for (int y = 0; y < height; y += pixelSize) {
                for (int x = 0; x < width; x += pixelSize) {
                    int sumR = 0, sumG = 0, sumB = 0, sumA = 0, count = 0;
                    
                    for (int dy = 0; dy < pixelSize && y + dy < height; dy++) {
                        for (int dx = 0; dx < pixelSize && x + dx < width; dx++) {
                            int pixel = pixels[(y + dy) * width + (x + dx)];
                            sumA += (pixel >> 24) & 0xFF;
                            sumR += (pixel >> 16) & 0xFF;
                            sumG += (pixel >> 8) & 0xFF;
                            sumB += pixel & 0xFF;
                            count++;
                        }
                    }
                    
                    int avgA = sumA / count;
                    int avgR = sumR / count;
                    int avgG = sumG / count;
                    int avgB = sumB / count;
                    int avgPixel = (avgA << 24) | (avgR << 16) | (avgG << 8) | avgB;
                    
                    for (int dy = 0; dy < pixelSize && y + dy < height; dy++) {
                        for (int dx = 0; dx < pixelSize && x + dx < width; dx++) {
                            result.setPixel(x + dx, y + dy, avgPixel);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying pixelation effect", e);
            return bitmap;
        }
    }

    public static Bitmap getSaturationEffect(Bitmap bitmap, float saturation) {
        try {
            Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(saturation);
            ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(colorMatrix);
            paint.setColorFilter(colorFilter);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying saturation effect", e);
            return bitmap;
        }
    }

    public static Bitmap getSepiaEffect(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int a = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                
                int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int sepiaR = (int) Math.min(255, gray + 100);
                int sepiaG = (int) Math.min(255, gray + 50);
                int sepiaB = (int) Math.max(0, gray - 100);
                
                pixels[i] = (a << 24) | (sepiaR << 16) | (sepiaG << 8) | sepiaB;
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying sepia effect", e);
            return bitmap;
        }
    }

    public static Bitmap getSharpenEffect(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            int[] result = new int[width * height];
            float[] kernel = {0, -1, 0, -1, 5, -1, 0, -1, 0};
            
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    float sumR = 0, sumG = 0, sumB = 0;
                    int idx = 0;
                    
                    for (int ky = -1; ky <= 1; ky++) {
                        for (int kx = -1; kx <= 1; kx++) {
                            int pixel = pixels[(y + ky) * width + (x + kx)];
                            sumR += ((pixel >> 16) & 0xFF) * kernel[idx];
                            sumG += ((pixel >> 8) & 0xFF) * kernel[idx];
                            sumB += (pixel & 0xFF) * kernel[idx];
                            idx++;
                        }
                    }
                    
                    int a = (pixels[y * width + x] >> 24) & 0xFF;
                    int r = (int) Math.max(0, Math.min(255, sumR));
                    int g = (int) Math.max(0, Math.min(255, sumG));
                    int b = (int) Math.max(0, Math.min(255, sumB));
                    
                    result[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            
            Bitmap sharpened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            sharpened.setPixels(result, 0, width, 0, 0, width, height);
            return sharpened;
        } catch (Exception e) {
            Log.e(TAG, "Error applying sharpen effect", e);
            return bitmap;
        }
    }

    public static Bitmap getGrayscaleEffect(Bitmap bitmap) {
        try {
            Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(colorMatrix);
            paint.setColorFilter(colorFilter);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying grayscale effect", e);
            return bitmap;
        }
    }

    public static Bitmap getNegativeEffect(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int a = (pixel >> 24) & 0xFF;
                int r = 255 - ((pixel >> 16) & 0xFF);
                int g = 255 - ((pixel >> 8) & 0xFF);
                int b = 255 - (pixel & 0xFF);
                
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying negative effect", e);
            return bitmap;
        }
    }

    public static Bitmap getRadialBlurEffect(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            float centerX = width / 2f;
            float centerY = height / 2f;
            float maxDistance = (float) Math.sqrt(centerX * centerX + centerY * centerY);
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float distX = x - centerX;
                    float distY = y - centerY;
                    float distance = (float) Math.sqrt(distX * distX + distY * distY);
                    float blurAmount = (distance / maxDistance) * 5;
                    
                    int sumR = 0, sumG = 0, sumB = 0, count = 0;
                    for (int i = -2; i <= 2; i++) {
                        for (int j = -2; j <= 2; j++) {
                            int sampleX = Math.max(0, Math.min(width - 1, x + i));
                            int sampleY = Math.max(0, Math.min(height - 1, y + j));
                            int pixel = pixels[sampleY * width + sampleX];
                            sumR += (pixel >> 16) & 0xFF;
                            sumG += (pixel >> 8) & 0xFF;
                            sumB += pixel & 0xFF;
                            count++;
                        }
                    }
                    
                    int a = (pixels[y * width + x] >> 24) & 0xFF;
                    int r = sumR / count;
                    int g = sumG / count;
                    int b = sumB / count;
                    result.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying radial blur effect", e);
            return bitmap;
        }
    }

    public static Bitmap getPosterizeEffect(Bitmap bitmap, int levels) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            int step = 256 / levels;
            
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int a = (pixel >> 24) & 0xFF;
                int r = ((((pixel >> 16) & 0xFF) / step) * step);
                int g = ((((pixel >> 8) & 0xFF) / step) * step);
                int b = ((pixel & 0xFF) / step) * step;
                
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error applying posterize effect", e);
            return bitmap;
        }
    }
}