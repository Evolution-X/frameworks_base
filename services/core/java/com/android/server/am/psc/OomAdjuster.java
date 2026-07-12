/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_CPU_TIME;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_IMPLICIT_CPU_TIME;
import static android.app.ActivityManager.PROCESS_CAPABILITY_INSTRUMENTATION_DEFAULTS;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_ACTIVITY;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_ALLOWLIST;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_BACKUP;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_BATCH_UPDATE_REQUEST;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_BIND_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_COMPONENT_DISABLED;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_COUNT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_EXECUTING_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_FINISH_RECEIVER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_FOLLOW_UP;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_GET_PROVIDER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_NONE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_BEGIN;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_END;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_RECONFIGURATION;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_REMOVE_PROVIDER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_REMOVE_TASK;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_RESTRICTION_CHANGE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SERVICE_BINDER_CALL;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SHELL;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SHORT_FGS_TIMEOUT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_START_RECEIVER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_START_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_STOP_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SYSTEM_INIT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UID_IDLE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UI_VISIBILITY;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UNBIND_SERVICE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.CPU_TIME_REASONS;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.CUR_CAPABILITY_FLAGS;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.CUR_OOM_SCORE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.CUR_PROC_STATE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.IMPLICIT_CPU_TIME_REASONS;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.PID;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.PREV_CAPABILITY_FLAGS;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.PREV_OOM_SCORE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.PREV_PROC_STATE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.REASON;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.SEQ_ID;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidProcessStateChangedEvent.UID;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidTrackEvent.PROCESS_STATE_CHANGED_EVENT;
import static android.os.PerfettoCategories.PROC_STATE_CATEGORY;
import static android.os.PerfettoCategories.PROC_STATE_COUNTER_CATEGORY;
import static android.os.Process.THREAD_GROUP_BACKGROUND;
import static android.os.Process.THREAD_GROUP_DEFAULT;
import static android.os.Process.THREAD_GROUP_FOREGROUND_WINDOW;
import static android.os.Process.THREAD_GROUP_RESTRICTED;
import static android.os.Process.THREAD_GROUP_TOP_APP;
import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;

