/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import vendor.lineage.powershare.IPowerShare;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerShareTileTest {

    @Test
    public void isPowerShareAlive_nullService_returnsFalse() {
        assertThat(PowerShareTile.isPowerShareAlive(null)).isFalse();
    }

    @Test
    public void isPowerShareAlive_liveBinder_returnsTrue() {
        final IPowerShare powerShare = mock(IPowerShare.class);
        final IBinder binder = mock(IBinder.class);
        when(powerShare.asBinder()).thenReturn(binder);
        when(binder.isBinderAlive()).thenReturn(true);

        assertThat(PowerShareTile.isPowerShareAlive(powerShare)).isTrue();
    }

    @Test
    public void isPowerShareAlive_deadBinder_returnsFalse() {
        final IPowerShare powerShare = mock(IPowerShare.class);
        final IBinder binder = mock(IBinder.class);
        when(powerShare.asBinder()).thenReturn(binder);
        when(binder.isBinderAlive()).thenReturn(false);

        assertThat(PowerShareTile.isPowerShareAlive(powerShare)).isFalse();
    }

    @Test
    public void isPowerShareAlive_nullBinder_returnsFalse() {
        final IPowerShare powerShare = mock(IPowerShare.class);
        when(powerShare.asBinder()).thenReturn(null);

        assertThat(PowerShareTile.isPowerShareAlive(powerShare)).isFalse();
    }
}