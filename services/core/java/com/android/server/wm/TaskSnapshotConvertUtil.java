/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.util.Slog;
import android.window.TaskSnapshot;

/**
 * Utility class providing methods to convert snapshots between Bitmap formats and/or scale them.
 */
public class TaskSnapshotConvertUtil {

    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "TaskSnapshotResolutionConvert" : TAG_WM;

    static TaskSnapshot convertSnapshotToLowRes(TaskSnapshot snapshot, float scaleFactor) {
        final Bitmap swBitmap = copySWBitmap(snapshot);
        if (swBitmap == null) {
            return null;
        }
        final int width = snapshot.getHardwareBufferWidth();
        final int height = snapshot.getHardwareBufferHeight();
        final Bitmap lowResBitmap = Bitmap.createScaledBitmap(swBitmap,
                (int) (width * scaleFactor),
                (int) (height * scaleFactor),
                true /* filter */);
        swBitmap.recycle();
        final TaskSnapshot lowResSnapshot = convertLowResSnapshot(snapshot, lowResBitmap);
        lowResBitmap.recycle();
        return lowResSnapshot;
    }

    static boolean mustPersistByHardwareRender(@NonNull TaskSnapshot snapshot) {
        final int pixelFormat = snapshot.getHardwareBufferFormat();
        final boolean hasProtectedContent = snapshot.hasProtectedContent();
        return (pixelFormat != PixelFormat.RGB_565 && pixelFormat != PixelFormat.RGBA_8888)
                || !snapshot.isRealSnapshot()
                || hasProtectedContent;
    }

    static TaskSnapshot convertLowResSnapshot(TaskSnapshot sourceSnapshot,
            Bitmap lowBitmap) {
        final Bitmap hwBitmap = lowBitmap.copy(Bitmap.Config.HARDWARE, false);
        if (hwBitmap == null) {
            Slog.w(TAG, "Failed to create hardware bitmap");
            return null;
        }
        final HardwareBuffer buffer = hwBitmap.getHardwareBuffer();
        final TaskSnapshot lowResSnapshot = new TaskSnapshot(sourceSnapshot.getId(),
                sourceSnapshot.getCaptureTime(), sourceSnapshot.getTopActivityComponent(), buffer,
                hwBitmap.getColorSpace(), sourceSnapshot.getOrientation(),
                sourceSnapshot.getRotation(), sourceSnapshot.getTaskSize(),
                sourceSnapshot.getContentInsets(), sourceSnapshot.getLetterboxInsets(),
                true /* isLowResolution */, sourceSnapshot.isRealSnapshot(),
                sourceSnapshot.getWindowingMode(), sourceSnapshot.getAppearance(),
                sourceSnapshot.isTranslucent(), sourceSnapshot.hasImeSurface(),
                sourceSnapshot.getUiMode(), sourceSnapshot.getDensityDpi());
        hwBitmap.recycle();
        return lowResSnapshot;
    }

    static Bitmap copySWBitmap(TaskSnapshot snapshot) {
        final int width = snapshot.getHardwareBufferWidth();
        final int height = snapshot.getHardwareBufferHeight();
        final int pixelFormat = snapshot.getHardwareBufferFormat();
        return mustPersistByHardwareRender(snapshot)
                ? copyToSwBitmapReadBack(snapshot)
                : copyToSwBitmapDirect(width, height, pixelFormat, snapshot);
    }

    private static Bitmap copyToSwBitmapReadBack(TaskSnapshot snapshot) {
        final Bitmap bitmap = snapshot.wrapToBitmap();
        if (bitmap == null) {
            Slog.e(TAG, "Invalid task snapshot hw bitmap");
            return null;
        }

        final Bitmap swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false /* isMutable */);
        if (swBitmap == null) {
            Slog.e(TAG, "Bitmap conversion from (config=" + bitmap.getConfig()
                    + ", isMutable=" + bitmap.isMutable()
                    + ") to (config=ARGB_8888, isMutable=false) failed.");
            return null;
        }
        bitmap.recycle();
        return swBitmap;
    }

    /**
     * Use ImageReader to create the software bitmap, so SkImage won't create an extra texture.
     */
    private static Bitmap copyToSwBitmapDirect(int width, int height, int pixelFormat,
            TaskSnapshot snapshot) {
        try (ImageReader ir = ImageReader.newInstance(width, height,
                pixelFormat, 1 /* maxImages */)) {
            snapshot.attachAndQueueBufferWithColorSpace(ir.getSurface());
            try (Image image = ir.acquireNextImage()) {
                if (image == null || image.getPlaneCount() < 1) {
                    Slog.e(TAG, "Image reader cannot acquire image");
                    return null;
                }

                final Image.Plane[] planes = image.getPlanes();
                if (planes.length != 1) {
                    Slog.e(TAG, "Image reader cannot get plane");
                    return null;
                }
                final Image.Plane plane = planes[0];
                final int rowPadding = plane.getRowStride() - plane.getPixelStride()
                        * image.getWidth();
                final int widthPadding = rowPadding / plane.getPixelStride();
                final Bitmap swBitmap = Bitmap.createBitmap(
                        image.getWidth() + widthPadding /* width */,
                        image.getHeight() /* height */,
                        pixelFormat == PixelFormat.RGB_565
                                ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
                swBitmap.copyPixelsFromBuffer(plane.getBuffer());
                if (widthPadding == 0) {
                    return swBitmap;
                }
                // Crop the full memory width of the image data (rowStride), which often includes
                // extra padding bytes on the side for memory alignment.
                final Bitmap finalBitmap = Bitmap.createBitmap(swBitmap, 0 /* x */, 0 /* y */,
                        width, // Crop to the required width of the image.
                        height);
                swBitmap.recycle();
                return finalBitmap;
            }
        }
    }
}
