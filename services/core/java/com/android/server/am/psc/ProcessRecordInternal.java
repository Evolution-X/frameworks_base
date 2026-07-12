/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am.psc;

import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import static com.android.server.am.ProcessList.makeProcStateProtoEnum;
import static com.android.server.am.psc.Constants.CACHED_APP_MIN_ADJ;
import static com.android.server.am.psc.Constants.INVALID_ADJ;
import static com.android.server.am.psc.Constants.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.psc.Constants.SERVICE_B_ADJ;
import static com.android.server.am.psc.Constants.UNKNOWN_ADJ;
import static com.android.server.am.psc.OomAdjuster.CPU_TIME_REASON_NONE;
import static com.android.server.am.psc.OomAdjuster.IMPLICIT_CPU_TIME_REASON_NONE;
import static com.android.server.am.psc.OomAdjusterImpl.ProcessRecordNode.NUM_NODE_TYPE;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_VISIBLE;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.app.ActivityManager.ProcessState;
import android.app.ApplicationExitInfo;
import android.app.ProcessMemoryState.HostingComponentType;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.server.am.Flags;
import com.android.server.am.ProcessOomProto;
import com.android.server.am.psc.Constants.OomAdjust;
import com.android.server.am.psc.Constants.SchedGroup;
import com.android.server.am.psc.PlatformCompatCache.CachedCompatChangeId;

import java.io.PrintWriter;

/** The state info of the process, including proc state, oom adj score, et al. */
public abstract class ProcessRecordInternal {
    /**
     * An observer interface for {@link ProcessRecordInternal} to notify about changes to its
     * internal state fields.
     * TODO(b/429069530): Investigate why WindowManager needs to know any of the mCurXXX value.
     */
    public interface Observer {
        /**
         * Called when mCurRawAdj changes.
         *
         * @param curRawAdj The new mCurRawAdj value.
         */
        void onCurRawAdjChanged(@OomAdjust int curRawAdj);

        /**
         * Called when mCurAdj changes.
         *
         * @param curAdj The new mCurAdj value.
         */
        void onCurAdjChanged(@OomAdjust int curAdj);

        /**
         * Called when mCurSchedGroup changes.
         *
         * @param curSchedGroup The new mCurSchedGroup value.
         */
        void onCurrentSchedulingGroupChanged(@SchedGroup int curSchedGroup);

        /**
         * Called when mCurProcState changes.
         * TODO: b/485394632 - Remove this method after the {@link Flags#encapsulateCurProcState()}
         *                     is enabled.
         *
         * @param curProcState The new mCurProcState value.
         */
        void onCurProcStateChanged(@ProcessState int curProcState);

        /**
         * Called when mRepProcState changes.
         *
         * @param repProcState The new mRepProcState value.
         */
        void onReportedProcStateChanged(@ProcessState int repProcState);

        /**
         * Called when mHasTopUi changes.
         *
         * @param hasTopUi The new mHasTopUi value.
         */
        void onHasTopUiChanged(boolean hasTopUi);

        /**
         * Called when mHasOverlayUi changes.
         *
         * @param hasOverlayUi The new mHasOverlayUi value.
         */
        void onHasOverlayUiChanged(boolean hasOverlayUi);
    }

    /**
     * An observer interface for {@link ProcessRecordInternal} to notify about changes
     * to component-related states like services, receivers, and activities.
     */
    public interface StartedServiceObserver {
        /**
         * Called when mHasStartedServices changes.
         *
         * @param hasStartedServices The new mHasStartedServices value.
         */
        void onHasStartedServicesChanged(boolean hasStartedServices);
    }

    /** Retrieves the last reported PSS (Proportional Set Size) for this process. */
    public abstract long getLastPss();

    /** Retrieves the last reported RSS (Resident Set Size) for this process. */
    public abstract long getLastRss();

    /**
     * Checks if a specific compatibility change is enabled for the process.
     *
     * @param cachedCompatChangeId The ID of the compatibility change to check.
     * @return {@code true} if the change is enabled.
     */
    public abstract boolean hasCompatChange(@CachedCompatChangeId int cachedCompatChangeId);

    /** Returns true if there is an active instrumentation running in this process. */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean hasActiveInstrumentation() {
        return mHasActiveInstrumentation;
    }

    /** Sets whether an active instrumentation is running in this process. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setHasActiveInstrumentation(boolean value) {
        mHasActiveInstrumentation = value;
    }

    /** Returns whether this process is frozen. */
    public abstract boolean isFrozen();

    /** Returns whether this process has been scheduled for freezing. */
    public abstract boolean isPendingFreeze();

    /** Sets whether we would like to clean-up UI resources for this process. */
    public abstract void setPendingUiClean(boolean pendingUiClean);

    /**
     * Returns the UID of the application associated with this process.
     * This may differ from the process's actual UID if it's an isolated process.
     */
    public abstract int getApplicationUid();

    /** Returns the package name of the application this process belongs to. */
    public abstract String getPackageName();

    /** Returns whether this process should not be frozen by the cached app freezer. */
    public boolean isCachedAppFreezerExempt() {
        return false;
    }

    /** Returns whether this process is for an instant app. */
    public abstract boolean isInstantApp();

    /** Returns the {@link UidRecordInternal} associated with this process. */
    public abstract UidRecordInternal getUidRecord();

    /** Returns the internal service-related record for this process. */
    public abstract ProcessServiceRecordInternal getServices();

    /** Returns the internal content provider related record for this process. */
    public abstract ProcessProviderRecordInternal getProviders();

    /** Returns the internal broadcast receiver related record for this process. */
    public abstract ProcessReceiverRecordInternal getReceivers();

    /** Returns the process ID. */
    public abstract int getPid();

    /** Checks if the process is currently running (i.e. has an active thread). */
    public abstract boolean isProcessRunning();

    /** Sends the given process state to the application thread. */
    public abstract void setProcessStateToThread(int state);

    /** Determines if UI scheduling for this process should use FIFO priority. */
    public abstract boolean useFifoUiScheduling();

    /** Notifies the window process controller about a change in top process status. */
    public abstract void notifyTopProcChanged();

    /** Returns an array of package names associated with this process. */
    public abstract String[] getProcessPackageNames();

    /** Checks if this process hosts any packages that should be kept warm. */
    public abstract boolean shouldKeepWarm();

    /** Returns a short string representation of the process. */
    public abstract String toShortString();

    /** Returns the next scheduled time for PSS collection for this process. */
    public abstract long getNextPssTime();

    /** Registers a hosting component type for this process. */
    public abstract void addHostingComponentType(@HostingComponentType int type);

    /** Unregisters a hosting component type for this process. */
    public abstract void clearHostingComponentType(@HostingComponentType int type);