import static com.android.internal.app.procstats.DumpUtils.STATE_PERFETTO_TRACK_NAMES;
import static com.android.internal.app.procstats.ProcessState.PROCESS_STATE_TO_STATE;
import static com.android.internal.app.procstats.ProcessStats.STATE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.STATE_FROZEN;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_OOM_ADJ;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.AppProfiler.TAG_PSS;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_ADDED_APPLICATION;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_BACKUP;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_BROADCAST;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_LINK_FAIL;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_ON_HOLD;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_RESTART;
import static com.android.server.am.HostingRecord.HOSTING_TYPE_SYSTEM;
import static com.android.server.am.psc.Constants.BACKUP_APP_ADJ;
import static com.android.server.am.psc.Constants.CACHED_APP_IMPORTANCE_LEVELS;
import static com.android.server.am.psc.Constants.CACHED_APP_MAX_ADJ;
import static com.android.server.am.psc.Constants.CACHED_APP_MIN_ADJ;
import static com.android.server.am.psc.Constants.FOREGROUND_APP_ADJ;
import static com.android.server.am.psc.Constants.INVALID_ADJ;
import static com.android.server.am.psc.Constants.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.psc.Constants.PERCEPTIBLE_LOW_APP_ADJ;
import static com.android.server.am.psc.Constants.PERCEPTIBLE_MEDIUM_APP_ADJ;
import static com.android.server.am.psc.Constants.PREVIOUS_APP_ADJ;
import static com.android.server.am.psc.Constants.PREVIOUS_APP_MAX_ADJ;
import static com.android.server.am.psc.Constants.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.psc.Constants.SCHED_GROUP_DEFAULT;
import static com.android.server.am.psc.Constants.SCHED_GROUP_FOREGROUND_WINDOW;
import static com.android.server.am.psc.Constants.SCHED_GROUP_RESTRICTED;
import static com.android.server.am.psc.Constants.SCHED_GROUP_TOP_APP;
import static com.android.server.am.psc.Constants.SCHED_GROUP_TOP_APP_BOUND;
import static com.android.server.am.psc.Constants.SERVICE_ADJ;
import static com.android.server.am.psc.Constants.SYSTEM_ADJ;
import static com.android.server.am.psc.Constants.UNKNOWN_ADJ;
import static com.android.server.am.psc.Constants.VISIBLE_APP_ADJ;
import static com.android.server.am.psc.Constants.VISIBLE_APP_LAYER_MAX;
import static com.android.server.am.psc.Constants.VISIBLE_APP_MAX_ADJ;
import static com.android.server.am.psc.OomAdjusterImpl.Connection.CPU_TIME_TRANSMISSION_LEGACY;
import static com.android.server.am.psc.OomAdjusterImpl.Connection.CPU_TIME_TRANSMISSION_NONE;
import static com.android.server.am.psc.ProcessStateController.FOLLOW_UP_UPDATE_MSG;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_IS_VISIBLE;
import static com.android.server.wm.WindowProcessController.ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.app.ActivityManager.ProcessState;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.net.NetworkPolicyManager;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.dev.perfetto.sdk.PerfettoTrace;
import com.android.server.ServiceThread;
import com.android.server.am.ActivityManagerServiceDumpProcessesProto;
import com.android.server.am.EventLogTags;
import com.android.server.am.Flags;
import com.android.server.am.ProcessList;
import com.android.server.am.UidRecord;
import com.android.server.am.psc.Constants.OomAdjust;
import com.android.server.am.psc.Constants.SchedGroup;
import com.android.server.am.psc.PlatformCompatCache.CachedCompatChangeId;
import com.android.server.wm.WindowProcessController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * All of the code required to compute proc states and oom_adj values.
 */
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public abstract class OomAdjuster {
    static final String TAG = "OomAdjuster";

    static final String[] OOM_ADJ_REASON_TAGS = new String[OOM_ADJ_REASON_COUNT];
    static {
        Arrays.setAll(OOM_ADJ_REASON_TAGS,
                i -> "updateOomAdj_" + oomAdjReasonToStringSuffix(i));
    }

    /** To be used when the process does not have PROCESS_CAPABILITY_CPU_TIME. */
    public static final int CPU_TIME_REASON_NONE = 0;
    /** The process has PROCESS_CAPABILITY_CPU_TIME, but the reason is not interesting for logs. */
    public static final int CPU_TIME_REASON_OTHER = 0x1;
    /**
     * The process has PROCESS_CAPABILITY_CPU_TIME because it was transmitted over a connection
     * from a client. This is interesting because this reason will cease to exist if all the
     * responsible bindings started using {@link Context#BIND_ALLOW_FREEZE}.
     */
    public static final int CPU_TIME_REASON_TRANSMITTED = 0x2;
    /**
     * The process has PROCESS_CAPABILITY_CPU_TIME because it was transmitted over a connection
     * from a client transitively only because of {@link Context#BIND_SIMULATE_ALLOW_FREEZE}.
     * This indicates that this reason will soon go away and in absence of other reasons, the app
     * will not have PROCESS_CAPABILITY_CPU_TIME.
     */
    public static final int CPU_TIME_REASON_TRANSMITTED_LEGACY = 0x4;
    /**
     * The process has PROCESS_CAPABILITY_CPU_TIME because it was added to the power
     * allow list. The capability will go away as soon as it is removed from the allow list.
     */
    public static final int CPU_TIME_REASON_ALLOW_LIST = 0x8;

    @IntDef(flag = true, prefix = "CPU_TIME_REASON_", value = {
            CPU_TIME_REASON_NONE,
            CPU_TIME_REASON_OTHER,
            CPU_TIME_REASON_TRANSMITTED,
            CPU_TIME_REASON_TRANSMITTED_LEGACY,
            CPU_TIME_REASON_ALLOW_LIST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CpuTimeReasons {
    }

    /** To be used when the process does not have PROCESS_CAPABILITY_IMPLICIT_CPU_TIME. */
    public static final int IMPLICIT_CPU_TIME_REASON_NONE = 0;
    /**
     * The process has PROCESS_CAPABILITY_IMPLICIT_CPU_TIME, but the reason is not interesting for
     * logs.
     */
    public static final int IMPLICIT_CPU_TIME_REASON_OTHER = 0x1;
    /**
     * The process has PROCESS_CAPABILITY_IMPLICIT_CPU_TIME because it was transmitted over a
     * connection from a client. This is interesting because this reason will cease to exist if all
     * the responsible bindings started using {@link Context#BIND_ALLOW_FREEZE}.
     */
    public static final int IMPLICIT_CPU_TIME_REASON_TRANSMITTED = 0x2;
    /**
     * The process has PROCESS_CAPABILITY_IMPLICIT_CPU_TIME because it was transmitted over a
     * connection from a client transitively only because of
     * {@link Context#BIND_SIMULATE_ALLOW_FREEZE}.
     * This indicates that this reason will soon go away and in absence of other reasons, the app
     * will not have PROCESS_CAPABILITY_IMPLICIT_CPU_TIME.
     */
    public static final int IMPLICIT_CPU_TIME_REASON_TRANSMITTED_LEGACY = 0x4;

    @IntDef(flag = true, prefix = "IMPLICIT_CPU_TIME_REASON_", value = {
            IMPLICIT_CPU_TIME_REASON_NONE,
            IMPLICIT_CPU_TIME_REASON_OTHER,
            IMPLICIT_CPU_TIME_REASON_TRANSMITTED,
            IMPLICIT_CPU_TIME_REASON_TRANSMITTED_LEGACY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImplicitCpuTimeReasons {
    }

    public static final int DEFAULT_ZRAM_WRITEBACK_OOM_ADJ = 249;
    /**
     * Return a human readable string for OomAdjuster updates with {@link OomAdjReason}.
     */
    public static String oomAdjReasonToString(@OomAdjReason int oomReason) {
        return OOM_ADJ_REASON_TAGS[oomReason];
    }

    /**
     * Return a human readable string for {@link OomAdjReason} to append to debug messages.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    static String oomAdjReasonToStringSuffix(@OomAdjReason int oomReason) {
        return switch (oomReason) {
            case OOM_ADJ_REASON_NONE -> "meh";
            case OOM_ADJ_REASON_ACTIVITY -> "activityChange";
            case OOM_ADJ_REASON_FINISH_RECEIVER -> "finishReceiver";
            case OOM_ADJ_REASON_START_RECEIVER -> "startReceiver";
            case OOM_ADJ_REASON_BIND_SERVICE -> "bindService";
            case OOM_ADJ_REASON_UNBIND_SERVICE -> "unbindService";
            case OOM_ADJ_REASON_START_SERVICE -> "startService";
            case OOM_ADJ_REASON_GET_PROVIDER -> "getProvider";
            case OOM_ADJ_REASON_REMOVE_PROVIDER -> "removeProvider";
            case OOM_ADJ_REASON_UI_VISIBILITY -> "uiVisibility";
            case OOM_ADJ_REASON_ALLOWLIST -> "allowlistChange";
            case OOM_ADJ_REASON_PROCESS_BEGIN -> "processBegin";
            case OOM_ADJ_REASON_PROCESS_END -> "processEnd";
            case OOM_ADJ_REASON_SHORT_FGS_TIMEOUT -> "shortFgs";
            case OOM_ADJ_REASON_SYSTEM_INIT -> "systemInit";
            case OOM_ADJ_REASON_BACKUP -> "backup";
            case OOM_ADJ_REASON_SHELL -> "shell";
            case OOM_ADJ_REASON_REMOVE_TASK -> "removeTask";
            case OOM_ADJ_REASON_UID_IDLE -> "uidIdle";
            case OOM_ADJ_REASON_STOP_SERVICE -> "stopService";
            case OOM_ADJ_REASON_EXECUTING_SERVICE -> "executingService";
            case OOM_ADJ_REASON_RESTRICTION_CHANGE -> "restrictionChange";
            case OOM_ADJ_REASON_COMPONENT_DISABLED -> "componentDisabled";
            case OOM_ADJ_REASON_FOLLOW_UP -> "followUp";
            case OOM_ADJ_REASON_RECONFIGURATION -> "reconfiguration";
            case OOM_ADJ_REASON_SERVICE_BINDER_CALL -> "serviceBinderCall";
            case OOM_ADJ_REASON_BATCH_UPDATE_REQUEST -> "batchUpdateRequest";
            default -> "unknown";
        };
    }

    private final int[] mProcessCountsByState = new int[STATE_COUNT];

    private final Handler mUpdateHandler;

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Keep track of the number of service processes we last found, to
     * determine on the next iteration which should be B services.
     */
    int mNumServiceProcs = 0;
    int mNewNumAServiceProcs = 0;
    int mNewNumServiceProcs = 0;

    /**
     * Keep track of the non-cached/empty process we last found, to help
     * determine how to distribute cached/empty processes next time.
     */
    int mNumNonCachedProcs = 0;

    /**
     * Keep track of the number of cached hidden procs, to balance oom adj
     * distribution between those and empty procs.
     */
    int mNumCachedHiddenProcs = 0;

    /** Track all uids that have actively running processes. */
    @CompositeRWLock({"mServiceLock", "mProcLock"})
    ActiveUidsInternal mActiveUids;

    /**
     * The handler to execute {@link Callback#onProcessGroupUpdated} (it may be heavy if the process
     * has many threads) for reducing the time spent in {@link #applyResultsLSP}.
     */
    private final Handler mProcessGroupHandler;

    protected final int[] mTmpSchedGroup = new int[1];

    /** The ActivityManagerService object, which can only be used as a lock object. */
    final Object mServiceLock;
    /** The ActivityManagerGlobalLock object, which can only be used as a lock object. */
    final Object mProcLock;

    final Callback mCallback;
    final HostingTypeProvider mHostingTypeProvider;
    final Injector mInjector;
    protected final Constants mOomConstants;
    final @NonNull GlobalState mGlobalState;
    final ProcessListInternal mProcessList;

    private final int mNumSlots;
    protected final ArrayList<ProcessRecordInternal> mTmpProcessList = new ArrayList<>();
    protected final ArrayList<UidRecordInternal> mTmpBecameIdle = new ArrayList<>();
    protected final ActiveUidsInternal mTmpUidRecords;
    protected final ArrayDeque<ProcessRecordInternal> mTmpQueue;
    protected final ArraySet<ProcessRecordInternal> mTmpProcessSet = new ArraySet<>();
    protected final ArraySet<ProcessRecordInternal> mPendingProcessSet = new ArraySet<>();

    /**
     * List of processes that we want to batch for LMKD to adjust their respective
     * OOM scores.
     */
    @GuardedBy("mServiceLock")
    protected final ArrayList<ProcessRecordInternal> mProcsToOomAdj = new ArrayList<>();

    /**
     * Flag to mark if there is an ongoing oomAdjUpdate: potentially the oomAdjUpdate
     * could be called recursively because of the indirect calls during the update;
     * however the oomAdjUpdate itself doesn't support recursion - in this case we'd
     * have to queue up the new targets found during the update, and perform another
     * round of oomAdjUpdate at the end of last update.
     */
    @GuardedBy("mServiceLock")
    private boolean mOomAdjUpdateOngoing = false;

    /**
     * Flag to mark if there is a pending full oomAdjUpdate.
     */
    @GuardedBy("mServiceLock")
    private boolean mPendingFullOomAdjUpdate = false;

    /**
     * Most recent reason string. We update it in sync with the trace.
     */
    @OomAdjReason
    protected int mLastReason;

    private final OomAdjusterDebugLogger mLogger;

    /**
     * The process state of the current TOP app.
     */
    @GuardedBy("mServiceLock")
    protected @ProcessState int mProcessStateCurTop = PROCESS_STATE_TOP;

    @GuardedBy("mServiceLock")
    private final ArraySet<ProcessRecordInternal> mFollowUpUpdateSet = new ArraySet<>();

    protected static final long NO_FOLLOW_UP_TIME = Long.MAX_VALUE;
    @GuardedBy("mServiceLock")
    private long mNextFollowUpUpdateUptimeMs = NO_FOLLOW_UP_TIME;

    /**
     * The oom score a client needs to be to raise a service with UI out of cache.
     */
    protected static final int CACHING_UI_SERVICE_CLIENT_ADJ_THRESHOLD = SERVICE_ADJ;

    @VisibleForTesting
    public static final long PERCEPTIBLE_TASK_TIMEOUT_MILLIS = 5 * 60 * 1000;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static final int ALL_CPU_TIME_CAPABILITIES =
            PROCESS_CAPABILITY_CPU_TIME | PROCESS_CAPABILITY_IMPLICIT_CPU_TIME;

    /**
     * Callback interface for {@link OomAdjuster} to interact with components outside the PSC
     * package, abstracting away direct dependencies.
     */
    public interface Callback {
        /** Notifies the client component when a process's OOM adjustment changes. */
        void onOomAdjustChanged(@OomAdjust int oldAdj, @OomAdjust int newAdj,
                ProcessRecordInternal appInternal);

        /**
         * Notifies the client component to evaluate and apply process freeze state changes.
         *
         * @param app The process record representing the application.
         * @param freezePolicy True if the process should be frozen.
         * @param oomAdjReason The reason for the OOM adjustment leading to this call.
         * @param immediate True if the freeze/unfreeze action should be applied immediately.
         * @param oldOomAdj The previous OOM adjustment score of the process.
         * @param newOomAdj The current OOM adjustment score of the process.
         */
        void onProcessFreezabilityChanged(ProcessRecordInternal app, boolean freezePolicy,
                @OomAdjReason int oomAdjReason, boolean immediate, @OomAdjust int oldOomAdj,
                @OomAdjust int newOomAdj);

        /**
         * Notifies the client component when a process's process state is updated.
         *
         * @param app The process that was updated.
         * @param oldProcState The process's state before the update.
         * @param newProcState The process's state after the update.
         * @param newOomAdj The process's OOM adjustment after the update.
         * @param now Current uptime.
         * @param nowElapsed Current elapsed time.
         * @param forceUpdatePssTime Whether to force a PSS update.
         * @param doingAll Whether this is part of a full update.
         * @param reportDebugMsgs Whether to report debug messages.
         */
        void onProcStateUpdated(ProcessRecordInternal app, @ProcessState int oldProcState,
                @ProcessState int newProcState, @OomAdjust int newOomAdj, long now, long nowElapsed,
                boolean forceUpdatePssTime, boolean doingAll, boolean reportDebugMsgs);

        /** Notifies when the process group for an application process has been updated. */
        void onProcessGroupUpdated(ProcessRecordInternal app, int group);

        /**
         * Notifies when a process's state has changed. This is triggered when there are
         * updates to the process's activities, foreground services, or other state attributes.
         *
         * @param app The application process that has undergone a change.
         * @param changes A bitmask of flags from
         *                {@link com.android.server.am.ProcessList.ProcessChangeItem}
         *                indicating the specific attributes that have changed.
         */
        void onProcessChanged(ProcessRecordInternal app, int changes);

        /** Notifies when the process state sequence number has been incremented for active UIDs. */
        void onProcStateSeqIncremented(ActiveUidsInternal activeUids);

        /** Notifies when the {@link UidRecordInternal}'s last background time is updated. */
        void onUidLastBackgroundTimeUpdated(UidRecordInternal uidRec, long nowElapsed,
                OomAdjusterDebugLogger logger);

        /**
         * Notifies after the OOM adjustment values for all processes have been updated.
         *
         * @param adjSeq The sequence number of the adjustment pass that has been completed.
         *               See {@link OomAdjuster#mAdjSeq}.
         */
        void onOomAdjUpdated(int adjSeq);

        /**
         * Notifies after OOM adjustment values are updated for all processes and memory trimming
         * has been performed.
         *
         * @param numCached The number of processes in a cached state.
         * @param numEmpty The number of empty processes.
         * @param now The uptime timestamp of this event.
         */
        void onProcessUpdatedAndTrimmed(int numCached, int numEmpty, long now);

        /** Notifies at the start of the UIDs update process. */
        void onUpdateUidsStarted();

        /**
         * Notifies at the end of the UIDs update process.
         *
         * @param activeUids The set of all UIDs that were active during this update cycle.
         * @param nowElapsed The timestamp (in elapsed realtime) at which the update occurred.
         * @param becameIdle A list of UIDs that became idle during this update cycle.
         */
        void onUpdateUidsFinished(ActiveUidsInternal activeUids, long nowElapsed,
                ArrayList<UidRecordInternal> becameIdle);

        /**
         * Notifies when a UID's state has been updated.
         *
         * @param uidRec The UidRecordInternal that was updated.
         * @param uidChange A bitmask of flags indicating what specific parts of the UID's state
         *                  have changed, such as {@link UidRecord#CHANGE_PROCSTATE} or
         *                  {@link UidRecord#CHANGE_CAPABILITY}.
         */
        void onUidUpdated(UidRecordInternal uidRec, int uidChange);

        /** Notifies when a process becomes effectively background restricted. */
        void onProcessBackgroundRestricted(ProcessRecordInternal app);

        /** Notifies when a process transitions to a cached state. */
        void onProcessCached(ProcessRecordInternal app, OomAdjusterDebugLogger logger);

        /** Notifies when a debugging message related to OOM adjustments is reported. */
        void onReportOomAdjMessage(String msg);

        /** Enqueues the pending top app if necessary. */
        void enqueuePendingTopAppIfNecessaryLocked();

        /**
         * Sets the scheduling priority for the given application's UI-related threads.
         * This typically involves switching between SCHED_FIFO for high-priority UI rendering
         * and SCHED_OTHER for normal operation.
         */
        void setFifoPriority(@NonNull ProcessRecordInternal app, boolean enable);

        /** Schedules the specified thread with SCHED_FIFO priority. */
        boolean scheduleAsFifoPriority(int tid, boolean suppressLogs);

        /** Returns the current percentage of free swap space available on the system. */
        double getFreeSwapPercent();
    }

    /**
     * An interface for providing hosting type information for a given process.
     */
    public interface HostingTypeProvider {
        /** Returns the hosting type for the given process. */
        String getHostingType(ProcessRecordInternal app);
    }

    /**
     * Injects dependencies into the OomAdjuster, allowing for easier testing by substituting
     * mocks for external dependencies.
     */
    @VisibleForTesting
    public static class Injector {
        /** Checks if a specific compatibility change is enabled for the given application. */
        public boolean isChangeEnabled(@CachedCompatChangeId int cachedCompatChangeId,
                ApplicationInfo app, boolean defaultValue) {
            return PlatformCompatCache.getInstance()
                    .isChangeEnabled(cachedCompatChangeId, app, defaultValue);
        }

        /** Returns the number of milliseconds since boot, not counting time spent in deep sleep. */
        public long getUptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        /** Returns the number of milliseconds since boot, including time spent in sleep. */
        public long getElapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }

        /**
         * Sets the OOM adjustment scores for a list of processes in a single batch operation
         * to improve performance.
         */
        public void batchSetOomAdj(ArrayList<ProcessRecordInternal> procsToOomAdj) {
            ProcessList.batchSetOomAdj(procsToOomAdj);
        }

        /** Sets the OOM adjustment score for a single process. */
        public void setOomAdj(int pid, int uid, @OomAdjust int adj, boolean forLmkdOnly) {
            ProcessList.setOomAdj(pid, uid, adj, forLmkdOnly);
        }

        /** Sets the priority of a specific thread. */
        public void setThreadPriority(int tid, int priority) {
            Process.setThreadPriority(tid, priority);
        }
    }

    /**
     * Holds various constant values used by the OomAdjuster, which are duplicated from
     * {@link com.android.server.am.ActivityManagerConstants}.
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static final class Constants {
        /**
         * The timeout duration (in milliseconds) for a service binding to be considered
         * "almost perceptible" after leaving the TOP process state.
         */
        public volatile long mServiceBindAlmostPerceptibleTimeoutMs;
        /**
         * The timeout duration (in milliseconds) for a service designated as
         * `FOREGROUND_SERVICE_TYPE_SHORT_SERVICE` before it is considered timed out.
         */
        public volatile long mShortFgsTimeoutDuration;
        /**
         * The additional duration (in milliseconds) after `mShortFgsTimeoutDuration`
         * before the process state of a timed-out short FGS is demoted.
         */
        public volatile long mShortFgsProcStateExtraWaitDuration;
        /** The maximum number of cached processes to keep before killing them. */
        public volatile int mCurMaxCachedProcesses;
        /** The maximum number of empty app processes to keep. */
        public volatile int mCurMaxEmptyProcesses;
        /** The number of empty processes to maintain before starting to trim old ones. */
        public volatile int mCurTrimEmptyProcesses;
        /** A sparse array of UIDs for which detailed process state change logging is enabled. */
        public volatile SparseBooleanArray mProcStateDebugUids = new SparseBooleanArray(0);
        /**
         * Indicates whether PSS profiling is force-enabled, overriding the default RSS-based
         * profiling.
         */
        public volatile boolean mForceEnablePssProfiling;
        /**
         * A modifier for PSS-based thresholds when RSS is used for memory measurement. Since RSS
         * values are generally larger than PSS, this factor is used to scale thresholds to ensure
         * comparable behavior.
         */
        public volatile float mPssToRssThresholdModifier;
        /**
         * The maximum time in milliseconds an empty process can exist before it is considered for
         * trimming due to age.
         */
        public volatile long mMaxEmptyTimeMillis;
        /**
         * The grace period in milliseconds for an app that transitions from the TOP state to
         * having a foreground service, during which it retains a higher oom_adj score.
         */
        public volatile long mTopToFgsGraceDuration;
        /**
         * The grace period in milliseconds for an app that has just left the TOP state but still
         * has an "almost perceptible" service running. During this period, the app retains a
         * higher oom_adj score.
         */
        public volatile long mTopToAlmostPerceptibleGraceDuration;
        /**
         * The maximum duration in milliseconds a process can remain in the 'previous' oom_adj
         * state before being demoted to a cached state.
         */
        public volatile long mMaxPreviousTime;
        /**
         * The maximum time in milliseconds that a service can remain inactive (with no new
         * activity) before its process is considered non-essential and can be moved to the LRU
         * background list.
         */
        public volatile long mMaxServiceInactivity;
        /**
         * The duration in milliseconds to retain a process hosting a content provider in the
         * "last activity" state before allowing it to be demoted to the regular cached LRU list.
         */
        public volatile long mContentProviderRetainTime;
        /**
         * When enabled, logs a stack trace whenever the process state or UID state is updated
         * for a debuggable UID.
         */
        public volatile boolean mEnableProcStateStacktrace;
        /**
         * A delay in milliseconds to introduce when updating the proc state for a debuggable UID.
         */
        public volatile int mProcStateDebugSetProcStateDelay;
        /**
         * A delay in milliseconds to introduce when updating the UID state for a debuggable UID.
         */
        public volatile int mProcStateDebugSetUidStateDelay;
        /**
         * If true, enables the proactive killing of cached apps to manage memory.
         */
        public volatile boolean mProactiveKillsEnabled;
        /**
         * The minimum percentage of free swap space. If free swap falls below this threshold,
         * LRU cached apps may be trimmed.
         * This is only active if {@link #mProactiveKillsEnabled} is true.
         */
        public volatile float mLowSwapThresholdPercent;
        /**
         * If true, prevents proactive killing of excessive cached processes until the primary user
         * has unlocked.
         */
        public volatile boolean mNoKillCachedProcessesUntilBootCompleted;
        /**
         * The duration in milliseconds after each user unlock during which excessive cached
         * processes will not be proactively killed.
         */
        public volatile long mNoKillCachedProcessesPostBootCompletedDurationMillis;
        /**
         * The oom_adj score cutoff. Processes with an oom_adj score greater than or equal to this
         * value may be frozen if they do not hold the CPU_TIME capability.
         */
        public volatile int mFreezerCutoffAdj;
        /**
         * Whether to enable batching of OOM adjustment score updates sent to the lmkd.
         * When enabled, instead of sending updates for each process individually, the OomAdjuster
         * collects them and sends them in a single batch.
         */
        public boolean mEnableBatchingOomAdj;
        /** The minimum duration to wait before scheduling another follow-up update. */
        public volatile long mFollowUpOomadjUpdateWaitDuration;
    }

    /**
     * An interface for providing global state information required by the OomAdjuster. This
     * abstraction allows for decoupling the OomAdjuster from direct dependencies on services
     * that provide this state.
     */
    // TODO(b/346822474): hook up global state usage.
    public interface GlobalState {
        /** Is device's screen on. */
        boolean isAwake();

        /** What process is running a backup for a given userId. */
        ProcessRecordInternal getBackupTarget(@UserIdInt int userId);

        /** Is memory level normal since last evaluation. */
        boolean isLastMemoryLevelNormal();

        /** The ProcessState to assign to the Top process. */
        @ProcessState int getTopProcessState();

        /** Keyguard is in the process of unlocking. */
        boolean isUnlocking();

        /** The notification shade is expanded. */
        boolean hasExpandedNotificationShade();

        /** The current Top process. */
        @Nullable ProcessRecordInternal getTopProcess();

        /** The current Home process. */
        @Nullable ProcessRecordInternal getHomeProcess();

        /** The current Heavy Weight process. */
        @Nullable ProcessRecordInternal getHeavyWeightProcess();

        /** The current process showing UI if the device is in doze. */
        @Nullable ProcessRecordInternal getShowingUiWhileDozingProcess();

        /** The previous process that showed an activity. */
        @Nullable ProcessRecordInternal getPreviousProcess();

        /** Checks whether the debugging messages should be reported for the given process's UID. */
        boolean isDebugEnabled(ProcessRecordInternal app);

        /** Returns the uptime timestamp when any user most recently started unlocking. */
        long getLastUserUnlockingUptime();

        /** Returns the number of frozen processes. */
        int getFrozenProcessCount();
    }

    /**
     * Checks if a specific compatibility change is enabled for the given application by wrapping
     * the call to the injector.
     */
    public boolean isChangeEnabled(@CachedCompatChangeId int cachedCompatChangeId,
            ApplicationInfo app, boolean defaultValue) {
        return mInjector.isChangeEnabled(cachedCompatChangeId, app, defaultValue);
    }

    /**
     * Creates and starts the service thread used for OomAdjuster operations. This thread
     * runs with a boosted priority to ensure timely OOM adjustments.
     */
    public static ServiceThread createAdjusterThread() {
        // The process group is usually critical to the response time of foreground app, so the
        // setter should apply it as soon as possible.
        final ServiceThread adjusterThread =
                new ServiceThread(TAG, THREAD_PRIORITY_TOP_APP_BOOST, false /* allowIo */);
        adjusterThread.start();
        return adjusterThread;
    }

    OomAdjuster(Object serviceLock, Object procLock, ProcessListInternal processList,
            ActiveUidsInternal activeUids, ServiceThread adjusterThread, Constants oomConstants,
            @NonNull GlobalState globalState, Injector injector, Callback callback,
            Handler updateHandler, HostingTypeProvider hostingTypeProvider) {
        mServiceLock = serviceLock;
        mProcLock = procLock;
        mUpdateHandler = updateHandler;
        mCallback = callback;
        mOomConstants = oomConstants;
        mGlobalState = globalState;
        mInjector = injector;
        mProcessList = processList;
        mActiveUids = activeUids;
        mHostingTypeProvider = hostingTypeProvider;

        mLogger = new OomAdjusterDebugLogger(this, mOomConstants);

        mProcessGroupHandler = new Handler(adjusterThread.getLooper(), msg -> {
            final int group = msg.what;
            final ProcessRecordInternal app = (ProcessRecordInternal) msg.obj;

            mCallback.onProcessGroupUpdated(app, group);
            return true;
        });
        mTmpUidRecords = new ActiveUidsInternal();
        mTmpQueue = new ArrayDeque<>(mOomConstants.mCurMaxCachedProcesses << 1);
        mNumSlots = ((CACHED_APP_MAX_ADJ - CACHED_APP_MIN_ADJ + 1) >> 1)
                / CACHED_APP_IMPORTANCE_LEVELS;
    }

    public OomAdjusterDebugLogger getLogger() {
        return mLogger;
    }

    void setAppAndChildProcessGroup(ProcessRecordInternal app, int group) {
        mProcessGroupHandler.sendMessage(mProcessGroupHandler.obtainMessage(
                group, app));
    }

    /**
     * Update the keep-warming service flags upon user switches
     */
    @GuardedBy("mServiceLock")
    public void prewarmServicesIfNecessary() {
        final ArrayList<? extends ProcessRecordInternal> lruList =
                mProcessList.getLruProcessesLOSP();
        for (int i = lruList.size() - 1; i >= 0; i--) {
            updateKeepWarmIfNecessaryForProcessLocked(lruList.get(i));
        }
    }

    @GuardedBy("mServiceLock")
    private void updateKeepWarmIfNecessaryForProcessLocked(final ProcessRecordInternal app) {
        if (!app.shouldKeepWarm()) {
            return;
        }
        final ProcessServiceRecordInternal psr = app.getServices();
        for (int j = psr.numberOfRunningServices() - 1; j >= 0; j--) {
            psr.getRunningServiceInternalAt(j).updateKeepWarmLocked();
        }
    }

    /**
     * Update OomAdj for all processes in LRU list
     */
    @GuardedBy("mServiceLock")
    void updateOomAdjLocked(@OomAdjReason int oomAdjReason) {
        synchronized (mProcLock) {
            updateOomAdjLSP(oomAdjReason);
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    private void updateOomAdjLSP(@OomAdjReason int oomAdjReason) {
        // Simply return as there is an oomAdjUpdate ongoing
        if (mOomAdjUpdateOngoing) {
            mPendingFullOomAdjUpdate = true;
            return;
        }

        try {
            mOomAdjUpdateOngoing = true;
            performUpdateOomAdjLSP(oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected abstract void performUpdateOomAdjLSP(@OomAdjReason int oomAdjReason);

    /**
     * Update OomAdj for specific process and its reachable processes (with direction/indirect
     * bindings from this process); Note its clients' proc state won't be re-evaluated if this proc
     * is hosting any service/content provider.
     *
     * @param app The process to update, or null to update all processes
     * @param oomAdjReason
     */
    @GuardedBy("mServiceLock")
    boolean updateOomAdjLocked(ProcessRecordInternal app, @OomAdjReason int oomAdjReason) {
        synchronized (mProcLock) {
            return updateOomAdjLSP(app, oomAdjReason);
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    private boolean updateOomAdjLSP(ProcessRecordInternal app, @OomAdjReason int oomAdjReason) {
        if (app == null) {
            updateOomAdjLSP(oomAdjReason);
            return true;
        }

        // Simply return true as there is an oomAdjUpdate ongoing.
        if (mOomAdjUpdateOngoing) {
            mPendingProcessSet.add(app);
            return true;
        }

        try {
            mOomAdjUpdateOngoing = true;
            return performUpdateOomAdjLSP(app, oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected abstract boolean performUpdateOomAdjLSP(ProcessRecordInternal app,
            @OomAdjReason int oomAdjReason);

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected @ProcessState int enqueuePendingTopAppIfNecessaryLSP() {
        final @ProcessState int prevTopProcessState = getTopProcessState();
        mCallback.enqueuePendingTopAppIfNecessaryLocked();
        final @ProcessState int topProcessState = getTopProcessState();
        if (prevTopProcessState != topProcessState) {
            // Unlikely but possible: WM just updated the top process state, it may have
            // enqueued the new top app to the pending top UID list. Enqueue that one here too.
            mCallback.enqueuePendingTopAppIfNecessaryLocked();
        }
        return topProcessState;
    }

    /**
     * Expand the provided {@code reachables} list with all processes reachable from those
     * provided in the list.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    protected abstract void collectReachableProcessesLSP(
            @NonNull ArrayList<ProcessRecordInternal> reachables);

    /**
     * Collect the reachable processes from the given {@code apps}, the result will be
     * returned in the given {@code processes}, which will include the processes from
     * the given {@code apps}.
     */
    @GuardedBy("mServiceLock")
    protected boolean collectReachableProcessesLocked(ArraySet<ProcessRecordInternal> apps,
            ArrayList<ProcessRecordInternal> processes) {
        final ActiveUidsInternal uids = mTmpUidRecords;
        final ArrayDeque<ProcessRecordInternal> queue = mTmpQueue;
        queue.clear();
        processes.clear();
        for (int i = 0, size = apps.size(); i < size; i++) {
            final ProcessRecordInternal app = apps.valueAt(i);
            app.setReachable(true);
            queue.offer(app);
        }

        uids.clear();

        // Track if any of them reachables could include a cycle
        boolean containsCycle = false;
        // Scan downstreams of the process record
        while (!queue.isEmpty()) {
            final ProcessRecordInternal pr = queue.poll();
            processes.add(pr);
            final UidRecordInternal uidRec = pr.getUidRecord();
            if (uidRec != null) {
                uids.put(uidRec.getUid(), uidRec);
            }
            final ProcessServiceRecordInternal psr = pr.getServices();
            for (int i = psr.numberOfConnections() - 1; i >= 0; i--) {
                final ConnectionRecordInternal cr = psr.getConnectionInternalAt(i);
                ProcessRecordInternal service = cr.hasFlag(ServiceInfo.FLAG_ISOLATED_PROCESS)
                        ? cr.getService().getIsolationHostProcess()
                        : cr.getService().getHostProcessInternal();
                if (service == null || service == pr
                        || ((service.getMaxAdj() >= SYSTEM_ADJ)
                                && (service.getMaxAdj() < FOREGROUND_APP_ADJ))) {
                    continue;
                }
                containsCycle |= service.isReachable();
                if (service.isReachable()) {
                    continue;
                }
                if (cr.hasFlag(Context.BIND_WAIVE_PRIORITY)
                        && cr.notHasFlag(Context.BIND_TREAT_LIKE_ACTIVITY
                        | Context.BIND_ADJUST_WITH_ACTIVITY)) {
                    continue;
                }
                queue.offer(service);
                service.setReachable(true);
            }
            final ProcessProviderRecordInternal ppr = pr.getProviders();
            for (int i = ppr.numberOfProviderConnections() - 1; i >= 0; i--) {
                ContentProviderConnectionInternal cpc = ppr.getProviderConnectionInternalAt(i);
                ProcessRecordInternal provider = cpc.getProvider().getHostProcess();
                if (provider == null || provider == pr
                        || ((provider.getMaxAdj() >= SYSTEM_ADJ)
                                && (provider.getMaxAdj() < FOREGROUND_APP_ADJ))) {
                    continue;
                }
                containsCycle |= provider.isReachable();
                if (provider.isReachable()) {
                    continue;
                }
                queue.offer(provider);
                provider.setReachable(true);
            }
            // See if this process has any corresponding SDK sandbox processes running, and if so
            // scan them as well.
            final List<? extends ProcessRecordInternal> sdkSandboxes =
                    mProcessList.getSdkSandboxProcessesForAppLocked(pr.uid);
            final int numSdkSandboxes = sdkSandboxes != null ? sdkSandboxes.size() : 0;
            for (int i = numSdkSandboxes - 1; i >= 0; i--) {
                final ProcessRecordInternal sdkSandbox = sdkSandboxes.get(i);
                containsCycle |= sdkSandbox.isReachable();
                if (sdkSandbox.isReachable()) {
                    continue;
                }
                queue.offer(sdkSandbox);
                sdkSandbox.setReachable(true);
            }
            // If this process is a sandbox itself, also scan the app on whose behalf its running
            if (pr.isSdkSandbox) {
                for (int is = psr.numberOfRunningServices() - 1; is >= 0; is--) {
                    ServiceRecordInternal s = psr.getRunningServiceInternalAt(is);
                    for (int conni = s.getConnectionsSize() - 1; conni >= 0; conni--) {
                        ArrayList<? extends ConnectionRecordInternal> clist =
                                s.getConnectionAt(conni);
                        for (int i = clist.size() - 1; i >= 0; i--) {
                            final ConnectionRecordInternal cr = clist.get(i);
                            final ProcessRecordInternal attributedApp = cr.getAttributedClient();
                            if (attributedApp == null || attributedApp == pr
                                    || ((attributedApp.getMaxAdj() >= SYSTEM_ADJ)
                                    && (attributedApp.getMaxAdj() < FOREGROUND_APP_ADJ))) {
                                continue;
                            }
                            if (attributedApp.isReachable()) {
                                continue;
                            }
                            queue.offer(attributedApp);
                            attributedApp.setReachable(true);
                        }
                    }
                }
            }
        }

        int size = processes.size();
        if (size > 0) {
            // Reverse the process list, since the updateOomAdjInnerLSP scans from the end of it.
            for (int l = 0, r = size - 1; l < r; l++, r--) {
                final ProcessRecordInternal t = processes.get(l);
                final ProcessRecordInternal u = processes.get(r);
                t.setReachable(false);
                u.setReachable(false);
                processes.set(l, u);
                processes.set(r, t);
            }
        }

        return containsCycle;
    }

    /**
     * Enqueue the given process for a later oom adj update
     */
    @GuardedBy("mServiceLock")
    void enqueueOomAdjTargetLocked(ProcessRecordInternal app) {
        if (app != null && app.getMaxAdj() > FOREGROUND_APP_ADJ) {
            mPendingProcessSet.add(app);
        }
    }

    /**
     * Removes a process from the set of pending OOM adjustment targets. If the process died,
     * its package information may be invalidated from the compatibility cache.
     */
    @GuardedBy("mServiceLock")
    void removeOomAdjTargetLocked(ProcessRecordInternal app, boolean procDied) {
        if (app != null) {
            mPendingProcessSet.remove(app);
            if (procDied) {
                PlatformCompatCache.getInstance().invalidate(app.getPackageName());
            }
        }
    }

    /**
     * Kick off an oom adj update pass for the pending targets which are enqueued via
     * {@link #enqueueOomAdjTargetLocked}.
     */
    @GuardedBy("mServiceLock")
    void updateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason) {
        // First check if there is pending full update
        if (mPendingFullOomAdjUpdate) {
            mPendingFullOomAdjUpdate = false;
            mPendingProcessSet.clear();
            updateOomAdjLocked(oomAdjReason);
            return;
        }
        if (mPendingProcessSet.isEmpty()) {
            return;
        }

        if (mOomAdjUpdateOngoing) {
            // There's another oomAdjUpdate ongoing, return from here now;
            // that ongoing update would call us again at the end of it.
            return;
        }
        try {
            mOomAdjUpdateOngoing = true;
            performUpdateOomAdjPendingTargetsLocked(oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    /**
     * Updates processes that require a follow-up OOM adjustment. This is typically used
     * for states that have a time-based duration, such as the "previous app" state.
     */
    @GuardedBy("mServiceLock")
    void updateOomAdjFollowUpTargetsLocked() {
        final long now = mInjector.getUptimeMillis();
        long nextFollowUpUptimeMs = Long.MAX_VALUE;
        mNextFollowUpUpdateUptimeMs = NO_FOLLOW_UP_TIME;
        for (int i = mFollowUpUpdateSet.size() - 1; i >= 0; i--) {
            final ProcessRecordInternal proc = mFollowUpUpdateSet.valueAtUnchecked(i);
            final long followUpUptimeMs = proc.getFollowupUpdateUptimeMs();

            if (proc.isKilled()) {
                // Process is dead, just remove from follow up set.
                mFollowUpUpdateSet.removeAt(i);
            } else if (followUpUptimeMs <= now) {
                // Add processes that need a follow up update.
                mPendingProcessSet.add(proc);
                proc.setFollowupUpdateUptimeMs(NO_FOLLOW_UP_TIME);
                mFollowUpUpdateSet.removeAt(i);
            } else if (followUpUptimeMs < nextFollowUpUptimeMs) {
                // Figure out when to schedule the next follow up update.
                nextFollowUpUptimeMs = followUpUptimeMs;
            } else if (followUpUptimeMs == NO_FOLLOW_UP_TIME) {
                // The follow up is no longer needed for this process.
                mFollowUpUpdateSet.removeAt(i);
            }
        }

        if (nextFollowUpUptimeMs != Long.MAX_VALUE) {
            // There is still at least one process that needs a follow up.
            scheduleFollowUpOomAdjusterUpdateLocked(nextFollowUpUptimeMs, now);
        }

        updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_FOLLOW_UP);
    }

    @GuardedBy("mServiceLock")
    protected abstract void performUpdateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason);

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void postUpdateOomAdjInnerLSP(@OomAdjReason int oomAdjReason,
            ActiveUidsInternal activeUids, long now, long nowElapsed, long oldTime,
            boolean doingAll) {
        mNumNonCachedProcs = 0;
        mNumCachedHiddenProcs = 0;

        updateAndTrimProcessLSP(now, nowElapsed, oldTime, oomAdjReason, doingAll);
        mNumServiceProcs = mNewNumServiceProcs;

        updateUidsLSP(activeUids, nowElapsed);

        mCallback.onOomAdjUpdated(mAdjSeq);

        if (DEBUG_OOM_ADJ) {
            final long duration = mInjector.getUptimeMillis() - now;
            if (false) {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms",
                        new RuntimeException("here"));
            } else {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms");
            }
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void applyLruAdjust(ArrayList<? extends ProcessRecordInternal> lruList) {
        final int numLru = lruList.size();
        int nextVisibleAppAdj = VISIBLE_APP_ADJ;
        int nextPreviousAppAdj = PREVIOUS_APP_ADJ;

        // First update the OOM adjustment for each of the
        // application processes based on their current state.
        int curCachedAdj = CACHED_APP_MIN_ADJ;
        int nextCachedAdj = curCachedAdj + (CACHED_APP_IMPORTANCE_LEVELS * 2);
        int curCachedImpAdj = 0;
        int curEmptyAdj = CACHED_APP_MIN_ADJ + CACHED_APP_IMPORTANCE_LEVELS;
        int nextEmptyAdj = curEmptyAdj + (CACHED_APP_IMPORTANCE_LEVELS * 2);

        final int emptyProcessLimit = mOomConstants.mCurMaxEmptyProcesses;
        final int cachedProcessLimit = mOomConstants.mCurMaxCachedProcesses - emptyProcessLimit;
        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        int numEmptyProcs = numLru - mNumNonCachedProcs - mNumCachedHiddenProcs;
        if (numEmptyProcs > cachedProcessLimit) {
            // If there are more empty processes than our limit on cached
            // processes, then use the cached process limit for the factor.
            // This ensures that the really old empty processes get pushed
            // down to the bottom, so if we are running low on memory we will
            // have a better chance at keeping around more cached processes
            // instead of a gazillion empty processes.
            numEmptyProcs = cachedProcessLimit;
        }
        int cachedFactor = (mNumCachedHiddenProcs > 0
                ? (mNumCachedHiddenProcs + mNumSlots - 1) : 1)
                            / mNumSlots;
        if (cachedFactor < 1) cachedFactor = 1;

        int emptyFactor = (numEmptyProcs + mNumSlots - 1) / mNumSlots;
        if (emptyFactor < 1) emptyFactor = 1;

        int stepCached = -1;
        int stepEmpty = -1;
        int lastCachedGroup = 0;
        int lastCachedGroupImportance = 0;
        int lastCachedGroupUid = 0;

        for (int i = numLru - 1; i >= 0; i--) {
            final ProcessRecordInternal app = lruList.get(i);
            final @OomAdjust int curAdj = app.getCurAdj();
            if (VISIBLE_APP_ADJ <= curAdj && curAdj <= VISIBLE_APP_MAX_ADJ) {
                app.setCurAdj(nextVisibleAppAdj);
                nextVisibleAppAdj = Math.min(nextVisibleAppAdj + 1, VISIBLE_APP_MAX_ADJ);
            } else if (PREVIOUS_APP_ADJ <= curAdj && curAdj <= PREVIOUS_APP_MAX_ADJ) {
                app.setCurAdj(nextPreviousAppAdj);
                nextPreviousAppAdj = Math.min(nextPreviousAppAdj + 1, PREVIOUS_APP_MAX_ADJ);
            } else if (!app.isKilledByAm() && app.isProcessRunning() && curAdj >= UNKNOWN_ADJ) {
                // If we haven't yet assigned the final cached adj to the process, do that now.
                final ProcessServiceRecordInternal psr = app.getServices();
                switch (app.getCurProcState()) {
                    case PROCESS_STATE_LAST_ACTIVITY:
                    case PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                    case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                        // Figure out the next cached level, taking into account groups.
                        boolean inGroup = false;
                        final int connectionGroup = psr.getConnectionGroup();
                        if (connectionGroup != 0) {
                            final int connectionImportance = psr.getConnectionImportance();
                            if (lastCachedGroupUid == app.uid
                                    && lastCachedGroup == connectionGroup) {
                                // This is in the same group as the last process, just tweak
                                // adjustment by importance.
                                if (connectionImportance > lastCachedGroupImportance) {
                                    lastCachedGroupImportance = connectionImportance;
                                    if (curCachedAdj < nextCachedAdj
                                            && curCachedAdj < CACHED_APP_MAX_ADJ) {
                                        curCachedImpAdj++;
                                    }
                                }
                                inGroup = true;
                            } else {
                                lastCachedGroupUid = app.uid;
                                lastCachedGroup = connectionGroup;
                                lastCachedGroupImportance = connectionImportance;
                            }
                        }
                        if (!inGroup && curCachedAdj != nextCachedAdj) {
                            stepCached++;
                            curCachedImpAdj = 0;
                            if (stepCached >= cachedFactor) {
                                stepCached = 0;
                                curCachedAdj = nextCachedAdj;
                                nextCachedAdj += CACHED_APP_IMPORTANCE_LEVELS * 2;
                                if (nextCachedAdj > CACHED_APP_MAX_ADJ) {
                                    nextCachedAdj = CACHED_APP_MAX_ADJ;
                                }
                            }
                        }
                        // This process is a cached process holding activities...
                        // assign it the next cached value for that type, and then
                        // step that cached level.
                        final int rawAdj = curCachedAdj + curCachedImpAdj;
                        app.setCurRawAdj(rawAdj);
                        app.setCurAdj(
                                applyBindAboveClientToAdj(psr.hasBindAboveClient(), rawAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning activity LRU #" + i
                                    + " adj: " + app.getCurAdj()
                                    + " (curCachedAdj=" + curCachedAdj
                                    + " curCachedImpAdj=" + curCachedImpAdj + ")");
                        }
                        break;
                    default:
                        // Figure out the next cached level.
                        if (curEmptyAdj != nextEmptyAdj) {
                            stepEmpty++;
                            if (stepEmpty >= emptyFactor) {
                                stepEmpty = 0;
                                curEmptyAdj = nextEmptyAdj;
                                nextEmptyAdj += CACHED_APP_IMPORTANCE_LEVELS * 2;
                                if (nextEmptyAdj > CACHED_APP_MAX_ADJ) {
                                    nextEmptyAdj = CACHED_APP_MAX_ADJ;
                                }
                            }
                        }
                        // For everything else, assign next empty cached process
                        // level and bump that up.  Note that this means that
                        // long-running services that have dropped down to the
                        // cached level will be treated as empty (since their process
                        // state is still as a service), which is what we want.
                        app.setCurRawAdj(curEmptyAdj);
                        app.setCurAdj(applyBindAboveClientToAdj(psr.hasBindAboveClient(),
                                curEmptyAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning empty LRU #" + i
                                    + " adj: " + app.getCurAdj()
                                    + " (curEmptyAdj=" + curEmptyAdj
                                    + ")");
                        }
                        break;
                }
            }
        }
    }
    private long mNextNoKillDebugMessageTime;

    private double mLastFreeSwapPercent = 1.00;

    @GuardedBy({"mServiceLock", "mProcLock"})
    private void updateAndTrimProcessLSP(final long now, final long nowElapsed,
            final long oldTime, @OomAdjReason int oomAdjReason,
            boolean doingAll) {
        final ArrayList<? extends ProcessRecordInternal> lruList =
                mProcessList.getLruProcessesLOSP();
        final int numLru = lruList.size();

        final boolean doKillExcessiveProcesses = shouldKillExcessiveProcesses(now);
        if (!doKillExcessiveProcesses) {
            if (mNextNoKillDebugMessageTime < now) {
                Slog.d(TAG, "Not killing cached processes"); // STOPSHIP Remove it b/222365734
                mNextNoKillDebugMessageTime = now + 5000; // Every 5 seconds
            }
        }
        final int emptyProcessLimit = doKillExcessiveProcesses
                ? mOomConstants.mCurMaxEmptyProcesses : Integer.MAX_VALUE;
        final int cachedProcessLimit = doKillExcessiveProcesses
                ? (mOomConstants.mCurMaxCachedProcesses - emptyProcessLimit) : Integer.MAX_VALUE;
        int lastCachedGroup = 0;
        int lastCachedGroupUid = 0;
        int numCached = 0;
        int numCachedExtraGroup = 0;
        int numEmpty = 0;
        int numTrimming = 0;

        final boolean proactiveKillsEnabled = mOomConstants.mProactiveKillsEnabled;
        final double lowSwapThresholdPercent = mOomConstants.mLowSwapThresholdPercent;
        final double freeSwapPercent = proactiveKillsEnabled
                ? mCallback.getFreeSwapPercent() : 1.00;
        ProcessRecordInternal lruCachedApp = null;

        for (int i = 0; i < STATE_COUNT; i++) {
            mProcessCountsByState[i] = 0;
        }

        for (int i = numLru - 1; i >= 0; i--) {
            final ProcessRecordInternal app = lruList.get(i);
            if (!app.isKilledByAm() && app.isProcessRunning()) {
                if (!Flags.fixApplyOomadjOrder()) {
                    // We don't need to apply the update for the process which didn't get computed
                    if (app.getCompletedAdjSeq() == mAdjSeq) {
                        applyResultsLSP(app, doingAll, now, nowElapsed, oomAdjReason, true);
                    }
                }
                int state = app.getCurProcState();
                // PROCESS_STATE_TO_STATE mapping does not include PROCESS_STATE_NONEXISTENT.
                // state < PROCESS_STATE_TO_STATE.length excludes PROCESS_STATE_NONEXISTENT.
                if (state >= 0 && state < PROCESS_STATE_TO_STATE.length) {
                    int transformedState = PROCESS_STATE_TO_STATE[state];
                    mProcessCountsByState[transformedState] =
                            (mProcessCountsByState[transformedState]) + 1;
                }

                if (app.isPendingFinishAttach()) {
                    // Avoid trimming processes that are still initializing. If they aren't
                    // hosting any components yet because they may be unfairly killed.
                    // We however apply the oom scores set at #setAttachingProcessStatesLSP.
                    updateAppUidRecLSP(app);
                    continue;
                }

                final ProcessServiceRecordInternal psr = app.getServices();
                // Count the number of process types.
                switch (app.getCurProcState()) {
                    case PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        mNumCachedHiddenProcs++;
                        numCached++;
                        final int connectionGroup = psr.getConnectionGroup();
                        if (connectionGroup != 0) {
                            if (lastCachedGroupUid == app.getApplicationUid()
                                    && lastCachedGroup == connectionGroup) {
                                // If this process is the next in the same group, we don't
                                // want it to count against our limit of the number of cached
                                // processes, so bump up the group count to account for it.
                                numCachedExtraGroup++;
                            } else {
                                lastCachedGroupUid = app.getApplicationUid();
                                lastCachedGroup = connectionGroup;
                            }
                        } else {
                            lastCachedGroupUid = lastCachedGroup = 0;
                        }
                        if ((numCached - numCachedExtraGroup) > cachedProcessLimit) {
                            app.killLocked("cached #" + numCached,
                                    "too many cached",
                                    ApplicationExitInfo.REASON_OTHER,
                                    ApplicationExitInfo.SUBREASON_TOO_MANY_CACHED,
                                    true);
                        } else if (proactiveKillsEnabled) {
                            lruCachedApp = app;
                        }
                        break;
                    case PROCESS_STATE_CACHED_EMPTY:
                        if (numEmpty > mOomConstants.mCurTrimEmptyProcesses
                                && app.getLastActivityTime() < oldTime) {
                            app.killLocked("empty for " + ((now
                                    - app.getLastActivityTime()) / 1000) + "s",
                                    "empty for too long",
                                    ApplicationExitInfo.REASON_OTHER,
                                    ApplicationExitInfo.SUBREASON_TRIM_EMPTY,
                                    true);
                        } else {
                            numEmpty++;
                            if (numEmpty > emptyProcessLimit) {
                                app.killLocked("empty #" + numEmpty,
                                        "too many empty",
                                        ApplicationExitInfo.REASON_OTHER,
                                        ApplicationExitInfo.SUBREASON_TOO_MANY_EMPTY,
                                        true);
                            } else if (proactiveKillsEnabled) {
                                lruCachedApp = app;
                            }
                        }
                        break;
                    default:
                        mNumNonCachedProcs++;
                        break;
                }

                // TODO: b/319163103 - limit isolated/sandbox trimming to just the processes
                //  evaluated in the current update.
                if (app.isolated && psr.numberOfRunningServices() <= 0
                        && app.getIsolatedEntryPoint() == null) {
                    // If this is an isolated process, there are no services
                    // running in it, and it's not a special process with a
                    // custom entry point, then the process is no longer
                    // needed.  We agressively kill these because we can by
                    // definition not re-use the same process again, and it is
                    // good to avoid having whatever code was running in them
                    // left sitting around after no longer needed.
                    app.killLocked("isolated not needed", ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_ISOLATED_NOT_NEEDED, true);
                } else if (app.isSdkSandbox && psr.numberOfRunningServices() <= 0
                        && !app.hasActiveInstrumentation()) {
                    // If this is an SDK sandbox process and there are no services running it, we
                    // aggressively kill the sandbox as we usually don't want to re-use the same
                    // sandbox again.
                    app.killLocked("sandbox not needed", ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_SDK_SANDBOX_NOT_NEEDED, true);
                } else {
                    // Keeping this process, update its uid.
                    updateAppUidRecLSP(app);
                }

                if (app.getCurProcState() >= ActivityManager.PROCESS_STATE_HOME
                        && !app.isKilledByAm()) {
                    numTrimming++;
                }
            }
        }

        if (Flags.fixApplyOomadjOrder()) {
            // We need to apply the update starting from the least recently used.
            // Otherwise, they won't be in the correct LRU order in LMKD.
            for (int i = 0; i < numLru; i++) {
                final ProcessRecordInternal app = lruList.get(i);
                // We don't need to apply the update for the process which didn't get computed
                if (!app.isKilledByAm() && app.isProcessRunning()
                        && app.getCompletedAdjSeq() == mAdjSeq) {
                    applyResultsLSP(app, doingAll, now, nowElapsed, oomAdjReason, true);
                }
            }
        }

        if (android.os.Flags.perfettoSdkTracingV3()
                && PROC_STATE_COUNTER_CATEGORY != null
                && PROC_STATE_COUNTER_CATEGORY.isEnabled()) {
            for (int i = 0; i < STATE_COUNT; i++) {
                try {
                    int count;
                    if (i == STATE_FROZEN) {
                        count = mGlobalState.getFrozenProcessCount();
                    } else {
                        count = mProcessCountsByState[i];
                    }

                    final String trackName = STATE_PERFETTO_TRACK_NAMES[i];
                    if (trackName != null) {
                        PerfettoTrace.counter(PROC_STATE_COUNTER_CATEGORY, count)
                                .usingProcessCounterTrack(trackName)
                                .emit();
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to emit Perfetto proc state counter for state " + i, e);
                }
            }
        }

        if (!mProcsToOomAdj.isEmpty()) {
            mInjector.batchSetOomAdj(mProcsToOomAdj);
            mProcsToOomAdj.clear();
        }

        if (proactiveKillsEnabled                               // Proactive kills enabled?
                && doKillExcessiveProcesses                     // Should kill excessive processes?
                && freeSwapPercent < lowSwapThresholdPercent    // Swap below threshold?
                && lruCachedApp != null                         // If no cached app, let LMKD decide
                // If swap is non-decreasing, give reclaim a chance to catch up
                && freeSwapPercent < mLastFreeSwapPercent) {
            lruCachedApp.killLocked("swap low and too many cached",
                    ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_TOO_MANY_CACHED,
                    true);
        }

        mLastFreeSwapPercent = freeSwapPercent;

        mCallback.onProcessUpdatedAndTrimmed(numCached, numEmpty, now);
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void updateAppUidRecIfNecessaryLSP(final ProcessRecordInternal app) {
        if (!app.isKilledByAm() && app.isProcessRunning()) {
            if (app.isolated && app.getServices().numberOfRunningServices() <= 0
                    && app.getIsolatedEntryPoint() == null) {
                // No op.
            } else {
                // Keeping this process, update its uid.
                updateAppUidRecLSP(app);
            }
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    private void updateAppUidRecLSP(ProcessRecordInternal app) {
        final UidRecordInternal uidRec = app.getUidRecord();
        if (uidRec != null) {
            uidRec.setEphemeral(app.isInstantApp());
            if (uidRec.getCurProcState() > app.getCurProcState()) {
                uidRec.setCurProcState(app.getCurProcState());
            }
            if (app.getServices().hasForegroundServices()) {
                uidRec.setHasForegroundServices(true);
            }
            uidRec.setCurCapability(uidRec.getCurCapability() | app.getCurCapability());
        }
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void updateUidsLSP(ActiveUidsInternal activeUids, final long nowElapsed) {
        // This compares previously set procstate to the current procstate in regards to whether
        // or not the app's network access will be blocked. So, this needs to be called before
        // we update the UidRecord's procstate by calling {@link UidRecord#setSetProcState}.
        mProcessList.incrementProcStateSeqLSP(activeUids);
        mCallback.onProcStateSeqIncremented(activeUids);

        ArrayList<UidRecordInternal> becameIdle = mTmpBecameIdle;
        becameIdle.clear();

        // Update from any uid changes.
        mCallback.onUpdateUidsStarted();
        for (int i = activeUids.size() - 1; i >= 0; i--) {
            final UidRecordInternal uidRec = activeUids.valueAt(i);
            if (uidRec.getCurProcState() != PROCESS_STATE_NONEXISTENT) {
                if (uidRec.getSetProcState() != uidRec.getCurProcState()
                        || uidRec.getSetCapability() != uidRec.getCurCapability()
                        || uidRec.isSetAllowListed() != uidRec.isCurAllowListed()
                        || uidRec.getProcAdjChanged()) {
                    int uidChange = 0;
                    final boolean shouldLog = mLogger.shouldLog(uidRec.getUid());
                    if (DEBUG_UID_OBSERVERS) {
                        Slog.i(TAG_UID_OBSERVERS, "Changes in " + uidRec
                                + ": proc state from " + uidRec.getSetProcState() + " to "
                                + uidRec.getCurProcState() + ", capability from "
                                + uidRec.getSetCapability() + " to " + uidRec.getCurCapability()
                                + ", allowlist from " + uidRec.isSetAllowListed()
                                + " to " + uidRec.isCurAllowListed()
                                + ", procAdjChanged: " + uidRec.getProcAdjChanged());
                    }
                    if (ActivityManager.isProcStateBackground(uidRec.getCurProcState())
                            && !uidRec.isCurAllowListed()) {
                        // UID is now in the background (and not on the temp allowlist).  Was it
                        // previously in the foreground (or on the temp allowlist)?
                        // Or, it wasn't in the foreground / allowlist, but its last background
                        // timestamp is also 0, this means it's never been in the
                        // foreground / allowlist since it's born at all.
                        if (!ActivityManager.isProcStateBackground(uidRec.getSetProcState())
                                || uidRec.isSetAllowListed()
                                || uidRec.getLastBackgroundTime() == 0) {
                            uidRec.setLastBackgroundTime(nowElapsed);
                            mCallback.onUidLastBackgroundTimeUpdated(uidRec, nowElapsed, mLogger);
                        }
                        if (uidRec.isIdle() && !uidRec.isSetIdle()) {
                            uidChange |= UidRecord.CHANGE_IDLE;
                            if (uidRec.getSetProcState() != PROCESS_STATE_NONEXISTENT) {
                                // don't stop the bg services if it's just started.
                                becameIdle.add(uidRec);
                            }
                        }
                    } else {
                        if (uidRec.isIdle()) {
                            uidChange |= UidRecord.CHANGE_ACTIVE;
                            EventLogTags.writeAmUidActive(uidRec.getUid());
                            uidRec.setIdle(false);
                        }
                        uidRec.setLastBackgroundTime(0);
                        uidRec.setLastIdleTime(0);
                        if (shouldLog) {
                            mLogger.logClearLastBackgroundTime(uidRec.getUid());
                        }
                    }
                    final boolean wasCached = uidRec.getSetProcState()
                            > ActivityManager.PROCESS_STATE_RECEIVER;
                    final boolean isCached = uidRec.getCurProcState()
                            > ActivityManager.PROCESS_STATE_RECEIVER;
                    if (wasCached != isCached
                            || uidRec.getSetProcState() == PROCESS_STATE_NONEXISTENT) {
                        uidChange |= isCached ? UidRecord.CHANGE_CACHED :
                                UidRecord.CHANGE_UNCACHED;
                    }
                    if (uidRec.getSetCapability() != uidRec.getCurCapability()) {
                        uidChange |= UidRecord.CHANGE_CAPABILITY;
                    }
                    if (uidRec.getSetProcState() != uidRec.getCurProcState()) {
                        uidChange |= UidRecord.CHANGE_PROCSTATE;
                    }
                    if (uidRec.getProcAdjChanged()) {
                        uidChange |= UidRecord.CHANGE_PROCADJ;
                    }
                    int oldProcState = uidRec.getSetProcState();
                    int oldCapability = uidRec.getSetCapability();
                    uidRec.setSetProcState(uidRec.getCurProcState());
                    uidRec.setSetCapability(uidRec.getCurCapability());
                    uidRec.setSetAllowListed(uidRec.isCurAllowListed());
                    uidRec.setSetIdle(uidRec.isIdle());
                    uidRec.setProcAdjChanged(false);
                    if (shouldLog
                            && ((uidRec.getSetProcState() != oldProcState)
                            || (uidRec.getSetCapability() != oldCapability))) {
                        int flags = 0;
                        if (uidRec.isSetAllowListed()) {
                            flags |= 1;
                        }
                        mLogger.logUidStateChanged(uidRec.getUid(),
                                uidRec.getSetProcState(), oldProcState,
                                uidRec.getSetCapability(), oldCapability,
                                flags);
                    }
                    mCallback.onUidUpdated(uidRec, uidChange);
                }
            }
        }
        mCallback.onUpdateUidsFinished(activeUids, nowElapsed, becameIdle);
    }

    /**
     * Return true if we should kill excessive cached/empty processes.
     */
    private boolean shouldKillExcessiveProcesses(long nowUptime) {
        final long lastUserUnlockingUptime = mGlobalState.getLastUserUnlockingUptime();

        if (lastUserUnlockingUptime == 0) {
            // No users have been unlocked.
            return !mOomConstants.mNoKillCachedProcessesUntilBootCompleted;
        }
        final long noKillCachedProcessesPostBootCompletedDurationMillis =
                mOomConstants.mNoKillCachedProcessesPostBootCompletedDurationMillis;
        if ((lastUserUnlockingUptime + noKillCachedProcessesPostBootCompletedDurationMillis)
                > nowUptime) {
            return false;
        }
        return true;
    }

    @VisibleForTesting
    public final OomAdjWindowCalculator mTmpOomAdjWindowCalculator =
            new OomAdjWindowCalculator();

    /**
     * The computeOomAdjFromActivitiesIfNecessary method computes the initial importance of the
     * process which contains activities and is not the global top. The importance are stored at the
     * ProcessRecordInternal's cached fields.
     * The method is called during computeOomAdjLSP(), on the same thread.
     */
    @VisibleForTesting
    public final class OomAdjWindowCalculator {
        private ProcessRecordInternal mApp;
        private @OomAdjust int mAdj;
        private boolean mForegroundActivities;
        private boolean mHasVisibleActivities;
        private @ProcessState int mProcState;
        private @SchedGroup int mSchedGroup;
        private @ProcessState int mProcessStateCurTop;
        private String mAdjType;
        private boolean mReportDebugMsgs;

        @VisibleForTesting
        @GuardedBy("this.OomAdjuster.mServiceLock")
        public @OomAdjust int getAdj() {
            return mAdj;
        }

        @GuardedBy("this.OomAdjuster.mServiceLock")
        void computeOomAdjFromActivitiesIfNecessary(ProcessRecordInternal app, @OomAdjust int adj,
                boolean foregroundActivities, boolean hasVisibleActivities,
                @ProcessState int procState, @SchedGroup int schedGroup,
                @ProcessState int processCurTop, boolean reportDebugMsgs) {
            if (app.getCachedAdj() != INVALID_ADJ) {
                return;
            }
            initialize(app, adj, foregroundActivities, hasVisibleActivities, procState,
                    schedGroup, processCurTop, reportDebugMsgs);

            final int flags = mApp.getActivityStateFlags();
            if ((flags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0) {
                onVisibleActivity(flags);
            } else if ((flags & ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED) != 0) {
                onPausedActivity();
            } else if ((flags & ACTIVITY_STATE_FLAG_IS_STOPPING) != 0) {
                onStoppingActivity((flags & ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING) != 0);
            } else {
                final long ts = mApp.getPerceptibleTaskStoppedTimeMillis();
                onOtherActivity(ts);
            }

            if (Flags.oomadjusterVisLaddering() && Flags.removeLruSpamPrevention()) {
                // Do nothing.
                // When these flags are enabled, processes within the vis oom score range will
                // have vis+X oom scores according their position in the LRU list with respect
                // to the other vis processes, rather than their activity's taskLayer, which is
                // not handling all the cases for apps with multi-activities and multi-processes,
                // because there is no direct connection between the activities and bindings
                // (processes) of an app.
            } else {
                if (mAdj == VISIBLE_APP_ADJ) {
                    final int taskLayer = flags & ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
                    final int minLayer = Math.min(VISIBLE_APP_LAYER_MAX, taskLayer);
                    mAdj += minLayer;
                }
            }

            mApp.setCachedAdj(mAdj);
            mApp.setCachedForegroundActivities(mForegroundActivities);
            mApp.setCachedProcState(mProcState);
            mApp.setCachedSchedGroup(mSchedGroup);
            mApp.setCachedAdjType(mAdjType);
        }

        /** Initializes the calculator for a new process evaluation. */
        @VisibleForTesting
        public void initialize(ProcessRecordInternal app, @OomAdjust int adj,
                boolean foregroundActivities, boolean hasVisibleActivities,
                @ProcessState int procState, @SchedGroup int schedGroup,
                @ProcessState int processStateCurTop, boolean reportDebugMsgs) {
            this.mApp = app;
            this.mAdj = adj;
            this.mForegroundActivities = foregroundActivities;
            this.mHasVisibleActivities = hasVisibleActivities;
            this.mProcState = procState;
            this.mSchedGroup = schedGroup;
            this.mProcessStateCurTop = processStateCurTop;
            this.mAdjType = app.getAdjType();
            this.mReportDebugMsgs = reportDebugMsgs;
        }

        void onVisibleActivity(int flags) {
            // App has a visible activity; only upgrade adjustment.
            if (mAdj > VISIBLE_APP_ADJ) {
                mAdj = VISIBLE_APP_ADJ;
                mAdjType = "vis-activity";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to vis-activity: " + mApp);
                }
            }
            if (mProcState > mProcessStateCurTop) {
                mProcState = mProcessStateCurTop;
                mAdjType = "vis-activity";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to vis-activity (top): " + mApp);
                }
            }
            if (mSchedGroup < SCHED_GROUP_DEFAULT) {
                mSchedGroup = SCHED_GROUP_DEFAULT;
            }
            if ((flags & WindowProcessController.ACTIVITY_STATE_FLAG_RESUMED_SPLIT_SCREEN) != 0) {
                // Another side of split should be the current global top. Use the same top
                // priority for this non-top split.
                mSchedGroup = SCHED_GROUP_TOP_APP;
                mAdjType = "resumed-split-screen-activity";
            } else if ((flags
                    & WindowProcessController.ACTIVITY_STATE_FLAG_PERCEPTIBLE_FREEFORM) != 0) {
                // The recently used non-top visible freeform app.
                mSchedGroup = SCHED_GROUP_TOP_APP;
                mAdjType = "perceptible-freeform-activity";
            } else if ((flags
                    & WindowProcessController.ACTIVITY_STATE_FLAG_VISIBLE_MULTI_WINDOW_MODE) != 0) {
                // Currently the only case is from freeform apps which are not close to top.
                mSchedGroup = SCHED_GROUP_FOREGROUND_WINDOW;
                mAdjType = "vis-multi-window-activity";
            } else if ((flags
                    & WindowProcessController.ACTIVITY_STATE_FLAG_OCCLUDED_FREEFORM) != 0) {
                mSchedGroup = SCHED_GROUP_BACKGROUND;
                mAdjType = "occluded-freeform-activity";
            }
            mForegroundActivities = true;
            mHasVisibleActivities = true;
        }

        void onPausedActivity() {
            if (mAdj > PERCEPTIBLE_APP_ADJ) {
                mAdj = PERCEPTIBLE_APP_ADJ;
                mAdjType = "pause-activity";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to pause-activity: "  + mApp);
                }
            }
            if (mProcState > mProcessStateCurTop) {
                mProcState = mProcessStateCurTop;
                mAdjType = "pause-activity";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to pause-activity (top): "  + mApp);
                }
            }
            if (mSchedGroup < SCHED_GROUP_DEFAULT) {
                mSchedGroup = SCHED_GROUP_DEFAULT;
            }
            mForegroundActivities = true;
            mHasVisibleActivities = false;
        }

        void onStoppingActivity(boolean finishing) {
            if (mAdj > PERCEPTIBLE_APP_ADJ) {
                mAdj = PERCEPTIBLE_APP_ADJ;
                mAdjType = "stop-activity";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise adj to stop-activity: "  + mApp);
                }
            }

            // For the process state, we will at this point consider the process to be cached. It
            // will be cached either as an activity or empty depending on whether the activity is
            // finishing. We do this so that we can treat the process as cached for purposes of
            // memory trimming (determining current memory level, trim command to send to process)
            // since there can be an arbitrary number of stopping processes and they should soon all
            // go into the cached state.
            if (!finishing) {
                if (mProcState > PROCESS_STATE_LAST_ACTIVITY) {
                    mProcState = PROCESS_STATE_LAST_ACTIVITY;
                    mAdjType = "stop-activity";
                    if (mReportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to stop-activity: " + mApp);
                    }
                }
            }
            mForegroundActivities = true;
            mHasVisibleActivities = false;
        }

        /**
         * Updates the adjustment values for a process with activities that are not visible, paused,
         * or stopping. This handles cases like cached activities and perceptible tasks.
         */
        @VisibleForTesting
        public void onOtherActivity(long perceptibleTaskStoppedTimeMillis) {
            if (mProcState > PROCESS_STATE_CACHED_ACTIVITY) {
                mProcState = PROCESS_STATE_CACHED_ACTIVITY;
                mAdjType = "cch-act";
                if (mReportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to cached activity: " + mApp);
                }
            }
            if (Flags.perceptibleTasks() && mAdj > PERCEPTIBLE_MEDIUM_APP_ADJ) {
                if (perceptibleTaskStoppedTimeMillis >= 0) {
                    final long now = mInjector.getUptimeMillis();
                    if (now - perceptibleTaskStoppedTimeMillis < PERCEPTIBLE_TASK_TIMEOUT_MILLIS) {
                        mAdj = PERCEPTIBLE_MEDIUM_APP_ADJ;
                        mAdjType = "perceptible-act";
                        if (mProcState > PROCESS_STATE_IMPORTANT_BACKGROUND) {
                            mProcState = PROCESS_STATE_IMPORTANT_BACKGROUND;
                        }

                        maybeSetProcessFollowUpUpdateLocked(mApp,
                                perceptibleTaskStoppedTimeMillis + PERCEPTIBLE_TASK_TIMEOUT_MILLIS,
                                now);
                    } else if (mAdj > PREVIOUS_APP_ADJ) {
                        mAdj = PREVIOUS_APP_ADJ;
                        mAdjType = "stale-perceptible-act";
                        if (mProcState > PROCESS_STATE_LAST_ACTIVITY) {
                            mProcState = PROCESS_STATE_LAST_ACTIVITY;
                        }
                    }
                }
            }
            mHasVisibleActivities = false;
        }
    }

    protected boolean isDeviceFullyAwake() {
        return mGlobalState.isAwake();
    }

    protected boolean isScreenOnOrAnimatingLocked(ProcessRecordInternal state) {
        return isDeviceFullyAwake() || state.isRunningRemoteAnimation();
    }

    protected boolean isBackupProcess(ProcessRecordInternal app) {
        return app == mGlobalState.getBackupTarget(app.userId);
    }

    protected boolean isLastMemoryLevelNormal() {
        return mGlobalState.isLastMemoryLevelNormal();
    }

    protected boolean isReceivingBroadcast(ProcessRecordInternal app) {
        return app.getReceivers().isReceivingBroadcast();
    }

    protected int getTopProcessState() {
        return mGlobalState.getTopProcessState();
    }

    protected boolean useTopSchedGroupForTopProcess() {
        if (mGlobalState.isUnlocking()) {
            // Keyguard is unlocking, suppress the top process priority for now.
            return false;
        }
        if (mGlobalState.hasExpandedNotificationShade()) {
            // The notification shade is occluding the top process, suppress top.
            return false;
        }
        return true;
    }

    protected ProcessRecordInternal getTopProcess() {
        return mGlobalState.getTopProcess();
    }

    protected boolean isHomeProcess(ProcessRecordInternal proc) {
        return mGlobalState.getHomeProcess() == proc;
    }

    protected boolean isHeavyWeightProcess(ProcessRecordInternal proc) {
        return mGlobalState.getHeavyWeightProcess() == proc;
    }

    protected boolean isVisibleDozeUiProcess(ProcessRecordInternal proc) {
        return mGlobalState.getShowingUiWhileDozingProcess() == proc;
    }

    protected boolean isPreviousProcess(ProcessRecordInternal proc) {
        return mGlobalState.getPreviousProcess() == proc;
    }

    /**
     * @return The proposed change to the schedGroup.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    protected @SchedGroup int setIntermediateAdjLSP(ProcessRecordInternal app, @OomAdjust int adj,
            @SchedGroup int schedGroup) {
        app.setCurRawAdj(adj);

        adj = applyBindAboveClientToAdj(app.getServices().hasBindAboveClient(), adj);
        if (adj > app.getMaxAdj()) {
            adj = app.getMaxAdj();
            if (adj <= PERCEPTIBLE_LOW_APP_ADJ) {
                schedGroup = SCHED_GROUP_DEFAULT;
            }
        }

        if (app.isZramWrittenBack()) {
            app.setCurAdj(Math.min(adj, getAdjForZramWriteback()));
        } else {
            app.setCurAdj(adj);
        }
        return schedGroup;
    }

    private static @OomAdjust int applyBindAboveClientToAdj(boolean hasAboveClient,
            @OomAdjust int adj) {
        if (hasAboveClient) {
            // If this process has bound to any services with BIND_ABOVE_CLIENT,
            // then we need to drop its adjustment to be lower than the service's
            // in order to honor the request.  We want to drop it by one adjustment
            // level...  but there is special meaning applied to various levels so
            // we will skip some of them.
            if (adj < FOREGROUND_APP_ADJ) {
                // System process will not get dropped, ever
            } else if (adj < VISIBLE_APP_ADJ) {
                adj = VISIBLE_APP_ADJ;
            } else if (adj < PERCEPTIBLE_APP_ADJ) {
                adj = PERCEPTIBLE_APP_ADJ;
            } else if (adj < PERCEPTIBLE_LOW_APP_ADJ) {
                adj = PERCEPTIBLE_LOW_APP_ADJ;
            } else if (adj < SERVICE_ADJ) {
                adj = SERVICE_ADJ;
            } else if (adj < CACHED_APP_MIN_ADJ) {
                adj = CACHED_APP_MIN_ADJ;
            } else if (adj < CACHED_APP_MAX_ADJ) {
                adj++;
            }
        }
        return adj;
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void setIntermediateProcStateLSP(ProcessRecordInternal state,
            @ProcessState int procState) {
        state.setCurProcState(procState);
        state.setCurRawProcState(procState);
    }

    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void setIntermediateSchedGroupLSP(ProcessRecordInternal state,
            @SchedGroup int schedGroup) {
        // Put bound foreground services in a special sched group for additional
        // restrictions on screen off
        if (state.getCurProcState() >= PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                && !isDeviceFullyAwake()
                && !state.getScheduleLikeTopApp()) {
            if (schedGroup > SCHED_GROUP_RESTRICTED) {
                schedGroup = SCHED_GROUP_RESTRICTED;
            }
        }

        state.setCurrentSchedulingGroup(schedGroup);
    }

    /**
     * Computes the impact on {@code app} the service connections from {@code client} has.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    public abstract boolean computeServiceHostOomAdjLSP(ConnectionRecordInternal cr,
            ProcessRecordInternal app, ProcessRecordInternal client, long now, boolean dryRun);

    /**
     * Computes the impact on {@code app} the provider connections from {@code client} has.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    public abstract boolean computeProviderHostOomAdjLSP(ContentProviderConnectionInternal conn,
            ProcessRecordInternal app, ProcessRecordInternal client, boolean dryRun);

    /** Determines the default process capabilities based on its current process state. */
    // LINT.IfChange(getDefaultCapability)
    @VisibleForTesting
    public @ProcessCapability int getDefaultCapability(ProcessRecordInternal app,
            @ProcessState int procState) {
        final @ProcessCapability int networkCapabilities =
                NetworkPolicyManager.getDefaultProcessNetworkCapabilities(procState);
        final @ProcessCapability int baseCapabilities;
        switch (procState) {
            case PROCESS_STATE_PERSISTENT:
            case PROCESS_STATE_PERSISTENT_UI:
            case PROCESS_STATE_TOP:
                if (Flags.explicitCpuCapabilityForTopProcesses()) {
                    baseCapabilities =
                            PROCESS_CAPABILITY_ALL & (~ALL_CPU_TIME_CAPABILITIES); // BFSL allowed
                } else {
                    baseCapabilities = PROCESS_CAPABILITY_ALL;
                }
                break;
            case PROCESS_STATE_BOUND_TOP:
                if (app.hasActiveInstrumentation()) {
                    baseCapabilities = PROCESS_CAPABILITY_BFSL
                            | PROCESS_CAPABILITY_INSTRUMENTATION_DEFAULTS;
                } else {
                    baseCapabilities = PROCESS_CAPABILITY_BFSL;
                }
                break;
            case PROCESS_STATE_FOREGROUND_SERVICE:
                if (app.hasActiveInstrumentation()) {
                    baseCapabilities = PROCESS_CAPABILITY_INSTRUMENTATION_DEFAULTS;
                } else {
                    // Capability from foreground service is conditional depending on
                    // foregroundServiceType in the manifest file and the
                    // mAllowWhileInUsePermissionInFgs flag.
                    baseCapabilities = PROCESS_CAPABILITY_NONE;
                }
                break;
            default:
                baseCapabilities = PROCESS_CAPABILITY_NONE;
                break;
        }
        return baseCapabilities | networkCapabilities;
    }
    // LINT.ThenChange(CapabilityController.java:evaluateProcStatePolicy)

    // LINT.IfChange(getCpuCapability)
    @CpuTimeReasons
    private static int getCpuTimeReasons(ProcessRecordInternal app,
            @ProcessState int procState, boolean hasForegroundActivities) {
        // Note: persistent processes always get CPU_TIME with reason CPU_TIME_REASON_OTHER.
        // Currently, we only cite CPU_TIME_REASON_OTHER for all reasons. More specific reasons
        // can be used when they become interesting to observe.
        final UidRecordInternal uidRec = app.getUidRecord();
        if (uidRec != null && uidRec.isCurAllowListed()) {
            // Process is in the power allowlist.
            return CPU_TIME_REASON_ALLOW_LIST;
        }
        if (procState <= PROCESS_STATE_TOP && procState != PROCESS_STATE_UNKNOWN) {
            return CPU_TIME_REASON_OTHER;
        }
        if (hasForegroundActivities) {
            // TODO: b/402987519 - This grants the Top Sleeping process CPU_TIME but eventually
            //  should not.
            // Process has user perceptible activities.
            return CPU_TIME_REASON_OTHER;
        }
        if (app.getServices().hasExecutingServices()) {
            // Ensure that services get cpu time during start-up and tear-down.
            return CPU_TIME_REASON_OTHER;
        }
        if (app.getServices().hasForegroundServices()) {
            return CPU_TIME_REASON_OTHER;
        }
        if (app.getReceivers().isReceivingBroadcast()) {
            return CPU_TIME_REASON_OTHER;
        }
        if (app.hasActiveInstrumentation()) {
            return CPU_TIME_REASON_OTHER;
        }

        // TODO(b/370817323): Populate this method with all of the reasons to keep a process
        //  unfrozen.
        return CPU_TIME_REASON_NONE;
    }

    protected static int getCpuCapability(ProcessRecordInternal app, @ProcessState int procState,
            boolean hasForegroundActivities) {
        final int reasons = getCpuTimeReasons(app, procState, hasForegroundActivities);
        app.addCurCpuTimeReasons(reasons);
        return (reasons != CPU_TIME_REASON_NONE) ? PROCESS_CAPABILITY_CPU_TIME : 0;
    }
    // LINT.ThenChange(CapabilityController.java:evaluateCpuTimePolicy)

    // Grant PROCESS_CAPABILITY_IMPLICIT_CPU_TIME to processes based on oom adj score.
    protected int getImplicitCpuCapability(ProcessRecordInternal app, @OomAdjust int adj) {
        if (adj < mOomConstants.mFreezerCutoffAdj
                || app.getMaxAdj() < mOomConstants.mFreezerCutoffAdj) {
            app.addCurImplicitCpuTimeReasons(IMPLICIT_CPU_TIME_REASON_OTHER);
            if (Flags.enableCapabilityControllerComputation()) {
                app.getProcessNode().setHasIntrinsicImplicitCpuTime(true);
            }
            return PROCESS_CAPABILITY_IMPLICIT_CPU_TIME;
        }
        return 0;
    }

    /**
     * @return the BFSL capability from a client (of a service binding or provider).
     */
    protected int getBfslCapabilityFromClient(ProcessRecordInternal client) {
        // Procstates above FGS should always have this flag. We shouldn't need this logic,
        // but let's do it just in case.
        if (client.getCurProcState() < PROCESS_STATE_FOREGROUND_SERVICE) {
            return PROCESS_CAPABILITY_BFSL;
        }
        // Otherwise, use the process's cur capability.

        // Note: BFSL is a per-UID check, not per-process, but here, the BFSL capability is still
        // propagated on a per-process basis.
        //
        // For example, consider this case:
        // - There are App 1 and App 2.
        // - App 1 has two processes
        //   Proc #1A, procstate BFGS with CAPABILITY_BFSL
        //   Proc #1B, procstate FGS with no CAPABILITY_BFSL (i.e. process has a short FGS)
        //        And this process binds to Proc #2 of App 2.
        //
        //       (Note because #1A has CAPABILITY_BFSL, App 1's UidRecord has CAPABILITY_BFSL.)
        //
        // - App 2 has one process:
        //   Proc #2, procstate FGS due to the above binding, _with no CAPABILITY_BFSL_.
        //
        // In this case, #2 will not get CAPABILITY_BFSL because the binding client (#1B)
        // doesn't have this capability. (Even though App 1's UidRecord has it.)
        //
        // This may look weird, because App 2 _is_ still BFSL allowed, because "it's bound by
        // an app that is BFSL-allowed". (See [bookmark: 61867f60-007c-408c-a2c4-e19e96056135]
        // in ActiveServices.)
        //
        // So why don't we propagate PROCESS_CAPABILITY_BFSL from App 1's UID record?
        // This is because short-FGS acts like "below BFGS" as far as BFSL is concerned,
        // similar to how JobScheduler jobs are below BFGS and apps can't start FGS from there.
        //
        // If #1B was running a job instead of a short-FGS, then its procstate would be below BFGS.
        // Then #2's procstate would also be below BFGS. So #2 wouldn't get CAPABILITY_BFSL.
        // Similarly, if #1B has a short FGS, even though the procstate of #1B and #2 would be FGS,
        // they both still wouldn't get CAPABILITY_BFSL.
        //
        // However, again, because #2 is bound by App 1, which is BFSL-allowed (because of #1A)
        // App 2 would still BFSL-allowed, due to the aforementioned check in ActiveServices.
        return client.getCurCapability() & PROCESS_CAPABILITY_BFSL;
    }

    /**
     * @return the CPU capability from a client (of a service binding or provider).
     */
    protected static int getCpuCapabilitiesFromClient(ProcessRecordInternal app,
            ProcessRecordInternal client, OomAdjusterImpl.Connection conn) {
        // LINT.IfChange(getCpuCapabilitiesFromTransmissionType)
        final int clientCpuCaps = client.getCurCapability() & ALL_CPU_TIME_CAPABILITIES;
        final @OomAdjusterImpl.Connection.CpuTimeTransmissionType int transmissionType =
                (conn != null) ? conn.cpuTimeTransmissionType() : CPU_TIME_TRANSMISSION_NONE;

        if (transmissionType == CPU_TIME_TRANSMISSION_NONE) {
            // The binding does not transmit CPU_TIME capabilities in any way.
            return 0;
        }
        // LINT.ThenChange(CapabilityController.java:getCpuTimeFilterFromTransmissionType)

        final @CpuTimeReasons int clientCpuReasons = client.getCurCpuTimeReasons();
        final @ImplicitCpuTimeReasons int clientImplicitCpuReasons =
                client.getCurImplicitCpuTimeReasons();
        @CpuTimeReasons int cpuReasons = CPU_TIME_REASON_NONE;
        @ImplicitCpuTimeReasons int implicitCpuReasons = IMPLICIT_CPU_TIME_REASON_NONE;

        if ((clientCpuCaps & PROCESS_CAPABILITY_CPU_TIME) != 0) {
            if (clientCpuReasons == CPU_TIME_REASON_TRANSMITTED_LEGACY) {
                // Client has CPU_TIME only for a legacy reason.
                cpuReasons = CPU_TIME_REASON_TRANSMITTED_LEGACY;
            } else if (transmissionType == CPU_TIME_TRANSMISSION_LEGACY) {
                // Binding only transmits CPU_TIME for a legacy reason.
                cpuReasons = CPU_TIME_REASON_TRANSMITTED_LEGACY;
            } else {
                cpuReasons = CPU_TIME_REASON_TRANSMITTED;
            }
        }
        if ((clientCpuCaps & PROCESS_CAPABILITY_IMPLICIT_CPU_TIME) != 0) {
            if (clientImplicitCpuReasons == IMPLICIT_CPU_TIME_REASON_TRANSMITTED_LEGACY) {
                // Client has IMPLICIT_CPU_TIME only for a legacy reason.
                implicitCpuReasons = IMPLICIT_CPU_TIME_REASON_TRANSMITTED_LEGACY;
            } else if (transmissionType == CPU_TIME_TRANSMISSION_LEGACY) {
                // Binding only transmits IMPLICIT_CPU_TIME for a legacy reason.
                implicitCpuReasons = IMPLICIT_CPU_TIME_REASON_TRANSMITTED_LEGACY;
            } else {
                implicitCpuReasons = IMPLICIT_CPU_TIME_REASON_TRANSMITTED;
            }
        }
        app.addCurCpuTimeReasons(cpuReasons);
        app.addCurImplicitCpuTimeReasons(implicitCpuReasons);
        return clientCpuCaps;
    }

    /**
     * @return the audio capability from a client (of a service binding or provider).
     */
    protected int getAudioCapabilitiesFromClient(ProcessRecordInternal client) {
        // Similar to bfsl/cpu, there isn't a compelling reason to prevent the capability
        // to control/play audio from propagating through binds: if the client has the
        // capability, we generally want the service it binds to to hold the capability as well
        // (e.g. TTS).
        return client.getCurCapability() & PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
    }

    /** Inform the oomadj observer of changes to oomadj. Used by tests. */
    @GuardedBy("mServiceLock")
    protected void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        mCallback.onReportOomAdjMessage(msg);
    }

    /** Applies the computed oomadj. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    protected void applyOomAdjLSP(ProcessRecordInternal state, boolean isBatchingOomAdj) {
        final UidRecordInternal uidRec = state.getUidRecord();

        final boolean reportDebugMsgs = DEBUG_SWITCH || DEBUG_OOM_ADJ
                        || mGlobalState.isDebugEnabled(state);

        if (state.getCurRawAdj() != state.getSetRawAdj()) {
            state.setSetRawAdj(state.getCurRawAdj());
        }

        if (state.getCurAdj() != state.getSetAdj()) {
            mCallback.onOomAdjustChanged(state.getSetAdj(), state.getCurAdj(), state);
            if (isBatchingOomAdj && mOomConstants.mEnableBatchingOomAdj) {
                mProcsToOomAdj.add(state);
            } else {
                boolean forLmkdOnly = false;
                if (state.isZramWrittenBack()) {
                    forLmkdOnly = true;
                }
                mInjector.setOomAdj(state.getPid(), state.uid, state.getCurAdj(), forLmkdOnly);
            }

            if (reportDebugMsgs) {
                String msg = "Set " + state.getPid() + " " + state.processName + " adj "
                        + state.getCurAdj() + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            state.setSetAdj(state.getCurAdj());
            if (uidRec != null) {
                uidRec.setProcAdjChanged(true);
            }
        }
    }

    /** Applies the computed oomadj, procstate and sched group values and freezes them in set* */
    @GuardedBy({"mServiceLock", "mProcLock"})
    protected boolean applyResultsLSP(ProcessRecordInternal state, boolean doingAll, long now,
            long nowElapsed, @OomAdjReason int oomAdjReason, boolean isBatchingOomAdj) {
        final boolean reportDebugMsgs = DEBUG_SWITCH || DEBUG_OOM_ADJ
                        || mGlobalState.isDebugEnabled(state);
        final @OomAdjust int oldOomAdj = state.getSetAdj();
        boolean success = true;
        int changes = 0;
        applyOomAdjLSP(state, isBatchingOomAdj);
        final @SchedGroup int curSchedGroup = state.getCurrentSchedulingGroup();
        if (state.getWaitingToKill() != null && !state.getReceivers().isReceivingBroadcast()
                && ActivityManager.isProcStateBackground(state.getCurProcState())
                && !state.getHasStartedServices()) {
            state.killLocked(state.getWaitingToKill(), ApplicationExitInfo.REASON_USER_REQUESTED,
                    ApplicationExitInfo.SUBREASON_REMOVE_TASK, true);
            success = false;
        } else if (state.getSetSchedGroup() != curSchedGroup) {
            int oldSchedGroup = state.getSetSchedGroup();
            state.setSetSchedGroup(curSchedGroup);
            if (reportDebugMsgs) {
                String msg = "Setting sched group of " + state.processName
                        + " to " + curSchedGroup + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            int processGroup;
            switch (curSchedGroup) {
                case SCHED_GROUP_BACKGROUND:
                    processGroup = THREAD_GROUP_BACKGROUND;
                    break;
                case SCHED_GROUP_TOP_APP:
                case SCHED_GROUP_TOP_APP_BOUND:
                    processGroup = THREAD_GROUP_TOP_APP;
                    break;
                case SCHED_GROUP_RESTRICTED:
                    processGroup = THREAD_GROUP_RESTRICTED;
                    break;
                case SCHED_GROUP_FOREGROUND_WINDOW:
                    processGroup = THREAD_GROUP_FOREGROUND_WINDOW;
                    break;
                default:
                    processGroup = THREAD_GROUP_DEFAULT;
                    break;
            }
            setAppAndChildProcessGroup(state, processGroup);
            try {
                final int renderThreadTid = state.getRenderThreadTid();
                if (curSchedGroup == SCHED_GROUP_TOP_APP) {
                    // do nothing if we already switched to RT
                    if (oldSchedGroup != SCHED_GROUP_TOP_APP) {
                        state.notifyTopProcChanged();
                        if (state.useFifoUiScheduling()) {
                            // Switch UI pipeline for app to SCHED_FIFO
                            state.setSavedPriority(Process.getThreadPriority(state.getPid()));
                            mCallback.setFifoPriority(state, true /* enable */);
                        } else {
                            // Boost priority for top app UI and render threads
                            mInjector.setThreadPriority(state.getPid(),
                                    THREAD_PRIORITY_TOP_APP_BOOST);
                            if (renderThreadTid != 0) {
                                try {
                                    mInjector.setThreadPriority(renderThreadTid,
                                            THREAD_PRIORITY_TOP_APP_BOOST);
                                } catch (IllegalArgumentException e) {
                                    // thread died, ignore
                                }
                            }
                        }
                    }
                } else if (oldSchedGroup == SCHED_GROUP_TOP_APP
                        && curSchedGroup != SCHED_GROUP_TOP_APP) {
                    state.notifyTopProcChanged();
                    if (state.useFifoUiScheduling()) {
                        // Reset UI pipeline to SCHED_OTHER
                        mCallback.setFifoPriority(state, false /* enable */);
                        mInjector.setThreadPriority(state.getPid(), state.getSavedPriority());
                    } else {
                        // Reset priority for top app UI and render threads
                        mInjector.setThreadPriority(state.getPid(), 0);
                    }

                    if (renderThreadTid != 0) {
                        mInjector.setThreadPriority(renderThreadTid, THREAD_PRIORITY_DISPLAY);
                    }
                }
            } catch (Exception e) {
                if (DEBUG_ALL) {
                    Slog.w(TAG, "Failed setting thread priority of " + state.getPid(), e);
                }
            }
        }
        if (state.getHasRepForegroundActivities() != state.getHasForegroundActivities()) {
            state.setRepForegroundActivities(state.getHasForegroundActivities());
            changes |= ProcessListInternal.ProcessChangeItem.CHANGE_ACTIVITIES;
        }

        updateAppFreezeStateLSP(state, oomAdjReason, false, oldOomAdj, state.getCurAdj());

        if (state.getReportedProcState() != state.getCurProcState()) {
            state.setReportedProcState(state.getCurProcState());
            state.setProcessStateToThread(state.getReportedProcState());
        }
        boolean forceUpdatePssTime = false;
        if (state.getSetProcState() == PROCESS_STATE_NONEXISTENT
                || ProcessList.procStatesDifferForMem(
                        state.getCurProcState(), state.getSetProcState())) {
            state.setLastStateTime(now);
            forceUpdatePssTime = true;
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "Process state change from "
                        + ProcessList.makeProcStateString(state.getSetProcState()) + " to "
                        + ProcessList.makeProcStateString(state.getCurProcState()) + " next pss in "
                        + (state.getNextPssTime() - now) + ": " + state);
            }
        }
        mCallback.onProcStateUpdated(state, state.getSetProcState(), state.getCurProcState(),
                state.getCurAdj(), now, nowElapsed, forceUpdatePssTime, doingAll, reportDebugMsgs);

        int oldProcState = state.getSetProcState();
        if (state.getSetProcState() != state.getCurProcState()) {
            maybeUpdateLastTopTime(state, now);
            state.setSetProcState(state.getCurProcState());
        }

        int oldCapability = state.getSetCapability();
        if (state.getCurCapability() != oldCapability) {
            state.setSetCapability(state.getCurCapability());
            state.setSetCpuTimeReasons(state.getCurCpuTimeReasons());
            state.setSetImplicitCpuTimeReasons(state.getCurImplicitCpuTimeReasons());
        }

        if (oldOomAdj != state.getCurAdj() || oldProcState != state.getCurProcState()
                || oldCapability != state.getCurCapability()) {
            if (android.os.Flags.perfettoSdkTracingV3()) {
                PerfettoTrace.instant(PROC_STATE_CATEGORY, "process_state_changed")
                        .beginProto()
                        .beginNested(PROCESS_STATE_CHANGED_EVENT)
                        .addField(UID, state.uid)
                        .addField(PID, state.getPid())
                        .addField(PREV_PROC_STATE,
                                  ActivityManager.processStateAmToProto(oldProcState))
                        .addField(CUR_PROC_STATE,
                                  ActivityManager.processStateAmToProto(state.getCurProcState()))
                        .addField(PREV_OOM_SCORE, oldOomAdj)
                        .addField(CUR_OOM_SCORE, state.getCurAdj())
                        .addField(PREV_CAPABILITY_FLAGS, oldCapability)
                        .addField(CUR_CAPABILITY_FLAGS, state.getCurCapability())
                        .addField(REASON, oomAdjReason)
                        .addField(SEQ_ID, mAdjSeq)
                        .addField(CPU_TIME_REASONS, state.getCurCpuTimeReasons())
                        .addField(IMPLICIT_CPU_TIME_REASONS, state.getCurImplicitCpuTimeReasons())
                        .endNested()
                        .endProto()
                        .emit();
            }
        }

        final boolean curBoundByNonBgRestrictedApp = state.isCurBoundByNonBgRestrictedApp();
        if (curBoundByNonBgRestrictedApp != state.isSetBoundByNonBgRestrictedApp()) {
            state.setSetBoundByNonBgRestrictedApp(curBoundByNonBgRestrictedApp);
            if (!curBoundByNonBgRestrictedApp && state.isBackgroundRestricted()) {
                mCallback.onProcessBackgroundRestricted(state);
            }
        }

        if (changes != 0) {
            mCallback.onProcessChanged(state, changes);
        }

        if (state.isCached() && !state.isSetCached()) {
            // Cached procs are eligible to get killed when in an UID idle and bg restricted.
            // However, we want to debounce state changes to avoid thrashing. Mark down when this
            // process became eligible and then schedule a check for eligible processes after
            // a background settling time, if needed.
            state.setLastCachedTime(nowElapsed);
            mCallback.onProcessCached(state, mLogger);
        }
        state.setSetCached(state.isCached());
        if (((oldProcState != state.getSetProcState()) || (oldOomAdj != state.getSetAdj()))
                && mLogger.shouldLog(state.uid)) {
            mLogger.logProcStateChanged(state.uid, state.getPid(),
                    state.getSetProcState(), oldProcState,
                    state.getSetAdj(), oldOomAdj);
        }

        return success;
    }

    /** Sets the initial process state and scheduling group for a newly attaching process. */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setAttachingProcessStatesLSP(ProcessRecordInternal app) {
        @SchedGroup int initialSchedGroup = SCHED_GROUP_DEFAULT;
        @ProcessState int initialProcState = PROCESS_STATE_CACHED_EMPTY;
            // Avoid freezing a freshly attached process.
        @ProcessCapability int initialCapability = ALL_CPU_TIME_CAPABILITIES;
        @ProcessState final int prevProcState = app.getCurProcState();
        final @OomAdjust int prevAdj = app.getCurRawAdj();
        // If the process has been marked as foreground, it is starting as the top app (with
        // Zygote#START_AS_TOP_APP_ARG), so boost the thread priority of its default UI thread.
        if (app.getHasForegroundActivities()) {
            try {
                // The priority must be the same as how does {@link #applyResultsLSP} set for
                // {@link SCHED_GROUP_TOP_APP}. We don't check render thread because it
                // is not ready when attaching.
                app.notifyTopProcChanged();
                if (app.useFifoUiScheduling()) {
                    mCallback.scheduleAsFifoPriority(app.getPid(), true);
                } else {
                    mInjector.setThreadPriority(app.getPid(), THREAD_PRIORITY_TOP_APP_BOOST);
                }
                if (isScreenOnOrAnimatingLocked(app)) {
                    initialSchedGroup = SCHED_GROUP_TOP_APP;
                    initialProcState = PROCESS_STATE_TOP;
                }
                initialCapability = PROCESS_CAPABILITY_ALL;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to pre-set top priority to " + app + " " + e);
            }
        }

        app.setCurrentSchedulingGroup(initialSchedGroup);
        app.setCurProcState(initialProcState);
        app.setCurRawProcState(initialProcState);
        app.setCurCapability(initialCapability);
        app.addCurCpuTimeReasons(CPU_TIME_REASON_OTHER);
        app.addCurImplicitCpuTimeReasons(IMPLICIT_CPU_TIME_REASON_OTHER);
        if (Flags.enableCapabilityControllerComputation()) {
            app.getProcessNode().setHasIntrinsicImplicitCpuTime(true);
        }

        if (!Flags.setInitialOomScoreAdj()) {
            // Original behavior (flag disabled)
            app.setCurRawAdj(FOREGROUND_APP_ADJ);
            app.setCurAdj(FOREGROUND_APP_ADJ);
        } else {
            String hostingType = mHostingTypeProvider.getHostingType(app);
            @OomAdjust int prevSetRawAdj = app.getPrevSetRawAdj();
            @OomAdjust int targetOomScore = getInitialRawAdjForHostingRecord(hostingType,
                    prevSetRawAdj);
            app.setCurRawAdj(targetOomScore);
            app.setCurAdj(targetOomScore);
        }

        app.setForcingToImportant(null);
        app.setHasShownUi(false);

        onProcessStateChanged(app, prevProcState);
        onProcessOomAdjChanged(app, prevAdj);
    }

    private @OomAdjust int getInitialRawAdjForHostingRecord(String hostingType,
            @OomAdjust int prevAdj) {
        return switch (hostingType) {
            // Broadcast receiver: Time-sensitive, should run with reasonable priority.
            case HOSTING_TYPE_BROADCAST -> !Flags.setInitialOomScoreAdjForTypeBroadcast()
                    ? FOREGROUND_APP_ADJ : PERCEPTIBLE_APP_ADJ; // 200

            // Backup process.
            case HOSTING_TYPE_BACKUP -> BACKUP_APP_ADJ; // 300

            // Restarting process: Use its previous score to avoid thrashing.
            case HOSTING_TYPE_RESTART -> prevAdj == INVALID_ADJ ? FOREGROUND_APP_ADJ : prevAdj;

            // Process started for package management (added app).
            case HOSTING_TYPE_ADDED_APPLICATION ->
                    !Flags.setInitialOomScoreAdjForTypeAddedApplication()
                            ? FOREGROUND_APP_ADJ : PREVIOUS_APP_ADJ; // 700

            // Low priority types.
            case HOSTING_TYPE_LINK_FAIL -> !Flags.setInitialOomScoreAdjForTypeLinkFailed()
                    ? FOREGROUND_APP_ADJ : CACHED_APP_MIN_ADJ; // 900
            case HOSTING_TYPE_ON_HOLD -> !Flags.setInitialOomScoreAdjForTypeOnHold()
                    ? FOREGROUND_APP_ADJ : CACHED_APP_MIN_ADJ; // 900

            // System process: Should use the defined system adjustment.
            case HOSTING_TYPE_SYSTEM -> SYSTEM_ADJ; // -900

            // Other cases: Default to FOREGROUND_APP_ADJ, matching the original behavior.
            default -> FOREGROUND_APP_ADJ; // 0
        };
    }

    private void maybeUpdateLastTopTime(ProcessRecordInternal state, long nowUptime) {
        if (state.getSetProcState() <= PROCESS_STATE_TOP
                && state.getCurProcState() > PROCESS_STATE_TOP) {
            state.setLastTopTime(nowUptime);
        }
    }

    /**
     * Updates the temporary allowlist state for a given UID and triggers an OOM adjustment update
     * for all processes belonging to that UID if the state changes.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    void setUidTempAllowlistStateLSP(int uid, boolean onAllowlist) {
        final UidRecordInternal uidRec = mActiveUids.get(uid);
        if (uidRec != null && uidRec.isCurAllowListed() != onAllowlist) {
            uidRec.setCurAllowListed(onAllowlist);
            for (int i = uidRec.getNumOfProcs() - 1; i >= 0; i--) {
                enqueueOomAdjTargetLocked(uidRec.getProcessRecordByIndex(i));
            }
            updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_ALLOWLIST);
        }
    }

    /** Dumps process list variables to a ProtoOutputStream for debugging and analysis. */
    @GuardedBy("mServiceLock")
    public void dumpProcessListVariablesLocked(ProtoOutputStream proto) {
        proto.write(ActivityManagerServiceDumpProcessesProto.ADJ_SEQ, mAdjSeq);
        proto.write(ActivityManagerServiceDumpProcessesProto.LRU_SEQ, mProcessList.getLruSeqLOSP());
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_NON_CACHED_PROCS,
                mNumNonCachedProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_SERVICE_PROCS, mNumServiceProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NEW_NUM_SERVICE_PROCS,
                mNewNumServiceProcs);
    }

    /** Dumps OOM adjustment sequence numbers (adjSeq, lruSeq) to a PrintWriter. */
    @GuardedBy("mServiceLock")
    public void dumpSequenceNumbersLocked(PrintWriter pw) {
        pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mProcessList.getLruSeqLOSP());
    }

    /** Dumps process counts (non-cached, cached, service) to a PrintWriter. */
    @GuardedBy("mServiceLock")
    public void dumpProcCountsLocked(PrintWriter pw) {
        pw.println("  mNumNonCachedProcs=" + mNumNonCachedProcs
                + " (" + mProcessList.getLruProcessesLOSP().size() + " total)"
                + " mNumCachedHiddenProcs=" + mNumCachedHiddenProcs
                + " mNumServiceProcs=" + mNumServiceProcs
                + " mNewNumServiceProcs=" + mNewNumServiceProcs);
    }

    /**
     * Return whether or not a process should be frozen.
     * A process is unfrozen only if it is important enough (see {@link #getCpuCapability} and
     * {@link #getImplicitCpuCapability}) or bound by something important enough (see
     * {@link #getCpuCapabilitiesFromClient}).
     */
    public static boolean getFreezePolicy(ProcessRecordInternal proc) {
        if (proc.isCachedAppFreezerExempt()) {
            return false;
        }
        if ((proc.getCurCapability() & ALL_CPU_TIME_CAPABILITIES) != 0) {
            return false;
        }
        // Default, freeze a process.
        return true;
    }

    /**
     * Updates the freeze state of an application based on the current policy and its OOM
     * adjustment state.
     */
    @GuardedBy({"mServiceLock", "mProcLock"})
    public void updateAppFreezeStateLSP(ProcessRecordInternal app, @OomAdjReason int oomAdjReason,
            boolean immediate, @OomAdjust int oldOomAdj, @OomAdjust int newOomAdj) {
        final boolean freezePolicy = getFreezePolicy(app);
        mCallback.onProcessFreezabilityChanged(app, freezePolicy, oomAdjReason, immediate,
                oldOomAdj, newOomAdj);
    }

    /**
     * Collects the given application process and all other processes reachable from it
     * (e.g., via service or content provider connections) into the provided list.
     *
     * @param app The initial application process from which to start collecting.
     * @param processesOut The list to populate with the collected ProcessRecordInternal objects.
     */
    @GuardedBy("mServiceLock")
    public void populateAllReachableProcessesLocked(ProcessRecordInternal app,
            ArrayList<ProcessRecordInternal> processesOut) {
        if (Flags.consolidateCollectReachable()) {
            processesOut.add(app);
            synchronized (mProcLock) {
                collectReachableProcessesLSP(processesOut);
            }
        } else {
            mTmpProcessSet.add(app);
            collectReachableProcessesLocked(mTmpProcessSet, processesOut);
            mTmpProcessSet.clear();
        }
    }

    /** Called when the process ends. */
    @GuardedBy("mServiceLock")
    public abstract void onProcessEndLocked(@NonNull ProcessRecordInternal app);

    /**
     * Called when the zram writeback state of a process has changed.
     */
    @GuardedBy("mServiceLock")
    public abstract void onZramWritebackStateChanged(@NonNull ProcessRecordInternal app,
            boolean inZramWritebackState);

    /**
     * Called when the process state is changed outside of the OomAdjuster.
     */
    @GuardedBy("mServiceLock")
    abstract void onProcessStateChanged(@NonNull ProcessRecordInternal app,
            @ProcessState int prevProcState);

    /**
     * Configure the oom_score_adj for zram writeback.
     */
    protected abstract int getAdjForZramWriteback();

    public abstract void configureAdjForZramWriteback(int adj);

    /**
     * Called when the oom adj is changed outside of the OomAdjuster.
     */
    @GuardedBy("mServiceLock")
    abstract void onProcessOomAdjChanged(@NonNull ProcessRecordInternal app,
            @OomAdjust int prevAdj);

    /**
     * Resets the internal state of the OomAdjuster. This is intended for use in testing
     * environments to ensure a clean state between test cases.
     */
    @VisibleForTesting
    public abstract void resetInternal();

    /**
     * Evaluate the service connection, return {@code true} if the client will change any state
     * (ie. ProcessState, oomAdj, capability, etc) of the service host process by the given
     * connection.
     */
    @GuardedBy("mServiceLock")
    public boolean evaluateServiceConnectionAdd(ProcessRecordInternal client,
            ProcessRecordInternal app,
            ConnectionRecordInternal cr) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        boolean needDryRun = false;
        if (app.getSetAdj() > client.getSetAdj()) {
            // The connection might elevate the importance of the service's oom adj score.
            needDryRun = true;
        } else if (app.getSetProcState() > client.getSetProcState()) {
            // The connection might elevate the importance of the service's process state.
            needDryRun = true;
        } else if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES
                            | Context.BIND_BYPASS_USER_NETWORK_RESTRICTIONS)
                && (app.getSetCapability() & client.getSetCapability())
                        != client.getSetCapability()) {
            // The connection might elevate the importance of the service's capabilities.
            needDryRun = true;
        } else if ((client.getSetCapability() & ~app.getSetCapability()
                & ALL_CPU_TIME_CAPABILITIES) != 0) {
            // The connection might grant CPU capability to the service.
            needDryRun = true;
        }

        if (needDryRun) {
            // Take a dry run of the computeServiceHostOomAdjLSP, this would't be expensive
            // since it's only evaluating one service connection.
            return computeServiceHostOomAdjLSP(cr, app, client, mInjector.getUptimeMillis(),
                    true /* dryRun */);
        }
        return false;
    }

    /**
     * Evaluates the potential impact of removing a service connection on the OOM adjustment scores
     * and process states of the involved processes.
     */
    @GuardedBy("mServiceLock")
    public boolean evaluateServiceConnectionRemoval(ProcessRecordInternal client,
            ProcessRecordInternal app, ConnectionRecordInternal cr) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        if (app.getSetAdj() >= client.getSetAdj()) {
            return true;
        } else if (app.getSetProcState() >= client.getSetProcState()) {
            return true;
        } else if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES
                            | Context.BIND_BYPASS_USER_NETWORK_RESTRICTIONS)
                && (app.getSetCapability() & client.getSetCapability())
                            != PROCESS_CAPABILITY_NONE) {
            return true;
        } else if ((client.getSetCapability() & app.getSetCapability()
                    & ALL_CPU_TIME_CAPABILITIES) != 0) {
            return true;
        }
        return false;
    }

    /**
     * Evaluates the potential impact of adding a content provider connection on the OOM adjustment
     * scores and process states of the involved processes.
     */
    @GuardedBy("mServiceLock")
    public boolean evaluateProviderConnectionAdd(ProcessRecordInternal client,
            ProcessRecordInternal app) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        boolean needDryRun = false;
        if (app.getSetAdj() > client.getSetAdj()) {
            needDryRun = true;
        } else if (app.getSetProcState() > client.getSetProcState()) {
            needDryRun = true;
        } else if ((client.getSetCapability() & ~app.getSetCapability()
                    & ALL_CPU_TIME_CAPABILITIES) != 0) {
            // The connection might grant CPU capability to the provider.
            needDryRun = true;
        }

        if (needDryRun) {
            return computeProviderHostOomAdjLSP(null, app, client, true /* dryRun */);
        }
        return false;
    }

    /**
     * Evaluates the potential impact of removing a content provider connection on the OOM
     * adjustment scores and process states of the involved processes.
     */
    @GuardedBy("mServiceLock")
    public boolean evaluateProviderConnectionRemoval(ProcessRecordInternal client,
            ProcessRecordInternal app) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        if (app.getSetAdj() >= client.getSetAdj()) {
            return true;
        } else if (app.getSetProcState() >= client.getSetProcState()) {
            return true;
        } else if ((client.getSetCapability() & app.getSetCapability()
                & ALL_CPU_TIME_CAPABILITIES) != 0) {
            return true;
        }

        return false;
    }

    private boolean evaluateConnectionPrelude(ProcessRecordInternal client,
            ProcessRecordInternal app) {
        if (client == null || app == null) {
            return true;
        }
        if (app.isSdkSandbox || app.isolated || app.isKilledByAm() || app.isKilled()) {
            // Let's always re-evaluate them for now.
            return true;
        }
        return false;
    }

    @GuardedBy("mServiceLock")
    protected void maybeSetProcessFollowUpUpdateLocked(ProcessRecordInternal proc,
            long updateUptimeMs, long now) {
        if (updateUptimeMs <= now) {
            // Time sensitive period has already passed. No need to schedule a follow up.
            return;
        }

        mFollowUpUpdateSet.add(proc);
        proc.setFollowupUpdateUptimeMs(updateUptimeMs);

        scheduleFollowUpOomAdjusterUpdateLocked(updateUptimeMs, now);
    }


    @GuardedBy("mServiceLock")
    private void scheduleFollowUpOomAdjusterUpdateLocked(long updateUptimeMs, long now) {
        if (updateUptimeMs + mOomConstants.mFollowUpOomadjUpdateWaitDuration
                >= mNextFollowUpUpdateUptimeMs) {
            // Update time is too close or later than the next follow up update.
            return;
        }
        if (updateUptimeMs < now + mOomConstants.mFollowUpOomadjUpdateWaitDuration) {
            // Use a minimum delay for the follow up to possibly batch multiple process
            // evaluations and avoid rapid updates.
            updateUptimeMs = now + mOomConstants.mFollowUpOomadjUpdateWaitDuration;
        }

        // Schedule a follow up update. Don't bother deleting existing handler messages.
        // They will be cleared during the message while no locks are being held.
        mNextFollowUpUpdateUptimeMs = updateUptimeMs;
        mUpdateHandler.sendEmptyMessageAtTime(FOLLOW_UP_UPDATE_MSG, mNextFollowUpUpdateUptimeMs);
    }
}
