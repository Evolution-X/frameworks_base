/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockglymps.providers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import com.android.systemui.lockglymps.GlympsProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Local folder provider - reads user images from /sdcard/Glymps.
 *
 * Behavior identical to v1 (jpg/jpeg/png/webp, random pick), reshaped onto the
 * provider contract. Local candidates carry unknown dimensions, so
 * {@link GlympsCandidate#fitsTarget()} accepts them and the engine crops.
 */
public class LocalFolderProvider implements GlympsProvider {

    static final String STORAGE_FOLDER = "Glymps";
    static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"};

    private final Random mRandom = new Random();

    @Override
    public String id() {
        return "local";
    }

    @Override
    public CharSequence displayName(Context context) {
        return "Local folder"; // replaced by resource string during Evolver wiring
    }

    @Override
    public boolean isNetworkSource() {
        return false;
    }

    @Override
    public boolean needsConfiguration(Context context) {
        return listFiles().length == 0;
    }

    @Override
    public List<GlympsCandidate> fetch(GlympsQuery query, int page) {
        List<GlympsCandidate> out = new ArrayList<>();
        File[] files = listFiles();
        for (File file : files) {
            out.add(new GlympsCandidate(id(), file.getName(), "file:" + file.getPath(),
                    0, 0, "", "", ""));
        }
        return out;
    }

    /** One random candidate only, to preserve v1's one-change-one-file behavior. */
    public GlympsCandidate pickRandom() {
        File[] files = listFiles();
        if (files.length == 0) {
            return null;
        }
        File file = files[mRandom.nextInt(files.length)];
        return new GlympsCandidate(id(), file.getName(), "file:" + file.getPath(),
                0, 0, "", "", "");
    }

    @Override
    public Bitmap download(GlympsCandidate candidate) {
        String path = candidate.url;
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        return BitmapFactory.decodeFile(path);
    }

    static File storageDir() {
        return new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
    }

    static File[] listFiles() {
        File dir = storageDir();
        if (!dir.exists() || !dir.isDirectory()) {
            return new File[0];
        }
        File[] files = dir.listFiles((dir1, name) -> {
            String lower = name.toLowerCase();
            for (String ext : EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        });
        return files == null ? new File[0] : files;
    }
}
