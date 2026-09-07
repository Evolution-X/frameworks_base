/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_HIGH;
import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_LOW;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.AUTO_REVERT_THRESHOLD;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.SIGNAL_TYPE_NONE;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.SIGNAL_TYPE_UNKNOWN;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_REJECTED;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_SUPERSEDED;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_UNKNOWN;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_UNTRACKED;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_FUSED;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_LOCATION;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_MANUAL;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_TELEPHONY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.app.timezonedetector.NitzSignal;
import android.app.timezonedetector.TelephonySignal;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.icu.util.TimeZone;
import android.os.HandlerThread;
import android.os.Process;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.TimeZoneChangeRecord;
import com.android.server.timezonedetector.TimeZoneChangeListener.TimeZoneChangeEvent;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

/** White-box unit tests for {@link NotifyingTimeZoneChangeListener}. */
@RunWith(JUnitParamsRunner.class)
public class NotifyingTimeZoneChangeListenerTest {

    @ClassRule public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();

    @Rule(order = 0)
    public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    @Rule(order = 1)
    public final MockitoRule mockito = MockitoJUnit.rule();

    public static List<@TimeZoneDetectorStrategy.Origin Integer> getDetectionOrigins() {
        return List.of(ORIGIN_LOCATION, ORIGIN_TELEPHONY, ORIGIN_FUSED);
    }

    private static final String INTERACT_ACROSS_USERS_FULL_PERMISSION =
            "android.permission.INTERACT_ACROSS_USERS_FULL";

    private static final int PRIMARY_LOCATION_TIME_ZONE_PROVIDER_UID = 12345;

    private UiAutomation mUiAutomation;

    private FakeNotificationManager mNotificationManager;
    private HandlerThread mHandlerThread;
    private TestHandler mHandler;
    private FakeServiceConfigAccessor mServiceConfigAccessor;
    private FakeEnvironment mFakeEnvironment;
    private int mUid;
    private ApplicationInfo mApplicationInfo;
    private NotifyingTimeZoneChangeListener mTimeZoneChangeTracker;
    private FakeTimeZoneDetectorLogger mFakeTimeZoneDetectorLogger;
    private TimeZoneDetectorTelemetry mTimeZoneDetectorTelemetry;

    @Mock private Context mContext;
    @Mock private KeyguardManager mockKeyguardManager;
    @Mock private Resources mResources;
    @Mock private PackageManager mMockPackageManager;

    @Before
    public void setUp() throws Exception {
        mUid = Process.myUid();
        mFakeEnvironment = new FakeEnvironment();
        mFakeEnvironment.initializeClock(1735689600L, 1234L);
        mFakeTimeZoneDetectorLogger = new FakeTimeZoneDetectorLogger();
        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorInternalTest");
        mHandlerThread.start();
        mHandler = new TestHandler(mHandlerThread.getLooper());

        ConfigurationInternal config =
                new ConfigurationInternal.Builder()
                        .setUserId(mUid)
                        .setTelephonyDetectionFeatureSupported(true)
                        .setGeoDetectionFeatureSupported(true)
                        .setTelephonyFallbackSupported(false)
                        .setGeoDetectionRunInBackgroundEnabled(false)
                        .setEnhancedMetricsCollectionEnabled(false)
                        .setUserConfigAllowed(true)
                        .setAutoDetectionEnabledSetting(false)
                        .setLocationEnabledSetting(true)
                        .setGeoDetectionEnabledSetting(false)
                        .setNotificationsSupported(true)
                        .setNotificationsTrackingSupported(true)
                        .setNotificationsEnabledSetting(false)
                        .setManualChangeTrackingSupported(false)
                        .build();

        mServiceConfigAccessor = spy(new FakeServiceConfigAccessor());
        mServiceConfigAccessor.initializeCurrentUserConfiguration(config);

        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL_PERMISSION);

        mNotificationManager = new FakeNotificationManager(mContext, InstantSource.system());
        mApplicationInfo = new ApplicationInfo();