    /**
     * Kills the process with the given reason code, using the provided reason string
     * as both the reason and a default description. The process group is killed
     * asynchronously.
     *
     * @param reason A string describing the reason for the kill.
     * @param reasonCode The reason code for the kill.
     * @param noisy If true, a log message will be reported.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(String reason, @ApplicationExitInfo.Reason int reasonCode,
            boolean noisy) {
        killLocked(reason, reasonCode, ApplicationExitInfo.SUBREASON_UNKNOWN, noisy, true);
    }

    /**
     * Kills the process with the given reason and subreason codes, using the provided
     * reason string as both the reason and a default description. The process group
     * is killed asynchronously.
     *
     * @param reason A string describing the reason for the kill.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(String reason, @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason, boolean noisy) {
        killLocked(reason, reason, reasonCode, subReason, noisy, true);
    }

    /**
     * Kills the process with the given reason code and ANR info, using the provided reason string
     * as both the reason and a default description. The process group is killed asynchronously.
     *
     * @param reason A string describing the reason for the kill.
     * @param reasonCode The reason code for the kill.
     * @param noisy If true, a log message will be reported.
     * @param anrInfo The ANR info to be associated with the exit.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(
            String reason,
            @ApplicationExitInfo.Reason int reasonCode,
            @Nullable ApplicationExitInfo.AnrInfo anrInfo,
            boolean noisy) {
        killLocked(
                reason,
                reason,
                reasonCode,
                ApplicationExitInfo.SUBREASON_UNKNOWN,
                anrInfo,
                noisy,
                true);
    }

    /**
     * Kills the process with the given reason code , subreason, and ANR info, using the provided
     * reason string as both the reason and a default description. The process group is killed
     * asynchronously.
     *
     * @param reason A string describing the reason for the kill.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     * @param anrInfo The ANR info to be associated with the exit.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(
            String reason,
            @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason,
            @Nullable ApplicationExitInfo.AnrInfo anrInfo,
            boolean noisy) {
        killLocked(reason, reason, reasonCode, subReason, anrInfo, noisy, true);
    }

    /**
     * Kills the process with detailed reason information. The process group
     * is killed asynchronously.
     *
     * @param reason A string describing the high-level reason for the kill.
     * @param description A more detailed description of the kill reason.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(String reason, String description,
            @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason, boolean noisy) {
        killLocked(reason, description, reasonCode, subReason, noisy, true);
    }

    /**
     * Kills the process with the given reason and subreason codes, using the provided
     * reason string as both the reason and a default description. Allows control over
     * whether the process group is killed asynchronously.
     *
     * @param reason A string describing the reason for the kill.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     * @param asyncKPG If true, kills the process group asynchronously.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(String reason, @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason, boolean noisy, boolean asyncKPG) {
        killLocked(reason, reason, reasonCode, subReason, noisy, asyncKPG);
    }

    /**
     * Kills the process with the given reason, description, reason codes, and async KPG.
     *
     * @param reason A string describing the reason for the kill.
     * @param description A more detailed description of the kill reason.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     * @param asyncKPG If true, kills the process group asynchronously.
     */
    @GuardedBy("mServiceLock")
    public void killLocked(
            String reason,
            String description,
            @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason,
            boolean noisy,
            boolean asyncKPG) {
        killLocked(
                reason, description, reasonCode, subReason, /* anrInfo= */ null, noisy, asyncKPG);
    }

    /**
     * Kills the process with the given reason, description, reason codes, ANR info, and async KPG.
     *
     * @param reason A string describing the reason for the kill.
     * @param description A more detailed description of the kill reason.
     * @param reasonCode The reason code for the kill.
     * @param subReason The subreason code for the kill.
     * @param noisy If true, a log message will be reported.
     * @param anrInfo The ANR info to be associated with the exit.
     * @param asyncKPG If true, kills the process group asynchronously.
     */
    @GuardedBy("mServiceLock")
    public abstract void killLocked(
            String reason,
            String description,
            @ApplicationExitInfo.Reason int reasonCode,
            @ApplicationExitInfo.SubReason int subReason,
            @Nullable ApplicationExitInfo.AnrInfo anrInfo,
            boolean noisy,
            boolean asyncKPG);

    // Enable this to trace all OomAdjuster state transitions
    private static final boolean TRACE_OOM_ADJ = false;

    public final String processName;
    private String mTrackName;

    /**
     * The process's actual UID.
     * This may differ from {@link #getApplicationUid()} if it's an isolated process.
     */
    public final int uid;
    /**
     * The user ID of the process.
     * This is derived from {@link #uid} using {@link android.os.UserHandle#getUserId(int)}.
     */
    public final int userId;
    public final boolean isolated;     // true if this is a special isolated process
    public final boolean isSdkSandbox; // true if this is an SDK sandbox process

    private Observer mObserver;
    private StartedServiceObserver mStartedServiceObserver;

    // The ActivityManagerService object, which can only be used as a lock object.
    private final Object mServiceLock;
    // The ActivityManagerGlobalLock object, which can only be used as a lock object.
    private final Object mProcLock;

    /** True once we know the process has been killed. */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mKilled;

    /** True when proc has been killed by activity manager, not for RAM. */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mKilledByAm;

    /**
     * Maximum OOM adjustment for this process.
     */
    @GuardedBy("mServiceLock")
    private @OomAdjust int mMaxAdj = UNKNOWN_ADJ;

    /**
     *  Current OOM unlimited adjustment for this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @OomAdjust int mCurRawAdj = INVALID_ADJ;

    /**
     * Last set OOM unlimited adjustment for this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @OomAdjust int mSetRawAdj = INVALID_ADJ;

    /**
     * Current OOM adjustment for this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @OomAdjust int mCurAdj = INVALID_ADJ;

    /**
     * Last set OOM adjustment for this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @OomAdjust int mSetAdj = INVALID_ADJ;

    /**
     * The previously set raw OOM adjustment for this process.
     * This is only meaningful for a restarted process.
     */
    @GuardedBy("mServiceLock")
    private @OomAdjust int mPrevSetRawAdj = INVALID_ADJ;

