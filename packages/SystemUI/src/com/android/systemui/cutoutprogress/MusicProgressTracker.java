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
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.android.systemui.util.MediaSessionManagerHelper;

import java.util.Objects;

public final class MusicProgressTracker {

    public interface Callbacks {
        void onMusicProgress(float fraction);
        void onMusicPlayingChanged(boolean isPlaying);
        void onTrackChanged(String trackId, String title, String artist, long durationMs);
        void onAlbumArtChanged(Drawable art);
    }

    private static final long RESYNC_INTERVAL_MS = 2_000L;
    private static final long UPDATE_INTERVAL_INTERACTIVE_MS = 33L; // ~30 Hz
    private static final long UPDATE_INTERVAL_AMBIENT_MS = 1000L;
    private static final float MIN_SPEED = 0.01f;

    private final MediaSessionManagerHelper mHelper;
    private final Callbacks mCallbacks;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PowerManager mPowerManager;

    private boolean mIsPlaying = false;
    private long mPositionAtSync = 0L;
    private long mElapsedAtSync = 0L;
    private float mPlaybackSpeed = 1f;
    private long mDurationMs = -1L;
    private long mLastResyncMs = 0L;

    private String mLastTrackId = null;
    private Bitmap mLastArtBitmap = null;

    private boolean mFrameScheduled = false;
    private boolean mStarted = false;

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

    private final Runnable mProgressTick = new Runnable() {
        @Override
        public void run() {
            mFrameScheduled = false;
            if (mStarted && mIsPlaying && mDurationMs > 0) {
                dispatchInterpolatedProgress();
                scheduleFrame();
            }
        }
    };

    public MusicProgressTracker(Context context, MediaSessionManagerHelper helper,
                                Callbacks callbacks) {
        mHelper = helper;
        mCallbacks = callbacks;
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    public void start() {
        if (mStarted) return;
        mStarted = true;
        // MediaSessionManagerHelper immediately dispatches the current metadata/playback state
        // from addMediaMetadataListener(); do not process both snapshots twice.
        mHelper.addMediaMetadataListener(mListener);
    }

    public void stop() {
        if (!mStarted) return;
        mStarted = false;
        mHelper.removeMediaMetadataListener(mListener);
        stopFrames();
        mIsPlaying = false;
        mLastTrackId = null;
        mLastArtBitmap = null;
    }

    private void handleMetadataChanged() {
        if (!mStarted) return;
        MediaMetadata md = mHelper.getCurrentMediaMetadata();

        long dur  = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : -1L;
        mDurationMs = dur > 0 ? dur : -1L;

        String title = strOrEmpty(md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null);
        String artist = strOrEmpty(md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null);
        String newId = buildTrackId(md, title, artist);
        boolean trackChanged = !Objects.equals(newId, mLastTrackId);
        if (trackChanged) {
            mLastTrackId = newId;
            // Some media apps reuse the same mutable Bitmap object across tracks. Force artwork
            // re-evaluation when track identity changes even if object identity does not.
            mLastArtBitmap = null;
            mCallbacks.onTrackChanged(newId, title, artist, mDurationMs);
        }

        // Playback state may arrive before metadata/duration. If the duration becomes known later,
        // recover the progress scheduler without waiting for another playback-state callback.
        if (mIsPlaying && mDurationMs > 0) {
            scheduleFrame();
        } else if (mDurationMs <= 0) {
            stopFrames();
        }

        Bitmap art = mHelper.getMediaBitmap();
        if (art != mLastArtBitmap) {
            mLastArtBitmap = art;
            mCallbacks.onAlbumArtChanged(
                    art != null ? new BitmapDrawable(null, art) : null);
        }
    }

    private void handlePlaybackStateChanged() {
        if (!mStarted) return;
        PlaybackState ps = mHelper.getMediaControllerPlaybackState();
        boolean nowPlaying = mHelper.isMediaPlaying();

        if (nowPlaying) {
            if (ps != null) syncFromPlaybackState(ps);
            if (!mIsPlaying) {
                mIsPlaying = true;
                mCallbacks.onMusicPlayingChanged(true);
            }
            scheduleFrame();
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
        if (!Float.isFinite(speed)) {
            mPlaybackSpeed = 1f;
        } else if (Math.abs(speed) < MIN_SPEED) {
            mPlaybackSpeed = 0f;
        } else {
            mPlaybackSpeed = Math.max(-8f, Math.min(8f, speed));
        }
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
        if (!mFrameScheduled && mStarted && mIsPlaying && mDurationMs > 0) {
            mFrameScheduled = true;
            long delay = mPowerManager != null && !mPowerManager.isInteractive()
                    ? UPDATE_INTERVAL_AMBIENT_MS
                    : UPDATE_INTERVAL_INTERACTIVE_MS;
            mMainHandler.postDelayed(mProgressTick, delay);
        }
    }

    private void stopFrames() {
        mMainHandler.removeCallbacks(mProgressTick);
        mFrameScheduled = false;
    }

    private static String buildTrackId(MediaMetadata md, String title, String artist) {
        if (md == null) return null;
        String mediaId = strOrEmpty(md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (!mediaId.isEmpty()) return "id:" + mediaId;

        String mediaUri = strOrEmpty(md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI));
        if (!mediaUri.isEmpty()) return "uri:" + mediaUri;

        String album = strOrEmpty(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        String albumArtist = strOrEmpty(md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        long disc = md.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER);
        long track = md.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER);
        return "meta:" + title + "|" + artist + "|" + album + "|" + albumArtist
                + "|" + disc + "|" + track;
    }

    private static float fraction(long posMs, long durMs) {
        if (durMs <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) posMs / durMs));
    }

    private static String strOrEmpty(String s) {
        return s != null ? s : "";
    }
}