        // Setup mock PackageManager to return a valid UID for the location time zone provider.
        Resources resources = ApplicationProvider.getApplicationContext().getResources();
        String packageName = null;
        try {
            packageName =
                    resources.getString(R.string.config_primaryLocationTimeZoneProviderPackageName);
        } catch (Resources.NotFoundException e) {
            // Do nothing. Location time zone provider is not set on the test device.
        }
        ApplicationInfo mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.uid = PRIMARY_LOCATION_TIME_ZONE_PROVIDER_UID;
        when(mMockPackageManager.getApplicationInfo(packageName, 0)).thenReturn(mApplicationInfo);

        mTimeZoneDetectorTelemetry =
                new TimeZoneDetectorTelemetryImpl(
                        mContext,
                        mFakeEnvironment,
                        mFakeTimeZoneDetectorLogger,
                        mMockPackageManager);

        mTimeZoneChangeTracker =
                new NotifyingTimeZoneChangeListener(
                        mHandler,
                        mContext,
                        mServiceConfigAccessor,
                        mTimeZoneDetectorTelemetry,
                        mNotificationManager,
                        mFakeEnvironment,
                        () -> mockKeyguardManager,
                        mMockPackageManager);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void process_autoDetectionOff_noManualTracking_shouldTrackWithoutNotifying() {
        enableTimeZoneNotifications();

        TimeZoneChangeRecord expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 1,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 0,
                                /* unixEpochTimeMillis= */ 1726597800000L,
                                /* origin= */ ORIGIN_MANUAL,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Paris",
                                /* newZoneId= */ "Europe/London",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());

        assertEquals(
                expectedTimeZoneChangeRecord, mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(0);
    }

    @Test
    public void process_autoDetectionOff_shouldTrackWithoutNotifying() {
        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 1,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 0,
                                /* unixEpochTimeMillis= */ 1726597800000L,
                                /* origin= */ ORIGIN_MANUAL,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Paris",
                                /* newZoneId= */ "Europe/London",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());

        assertEquals(
                expectedTimeZoneChangeRecord, mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else if (origin == ORIGIN_FUSED) {
            enableAllTimeZoneDetectionAlgos();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 1,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 0,
                                /* unixEpochTimeMillis= */ 1726597800000L,
                                /* origin= */ origin,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Paris",
                                /* newZoneId= */ "Europe/London",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
        TimeZoneChangeRecord timeZoneChangeRecord1 =
                mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord1);
        assertEquals(1, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);

        expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 2,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 1000L,
                                /* unixEpochTimeMillis= */ 1726597800000L + 1000L,
                                /* origin= */ origin,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/London",
                                /* newZoneId= */ "Europe/Paris",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        // lastTrackedChangeEvent != null
        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
        TimeZoneChangeRecord timeZoneChangeRecord2 =
                mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(STATUS_SUPERSEDED, timeZoneChangeRecord1.getStatus());
        assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord2);
        assertEquals(2, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(2);

        disableTimeZoneAutoDetection();

        // Test manual change within revert threshold
        {
            expectedTimeZoneChangeRecord =
                    new TimeZoneChangeRecord(
                            /* id= */ 3,
                            new TimeZoneChangeEvent(
                                    /* elapsedRealtimeMillis= */ 999L + AUTO_REVERT_THRESHOLD,
                                    /* unixEpochTimeMillis= */ 1726597800000L
                                            + 999L
                                            + AUTO_REVERT_THRESHOLD,
                                    /* origin= */ ORIGIN_MANUAL,
                                    /* userId= */ mUid,
                                    /* oldZoneId= */ "Europe/Paris",
                                    /* newZoneId= */ "Europe/London",
                                    /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                    /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                    /* cause= */ "NO_REASON"));
            expectedTimeZoneChangeRecord.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

            mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
            TimeZoneChangeRecord timeZoneChangeRecord3 =
                    mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

            // The user manually changed the time zone within a short period of receiving the
            // notification, indicating that they rejected the automatic change of time zone
            assertEquals(STATUS_REJECTED, timeZoneChangeRecord2.getStatus());
            assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord3);
            assertEquals(2, mNotificationManager.getNotifications().size());
            mHandler.assertTotalMessagesEnqueued(3);
        }

