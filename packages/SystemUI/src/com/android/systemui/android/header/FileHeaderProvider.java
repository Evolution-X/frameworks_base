/*
 *  Copyright (C) 2018 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.android.header;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

import com.android.systemui.res.R;

public class FileHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "FileHeaderProvider";
    private static final boolean DEBUG = false;
    private static final String HEADER_FILE_NAME = "custom_file_header_image";

    private Context mContext;
    private Drawable mImage = null;
    private String mLastLoadedPath = null;
    private boolean mIsEnabled = false;

    public FileHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public void settingsChanged(Uri uri) {
        String newPath = getCustomHeaderPath();
        if (newPath != null && !newPath.equals(mLastLoadedPath)) {
            cleanupCurrentImage();
            mLastLoadedPath = null;
            if (mIsEnabled && isCustomHeaderEnabled()) {
                loadHeaderImage();
            }
        }
    }
    
    private boolean isCustomHeaderEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
    }
    
    private String getCustomHeaderPath() {
        return Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
                UserHandle.USER_CURRENT);
    }

    @Override
    public void enableProvider() {
        mIsEnabled = true;
        if (isCustomHeaderEnabled()) {
            loadHeaderImage();
            if (mImage instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) mImage;
                if (!animDrawable.isRunning()) {
                    animDrawable.start();
                }
            }
        }
    }

    @Override
    public void disableProvider() {
        mIsEnabled = false;
        if (mImage instanceof AnimatedImageDrawable) {
            try {
                AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) mImage;
                if (animDrawable.isRunning()) {
                    animDrawable.stop();
                }
            } catch (Exception e) {
                if (DEBUG) Log.e(TAG, "Error stopping animation", e);
            }
        }
        cleanupCurrentImage();
    }

    private void cleanupCurrentImage() {
        if (mImage != null) {
            if (mImage instanceof AnimatedImageDrawable) {
                try {
                    AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) mImage;
                    if (animDrawable.isRunning()) {
                        animDrawable.stop();
                    }
                } catch (Exception e) {
                    if (DEBUG) Log.e(TAG, "Error stopping animation during cleanup", e);
                }
            }
            
            if (mImage instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) mImage).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            
            mImage = null;
        }
    }

    private void loadHeaderImage() {
        if (mContext == null) return;
        
        String path = getCustomHeaderPath();
        if (path == null || path.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No custom header path set");
            return;
        }

        if (path.equals(mLastLoadedPath) && mImage != null) {
            if (DEBUG) Log.d(TAG, "Image already loaded for path: " + path);
            return;
        }

        if (!path.equals(mLastLoadedPath)) {
            cleanupCurrentImage();
        }

        mLastLoadedPath = path;

        Uri imageUri = Uri.parse(path);
        String mime = mContext.getContentResolver().getType(imageUri);
        boolean isAnimated = "image/gif".equals(mime) || "image/webp".equals(mime);

        if (isAnimated) {
            if (loadAnimatedImage(imageUri, path)) {
                return;
            }
            if (DEBUG) Log.d(TAG, "Falling back to static image loading");
        }

        loadStaticImage(imageUri, path);
    }

    private boolean loadAnimatedImage(Uri imageUri, String path) {
        try {
            ImageDecoder.Source source =
                    ImageDecoder.createSource(mContext.getContentResolver(), imageUri);
            Drawable drawable = ImageDecoder.decodeDrawable(source);
            
            if (drawable == null) {
                Log.w(TAG, "ImageDecoder returned null drawable");
                return false;
            }
            
            mImage = drawable;
            
            if (drawable instanceof AnimatedImageDrawable && mIsEnabled) {
                AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) drawable;
                animDrawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                if (!animDrawable.isRunning()) {
                    animDrawable.start();
                    if (DEBUG) Log.d(TAG, "Animation started for: " + path);
                }
            }
            
            if (DEBUG) Log.d(TAG, "Animated image loaded successfully: " + path);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "IOException loading animated image: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error loading animated image: " + e.getMessage());
            return false;
        }
    }

    private void loadStaticImage(Uri imageUri, String path) {
        try (InputStream in = mContext.getContentResolver().openInputStream(imageUri)) {
            if (in == null) {
                Log.w(TAG, "Failed to open input stream for: " + path);
                return;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap from: " + path);
                return;
            }

            mImage = new BitmapDrawable(mContext.getResources(), bitmap);
            if (DEBUG) Log.d(TAG, "Static image loaded successfully: " + path);

        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError loading static image: " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "IOException loading static header image: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load static header image: " + e.getMessage());
        }
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (mImage == null && isCustomHeaderEnabled()) {
            loadHeaderImage();
        }
        return mImage;
    }
}