    /**
     * The current reasons for granting {@link ActivityManager#PROCESS_CAPABILITY_CPU_TIME} to this
     * process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    @OomAdjuster.CpuTimeReasons
    private int mCurCpuTimeReasons = CPU_TIME_REASON_NONE;

    /**
     * The last reasons for granting {@link ActivityManager#PROCESS_CAPABILITY_CPU_TIME} to this
     * process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    @OomAdjuster.CpuTimeReasons
    private int mSetCpuTimeReasons = CPU_TIME_REASON_NONE;

    /**
     * The current reasons for granting {@link ActivityManager#PROCESS_CAPABILITY_IMPLICIT_CPU_TIME}
     * to this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    @OomAdjuster.ImplicitCpuTimeReasons
    private int mCurImplicitCpuTimeReasons = IMPLICIT_CPU_TIME_REASON_NONE;

    /**
     * The last reasons for granting {@link ActivityManager#PROCESS_CAPABILITY_IMPLICIT_CPU_TIME}
     * to this process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    @OomAdjuster.ImplicitCpuTimeReasons
    private int mSetImplicitCpuTimeReasons = IMPLICIT_CPU_TIME_REASON_NONE;

    /**
     * Current capability flags of this process.
     * For example, PROCESS_CAPABILITY_FOREGROUND_LOCATION is one capability.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessCapability int mCurCapability = PROCESS_CAPABILITY_NONE;

    /**
     * Last set capability flags.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessCapability int mSetCapability = PROCESS_CAPABILITY_NONE;

    /**
     * Currently desired scheduling class.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @SchedGroup int mCurSchedGroup = SCHED_GROUP_BACKGROUND;

    /**
     * Last set to background scheduling class.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @SchedGroup int mSetSchedGroup = SCHED_GROUP_BACKGROUND;

    /**
     * Currently computed process state.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessState int mCurProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last reported process state.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessState int mRepProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Temp state during computation.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessState int mCurRawProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last set process state in process tracker.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessState int mSetProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last time mSetProcState changed.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private long mLastStateTime;

    /**
     * Previous priority value before switching to non-{@link Process#SCHED_OTHER}.
     *
     * When a process needs to be moved to a higher priority scheduling group, we first save
     * its current priority, then change the scheduling group and priority. When the process
     * is moved back to the background, we restore the saved priority.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private int mSavedPriority;

    /**
     * Process currently is on the service B list.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mServiceB;

    /**
     * We are forcing to service B list due to its RAM use.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mServiceHighRam;

    /**
     * Are there any started services running in this process?
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mHasStartedServices;

    /**
     * Running any activities that are foreground?
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mHasForegroundActivities;

    /**
     * Last reported foreground activities.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mRepForegroundActivities;

    /**
     * Has UI been shown in this process since it was started?
     */
    @GuardedBy("mServiceLock")
    private boolean mHasShownUi;

    /**
     * Is this process currently showing a non-activity UI that the user
     * is interacting with? E.g. The status bar when it is expanded, but
     * not when it is minimized. When true the
     * process will be set to use the {@link Constants#SCHED_GROUP_TOP_APP}
     * scheduling group to boost performance.
     */
    @GuardedBy("mServiceLock")
    private boolean mHasTopUi;

    /**
     * Is the process currently showing a non-activity UI that
     * overlays on-top of activity UIs on screen. E.g. display a window
     * of type android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
     * When true the process will oom adj score will be set to
     * {@link Constants#PERCEPTIBLE_APP_ADJ} at minimum to reduce the chance
     * of the process getting killed.
     */
    @GuardedBy("mServiceLock")
    private boolean mHasOverlayUi;

    /**
     * Is the process currently running a remote animation? When true
     * the process will be set to use the
     * {@link Constants#SCHED_GROUP_TOP_APP} scheduling group to boost
     * performance, as well as oom adj score will be set to
     * {@link Constants#VISIBLE_APP_ADJ} at minimum to reduce the chance
     * of the process getting killed.
     */
    @GuardedBy("mServiceLock")
    private boolean mRunningRemoteAnimation;

    /**
     * Token that is forcing this process to be important.
     */
    @GuardedBy("mServiceLock")
    private Object mForcingToImportant;

    /**
     * Sequence id for identifying oom_adj assignment cycles.
     */
    @GuardedBy("mServiceLock")
    private int mAdjSeq;

    /**
     * Sequence id for identifying oom_adj assignment cycles.
     */
    @GuardedBy("mServiceLock")
    private int mCompletedAdjSeq;

    /**
     * The last time the process was in the TOP state or greater.
     */
    @GuardedBy("mServiceLock")
    private long mLastTopTime = Long.MIN_VALUE;

    /**
     * This is a system process, but not currently showing UI.
     */
    @GuardedBy("mServiceLock")
    private boolean mSystemNoUi;

    /**
     * Whether or not the app is background restricted (OP_RUN_ANY_IN_BACKGROUND is NOT allowed).
     */
    @GuardedBy("mServiceLock")
    private boolean mBackgroundRestricted = false;

    /**
     * Whether or not this process is being bound by a non-background restricted app.
     */
    @GuardedBy("mServiceLock")
    private boolean mCurBoundByNonBgRestrictedApp = false;

    /**
     * Last set state of {@link #mCurBoundByNonBgRestrictedApp}.
     */
    private boolean mSetBoundByNonBgRestrictedApp = false;

    /**
     * Debugging: primary thing impacting oom_adj.
     */
    @GuardedBy("mServiceLock")
    private String mAdjType;

    /**
     * Debugging: adj code to report to app.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private int mAdjTypeCode;

    /**
     * Debugging: the object that caused the OOM adjustment to be set.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private Object mAdjSource;

    /**
     * Debugging: proc state of mAdjSource's process.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private @ProcessState int mAdjSourceProcState;

    /**
     * Debugging: target component impacting oom_adj.
     */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private Object mAdjTarget;

    /**
     * Approximates the usage count of the app, used for cache re-ranking by CacheOomRanker.
     *
     * Counts the number of times the process is re-added to the cache (i.e. setCached(false);
     * setCached(true)). This over counts, as setCached is sometimes reset while remaining in the
     * cache. However, this happens uniformly across processes, so ranking is not affected.
     */
    @GuardedBy("mServiceLock")
    private int mCacheOomRankerUseCount;

    /**
     * Process memory usage (RSS).
     *
     * Periodically populated by {@code CacheOomRanker}, stored in this object to cache the values.
     */
    @GuardedBy("mServiceLock")
    private long mCacheOomRankerRss;

    /**
     * The last time, in milliseconds since boot, since {@link #mCacheOomRankerRss} was updated.
     */
    @GuardedBy("mServiceLock")
    private long mCacheOomRankerRssTimeMs;

    /**
     * Whether or not this process is reachable from given process.
     */
    @GuardedBy("mServiceLock")
    private boolean mReachable;

    /**
     * The most recent time when the last visible activity within this process became invisible.
     *
     * <p> It'll be set to 0 if there is never a visible activity, or Long.MAX_VALUE if there is
     * any visible activities within this process at this moment.</p>
     */
    @GuardedBy("mServiceLock")
    @ElapsedRealtimeLong
    private long mLastInvisibleTime;

    /**
     * Last set value of {@link #isCached()}.
     */
    @GuardedBy("mServiceLock")
    private boolean mSetCached;

    /**
     * When the proc became cached. Used to debounce killing bg restricted apps in
     * an idle UID.
     */
    @GuardedBy("mServiceLock")
    private @ElapsedRealtimeLong long mLastCachedTime;

    @GuardedBy("mServiceLock")
    private boolean mHasActivities = false;

    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private boolean mHasActiveInstrumentation = false;

    @GuardedBy("mServiceLock")
    private int mActivityStateFlags = ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;

    @GuardedBy("mServiceLock")
    private long mPerceptibleTaskStoppedTimeMillis = Long.MIN_VALUE;

    @GuardedBy("mServiceLock")
    private boolean mHasRecentTask = false;

    /** Process finish attach application is pending. */
    @GuardedBy("mServiceLock")
    private boolean mPendingFinishAttach;

