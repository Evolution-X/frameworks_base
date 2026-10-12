/*
 * Copyright (C) 2026 The Evolution X Project
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

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Regression coverage for the wake-lock lifecycle and duration cycling. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class CaffeineTileTest extends SysuiTestCase {
    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock private QSHost mHost;
    @Mock private QsEventLogger mUiEventLogger;

    private CaffeineTile mTile;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        mTestableLooper = TestableLooper.get(this);
        when(mHost.getContext()).thenReturn(mContext);

        mTile = new CaffeineTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mock(MetricsLogger.class),
                mock(StatusBarStateController.class),
                mock(ActivityStarter.class),
                mock(QSLogger.class));
        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        // Cancel any finite timer before draining the test looper.
        mTile.handleScreenOff();
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void initiallyInactive() {
        assertThat(state().state).isEqualTo(Tile.STATE_INACTIVE);
        assertThat(state().secondaryLabel).isNull();
    }

    @Test
    public void repeatedClicksCycleThroughDurationsAndStop() {
        mTile.handleClick(null);
        assertRemainingMinutes(5);
        mTile.handleClick(null);
        assertRemainingMinutes(10);
        mTile.handleClick(null);
        assertRemainingMinutes(30);
        mTile.handleClick(null);
        assertThat(state().label).isEqualTo("∞");
        mTile.handleClick(null);
        assertThat(state().state).isEqualTo(Tile.STATE_INACTIVE);
        assertThat(state().secondaryLabel).isNull();
    }

    @Test
    public void longClickActivatesInfinityAndNextClickStops() {
        mTile.handleLongClick(null);
        assertThat(state().state).isEqualTo(Tile.STATE_ACTIVE);
        assertThat(state().label).isEqualTo("∞");
        assertThat(state().handlesLongClick).isTrue();

        mTile.handleClick(null);
        assertThat(state().state).isEqualTo(Tile.STATE_INACTIVE);
    }

    @Test
    public void screenOffImmediatelyStopsFiniteAndInfiniteModes() {
        mTile.handleClick(null);
        mTile.handleScreenOff();
        assertThat(state().state).isEqualTo(Tile.STATE_INACTIVE);

        mTile.handleLongClick(null);
        mTile.handleScreenOff();
        assertThat(state().state).isEqualTo(Tile.STATE_INACTIVE);
        assertThat(state().secondaryLabel).isNull();
    }

    private void assertRemainingMinutes(int minutes) {
        final BooleanState current = state();
        assertThat(current.state).isEqualTo(Tile.STATE_ACTIVE);
        final String label = String.valueOf(current.label);
        assertThat(current.secondaryLabel).isEqualTo(
                mContext.getString(com.android.systemui.res.R.string.quick_settings_caffeine_label));
        // Accept a one-second scheduling delay and the test device's numeral locale.
        final String expected = String.format("%02d:", minutes);
        final String previous = String.format("%02d:", minutes - 1);
        assertThat(label.startsWith(expected) || label.startsWith(previous)).isTrue();
    }

    private BooleanState state() {
        final BooleanState state = new BooleanState();
        mTile.handleUpdateState(state, null);
        return state;
    }
}
