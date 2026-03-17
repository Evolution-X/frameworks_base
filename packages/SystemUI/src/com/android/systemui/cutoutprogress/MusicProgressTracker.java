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

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Choreographer;

import com.android.systemui.statusbar.util.MediaSessionManagerHelper;

public final class MusicProgressTracker {

    public interface Callbacks {
        void onMusicProgress(float fraction);
        void onMusicPlayingChanged(boolean isPlaying);
        void onTrackChanged(String title, String artist, long durationMs);
        void onAlbumArtChanged(Drawable art);
    }

    private static final long RESYNC_INTERVAL_MS = 2_000L;
    private static final float MIN_SPEED = 0.01f;

    private final MediaSessionManagerHelper mHelper;
    private final Callbacks mCallbacks;
    private final Choreographer mChoreographer;

    private boolean mIsPlaying = false;
    private long mPositionAtSync = 0L;
    private long mElapsedAtSync = 0L;
    private float mPlaybackSpeed = 1f;
    private long mDurationMs = -1L;
    private long mLastResyncMs = 0L;

    private String mLastTrackId = null;
    private Bitmap mLastArtBitmap = null;

    private boolean mFrameScheduled = false;

    private final MediaSessionManagerHelper.MediaMetadataListener mListener =
            new MediaSessionManagerHelper.MediaMetadataListener() {
                @Override
                public void onMediaMetadataChanged() {
                    handleMetadataChanged();
                }

                @Override
                public void onPlaybackStateChanged() {
                    handlePlaybackStateChanged();
                }
            };

    private final Choreographer.FrameCallback mFrameCallback = frameTimeNs -> {
        mFrameScheduled = false;
        if (mIsPlaying && mDurationMs > 0) {
            dispatchInterpolatedProgress();
            scheduleFrame();
        }
    };

    public MusicProgressTracker(MediaSessionManagerHelper helper, Callbacks callbacks) {
        mHelper = helper;
        mCallbacks = callbacks;
        mChoreographer = Choreographer.getInstance();
    }

    public void start() {
        mHelper.addMediaMetadataListener(mListener);
        handleMetadataChanged();
        handlePlaybackStateChanged();
    }

    public void stop() {
        mHelper.removeMediaMetadataListener(mListener);
        stopFrames();
        mIsPlaying = false;
        mLastTrackId = null;
        mLastArtBitmap = null;
    }

    private void handleMetadataChanged() {
        MediaMetadata md = mHelper.getCurrentMediaMetadata();

        long dur  = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : -1L;
        mDurationMs = dur > 0 ? dur : -1L;

        String title = strOrEmpty(md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE)  : null);
        String artist = strOrEmpty(md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null);
        String newId = title + "|" + artist;
        if (!newId.equals(mLastTrackId)) {
            mLastTrackId = newId;
            mCallbacks.onTrackChanged(title, artist, mDurationMs);
        }

        Bitmap art = mHelper.getMediaBitmap();
        if (art != mLastArtBitmap) {
            mLastArtBitmap = art;
            mCallbacks.onAlbumArtChanged(
                    art != null ? new BitmapDrawable(null, art) : null);
        }
    }

    private void handlePlaybackStateChanged() {
        PlaybackState ps = mHelper.getMediaControllerPlaybackState();
        boolean nowPlaying = mHelper.isMediaPlaying();

        if (nowPlaying) {
            if (ps != null) syncFromPlaybackState(ps);
            if (!mIsPlaying) {
                mIsPlaying = true;
                mCallbacks.onMusicPlayingChanged(true);
                scheduleFrame();
            }
        } else {
            if (mDurationMs > 0 && ps != null) {
                mCallbacks.onMusicProgress(fraction(ps.getPosition(), mDurationMs));
            }
            if (mIsPlaying) {
                mIsPlaying = false;
                mCallbacks.onMusicPlayingChanged(false);
            }
            stopFrames();
        }
    }

    private void syncFromPlaybackState(PlaybackState ps) {
        mPositionAtSync = ps.getPosition();
        long psTime = ps.getLastPositionUpdateTime();
        mElapsedAtSync = psTime > 0 ? psTime : SystemClock.elapsedRealtime();
        float speed = ps.getPlaybackSpeed();
        mPlaybackSpeed = speed > MIN_SPEED ? speed : 1f;
        mLastResyncMs = SystemClock.elapsedRealtime();
    }

    private void dispatchInterpolatedProgress() {
        if (mDurationMs <= 0) return;

        long nowMs = SystemClock.elapsedRealtime();
        long pos = mPositionAtSync + (long)((nowMs - mElapsedAtSync) * mPlaybackSpeed);

        if (nowMs - mLastResyncMs >= RESYNC_INTERVAL_MS) {
            PlaybackState ps = mHelper.getMediaControllerPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                syncFromPlaybackState(ps);
                pos = mPositionAtSync;
            }
        }

        mCallbacks.onMusicProgress(fraction(pos, mDurationMs));
    }

    private void scheduleFrame() {
        if (!mFrameScheduled && mIsPlaying && mDurationMs > 0) {
            mFrameScheduled = true;
            mChoreographer.postFrameCallback(mFrameCallback);
        }
    }

    private void stopFrames() {
        mChoreographer.removeFrameCallback(mFrameCallback);
        mFrameScheduled = false;
    }

    private static float fraction(long posMs, long durMs) {
        if (durMs <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) posMs / durMs));
    }

    private static String strOrEmpty(String s) {
        return s != null ? s : "";
    }
}
