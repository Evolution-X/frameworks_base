/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.cutoutprogress;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import com.android.systemui.cutoutprogress.ring.CutoutRingView;
import com.android.systemui.statusbar.util.MediaSessionManagerHelper;

public final class MusicRingController {

    private final Context mContext;
    private final Handler mMainHandler;
    private final CutoutRingView mRingView;
    private final MediaSessionManagerHelper mHelper;

    private MusicProgressTracker mTracker;
    private MusicRingColorManager mColorManager;
    private CutoutProgressSettings mSettings;

    private boolean mRunning = false;
    private boolean mIsPlaying = false;
    private float mFraction = 0f;
    private int mColor = 0xFF9C27B0;
    private String mTrackId = "";

    public MusicRingController(Context context, Handler mainHandler,
                               CutoutRingView ringView) {
        mContext = context;
        mMainHandler = mainHandler;
        mRingView = ringView;
        mHelper = MediaSessionManagerHelper.Companion.getInstance(context);
    }

    public void applySettings(CutoutProgressSettings settings) {
        mSettings = settings;
        if (mColorManager != null) {
            mColorManager.setMode(settings.getMusicColorMode());
            mColorManager.setCustomColor(settings.getMusicCustomColor());
        }
        mRingView.applyMusicSettings(
                settings.getMusicOpacity(),
                settings.getMusicStrokeWidthDp(),
                settings.isMusicClockwise(),
                mColor);
    }

    public void start() {
        if (mRunning) return;
        mRunning = true;

        mColorManager = new MusicRingColorManager(mContext, mMainHandler);
        mColorManager.setCallback(color -> {
            mColor = color;
            mRingView.setMusicRingColor(color);
        });
        if (mSettings != null) {
            mColorManager.setMode(mSettings.getMusicColorMode());
            mColorManager.setCustomColor(mSettings.getMusicCustomColor());
        }

        mTracker = new MusicProgressTracker(mHelper, new MusicProgressTracker.Callbacks() {

            @Override
            public void onMusicProgress(float fraction) {
                mFraction = fraction;
                mRingView.setMusicProgress(fraction);
            }

            @Override
            public void onMusicPlayingChanged(boolean isPlaying) {
                mIsPlaying = isPlaying;
                mRingView.setMusicPlaying(isPlaying);
                if (!isPlaying) {
                    mRingView.setMusicProgress(mFraction);
                }
            }

            @Override
            public void onTrackChanged(String title, String artist, long durationMs) {
                mFraction = 0f;
                mRingView.setMusicProgress(0f);

                mTrackId = title + "|" + artist;
                mColorManager.onTrackChanged(mTrackId, null);
            }

            @Override
            public void onAlbumArtChanged(Drawable art) {
                mColorManager.onAlbumArtChanged(art);
            }
        });

        mTracker.start();
    }

    public void stop() {
        if (!mRunning) return;
        mRunning = false;

        if (mTracker != null) {
            mTracker.stop();
            mTracker = null;
        }
        mColorManager = null;

        mRingView.setMusicPlaying(false);
        mRingView.setMusicProgress(0f);
    }

    public boolean isPlaying() {
        return mIsPlaying;
    }
}