    // Below are the cached task info for OomAdjuster only
    private static final int VALUE_INVALID = -1;
    private static final int VALUE_FALSE = 0;
    private static final int VALUE_TRUE = 1;

    /**
     * Cache the return value of PlatformCompat.isChangeEnabled().
     */
    @GuardedBy("mServiceLock")
    private int[] mCachedCompatChanges = new int[] {
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME
    };

    @GuardedBy("mServiceLock")
    private String mCachedAdjType = null;
    @GuardedBy("mServiceLock")
    private @OomAdjust int mCachedAdj = INVALID_ADJ;
    @GuardedBy("mServiceLock")
    private boolean mCachedForegroundActivities = false;
    @GuardedBy("mServiceLock")
    private @ProcessState int mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
    @GuardedBy("mServiceLock")
    private @SchedGroup int mCachedSchedGroup = SCHED_GROUP_BACKGROUND;

    @GuardedBy("mServiceLock")
    private boolean mScheduleLikeTopApp = false;

    @GuardedBy("mServiceLock")
    private long mFollowupUpdateUptimeMs = Long.MAX_VALUE;

    /** TID for RenderThread. */
    @GuardedBy("mProcLock")
    private int mRenderThreadTid;

    /** Class to run on start if this is a special isolated process. */
    @GuardedBy("mServiceLock")
    private String mIsolatedEntryPoint;

    /** Process is waiting to be killed when in the bg, and reason. */
    @GuardedBy("mServiceLock")
    private String mWaitingToKill;

    /** For managing the LRU list. */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    private long mLastActivityTime;

    /**
     * The node representation of this process in the process graph.
     * This is {@code null} unless {@link Flags#enableCapabilityControllerComputation} is true.
     */
    private final ProcessNode mProcessNode;
    /**
     * The intrinsic edge from the system to this process node in the process graph.
     * This is {@code null} unless {@link Flags#enableCapabilityControllerComputation} is true.
     */
    private final ProcessEdge mProcessEdge;

    // TODO(b/425766486): Change to package-private after the OomAdjusterImpl class is moved to
    //                    the psc package.
    public final OomAdjusterImpl.ProcessRecordNode[] mLinkedNodes =
            new OomAdjusterImpl.ProcessRecordNode[NUM_NODE_TYPE];

    /**
     *  Whether or not the zram memory of this process was written back to disk. Set to true when
     *  the zram memory is successfully written back, and set to false when the process is unfrozen.
     */
    @GuardedBy("mServiceLock")
    private boolean mIsZramWrittenBack = false;

    public ProcessRecordInternal(String processName, int uid, Object serviceLock, Object procLock) {
        this.processName = processName;
        this.uid = uid;
        userId = UserHandle.getUserId(uid);
        isSdkSandbox = Process.isSdkSandboxUid(this.uid);
        isolated = Process.isIsolatedUid(this.uid);
        mServiceLock = serviceLock;
        mProcLock = procLock;

        if (Flags.enableCapabilityControllerComputation()) {
            mProcessNode = new ProcessNode(this);
            mProcessEdge = new ProcessEdge(mProcessNode);
        } else {
            mProcessNode = null;
            mProcessEdge = null;
        }
    }

