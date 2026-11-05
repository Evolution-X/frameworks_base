/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
@TestableLooper.RunWithLooper
public class DozeUiTest extends SysuiTestCase {

    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private DozeMachine mMachine;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private DozeHost mHost;
    @Mock
    private DozeLog mDozeLog;
    private WakeLockFake mWakeLock;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private DozeUi mDozeUi;
    private FakeExecutor mFakeExecutor;
    @Captor
    private ArgumentCaptor<DozeHost.PulseCallback> mPulseCallbackCaptor;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
        DejankUtils.setImmediate(true);

        mHandlerThread = new HandlerThread("DozeUiTest");
        mHandlerThread.start();
        mWakeLock = new WakeLockFake();
        mHandler = mHandlerThread.getThreadHandler();
        mFakeExecutor = new FakeExecutor(new FakeSystemClock());
        mDozeUi = new DozeUi(mContext, mAlarmManager, mWakeLock, mHost, mHandler,
                mHandler, mDozeParameters, mFakeExecutor, mDozeLog);
        mDozeUi.setDozeMachine(mMachine);
    }

    @After
    public void tearDown() throws Exception {
        DejankUtils.setImmediate(false);
        mHandlerThread.quit();
        mHandler = null;
        mHandlerThread = null;
    }

    @Test
    public void pausingAndUnpausingAod_registersTimeTickAfterUnpausing() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);
        mDozeUi.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        clearInvocations(mAlarmManager);

        mDozeUi.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);

        verify(mAlarmManager).setExact(anyInt(), anyLong(), eq("doze_time_tick"), any(), any());
    }

    @Test
    public void attemptToScheduleTimeTickWhileInInvalidStateFails() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);

        verify(mAlarmManager).setExact(anyInt(), anyLong(), eq("doze_time_tick"), any(), any());
        clearInvocations(mAlarmManager);

        // This relys on the fakeExecutor not running so that the unschedule isn't called
        mDozeUi.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        // Now request an errant time tick while in this invalid state
        mDozeUi.scheduleTimeTick();

        verify(mDozeLog).traceTimeTickIgnored(DOZE_AOD_PAUSED);
        verify(mAlarmManager, never())
                .setExact(anyInt(), anyLong(), eq("doze_time_tick"), any(), any());
    }

    @Test
    public void transitionSetsAnimateWakeup_noAlwaysOn() {
        mDozeUi.transitionTo(UNINITIALIZED, DOZE);
        verify(mHost).setAnimateWakeup(eq(false));
    }

    @Test
    public void transitionSetsAnimateWakeup_alwaysOn() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);
        mDozeUi.transitionTo(UNINITIALIZED, DOZE);
        verify(mHost).setAnimateWakeup(eq(true));
    }

    @Test
    public void onPulseStarted_quickPickupRequestsPulsingAuthUI() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);

        when(mMachine.getPulseReason()).thenReturn(DozeLog.REASON_SENSOR_QUICK_PICKUP);
        mDozeUi.transitionTo(DOZE_AOD, DOZE_REQUEST_PULSE);

        capturePulseCallback(DozeLog.REASON_SENSOR_QUICK_PICKUP);

        mPulseCallbackCaptor.getValue().onPulseStarted();
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING_AUTH_UI);
    }

    @Test
    public void onPulseStarted_udfpsLongpressRequestsPulsingWithoutUI() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);

        when(mMachine.getPulseReason()).thenReturn(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS);
        mDozeUi.transitionTo(DOZE_AOD, DOZE_REQUEST_PULSE);

        capturePulseCallback(DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS);

        mPulseCallbackCaptor.getValue().onPulseStarted();
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING_WITHOUT_UI);
    }

    @Test
    public void onPulseStarted_fingerprintPulseShowAuthUI() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);

        when(mMachine.getPulseReason()).thenReturn(
                DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI
        );
        mDozeUi.transitionTo(DOZE_AOD, DOZE_REQUEST_PULSE);

        capturePulseCallback(DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI);

        mPulseCallbackCaptor.getValue().onPulseStarted();
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING_AUTH_UI);
    }

    @Test
    public void onPulseStarted_fingerprintPulseShowFullUI() {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);

        when(mMachine.getPulseReason()).thenReturn(
                DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_FULL_UI
        );
        mDozeUi.transitionTo(DOZE_AOD, DOZE_REQUEST_PULSE);

        capturePulseCallback(DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_FULL_UI);

        mPulseCallbackCaptor.getValue().onPulseStarted();
        verify(mMachine).requestState(DozeMachine.State.DOZE_PULSING);
    }

    private void capturePulseCallback(int pulseReason) {
        verify(mHost).pulseWhileDozing(mPulseCallbackCaptor.capture(), eq(pulseReason));
    }
}