        // Test manual change outside of revert threshold
        {
            // [START] Reset previous event
            enableNotificationsWithManualChangeTracking();
            mTimeZoneChangeTracker.process(timeZoneChangeRecord2.getEvent());
            timeZoneChangeRecord2 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();
            disableTimeZoneAutoDetection();
            // [END] Reset previous event

            expectedTimeZoneChangeRecord =
                    new TimeZoneChangeRecord(
                            /* id= */ 5,
                            new TimeZoneChangeEvent(
                                    /* elapsedRealtimeMillis= */ 1001L + AUTO_REVERT_THRESHOLD,
                                    /* unixEpochTimeMillis= */ 1726597800000L
                                            + 1001L
                                            + AUTO_REVERT_THRESHOLD,
                                    /* origin= */ ORIGIN_MANUAL,
                                    /* userId= */ mUid,
                                    /* oldZoneId= */ "Europe/Paris",
                                    /* newZoneId= */ "Europe/London",
                                    /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                    /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                    /* cause= */ "NO_REASON"));
            expectedTimeZoneChangeRecord.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

            mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
            TimeZoneChangeRecord timeZoneChangeRecord3 =
                    mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

            // The user manually changed the time zone outside of the period we consider as a revert
            assertEquals(STATUS_SUPERSEDED, timeZoneChangeRecord2.getStatus());
            assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord3);
            assertEquals(3, mNotificationManager.getNotifications().size());
            mHandler.assertTotalMessagesEnqueued(5);
        }
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported_missingTransition(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else if (origin == ORIGIN_FUSED) {
            enableAllTimeZoneDetectionAlgos();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 1,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 0,
                                /* unixEpochTimeMillis= */ 1726597800000L,
                                /* origin= */ origin,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Paris",
                                /* newZoneId= */ "Europe/London",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
        TimeZoneChangeRecord timeZoneChangeRecord1 =
                mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord1);
        assertEquals(1, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);

        expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 3,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 1000L,
                                /* unixEpochTimeMillis= */ 1726597800000L + 1000L,
                                /* origin= */ origin,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Athens",
                                /* newZoneId= */ "Europe/Paris",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,

                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        // lastTrackedChangeEvent != null
        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
        TimeZoneChangeRecord timeZoneChangeRecord2 =
                mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(STATUS_SUPERSEDED, timeZoneChangeRecord1.getStatus());
        assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord2);
        assertEquals(2, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(2);
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported_sameOffset(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else if (origin == ORIGIN_FUSED) {
            enableAllTimeZoneDetectionAlgos();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedTimeZoneChangeRecord =
                new TimeZoneChangeRecord(
                        /* id= */ 1,
                        new TimeZoneChangeEvent(
                                /* elapsedRealtimeMillis= */ 0,
                                /* unixEpochTimeMillis= */ 1726597800000L,
                                /* origin= */ origin,
                                /* userId= */ mUid,
                                /* oldZoneId= */ "Europe/Paris",
                                /* newZoneId= */ "Europe/Rome",
                                /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                                /* cause= */ "NO_REASON"));
        expectedTimeZoneChangeRecord.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedTimeZoneChangeRecord.getEvent());
        TimeZoneChangeRecord timeZoneChangeRecord1 =
                mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedTimeZoneChangeRecord, timeZoneChangeRecord1);
        // No notification sent for the same UTC offset
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_oldConfidenceIsZero_noNotificationSent(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else if (origin == ORIGIN_FUSED) {
            enableAllTimeZoneDetectionAlgos();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        // Process a first event with zero confidence.
        // We expect this to be tracked but not to generate a notification.
        TimeZoneChangeEvent firstEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_LOW,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_LOW, // Zero confidence

                        /* cause= */ "NO_REASON");

        mTimeZoneChangeTracker.process(firstEvent);

        // Verify the first event was tracked but did not trigger a notification.
        TimeZoneChangeRecord firstRecord = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();
        assertEquals(firstEvent, firstRecord.getEvent());
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);

        // Process a second event with non-zero confidence.
        TimeZoneChangeEvent secondEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L,
                        /* unixEpochTimeMillis= */ 1726597800000L + 1000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/London",
                        /* newZoneId= */ "America/New_York",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_LOW,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "NO_REASON");

        mTimeZoneChangeTracker.process(secondEvent);

        // Verify the second event was tracked but did not trigger a notification.
        TimeZoneChangeRecord secondRecord = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();
        assertEquals(secondEvent, secondRecord.getEvent());
        // No notification sent as the previous event had zero confidence.
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(2);
    }

    @Test
    @EnableFlags(android.timezone.flags.Flags.FLAG_ENABLE_AUTOMATIC_TIME_ZONE_REJECTION_LOGGING)
    public void process_automaticDetection_deviceLocked_defersHeuristic() {
        enableNotificationsWithManualChangeTracking();
        Mockito.when(mockKeyguardManager.isDeviceLocked()).thenReturn(true);

        TimeZoneChangeEvent event =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ ORIGIN_TELEPHONY,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "NO_REASON");

        mTimeZoneChangeTracker.process(event);

        // Verify that the heuristic callback is NOT posted immediately.
        mHandler.assertTotalMessagesEnqueued(0);

        // Simulate unlocking the device.
        Intent userPresentIntent = new Intent(Intent.ACTION_USER_PRESENT);
        mTimeZoneChangeTracker.mUserPresentReceiver.onReceive(mContext, userPresentIntent);

        // Now, the handler message should be enqueued.
        mHandler.assertTotalMessagesEnqueued(1);
    }

    @Test
    @EnableFlags(android.timezone.flags.Flags.FLAG_ENABLE_AUTOMATIC_TIME_ZONE_REJECTION_LOGGING)
    public void process_automaticDetection_keyguardBecomesAvailable() {
        enableNotificationsWithManualChangeTracking();
        KeyguardManager keyguardManager = mockKeyguardManager;
        mockKeyguardManager = null;
        mTimeZoneChangeTracker.process(new TimeZoneChangeEvent(
                0, 1726597800000L, ORIGIN_TELEPHONY, mUid,
                "Europe/Paris", "Europe/London", TIME_ZONE_CONFIDENCE_HIGH,
                TIME_ZONE_CONFIDENCE_HIGH, "NO_REASON"));
        mHandler.assertTotalMessagesEnqueued(1);

        mockKeyguardManager = keyguardManager;
        Mockito.when(mockKeyguardManager.isDeviceLocked()).thenReturn(true);
        mTimeZoneChangeTracker.process(new TimeZoneChangeEvent(
                Duration.ofHours(1).toMillis(), 1726601400000L, ORIGIN_TELEPHONY, mUid,
                "Europe/London", "America/New_York", TIME_ZONE_CONFIDENCE_HIGH,
                TIME_ZONE_CONFIDENCE_HIGH, "NO_REASON"));
        mHandler.assertTotalMessagesEnqueued(1);
        assertNotNull(mTimeZoneChangeTracker.mUserPresentReceiver);
    }

    @Test
    @EnableFlags(android.timezone.flags.Flags.FLAG_ENABLE_AUTOMATIC_TIME_ZONE_REJECTION_LOGGING)
    public void process_automaticDetection_deviceUnlocked_notDefersHeuristic() {
        enableNotificationsWithManualChangeTracking();
        Mockito.when(mockKeyguardManager.isDeviceLocked()).thenReturn(false);

        TimeZoneChangeEvent event =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ ORIGIN_TELEPHONY,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "NO_REASON");

        mTimeZoneChangeTracker.process(event);

        // Verify that the heuristic callback is posted immediately.
        mHandler.assertTotalMessagesEnqueued(1);
    }

    @Test
    @EnableFlags(android.timezone.flags.Flags.FLAG_ENABLE_AUTOMATIC_TIME_ZONE_REJECTION_LOGGING)
    public void process_manualRejection_shouldLogWhenFlagEnabled() throws Exception {
        enableTelephonyTimeZoneDetection();
        enableNotificationsWithManualChangeTracking();

        final String oldZoneId = "Europe/Paris";
        final String autoZoneId = "Europe/London";
        final String manualZoneId = "Europe/Paris";
        final String mcc = "123";
        final String mnc = "456";
        final Duration nitzOffset = Duration.ofHours(1);
        final Duration nitzDstOffset = Duration.ofMinutes(1);

        TelephonyTimeZoneSuggestion telephonyTimeZoneSuggestion =
                createTelephonyTimeZoneSuggestion(
                        /* mcc= */ mcc,
                        /* mnc= */ mnc,
                        /* nitzOffsetSeconds= */ (int) nitzOffset.toMillis(),
                        /* nitzDstOffsetSeconds= */ (int) nitzDstOffset.toMillis());

        // Simulate telephony suggestion which later should be logged through telemetry.
        mTimeZoneDetectorTelemetry.onTelephonyTimeZoneSuggestion(telephonyTimeZoneSuggestion);

        // Make device time zone change, which saves telephony suggestion for rejection logging.
        mTimeZoneDetectorTelemetry.onFusedTimeZoneChanged(autoZoneId);

        TimeZoneChangeEvent autoEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L,
                        /* unixEpochTimeMillis= */ 1726597801000L,
                        /* origin= */ ORIGIN_TELEPHONY,
                        /* userId= */ mUid,
                        /* oldZoneId= */ oldZoneId,
                        /* newZoneId= */ autoZoneId,
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "Auto Change");
        mTimeZoneChangeTracker.process(autoEvent);

        disableTimeZoneAutoDetection();
        TimeZoneChangeEvent manualEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L + AUTO_REVERT_THRESHOLD - 1,
                        /* unixEpochTimeMillis= */ 1726597801000L + AUTO_REVERT_THRESHOLD - 1,
                        /* origin= */ ORIGIN_MANUAL,
                        /* userId= */ mUid,
                        /* oldZoneId= */ autoZoneId,
                        /* newZoneId= */ manualZoneId,
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "Manual Override");

        mTimeZoneChangeTracker.process(manualEvent);

        assertEquals(1, mFakeTimeZoneDetectorLogger.getTimeZoneChangeRejectionLogCount());

        // Assertions on the captured autoEvent
        FakeTimeZoneDetectorLogger.TimeZoneChangeRejectionLog log =
                mFakeTimeZoneDetectorLogger.getTimeZoneChangeRejectionLog(0);

        assertEquals(ORIGIN_TELEPHONY, log.source());
        assertEquals(mcc, log.mcc());
        assertEquals(mnc, log.mnc());
        assertEquals(nitzOffset.toSeconds(), log.nitzOffsetSeconds());
        assertEquals(nitzDstOffset.toSeconds(), log.nitzDstOffsetSeconds());
        assertEquals(oldZoneId, log.previousTimeZoneId());
        assertEquals(autoZoneId, log.rejectedAutomaticTimeZone());
        assertEquals(manualZoneId, log.manualTimeZone());
        assertEquals(TimeZone.getTZDataVersion(), log.tzdbVersion());
        assertEquals(PRIMARY_LOCATION_TIME_ZONE_PROVIDER_UID, log.locationTimeZoneProviderUid());
    }

    @Test
    @DisableFlags(android.timezone.flags.Flags.FLAG_ENABLE_AUTOMATIC_TIME_ZONE_REJECTION_LOGGING)
    public void process_manualRejection_shouldNotLogWhenFlagDisabled() {
        enableTelephonyTimeZoneDetection();
        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeEvent autoEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L,
                        /* unixEpochTimeMillis= */ 1726597801000L,
                        /* origin= */ ORIGIN_TELEPHONY,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "Auto Change");
        mTimeZoneChangeTracker.process(autoEvent);

        disableTimeZoneAutoDetection();
        TimeZoneChangeEvent manualEvent =
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L + AUTO_REVERT_THRESHOLD - 1,
                        /* unixEpochTimeMillis= */ 1726597801000L + AUTO_REVERT_THRESHOLD - 1,
                        /* origin= */ ORIGIN_MANUAL,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/London",
                        /* newZoneId= */ "Europe/Paris",
                        /* oldConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* newConfidence= */ TIME_ZONE_CONFIDENCE_HIGH,
                        /* cause= */ "Manual Override");

        mTimeZoneChangeTracker.process(manualEvent);

        assertEquals(0, mFakeTimeZoneDetectorLogger.getTimeZoneChangeRejectionLogCount());
    }

    private void enableLocationTimeZoneDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setAutoDetectionEnabledSetting(true)
                        .setGeoDetectionFeatureSupported(true)
                        .setGeoDetectionEnabledSetting(true)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableTelephonyTimeZoneDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setAutoDetectionEnabledSetting(true)
                        .setGeoDetectionEnabledSetting(false)
                        .setTelephonyDetectionFeatureSupported(true)
                        .setTelephonyFallbackSupported(true)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableAllTimeZoneDetectionAlgos() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setAutoDetectionEnabledSetting(true)
                        .setGeoDetectionFeatureSupported(true)
                        .setGeoDetectionEnabledSetting(true)
                        .setTelephonyDetectionFeatureSupported(true)
                        .setTelephonyFallbackSupported(true)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableTimeZoneNotifications() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setNotificationsSupported(true)
                        .setNotificationsTrackingSupported(true)
                        .setNotificationsEnabledSetting(true)
                        .setManualChangeTrackingSupported(false)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableNotificationsWithManualChangeTracking() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setNotificationsSupported(true)
                        .setNotificationsTrackingSupported(true)
                        .setNotificationsEnabledSetting(true)
                        .setManualChangeTrackingSupported(true)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void disableTimeZoneAutoDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration =
                toBuilder(oldConfiguration)
                        .setAutoDetectionEnabledSetting(false)
                        .setGeoDetectionEnabledSetting(false)
                        .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private TelephonyTimeZoneSuggestion createTelephonyTimeZoneSuggestion(
            String mcc, String mnc, int nitzOffsetSeconds, int nitzDstOffsetSeconds) {
        return new TelephonyTimeZoneSuggestion.Builder(/* slotIndex= */ 0)
                .setTelephonySignal(
                        new TelephonySignal(
                                mcc,
                                mnc,
                                /* defaultCountryIsoCod= */ "us",
                                /* countryIsoCodes= */ Set.of("us"),
                                new NitzSignal(
                                        /* receiptElapsedMillis= */ 0L,
                                        /* ageMillis= */ 0L,
                                        /* zoneOffset= */ nitzOffsetSeconds,
                                        /* dstOffset= */ nitzDstOffsetSeconds,
                                        /* currentTimeMillis= */ 0L,
                                        /* emulatorHostTimeZone= */ null)))
                .build();
    }

    private ConfigurationInternal.Builder toBuilder(ConfigurationInternal config) {
        return new ConfigurationInternal.Builder()
                .setUserId(config.getUserId())
                .setTelephonyDetectionFeatureSupported(config.isTelephonyDetectionSupported())
                .setGeoDetectionFeatureSupported(config.isGeoDetectionSupported())
                .setTelephonyFallbackSupported(config.isTelephonyFallbackSupported())
                .setGeoDetectionRunInBackgroundEnabled(
                        config.getGeoDetectionRunInBackgroundEnabledSetting())
                .setEnhancedMetricsCollectionEnabled(config.isEnhancedMetricsCollectionEnabled())
                .setUserConfigAllowed(config.isUserConfigAllowed())
                .setAutoDetectionEnabledSetting(config.getAutoDetectionEnabledSetting())
                .setLocationEnabledSetting(config.getLocationEnabledSetting())
                .setGeoDetectionEnabledSetting(config.getGeoDetectionEnabledSetting())
                .setNotificationsTrackingSupported(config.isNotificationTrackingSupported())
                .setNotificationsEnabledSetting(config.getNotificationsEnabledBehavior())
                .setNotificationsSupported(config.areNotificationsSupported())
                .setManualChangeTrackingSupported(config.isManualChangeTrackingSupported());
    }
}