    /** Initializes the observers and the last time that the state of the process was changed. */
    public void init(Observer observer, StartedServiceObserver startedServiceObserver, long now) {
        mObserver = observer;
        mStartedServiceObserver = startedServiceObserver;
        mLastStateTime = now;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean isKilled() {
        return mKilled;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setKilled(boolean killed) {
        mKilled = killed;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean isKilledByAm() {
        return mKilledByAm;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setKilledByAm(boolean killedByAm) {
        mKilledByAm = killedByAm;
    }


    @GuardedBy("mServiceLock")
    void setMaxAdj(@OomAdjust int maxAdj) {
        mMaxAdj = maxAdj;
    }

    @GuardedBy("mServiceLock")
    public @OomAdjust int getMaxAdj() {
        return mMaxAdj;
    }

    /** Sets the current raw OOM adjustment for this process, and notifies the observer. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurRawAdj(@OomAdjust int curRawAdj) {
        mCurRawAdj = curRawAdj;
        mObserver.onCurRawAdjChanged(mCurRawAdj);
    }

    /**
     * Sets the current raw OOM adjustment for this process.
     *
     * @param curRawAdj The current raw OOM adjustment value.
     * @param dryRun If true, only checks if the adj score would be bumped, without actually
     *               setting it.
     * @return {@code true} if it's a dry run and it's going to bump the adj score of the process
     * if it was a real run.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    boolean setCurRawAdj(@OomAdjust int curRawAdj, boolean dryRun) {
        if (dryRun) {
            return mCurRawAdj > curRawAdj;
        }
        setCurRawAdj(curRawAdj);
        return false;
    }

    /**
     * TODO: b/489901078 - Change the method to package-private.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getCurRawAdj() {
        return mCurRawAdj;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetRawAdj(@OomAdjust int setRawAdj) {
        mSetRawAdj = setRawAdj;
    }

    /**
     * TODO: b/489901078 - Change the method to package-private.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getSetRawAdj() {
        return mSetRawAdj;
    }

    /** Sets the current OOM adjustment for this process, and notifies the observer. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurAdj(@OomAdjust int curAdj) {
        mCurAdj = curAdj;
        mObserver.onCurAdjChanged(mCurAdj);
    }

    /**
     * TODO: b/489901078 - Change the method to package-private.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getCurAdj() {
        return mCurAdj;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetAdj(@OomAdjust int setAdj) {
        mSetAdj = setAdj;
    }

    /**
     * TODO: b/489901078 - Switch to the {@link #getOomAdj()} method after the
     *                     {@link Flags#encapsulateCurOomAdj()} is enabled.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getSetAdj() {
        return mSetAdj;
    }

    /**
     * Returns the OOM adjustment for usage outside the psc package.
     *
     * <p>If {@link Flags#encapsulateCurOomAdj()} is enabled, it returns the stable
     * {@link #getSetAdj()}; otherwise, it returns the transient {@link #getCurAdj()}.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getOomAdj() {
        if (Flags.encapsulateCurOomAdj()) {
            return mSetAdj;
        }
        return mCurAdj;
    }

    /**
     * Returns the last set OOM adjustment for this process, potentially adjusted for services.
     * If the process is cached and has started services, it returns {@link SERVICE_B_ADJ}.
     * Otherwise, it returns the normal {@link #getSetAdj()}.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @OomAdjust int getSetAdjWithServices() {
        if (mSetAdj >= CACHED_APP_MIN_ADJ) {
            if (mHasStartedServices) {
                return SERVICE_B_ADJ;
            }
        }
        return mSetAdj;
    }

    @GuardedBy("mServiceLock")
    public @OomAdjust int getPrevSetRawAdj() {
        return mPrevSetRawAdj;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurCapability(@ProcessCapability int curCapability) {
        mCurCapability = curCapability;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessCapability int getCurCapability() {
        return mCurCapability;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetCapability(@ProcessCapability int setCapability) {
        mSetCapability = setCapability;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessCapability int getSetCapability() {
        return mSetCapability;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetCpuTimeReasons(@OomAdjuster.CpuTimeReasons int setCpuTimeReasons) {
        mSetCpuTimeReasons = setCpuTimeReasons;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    @OomAdjuster.CpuTimeReasons
    public int getCurCpuTimeReasons() {
        return mCurCpuTimeReasons;
    }

    /** Add given reasons to mCurCpuTimeReasons. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void addCurCpuTimeReasons(@OomAdjuster.CpuTimeReasons int cpuTimeReasons) {
        mCurCpuTimeReasons |= cpuTimeReasons;
    }

    /** Sets mCurCpuTimeReasons to CPU_TIME_REASON_NONE. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void clearCurCpuTimeReasons() {
        mCurCpuTimeReasons = CPU_TIME_REASON_NONE;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetImplicitCpuTimeReasons(
            @OomAdjuster.ImplicitCpuTimeReasons int setImplicitCpuTimeReasons) {
        mSetImplicitCpuTimeReasons = setImplicitCpuTimeReasons;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    @OomAdjuster.ImplicitCpuTimeReasons
    public int getCurImplicitCpuTimeReasons() {
        return mCurImplicitCpuTimeReasons;
    }

    /** Add given reasons to mCurImplicitCpuTimeReasons. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void addCurImplicitCpuTimeReasons(
            @OomAdjuster.ImplicitCpuTimeReasons int implicitCpuTimeReasons) {
        mCurImplicitCpuTimeReasons |= implicitCpuTimeReasons;
    }

    /** Sets mCurImplicitCpuTimeReasons to IMPLICIT_CPU_TIME_REASON_NONE. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void clearCurImplicitCpuTimeReasons() {
        mCurImplicitCpuTimeReasons = IMPLICIT_CPU_TIME_REASON_NONE;
    }

    /** Sets the current scheduling group for this process, and notifies the observer. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurrentSchedulingGroup(@SchedGroup int curSchedGroup) {
        mCurSchedGroup = curSchedGroup;
        mObserver.onCurrentSchedulingGroupChanged(mCurSchedGroup);
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @SchedGroup int getCurrentSchedulingGroup() {
        return mCurSchedGroup;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetSchedGroup(@SchedGroup int setSchedGroup) {
        mSetSchedGroup = setSchedGroup;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @SchedGroup int getSetSchedGroup() {
        return mSetSchedGroup;
    }

    /** Sets the current process state, and notifies the observer. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurProcState(@ProcessState int curProcState) {
        mCurProcState = curProcState;
        if (!Flags.encapsulateCurProcState()) {
            mObserver.onCurProcStateChanged(mCurProcState);
        }
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    @ProcessState int getCurProcState() {
        return mCurProcState;
    }

    /**
     * Returns the process state for usage outside the psc package.
     *
     * <p>If {@link Flags#encapsulateCurProcState()} is enabled, it returns the stable
     * {@link #getSetProcState()}; otherwise, it returns the transient {@link #getCurProcState()}.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessState int getProcState() {
        if (Flags.encapsulateCurProcState()) {
            return mSetProcState;
        }
        return mCurProcState;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setCurRawProcState(@ProcessState int curRawProcState) {
        mCurRawProcState = curRawProcState;
    }

    /**
     * Sets the current raw process state, with an option for a dry run.
     *
     * @param curRawProcState The current raw process state.
     * @param dryRun If true, only checks if the proc state would be bumped, without actually
     *               setting it.
     * @return {@code true} if it's a dry run and it's going to bump the procstate of the process
     * if it was a real run.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    boolean setCurRawProcState(@ProcessState int curRawProcState, boolean dryRun) {
        if (dryRun) {
            return mCurRawProcState > curRawProcState;
        }
        setCurRawProcState(curRawProcState);
        return false;
    }

    /**
     * TODO: b/485394632 - Make this method package-private.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessState int getCurRawProcState() {
        return mCurRawProcState;
    }

    /** Sets the last reported process state, and notifies the observer. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setReportedProcState(@ProcessState int repProcState) {
        mRepProcState = repProcState;
        mObserver.onReportedProcStateChanged(mRepProcState);
    }

    /**
     * TODO: b/485394632 - Migrate to the {@link #getProcState()} method after the
     *                     {@link Flags#encapsulateCurProcState()} is enabled.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessState int getReportedProcState() {
        return mRepProcState;
    }

    /** Sets the last set process state in the process tracker. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSetProcState(@ProcessState int setProcState) {
        if (ActivityManager.isProcStateCached(mSetProcState)
                && !ActivityManager.isProcStateCached(setProcState)) {
            mCacheOomRankerUseCount++;
        }
        mSetProcState = setProcState;
    }

    /**
     * TODO: b/485394632 - Switch to the {@link #getProcState()} method after the
     *                     {@link Flags#encapsulateCurProcState()} is enabled.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessState int getSetProcState() {
        return mSetProcState;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setLastStateTime(long lastStateTime) {
        mLastStateTime = lastStateTime;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public long getLastStateTime() {
        return mLastStateTime;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setSavedPriority(int savedPriority) {
        mSavedPriority = savedPriority;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public int getSavedPriority() {
        return mSavedPriority;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setServiceB(boolean serviceb) {
        mServiceB = serviceb;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean isServiceB() {
        return mServiceB;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setServiceHighRam(boolean serviceHighRam) {
        mServiceHighRam = serviceHighRam;
    }

    /**
     * Sets whether there are any started services running in this process, and notifies the
     * observer.
     */
    @GuardedBy("mProcLock")
    void setHasStartedServices(boolean hasStartedServices) {
        mHasStartedServices = hasStartedServices;
        mStartedServiceObserver.onHasStartedServicesChanged(mHasStartedServices);
    }

    @GuardedBy("mProcLock")
    public boolean getHasStartedServices() {
        return mHasStartedServices;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setHasForegroundActivities(boolean hasForegroundActivities) {
        mHasForegroundActivities = hasForegroundActivities;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean getHasForegroundActivities() {
        return mHasForegroundActivities;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setRepForegroundActivities(boolean repForegroundActivities) {
        mRepForegroundActivities = repForegroundActivities;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public boolean getHasRepForegroundActivities() {
        return mRepForegroundActivities;
    }

    @GuardedBy("mServiceLock")
    void setHasShownUi(boolean hasShownUi) {
        mHasShownUi = hasShownUi;
    }

    @GuardedBy("mServiceLock")
    public boolean getHasShownUi() {
        return mHasShownUi;
    }

    /** Sets whether this process is currently showing top UI, and notifies the observer. */
    @GuardedBy("mServiceLock")
    void setHasTopUi(boolean hasTopUi) {
        mHasTopUi = hasTopUi;
        mObserver.onHasTopUiChanged(mHasTopUi);
    }

    @GuardedBy("mServiceLock")
    public boolean getHasTopUi() {
        return mHasTopUi;
    }

    /** Sets whether the process is currently showing overlay UI, and notifies the observer. */
    @GuardedBy("mServiceLock")
    void setHasOverlayUi(boolean hasOverlayUi) {
        mHasOverlayUi = hasOverlayUi;
        mObserver.onHasOverlayUiChanged(mHasOverlayUi);
    }

    @GuardedBy("mServiceLock")
    public boolean getHasOverlayUi() {
        return mHasOverlayUi;
    }

    @GuardedBy("mServiceLock")
    public boolean isRunningRemoteAnimation() {
        return mRunningRemoteAnimation;
    }

    @GuardedBy("mServiceLock")
    void setIsRunningRemoteAnimation(boolean runningRemoteAnimation) {
        mRunningRemoteAnimation = runningRemoteAnimation;
    }

    @GuardedBy("mServiceLock")
    void setForcingToImportant(Object forcingToImportant) {
        mForcingToImportant = forcingToImportant;
    }

    @GuardedBy("mServiceLock")
    public Object getForcingToImportant() {
        return mForcingToImportant;
    }

    @GuardedBy("mServiceLock")
    void setAdjSeq(int adjSeq) {
        mAdjSeq = adjSeq;
    }

    @GuardedBy("mServiceLock")
    public int getAdjSeq() {
        return mAdjSeq;
    }

    @GuardedBy("mServiceLock")
    void setCompletedAdjSeq(int completedAdjSeq) {
        mCompletedAdjSeq = completedAdjSeq;
    }

    @GuardedBy("mServiceLock")
    public int getCompletedAdjSeq() {
        return mCompletedAdjSeq;
    }

    @GuardedBy("mServiceLock")
    void setLastTopTime(long lastTopTime) {
        mLastTopTime = lastTopTime;
    }

    @GuardedBy("mServiceLock")
    public long getLastTopTime() {
        return mLastTopTime;
    }

    @GuardedBy("mServiceLock")
    public boolean isEmpty() {
        return mCurProcState >= PROCESS_STATE_CACHED_EMPTY;
    }

    @GuardedBy("mServiceLock")
    public boolean isCached() {
        return mCurAdj >= CACHED_APP_MIN_ADJ;
    }

    @GuardedBy("mServiceLock")
    void setSystemNoUi(boolean systemNoUi) {
        mSystemNoUi = systemNoUi;
    }

    @GuardedBy("mServiceLock")
    public boolean isSystemNoUi() {
        return mSystemNoUi;
    }

    /**
     * Sets the primary thing impacting OOM adjustment for debugging.
     * Also traces the OOM adjustment type if tracing is enabled.
     */
    @GuardedBy("mServiceLock")
    void setAdjType(String adjType) {
        if (TRACE_OOM_ADJ) {
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, getTrackName(), 0);
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, getTrackName(),
                    adjType, 0);
        }
        mAdjType = adjType;
    }

    @GuardedBy("mServiceLock")
    public String getAdjType() {
        return mAdjType;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setAdjTypeCode(int adjTypeCode) {
        mAdjTypeCode = adjTypeCode;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public int getAdjTypeCode() {
        return mAdjTypeCode;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setAdjSource(Object adjSource) {
        mAdjSource = adjSource;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public Object getAdjSource() {
        return mAdjSource;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setAdjSourceProcState(@ProcessState int adjSourceProcState) {
        mAdjSourceProcState = adjSourceProcState;
    }

    /**
     * TODO: b/485394632 - Migrate to the {@link #getProcState()} method after the
     *                     {@link Flags#encapsulateCurProcState()} is enabled.
     */
    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public @ProcessState int getAdjSourceProcState() {
        return mAdjSourceProcState;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setAdjTarget(Object adjTarget) {
        mAdjTarget = adjTarget;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public Object getAdjTarget() {
        return mAdjTarget;
    }

    @GuardedBy("mServiceLock")
    public boolean isReachable() {
        return mReachable;
    }

    @GuardedBy("mServiceLock")
    void setReachable(boolean reachable) {
        mReachable = reachable;
    }

    @GuardedBy("mServiceLock")
    void setHasActivities(boolean hasActivities) {
        mHasActivities = hasActivities;
    }

    @GuardedBy("mServiceLock")
    public int getActivityStateFlags() {
        return mActivityStateFlags;
    }

    @GuardedBy("mServiceLock")
    void setActivityStateFlags(int flags) {
        mActivityStateFlags = flags;
    }

    @GuardedBy("mServiceLock")
    public long getPerceptibleTaskStoppedTimeMillis() {
        return mPerceptibleTaskStoppedTimeMillis;
    }

    @GuardedBy("mServiceLock")
    void setPerceptibleTaskStoppedTimeMillis(long uptimeMs) {
        mPerceptibleTaskStoppedTimeMillis = uptimeMs;
    }

    @GuardedBy("mServiceLock")
    void setHasRecentTask(boolean hasRecentTask) {
        mHasRecentTask = hasRecentTask;
    }

    /** Resets all cached information used by the OomAdjuster. */
    @GuardedBy("mServiceLock")
    void resetCachedInfo() {
        mCachedAdj = INVALID_ADJ;
        mCachedForegroundActivities = false;
        mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        mCachedSchedGroup = SCHED_GROUP_BACKGROUND;
        mCachedAdjType = null;
    }

    /** Returns whether the process has any activities. */
    @GuardedBy("mServiceLock")
    public boolean getHasActivities() {
        return mHasActivities;
    }

    /** Returns whether the process has any visible activities. */
    @GuardedBy("mServiceLock")
    public boolean getHasVisibleActivities() {
        return (mActivityStateFlags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0;
    }

    /** Returns whether the process is associated with any recent tasks. */
    @GuardedBy("mServiceLock")
    public boolean getHasRecentTasks() {
        return mHasRecentTask;
    }

    /**
     * Returns whether a specific compatibility change is enabled for the process, using a cached
     * value or pulling it.
     *
     * @param cachedCompatChangeId The ID of the compatibility change to check.
     * @return True if the change is enabled, false otherwise.
     */
    @GuardedBy("mServiceLock")
    public boolean getCachedCompatChange(@CachedCompatChangeId int cachedCompatChangeId) {
        if (mCachedCompatChanges[cachedCompatChangeId] == VALUE_INVALID) {
            mCachedCompatChanges[cachedCompatChangeId] =
                    hasCompatChange(cachedCompatChangeId) ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedCompatChanges[cachedCompatChangeId] == VALUE_TRUE;
    }

    @GuardedBy("mServiceLock")
    @OomAdjust int getCachedAdj() {
        return mCachedAdj;
    }

    @GuardedBy("mServiceLock")
    void setCachedAdj(@OomAdjust int cachedAdj) {
        mCachedAdj = cachedAdj;
    }

    @GuardedBy("mServiceLock")
    public boolean getCachedForegroundActivities() {
        return mCachedForegroundActivities;
    }

    @GuardedBy("mServiceLock")
    void setCachedForegroundActivities(boolean cachedForegroundActivities) {
        mCachedForegroundActivities = cachedForegroundActivities;
    }

    /**
     * TODO: b/485394632 - Make this method package-private.
     */
    @GuardedBy("mServiceLock")
    public @ProcessState int getCachedProcState() {
        return mCachedProcState;
    }

    @GuardedBy("mServiceLock")
    void setCachedProcState(@ProcessState int cachedProcState) {
        mCachedProcState = cachedProcState;
    }

    @GuardedBy("mServiceLock")
    public @SchedGroup int getCachedSchedGroup() {
        return mCachedSchedGroup;
    }

    @GuardedBy("mServiceLock")
    void setCachedSchedGroup(@SchedGroup int cachedSchedGroup) {
        mCachedSchedGroup = cachedSchedGroup;
    }

    @GuardedBy("mServiceLock")
    public String getCachedAdjType() {
        return mCachedAdjType;
    }

    @GuardedBy("mServiceLock")
    void setCachedAdjType(String cachedAdjType) {
        mCachedAdjType = cachedAdjType;
    }

    @GuardedBy("mServiceLock")
    public boolean getScheduleLikeTopApp() {
        return mScheduleLikeTopApp;
    }

    @GuardedBy("mServiceLock")
    void setScheduleLikeTopApp(boolean scheduleLikeTopApp) {
        mScheduleLikeTopApp = scheduleLikeTopApp;
    }

    @GuardedBy("mServiceLock")
    public long getFollowupUpdateUptimeMs() {
        return mFollowupUpdateUptimeMs;
    }

    @GuardedBy("mServiceLock")
    void setFollowupUpdateUptimeMs(long updateUptimeMs) {
        mFollowupUpdateUptimeMs = updateUptimeMs;
    }

    @GuardedBy("mServiceLock")
    void setPendingFinishAttach(boolean pendingFinishAttach) {
        mPendingFinishAttach = pendingFinishAttach;
    }

    @GuardedBy("mServiceLock")
    public boolean isPendingFinishAttach() {
        return mPendingFinishAttach;
    }

    /**
     * Performs cleanup operations on the ProcessRecordInternal when the application record is
     * cleaned up. This resets various state flags and adjustment values.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    public void onCleanupApplicationRecordLSP() {
        if (TRACE_OOM_ADJ) {
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, getTrackName(), 0);
        }
        setHasForegroundActivities(false);
        mHasShownUi = false;
        mForcingToImportant = null;
        mPrevSetRawAdj = mSetRawAdj;
        mCurRawAdj = mSetRawAdj = mCurAdj = mSetAdj = INVALID_ADJ;
        mCurCapability = mSetCapability = PROCESS_CAPABILITY_NONE;
        mCurSchedGroup = mSetSchedGroup = SCHED_GROUP_BACKGROUND;
        mCurProcState = mCurRawProcState = mSetProcState = PROCESS_STATE_NONEXISTENT;
        for (int i = 0; i < mCachedCompatChanges.length; i++) {
            mCachedCompatChanges[i] = VALUE_INVALID;
        }
        mHasActivities = false;
        mActivityStateFlags = ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
        mPerceptibleTaskStoppedTimeMillis = Long.MIN_VALUE;
        mHasRecentTask = false;
    }

    @GuardedBy("mServiceLock")
    public boolean isBackgroundRestricted() {
        return mBackgroundRestricted;
    }

    @GuardedBy("mServiceLock")
    void setBackgroundRestricted(boolean restricted) {
        mBackgroundRestricted = restricted;
    }

    @GuardedBy("mServiceLock")
    public boolean isCurBoundByNonBgRestrictedApp() {
        return mCurBoundByNonBgRestrictedApp;
    }

    @GuardedBy("mServiceLock")
    void setCurBoundByNonBgRestrictedApp(boolean bound) {
        mCurBoundByNonBgRestrictedApp = bound;
    }

    @GuardedBy("mServiceLock")
    public boolean isSetBoundByNonBgRestrictedApp() {
        return mSetBoundByNonBgRestrictedApp;
    }

    @GuardedBy("mServiceLock")
    void setSetBoundByNonBgRestrictedApp(boolean bound) {
        mSetBoundByNonBgRestrictedApp = bound;
    }

    /**
     * Updates the last time a visible activity within this process became invisible.
     *
     * @param hasVisibleActivities True if there are visible activities, false otherwise.
     */
    @GuardedBy("mServiceLock")
    void updateLastInvisibleTime(boolean hasVisibleActivities) {
        if (hasVisibleActivities) {
            mLastInvisibleTime = Long.MAX_VALUE;
        } else if (mLastInvisibleTime == Long.MAX_VALUE) {
            mLastInvisibleTime = SystemClock.elapsedRealtime();
        }
    }

    @GuardedBy("mServiceLock")
    @ElapsedRealtimeLong
    public long getLastInvisibleTime() {
        return mLastInvisibleTime;
    }

    @GuardedBy("mServiceLock")
    void setSetCached(boolean cached) {
        mSetCached = cached;
    }

    @GuardedBy("mServiceLock")
    public boolean isSetCached() {
        return mSetCached;
    }

    @GuardedBy("mServiceLock")
    void setLastCachedTime(@ElapsedRealtimeLong long now) {
        mLastCachedTime = now;
    }

    @ElapsedRealtimeLong
    @GuardedBy("mServiceLock")
    public long getLastCachedTime() {
        return mLastCachedTime;
    }

    @GuardedBy("mServiceLock")
    public String getIsolatedEntryPoint() {
        return mIsolatedEntryPoint;
    }

    @GuardedBy("mServiceLock")
    void setIsolatedEntryPoint(@Nullable String isolatedEntryPoint) {
        mIsolatedEntryPoint = isolatedEntryPoint;
    }

    @GuardedBy("mServiceLock")
    public String getWaitingToKill() {
        return mWaitingToKill;
    }

    @GuardedBy("mServiceLock")
    void setWaitingToKill(@Nullable String waitingToKill) {
        mWaitingToKill = waitingToKill;
    }

    @GuardedBy(anyOf = {"mServiceLock", "mProcLock"})
    public long getLastActivityTime() {
        return mLastActivityTime;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    void setLastActivityTime(long lastActivityTime) {
        mLastActivityTime = lastActivityTime;
    }

    @GuardedBy("mProcLock")
    public int getRenderThreadTid() {
        return mRenderThreadTid;
    }

    @GuardedBy("mProcLock")
    void setRenderThreadTid(int renderThreadTid) {
        mRenderThreadTid = renderThreadTid;
    }

    @GuardedBy("mServiceLock")
    public boolean isZramWrittenBack() {
        return mIsZramWrittenBack;
    }

    @GuardedBy("mServiceLock")
    void setIsZramWrittenBack(boolean isZramWrittenBack) {
        mIsZramWrittenBack = isZramWrittenBack;
    }

    ProcessNode getProcessNode() {
        return mProcessNode;
    }

    /** Get the intrinsic edge from the system to this process node. */
    ProcessEdge getProcessEdge() {
        return mProcessEdge;
    }

    /**
     * Lazily initiates and returns the track name for tracing.
     */
    private String getTrackName() {
        if (mTrackName == null) {
            mTrackName = "oom:" + processName + "/u" + uid;
        }
        return mTrackName;
    }

    /**
     * Dumps the current state of the ProcessRecordInternal to the given PrintWriter.
     *
     * @param pw The PrintWriter to dump to.
     * @param prefix The prefix string for each line.
     * @param nowUptime The current uptime in milliseconds.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    public void dump(PrintWriter pw, String prefix, long nowUptime) {
        pw.print(prefix); pw.print("adjSeq="); pw.println(mAdjSeq);
        pw.print(prefix); pw.print("oom adj: max="); pw.print(mMaxAdj);
        pw.print(" curRaw="); pw.print(mCurRawAdj);
        pw.print(" setRaw="); pw.print(mSetRawAdj);
        pw.print(" cur="); pw.print(mCurAdj);
        pw.print(" set="); pw.println(mSetAdj);
        pw.print(prefix); pw.print("mCurSchedGroup="); pw.print(mCurSchedGroup);
        pw.print(" setSchedGroup="); pw.print(mSetSchedGroup);
        pw.print(" systemNoUi="); pw.println(mSystemNoUi);
        pw.print(prefix); pw.print("curProcState="); pw.print(getCurProcState());
        pw.print(" mRepProcState="); pw.print(mRepProcState);
        pw.print(" setProcState="); pw.print(mSetProcState);
        pw.print(" lastStateTime=");
        TimeUtils.formatDuration(getLastStateTime(), nowUptime, pw);
        pw.println();
        pw.print(prefix); pw.print("curCapability=");
        ActivityManager.printCapabilitiesFull(pw, mCurCapability);
        pw.print(" setCapability=");
        ActivityManager.printCapabilitiesFull(pw, mSetCapability);
        pw.println();

        pw.print(prefix);
        pw.print("curCpuTimeReasons=0x"); pw.print(Integer.toHexString(mCurCpuTimeReasons));
        pw.print(" setCpuTimeReasons=0x"); pw.print(Integer.toHexString(mSetCpuTimeReasons));
        pw.print(" curImplicitCpuTimeReasons=0x");
        pw.print(Integer.toHexString(mCurImplicitCpuTimeReasons));
        pw.print(" setImplicitCpuTimeReasons=0x");
        pw.print(Integer.toHexString(mSetImplicitCpuTimeReasons));
        pw.println();

        if (mBackgroundRestricted) {
            pw.print(" backgroundRestricted=");
            pw.print(mBackgroundRestricted);
            pw.print(" boundByNonBgRestrictedApp=");
            pw.print(mSetBoundByNonBgRestrictedApp);
        }
        pw.println();
        if (mHasShownUi) {
            pw.print(prefix); pw.print("hasShownUi="); pw.println(mHasShownUi);
        }
        pw.print(prefix); pw.print("cached="); pw.print(isCached());
        pw.print(" empty="); pw.println(isEmpty());
        if (mServiceB) {
            pw.print(prefix); pw.print("serviceb="); pw.print(mServiceB);
            pw.print(" serviceHighRam="); pw.println(mServiceHighRam);
        }
        if (getHasTopUi() || getHasOverlayUi() || mRunningRemoteAnimation) {
            pw.print(prefix); pw.print("hasTopUi="); pw.print(getHasTopUi());
            pw.print(" hasOverlayUi="); pw.print(getHasOverlayUi());
            pw.print(" runningRemoteAnimation="); pw.println(mRunningRemoteAnimation);
        }
        if (mHasForegroundActivities || mRepForegroundActivities) {
            pw.print(prefix);
            pw.print("foregroundActivities="); pw.print(mHasForegroundActivities);
            pw.print(" (rep="); pw.print(mRepForegroundActivities); pw.println(")");
        }
        if (mLastTopTime > 0) {
            pw.print(prefix); pw.print("lastTopTime=");
            TimeUtils.formatDuration(mLastTopTime, nowUptime, pw);
            pw.println();
        }
        if (mLastInvisibleTime > 0 && mLastInvisibleTime < Long.MAX_VALUE) {
            pw.print(prefix); pw.print("lastInvisibleTime=");
            final long elapsedRealtimeNow = SystemClock.elapsedRealtime();
            final long currentTimeNow = System.currentTimeMillis();
            final long lastInvisibleCurrentTime =
                    currentTimeNow - elapsedRealtimeNow + mLastInvisibleTime;
            TimeUtils.dumpTimeWithDelta(pw, lastInvisibleCurrentTime, currentTimeNow);
            pw.println();
        }
        if (mHasStartedServices) {
            pw.print(prefix); pw.print("hasStartedServices="); pw.println(mHasStartedServices);
        }
    }

    /**
     * Writes the OOM adjustment and process state details to the {@link ProcessOomProto.Detail}
     * message of the given proto stream.
     *
     * @param proto The proto stream to write to.
     */
    public final void writeDetailToProto(@NonNull ProtoOutputStream proto) {
        final ProcessServiceRecordInternal psr = getServices();

        proto.write(ProcessOomProto.Detail.MAX_ADJ, getMaxAdj());
        proto.write(ProcessOomProto.Detail.CUR_RAW_ADJ, getCurRawAdj());
        proto.write(ProcessOomProto.Detail.SET_RAW_ADJ, getSetRawAdj());
        proto.write(ProcessOomProto.Detail.CUR_ADJ, getCurAdj());
        proto.write(ProcessOomProto.Detail.SET_ADJ, getSetAdj());
        proto.write(ProcessOomProto.Detail.CURRENT_STATE,
                makeProcStateProtoEnum(getCurProcState()));
        proto.write(ProcessOomProto.Detail.SET_STATE,
                makeProcStateProtoEnum(getSetProcState()));
        writeProcessCapabilitiesListToProto(proto, getCurCapability());
        proto.write(ProcessOomProto.Detail.CACHED, isCached());
        proto.write(ProcessOomProto.Detail.EMPTY, isEmpty());
        proto.write(ProcessOomProto.Detail.HAS_ABOVE_CLIENT, psr.hasBindAboveClient());
    }

    /**
     * Writes the process capabilities to the {@link ProcessOomProto.Detail#CAPABILITY_FLAGS} field
     * of the given proto stream.
     *
     * @param proto The proto stream to write to.
     * @param cap   The capabilities bitmask.
     */
    private static void writeProcessCapabilitiesListToProto(@NonNull ProtoOutputStream proto,
            @ProcessCapability int cap) {
        for (int i = 0; i < 32; i++) {
            final int capability = 1 << i;
            if ((cap & capability) != 0) {
                final int protoCapability = ActivityManager.processCapabilityAmToProto(capability);
                proto.write(ProcessOomProto.Detail.CAPABILITY_FLAGS, protoCapability);
            }
        }
    }
}
