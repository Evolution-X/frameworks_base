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
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.util.Random;

public class WallpaperUtils {

    public static Bitmap resizeAndCompress(Bitmap bitmap, Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        context.getDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;
        float maxScale = 1.10f;
        int targetWidth = Math.round(screenWidth * maxScale);
        int targetHeight = Math.round(screenHeight * maxScale);
        if (bitmap.getWidth() == targetWidth && bitmap.getHeight() == targetHeight) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    public static Bitmap getDimmedBitmap(Bitmap bitmap, int dimLevel) {
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
        RenderScript rs = null;
        Allocation input = null;
        Allocation output = null;
        ScriptIntrinsicBlur blurScript = null;
        Bitmap mutableBitmap = null;
        Bitmap scaledBitmap = null;
        Bitmap outputBitmap = null;
        
        try {
            rs = RenderScript.create(context);
            float scaleFactor = 0.25f;
            int scaledWidth = Math.round(bitmap.getWidth() * scaleFactor);
            int scaledHeight = Math.round(bitmap.getHeight() * scaleFactor);
            
            mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            scaledBitmap = Bitmap.createScaledBitmap(mutableBitmap, scaledWidth, scaledHeight, true);
            outputBitmap = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            
            blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            float blurRadius = Math.min(radius, 25);
            int passes = Math.max(1, radius / 25);
            
            input = Allocation.createFromBitmap(rs, scaledBitmap);
            output = Allocation.createFromBitmap(rs, outputBitmap);
            blurScript.setRadius(blurRadius);
            
            for (int i = 0; i < passes; i++) {
                blurScript.setInput(input);
                blurScript.forEach(output);
                output.copyTo(outputBitmap);
                input.copyFrom(outputBitmap);
            }
            
            Bitmap result = Bitmap.createScaledBitmap(outputBitmap, bitmap.getWidth(), bitmap.getHeight(), true);
            return result;
        } finally {
            if (input != null) input.destroy();
            if (output != null) output.destroy();
            if (blurScript != null) blurScript.destroy();
            if (rs != null) rs.destroy();
            if (mutableBitmap != null) mutableBitmap.recycle();
            if (scaledBitmap != null) scaledBitmap.recycle();
            if (outputBitmap != null) outputBitmap.recycle();
        }
    }

    public static Bitmap getFilmGrain(Bitmap bitmap, Context context) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        Random random = new Random();
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >> 24) & 0xff;
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            
            int noise = random.nextInt(51) - 25;
            red = Math.max(0, Math.min(255, red + noise));
            green = Math.max(0, Math.min(255, green + noise));
            blue = Math.max(0, Math.min(255, blue + noise));
            
            pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        
        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return mutableBitmap;
    }

    public static Bitmap getChromaticAberrationEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        int offset = 5;
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int redX = Math.max(0, Math.min(width - 1, x - offset));
                int blueX = Math.min(width - 1, Math.max(0, x + offset));
                
                int redPixel = pixels[y * width + redX];
                int greenPixel = pixels[y * width + x];
                int bluePixel = pixels[y * width + blueX];
                
                int alpha = (greenPixel >> 24) & 0xff;
                int red = (redPixel >> 16) & 0xff;
                int green = (greenPixel >> 8) & 0xff;
                int blue = bluePixel & 0xff;
                
                result.setPixel(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getVignetteEffect(Bitmap bitmap, float intensity) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        
        float centerX = width / 2f;
        float centerY = height / 2f;
        float maxRadius = (float) Math.sqrt(centerX * centerX + centerY * centerY);
        
        int vignetteAlpha = (int)(intensity * 255) & 0xff;
        RadialGradient gradient = new RadialGradient(
            centerX, centerY, maxRadius,
            new int[]{0x00000000, (vignetteAlpha << 24) | 0x00000000},
            new float[]{0.5f, 1.0f},
            Shader.TileMode.CLAMP
        );
        
        Paint paint = new Paint();
        paint.setShader(gradient);
        canvas.drawRect(0, 0, width, height, paint);
        return mutableBitmap;
    }

    public static Bitmap getPixelationEffect(Bitmap bitmap, int pixelSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        
        int downWidth = width / pixelSize;
        int downHeight = height / pixelSize;
        Bitmap downscaled = Bitmap.createScaledBitmap(bitmap, downWidth, downHeight, false);
        Bitmap upscaled = Bitmap.createScaledBitmap(downscaled, width, height, false);
        
        canvas.drawBitmap(upscaled, 0, 0, null);
        
        downscaled.recycle();
        upscaled.recycle();
        
        return result;
    }

    public static Bitmap getSaturationEffect(Bitmap bitmap, float saturation) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap result = Bitmap.createBitmap(mutableBitmap.getWidth(), mutableBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(saturation);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        
        canvas.drawBitmap(mutableBitmap, 0, 0, paint);
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getSepiaEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap result = Bitmap.createBitmap(mutableBitmap.getWidth(), mutableBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.set(new float[]{
            0.393f, 0.769f, 0.189f, 0, 0,
            0.349f, 0.686f, 0.168f, 0, 0,
            0.272f, 0.534f, 0.131f, 0, 0,
            0, 0, 0, 1, 0
        });
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        
        canvas.drawBitmap(mutableBitmap, 0, 0, paint);
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getSharpenEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        float[] kernel = {
            0, -1, 0,
            -1, 5, -1,
            0, -1, 0
        };
        
        int[] result = new int[width * height];
        System.arraycopy(pixels, 0, result, 0, pixels.length);
        
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                float red = 0, green = 0, blue = 0;
                
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int pixel = pixels[(y + ky) * width + (x + kx)];
                        float kernelVal = kernel[(ky + 1) * 3 + (kx + 1)];
                        
                        red += ((pixel >> 16) & 0xff) * kernelVal;
                        green += ((pixel >> 8) & 0xff) * kernelVal;
                        blue += (pixel & 0xff) * kernelVal;
                    }
                }
                
                int alpha = (pixels[y * width + x] >> 24) & 0xff;
                red = Math.max(0, Math.min(255, red));
                green = Math.max(0, Math.min(255, green));
                blue = Math.max(0, Math.min(255, blue));
                
                result[y * width + x] = (alpha << 24) | ((int)red << 16) | ((int)green << 8) | (int)blue;
            }
        }
        
        mutableBitmap.setPixels(result, 0, width, 0, 0, width, height);
        return mutableBitmap;
    }

    public static Bitmap getGrayscaleEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap result = Bitmap.createBitmap(mutableBitmap.getWidth(), mutableBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        
        canvas.drawBitmap(mutableBitmap, 0, 0, paint);
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getNegativeEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap result = Bitmap.createBitmap(mutableBitmap.getWidth(), mutableBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        
        ColorMatrix colorMatrix = new ColorMatrix(new float[]{
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0
        });
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        
        canvas.drawBitmap(mutableBitmap, 0, 0, paint);
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getRadialBlurEffect(Bitmap bitmap) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        float centerX = width / 2f;
        float centerY = height / 2f;
        int samples = 10;
        float strength = 0.05f;
        
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dirX = x - centerX;
                float dirY = y - centerY;
                float distance = (float) Math.sqrt(dirX * dirX + dirY * dirY);
                
                if (distance == 0) {
                    result.setPixel(x, y, pixels[y * width + x]);
                    continue;
                }
                
                float blurAmount = distance * strength;
                
                float totalRed = 0, totalGreen = 0, totalBlue = 0, totalAlpha = 0;
                
                for (int s = 0; s < samples; s++) {
                    float t = (float) s / samples;
                    int sampleX = (int) (x - dirX * t * blurAmount / distance);
                    int sampleY = (int) (y - dirY * t * blurAmount / distance);
                    
                    if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                        int pixel = pixels[sampleY * width + sampleX];
                        totalAlpha += (pixel >> 24) & 0xff;
                        totalRed += (pixel >> 16) & 0xff;
                        totalGreen += (pixel >> 8) & 0xff;
                        totalBlue += pixel & 0xff;
                    }
                }
                
                int alpha = (int) (totalAlpha / samples);
                int red = (int) (totalRed / samples);
                int green = (int) (totalGreen / samples);
                int blue = (int) (totalBlue / samples);
                
                result.setPixel(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        
        mutableBitmap.recycle();
        return result;
    }

    public static Bitmap getPosterizeEffect(Bitmap bitmap, int levels) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        int step = 256 / levels;
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >> 24) & 0xff;
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            
            red = (red / step) * step;
            green = (green / step) * step;
            blue = (blue / step) * step;
            
            pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        
        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return mutableBitmap;
    }
}