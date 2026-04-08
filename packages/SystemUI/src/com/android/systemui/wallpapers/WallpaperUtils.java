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
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.util.Random;

public class WallpaperUtils {

    public static Bitmap resizeAndCompress(Bitmap bitmap, Context context) {
        Rect bounds = context.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics()
                .getBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();

        if (bitmap.getWidth() >= screenWidth && bitmap.getHeight() >= screenHeight) {
            return bitmap;
        }

        float scale = Math.max(
                (float) screenWidth / bitmap.getWidth(),
                (float) screenHeight / bitmap.getHeight());
        int targetWidth = Math.round(bitmap.getWidth() * scale);
        int targetHeight = Math.round(bitmap.getHeight() * scale);
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
        float blurRadius = Math.max(1f, Math.min(radius, 150f));

        ImageReader imageReader = ImageReader.newInstance(
                bitmap.getWidth(), bitmap.getHeight(),
                PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);

        HardwareRenderer hardwareRenderer = new HardwareRenderer();
        RenderNode renderNode = new RenderNode("BlurEffect");

        try {
            hardwareRenderer.setSurface(imageReader.getSurface());
            hardwareRenderer.setContentRoot(renderNode);
            renderNode.setPosition(0, 0, imageReader.getWidth(), imageReader.getHeight());

            RenderEffect blurEffect = RenderEffect.createBlurEffect(
                    blurRadius, blurRadius, Shader.TileMode.MIRROR);
            renderNode.setRenderEffect(blurEffect);

            RecordingCanvas canvas = renderNode.beginRecording();
            try {
                canvas.drawBitmap(bitmap, 0f, 0f, null);
            } finally {
                renderNode.endRecording();
            }

            hardwareRenderer.createRenderRequest()
                    .setWaitForPresent(true)
                    .syncAndDraw();

            Image image = imageReader.acquireNextImage();
            HardwareBuffer hardwareBuffer = image.getHardwareBuffer();
            Bitmap result = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                                  .copy(Bitmap.Config.ARGB_8888, false);
            hardwareBuffer.close();
            image.close();
            return result;

        } finally {
            renderNode.discardDisplayList();
            hardwareRenderer.destroy();
            imageReader.close();
        }
    }

    public static Bitmap getFilmGrain(Bitmap bitmap, Context context) {
        Rect bounds = context.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics().getBounds();
        Bitmap working;
        if (bitmap.getWidth() > bounds.width() || bitmap.getHeight() > bounds.height()) {
            working = Bitmap.createScaledBitmap(bitmap, bounds.width(), bounds.height(), true);
        } else {
            working = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        }
        int width = working.getWidth();
        int height = working.getHeight();
        Random random = new Random();
        int[] pixels = new int[width * height];
        working.getPixels(pixels, 0, width, 0, 0, width, height);
        
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
        
        working.setPixels(pixels, 0, width, 0, 0, width, height);
        return working;
    }

    public static Bitmap getChromaticAberrationEffect(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] srcPixels = new int[width * height];
        int[] dstPixels = new int[width * height];
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height);
        
        int offset = 5;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int redX = Math.max(0, Math.min(width - 1, x - offset));
                int blueX = Math.min(width - 1, Math.max(0, x + offset));
                
                int redPixel = srcPixels[y * width + redX];
                int greenPixel = srcPixels[y * width + x];
                int bluePixel = srcPixels[y * width + blueX];
                
                int alpha = (greenPixel >> 24) & 0xff;
                int red = (redPixel >> 16) & 0xff;
                int green = (greenPixel >> 8) & 0xff;
                int blue = bluePixel & 0xff;
                
                dstPixels[y * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPixels, 0, width, 0, 0, width, height);
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
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] srcPixels = new int[width * height];
        int[] dstPixels = new int[width * height];
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height);
        
        float centerX = width / 2f;
        float centerY = height / 2f;
        int samples = 10;
        float strength = 0.05f;
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dirX = x - centerX;
                float dirY = y - centerY;
                float distance = (float) Math.sqrt(dirX * dirX + dirY * dirY);
                
                if (distance == 0) {
                    dstPixels[y * width + x] = srcPixels[y * width + x];
                    continue;
                }
                
                float blurAmount = distance * strength;
                
                float totalRed = 0, totalGreen = 0, totalBlue = 0, totalAlpha = 0;
                
                for (int s = 0; s < samples; s++) {
                    float t = (float) s / samples;
                    int sampleX = (int) (x - dirX * t * blurAmount / distance);
                    int sampleY = (int) (y - dirY * t * blurAmount / distance);
                    
                    if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                        int pixel = srcPixels[sampleY * width + sampleX];
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
                
                dstPixels[y * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPixels, 0, width, 0, 0, width, height);
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
