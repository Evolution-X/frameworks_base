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

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsFetcher {

    private static final String TAG = "LyricsFetcher";
    private static final boolean DEBUG = false;

    private static final long POSITION_POLL_INTERVAL_MS = 1000L;
    private static final String LRCLIB_URL = "https://lrclib.net/api/search?q=";
    private static final long RETRY_DELAY_MS = 4000L;
    private static final int MAX_RETRIES = 2;

    private static volatile LyricsFetcher sInstance;

    public static synchronized LyricsFetcher getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LyricsFetcher(context.getApplicationContext());
        }
        return sInstance;
    }

    private static final Pattern LRC_LINE_PATTERN =
            Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)");

    private static final Pattern SEPARATOR_REGEX =
            Pattern.compile("\\s+[-\u2013\u2014|\u2022]\\s+");
    private static final Pattern PARENTHETICAL_REGEX = Pattern.compile(
            "(?i)\\s*[\\(\\[]([^\\)\\]]*(?:feat|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded)[^\\)\\]]*)[\\]\\)]");
    private static final Pattern FEAT_REGEX =
            Pattern.compile("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*");
    private static final Pattern ARTIST_SPLIT_REGEX =
            Pattern.compile("(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+");

    public static class LyricLine {
        public final long timestampMs;
        public final String text;

        public LyricLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }

    public interface Callback {
        void onSyncedLineChanged(String prevLine, String currentLine, String nextLine);
        void onPlainLyricsAvailable(String plainLyrics);
        void onLyricsCleared();
    }

    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread mWorkerThread;
    private final Handler mWorkerHandler;

    private final Set<Callback> mCallbacks = new CopyOnWriteArraySet<>();
    private boolean mStarted;

    private MediaController mActiveController;
    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            dispatchPlaybackUpdate();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            maybeFetchForCurrentMetadata();
        }

        @Override
        public void onSessionDestroyed() {
            detachActiveController();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            controllers -> attachBestController(controllers);

    private List<LyricLine> mCurrentLines = Collections.emptyList();
    private String mLastFetchedSong;
    private String mLastFetchedArtist;
    private String mLastPlainLyrics;
    private int mFetchGeneration = 0;
    private int mLastActiveIndex = -1;
    private boolean mPolling;
    private boolean mPlainLyricsDelivered;
    private int mRetryCount = 0;

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            dispatchPlaybackUpdate();
            if (mPolling) {
                mMainHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS);
            }
        }
    };

    private LyricsFetcher(Context context) {
        mContext = context.getApplicationContext();
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mWorkerThread = new HandlerThread("LyricsFetcher-worker", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
    }

    public void addCallback(Callback callback) {
        mMainHandler.post(() -> {
            mCallbacks.add(callback);
            if (!mStarted) {
                mStarted = true;
                startInternal();
            } else {
                replayCurrentStateTo(callback);
            }
        });
    }


    public void removeCallback(Callback callback) {
        mMainHandler.post(() -> {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty() && mStarted) {
                mStarted = false;
                stopInternal();
            }
        });
    }

    private void replayCurrentStateTo(Callback callback) {
        if (!mCurrentLines.isEmpty()) {
            if (mLastActiveIndex >= 0) {
                String prev = mLastActiveIndex > 0
                        ? mCurrentLines.get(mLastActiveIndex - 1).text : null;
                String current = mCurrentLines.get(mLastActiveIndex).text;
                String next = mLastActiveIndex + 1 < mCurrentLines.size()
                        ? mCurrentLines.get(mLastActiveIndex + 1).text : null;
                callback.onSyncedLineChanged(prev, current, next);
            }
        } else if (mPlainLyricsDelivered && mLastPlainLyrics != null) {
            callback.onPlainLyricsAvailable(mLastPlainLyrics);
        }
    }

    private void startInternal() {
        if (mMediaSessionManager == null) return;
        ComponentName listenerComponent =
                new ComponentName(mContext, com.android.systemui.statusbar.NotificationListener.class);
        try {
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                    mSessionsChangedListener, listenerComponent, mMainHandler);
            attachBestController(mMediaSessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            Log.e(TAG, "Missing notification listener access", e);
        }
    }

    private void stopInternal() {
        if (mMediaSessionManager != null) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
        }
        detachActiveController();
    }

    private void attachBestController(List<MediaController> controllers) {
        MediaController best = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                PlaybackState state = c.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && state != null) {
                    best = c;
                }
            }
        }

        if (best == null) {
            detachActiveController();
            return;
        }
        if (mActiveController != null
                && TextUtils.equals(mActiveController.getPackageName(), best.getPackageName())
                && mActiveController.getSessionToken().equals(best.getSessionToken())) {
            return;
        }

        detachActiveController();
        mActiveController = best;
        mActiveController.registerCallback(mControllerCallback, mMainHandler);
        maybeFetchForCurrentMetadata();
        dispatchPlaybackUpdate();
    }

    private void detachActiveController() {
        if (mActiveController != null) {
            mActiveController.unregisterCallback(mControllerCallback);
            mActiveController = null;
        }
        stopPolling();
        clearLyrics();
    }

    private void clearLyrics() {
        mCurrentLines = Collections.emptyList();
        mLastFetchedSong = null;
        mLastFetchedArtist = null;
        mLastPlainLyrics = null;
        mLastActiveIndex = -1;
        mPlainLyricsDelivered = false;
        mMainHandler.post(() -> {
            for (Callback cb : mCallbacks) cb.onLyricsCleared();
        });
    }

    private boolean isSupportedPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("amazonmusic")
        || p.contains("apple")
        || p.contains("archivetune")
        || p.contains("deezer")
        || p.contains("music")
        || p.contains("pandora")
        || p.contains("spotify")
        || p.contains("youtube")
        || p.contains("youtubemusic")
        || p.contains("vanced")
        || p.contains("rvx");
    }

    private void maybeFetchForCurrentMetadata() {
        if (mActiveController == null) return;
        MediaMetadata metadata = mActiveController.getMetadata();
        if (metadata == null) {
            clearLyrics();
            return;
        }
        String song = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String pkg = mActiveController.getPackageName();

        if (TextUtils.isEmpty(song) || TextUtils.isEmpty(artist) || !isSupportedPackage(pkg)) {
            clearLyrics();
            return;
        }
        if (TextUtils.equals(song, mLastFetchedSong) && TextUtils.equals(artist, mLastFetchedArtist)) {
            return; // already fetched / fetching this track
        }
        mLastFetchedSong = song;
        mLastFetchedArtist = artist;
        mCurrentLines = Collections.emptyList();
        mLastActiveIndex = -1;
        mPlainLyricsDelivered = false;
        mRetryCount = 0;

        final int generation = ++mFetchGeneration;
        final String cleanArtist = cleanArtistName(artist);
        final String cleanSong = cleanSongTitle(song);
        mWorkerHandler.post(() -> fetchLyrics(cleanArtist, cleanSong, generation));
    }

    private void fetchLyrics(String artist, String song, int generation) {
        List<LyricLine> parsedSynced = Collections.emptyList();
        String plainLyrics = null;
        boolean failed = false;
        HttpURLConnection conn = null;
        try {
            String query = URLEncoder.encode(artist + " " + song, "UTF-8");
            URL url = new URL(LRCLIB_URL + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "SystemUI-LunarisLyrics/1.0 (https://github.com/Lunaris-AOSP)");

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONArray results = new JSONArray(sb.toString());
                if (results.length() > 0) {
                    JSONObject best = results.getJSONObject(0);
                    String syncedLyrics = best.optString("syncedLyrics", "");
                    String plain = best.optString("plainLyrics", "");
                    if (!TextUtils.isEmpty(syncedLyrics)) {
                        parsedSynced = parseLrc(syncedLyrics);
                    }
                    if (!TextUtils.isEmpty(plain)) {
                        plainLyrics = plain;
                    }
                }
                } else {
                failed = true;
            }
        } catch (Exception e) {
            failed = true;
            if (DEBUG) Log.e(TAG, "Failed to fetch lyrics", e);
            } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        final List<LyricLine> resultSynced = parsedSynced;
        final String resultPlain = plainLyrics;
        final boolean didFail = failed && resultSynced.isEmpty() && TextUtils.isEmpty(resultPlain);
        mMainHandler.post(() -> {
            if (generation != mFetchGeneration) return;
            mCurrentLines = resultSynced;

            if (!mCurrentLines.isEmpty()) {
                startPolling();
                dispatchPlaybackUpdate();
            } else if (!TextUtils.isEmpty(resultPlain)) {
                stopPolling();
                mPlainLyricsDelivered = true;
                mLastPlainLyrics = resultPlain;
                for (Callback cb : mCallbacks) cb.onPlainLyricsAvailable(resultPlain);
            } else if (didFail && mRetryCount < MAX_RETRIES) {
                mRetryCount++;
                mMainHandler.postDelayed(() -> {
                    if (generation != mFetchGeneration) return;
                    mWorkerHandler.post(() -> fetchLyrics(artist, song, generation));
                }, RETRY_DELAY_MS);
            } else {
                stopPolling();
                for (Callback cb : mCallbacks) cb.onLyricsCleared();
            }
        });
    }

    private void startPolling() {
        if (mPolling) return;
        mPolling = true;
        mMainHandler.post(mPollRunnable);
    }

    private void stopPolling() {
        mPolling = false;
        mMainHandler.removeCallbacks(mPollRunnable);
    }

    private void dispatchPlaybackUpdate() {
        if (mActiveController == null || mCurrentLines.isEmpty()) return;
        PlaybackState state = mActiveController.getPlaybackState();
        if (state == null) return;

        int playbackState = state.getState();
        boolean isPlaying = playbackState == PlaybackState.STATE_PLAYING;
        boolean isStoppedOrIdle = playbackState == PlaybackState.STATE_STOPPED
                || playbackState == PlaybackState.STATE_NONE
                || playbackState == PlaybackState.STATE_ERROR;

        if (!isPlaying) {
            if (mLastActiveIndex != -1) {
                mLastActiveIndex = -1;
                for (Callback cb : mCallbacks) cb.onSyncedLineChanged(null, null, null);
            }
            if (isStoppedOrIdle) {
                stopPolling();
            }
            return;
        }

        if (!mPolling) {
            startPolling();
        }

        long positionMs = state.getPosition();
        long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
        positionMs += (long) (elapsed * state.getPlaybackSpeed());
        positionMs = Math.max(0, positionMs);

        int activeIndex = -1;
        for (int i = 0; i < mCurrentLines.size(); i++) {
            if (positionMs >= mCurrentLines.get(i).timestampMs) {
                activeIndex = i;
            } else {
                break;
            }
        }

        if (activeIndex != mLastActiveIndex) {
            mLastActiveIndex = activeIndex;
            String prev = activeIndex > 0
                    ? mCurrentLines.get(activeIndex - 1).text : null;
            String current = activeIndex >= 0
                    ? mCurrentLines.get(activeIndex).text : null;
            String next = activeIndex >= 0 && activeIndex + 1 < mCurrentLines.size()
                    ? mCurrentLines.get(activeIndex + 1).text : null;
            String prevOut = TextUtils.isEmpty(prev) ? null : prev;
            String currentOut = TextUtils.isEmpty(current) ? null : current;
            String nextOut = TextUtils.isEmpty(next) ? null : next;
            for (Callback cb : mCallbacks) {
                cb.onSyncedLineChanged(prevOut, currentOut, nextOut);
            }
        }
    }

    private static List<LyricLine> parseLrc(String lrcText) {
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : lrcText.split("\n")) {
            Matcher m = LRC_LINE_PATTERN.matcher(raw.trim());
            if (m.matches()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String fracStr = m.group(3);
                long fracMs = 0;
                if (fracStr != null && !fracStr.isEmpty()) {
                    long frac = Long.parseLong(fracStr);
                    fracMs = fracStr.length() == 3 ? frac : frac * 10L;
                }
                long timestampMs = (min * 60 + sec) * 1000L + fracMs;
                String text = m.group(4).trim();
                if (!text.isEmpty() || !lines.isEmpty()) {
                    lines.add(new LyricLine(timestampMs, text));
                }
            }
        }
        Collections.sort(lines, Comparator.comparingLong(l -> l.timestampMs));
        return lines;
    }

    private static String cleanSongTitle(String title) {
        if (TextUtils.isEmpty(title)) return title;
        String cleaned = SEPARATOR_REGEX.split(title)[0];
        cleaned = PARENTHETICAL_REGEX.matcher(cleaned).replaceAll("");
        cleaned = FEAT_REGEX.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    private static String cleanArtistName(String artist) {
        if (TextUtils.isEmpty(artist)) return artist;
        return ARTIST_SPLIT_REGEX.split(artist)[0].trim();
    }
}