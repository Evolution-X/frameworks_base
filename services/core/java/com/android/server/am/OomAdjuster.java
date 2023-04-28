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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL_IMPLICIT;
import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_CPU_TIME;
import static android.app.ActivityManager.PROCESS_CAPABILITY_IMPLICIT_CPU_TIME;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
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
import static android.os.Process.THREAD_GROUP_BACKGROUND;
import static android.os.Process.THREAD_GROUP_DEFAULT;
import static android.os.Process.THREAD_GROUP_FOREGROUND_WINDOW;
import static android.os.Process.THREAD_GROUP_RESTRICTED;
import static android.os.Process.THREAD_GROUP_TOP_APP;
import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_USAGE_STATS;
import static com.android.server.am.ActivityManagerService.FOLLOW_UP_OOMADJUSTER_UPDATE_MSG;
import static com.android.server.am.ActivityManagerService.IDLE_UIDS_MSG;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_OOM_ADJ;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.AppProfiler.TAG_PSS;
import static com.android.server.am.OomAdjusterImpl.Connection.CPU_TIME_TRANSMISSION_LEGACY;
import static com.android.server.am.OomAdjusterImpl.Connection.CPU_TIME_TRANSMISSION_NONE;
import static com.android.server.am.ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
import static com.android.server.am.ProcessList.CACHED_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.INVALID_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.ProcessList.SCHED_GROUP_DEFAULT;
import static com.android.server.am.ProcessList.SCHED_GROUP_FOREGROUND_WINDOW;
import static com.android.server.am.ProcessList.SCHED_GROUP_RESTRICTED;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP_BOUND;
import static com.android.server.am.ProcessList.SERVICE_ADJ;
import static com.android.server.am.ProcessList.TAG_PROCESS_OBSERVERS;
import static com.android.server.am.ProcessList.UNKNOWN_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_MAX_ADJ;
import static com.android.server.am.psc.PlatformCompatCache.CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME;
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
import android.app.ActivityManagerInternal.OomAdjReason;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.net.NetworkPolicyManager;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.am.psc.ActiveUidsInternal;
import com.android.server.am.psc.ConnectionRecordInternal;
import com.android.server.am.psc.ContentProviderConnectionInternal;
import com.android.server.am.psc.PlatformCompatCache;
import com.android.server.am.psc.PlatformCompatCache.CachedCompatChangeId;
import com.android.server.am.psc.ProcessProviderRecordInternal;
import com.android.server.am.psc.ProcessRecordInternal;
import com.android.server.am.psc.ProcessServiceRecordInternal;
import com.android.server.am.psc.ServiceRecordInternal;
import com.android.server.am.psc.UidRecordInternal;
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

    public static final String[] OOM_ADJ_REASON_TAGS = new String[OOM_ADJ_REASON_COUNT];
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

    @IntDef(flag = true, prefix = "CPU_TIME_REASON_", value = {
            CPU_TIME_REASON_NONE,
            CPU_TIME_REASON_OTHER,
            CPU_TIME_REASON_TRANSMITTED,
            CPU_TIME_REASON_TRANSMITTED_LEGACY,
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
    public static String oomAdjReasonToStringSuffix(@OomAdjReason int oomReason) {
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

    ActivityManagerConstants mConstants;

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
    @CompositeRWLock({"mService", "mProcLock"})
    ActiveUids mActiveUids;

    /**
     * The handler to execute {@link #setProcessGroup} (it may be heavy if the process has many
     * threads) for reducing the time spent in {@link #applyOomAdjLSP}.
     */
    private final Handler mProcessGroupHandler;

    protected final int[] mTmpSchedGroup = new int[1];

    final Callback mCallback;
    final ActivityManagerService mService;
    final Injector mInjector;
    final GlobalState mGlobalState;
    final ProcessList mProcessList;
    final ActivityManagerGlobalLock mProcLock;

    private final int mNumSlots;
    protected final ArrayList<ProcessRecordInternal> mTmpProcessList = new ArrayList<>();
    protected final ArrayList<UidRecordInternal> mTmpBecameIdle = new ArrayList<>();
    protected final ActiveUids mTmpUidRecords;
    protected final ArrayDeque<ProcessRecordInternal> mTmpQueue;
    protected final ArraySet<ProcessRecordInternal> mTmpProcessSet = new ArraySet<>();
    protected final ArraySet<ProcessRecordInternal> mPendingProcessSet = new ArraySet<>();

    /**
     * List of processes that we want to batch for LMKD to adjust their respective
     * OOM scores.
     */
    @GuardedBy("mService")
    protected final ArrayList<ProcessRecordInternal> mProcsToOomAdj = new ArrayList<>();

    /**
     * Flag to mark if there is an ongoing oomAdjUpdate: potentially the oomAdjUpdate
     * could be called recursively because of the indirect calls during the update;
     * however the oomAdjUpdate itself doesn't support recursion - in this case we'd
     * have to queue up the new targets found during the update, and perform another
     * round of oomAdjUpdate at the end of last update.
     */
    @GuardedBy("mService")
    private boolean mOomAdjUpdateOngoing = false;

    /**
     * Flag to mark if there is a pending full oomAdjUpdate.
     */
    @GuardedBy("mService")
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
    @GuardedBy("mService")
    protected int mProcessStateCurTop = PROCESS_STATE_TOP;

    @GuardedBy("mService")
    private final ArraySet<ProcessRecordInternal> mFollowUpUpdateSet = new ArraySet<>();

    protected static final long NO_FOLLOW_UP_TIME = Long.MAX_VALUE;
    @GuardedBy("mService")
    private long mNextFollowUpUpdateUptimeMs = NO_FOLLOW_UP_TIME;

    /**
     * The oom score a client needs to be to raise a service with UI out of cache.
     */
    protected static final int CACHING_UI_SERVICE_CLIENT_ADJ_THRESHOLD = SERVICE_ADJ;

    static final long PERCEPTIBLE_TASK_TIMEOUT_MILLIS = 5 * 60 * 1000;

    static final int ALL_CPU_TIME_CAPABILITIES =
            PROCESS_CAPABILITY_CPU_TIME | PROCESS_CAPABILITY_IMPLICIT_CPU_TIME;

    /**
     * Callback interface for {@link OomAdjuster} to interact with components outside the PSC
     * package, abstracting away direct dependencies.
     */
    public interface Callback {
        /** Notifies the client component when a process's OOM adjustment changes. */
        void onOomAdjustChanged(int oldAdj, int newAdj, ProcessRecordInternal appInternal);

        /**
         * Notifies the client component to evaluate and apply process freeze state changes.
         *
         * @param app The process record representing the application.
         * @param freezePolicy True if the process should be frozen.
         * @param oomAdjReason The reason for the OOM adjustment leading to this call.
         * @param immediate True if the freeze/unfreeze action should be applied immediately.
         * @param oldOomAdj The previous OOM adjustment score of the process.
         * @param shouldNotFreezeChanged True if the process's {@code shouldNotFreeze} property
         *        has changed during the current OOM adjustment cycle.
         */
        void onProcessFreezabilityChanged(ProcessRecordInternal app, boolean freezePolicy,
                @OomAdjReason int oomAdjReason, boolean immediate, int oldOomAdj,
                boolean shouldNotFreezeChanged);

        /** Notifies the client component when a process's process state is updated. */
        void onProcStateUpdated(ProcessRecordInternal app, long now,
                boolean forceUpdatePssTime);
    }

    @VisibleForTesting
    public static class Injector {
        boolean isChangeEnabled(@CachedCompatChangeId int cachedCompatChangeId,
                ApplicationInfo app, boolean defaultValue) {
            return PlatformCompatCache.getInstance()
                    .isChangeEnabled(cachedCompatChangeId, app, defaultValue);
        }

        long getUptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        long getElapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }

        void batchSetOomAdj(ArrayList<ProcessRecordInternal> procsToOomAdj) {
            ProcessList.batchSetOomAdj(procsToOomAdj);
        }

        void setOomAdj(int pid, int uid, int adj) {
            ProcessList.setOomAdj(pid, uid, adj);
        }

        void setThreadPriority(int tid, int priority) {
            Process.setThreadPriority(tid, priority);
        }
    }

    // TODO(b/346822474): hook up global state usage.
    interface GlobalState {
        /** Is device's screen on. */
        boolean isAwake();

        /** What process is running a backup for a given userId. */
        ProcessRecord getBackupTarget(@UserIdInt int userId);

        /** Is memory level normal since last evaluation. */
        boolean isLastMemoryLevelNormal();

        /** The ProcessState to assign to the Top process. */
        @ActivityManager.ProcessState int getTopProcessState();

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
    }

    boolean isChangeEnabled(@CachedCompatChangeId int cachedCompatChangeId,
            ApplicationInfo app, boolean defaultValue) {
        return mInjector.isChangeEnabled(cachedCompatChangeId, app, defaultValue);
    }

    static ServiceThread createAdjusterThread() {
        // The process group is usually critical to the response time of foreground app, so the
        // setter should apply it as soon as possible.
        final ServiceThread adjusterThread =
                new ServiceThread(TAG, THREAD_PRIORITY_TOP_APP_BOOST, false /* allowIo */);
        adjusterThread.start();
        return adjusterThread;
    }

    OomAdjuster(ActivityManagerService service, ProcessList processList, ActiveUids activeUids,
            ServiceThread adjusterThread, GlobalState globalState, Injector injector,
            Callback callback) {
        mCallback = callback;
        mService = service;
        mGlobalState = globalState;
        mInjector = injector;
        mProcessList = processList;
        mProcLock = service.mProcLock;
        mActiveUids = activeUids;

        mConstants = mService.mConstants;

        mLogger = new OomAdjusterDebugLogger(this, mService.mConstants);

        mProcessGroupHandler = new Handler(adjusterThread.getLooper(), msg -> {
            final int group = msg.what;
            final ProcessRecord app = (ProcessRecord) msg.obj;
            setProcessGroup(app.getPid(), group, app.processName);
            mService.mPhantomProcessList.setProcessGroupForPhantomProcessOfApp(app, group);
            return true;
        });
        mTmpUidRecords = new ActiveUids(null);
        mTmpQueue = new ArrayDeque<>(mConstants.CUR_MAX_CACHED_PROCESSES << 1);
        mNumSlots = ((CACHED_APP_MAX_ADJ - CACHED_APP_MIN_ADJ + 1) >> 1)
                / CACHED_APP_IMPORTANCE_LEVELS;
    }

    void setProcessGroup(int pid, int group, String processName) {
        if (pid == ActivityManagerService.MY_PID) {
            // Skip setting the process group for system_server, keep it as default.
            return;
        }
        final boolean traceEnabled = Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        if (traceEnabled) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "setProcessGroup "
                    + processName + " to " + group);
        }
        try {
            android.os.Process.setProcessGroup(pid, group);
        } catch (Exception e) {
            if (DEBUG_ALL) {
                Slog.w(TAG, "Failed setting process group of " + pid + " to " + group, e);
            }
        } finally {
            if (traceEnabled) {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        }
    }

    void setAppAndChildProcessGroup(ProcessRecordInternal app, int group) {
        mProcessGroupHandler.sendMessage(mProcessGroupHandler.obtainMessage(
                group, app));
    }

    /**
     * Update the keep-warming service flags upon user switches
     */
    @GuardedBy("mService")
    public void prewarmServicesIfNecessary() {
        mProcessList.forEachLruProcessesLOSP(false,
                this::updateKeepWarmIfNecessaryForProcessLocked);
    }

    @GuardedBy("mService")
    private void updateKeepWarmIfNecessaryForProcessLocked(final ProcessRecord app) {
        final ArraySet<ComponentName> warmServices = mService.mConstants.KEEP_WARMING_SERVICES;
        boolean includeWarmPkg = false;
        final PackageList pkgList = app.getPkgList();
        for (int j = warmServices.size() - 1; j >= 0; j--) {
            if (pkgList.containsKey(warmServices.valueAt(j).getPackageName())) {
                includeWarmPkg = true;
                break;
            }
        }
        if (!includeWarmPkg) {
            return;
        }
        final ProcessServiceRecordInternal psr = app.mServices;
        for (int j = psr.numberOfRunningServices() - 1; j >= 0; j--) {
            psr.getRunningServiceAt(j).updateKeepWarmLocked();
        }
    }

    /**
     * Update OomAdj for all processes in LRU list
     */
    @GuardedBy("mService")
    void updateOomAdjLocked(@OomAdjReason int oomAdjReason) {
        synchronized (mProcLock) {
            updateOomAdjLSP(oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateOomAdjLSP(@OomAdjReason int oomAdjReason) {
        if (checkAndEnqueueOomAdjTargetLocked(null)) {
            // Simply return as there is an oomAdjUpdate ongoing
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

    @GuardedBy({"mService", "mProcLock"})
    protected abstract void performUpdateOomAdjLSP(@OomAdjReason int oomAdjReason);

    /**
     * Update OomAdj for specific process and its reachable processes (with direction/indirect
     * bindings from this process); Note its clients' proc state won't be re-evaluated if this proc
     * is hosting any service/content provider.
     *
     * @param app The process to update, or null to update all processes
     * @param oomAdjReason
     */
    @GuardedBy("mService")
    boolean updateOomAdjLocked(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        synchronized (mProcLock) {
            return updateOomAdjLSP(app, oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private boolean updateOomAdjLSP(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        if (app == null || !mConstants.OOMADJ_UPDATE_QUICK) {
            updateOomAdjLSP(oomAdjReason);
            return true;
        }

        if (checkAndEnqueueOomAdjTargetLocked(app)) {
            // Simply return true as there is an oomAdjUpdate ongoing
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

    @GuardedBy({"mService", "mProcLock"})
    protected abstract boolean performUpdateOomAdjLSP(ProcessRecord app,
            @OomAdjReason int oomAdjReason);

    @GuardedBy({"mService", "mProcLock"})
    protected int enqueuePendingTopAppIfNecessaryLSP() {
        final int prevTopProcessState = getTopProcessState();
        mService.enqueuePendingTopAppIfNecessaryLocked();
        final int topProcessState = getTopProcessState();
        if (prevTopProcessState != topProcessState) {
            // Unlikely but possible: WM just updated the top process state, it may have
            // enqueued the new top app to the pending top UID list. Enqueue that one here too.
            mService.enqueuePendingTopAppIfNecessaryLocked();
        }
        return topProcessState;
    }

    /**
     * Expand the provided {@code reachables} list with all processes reachable from those
     * provided in the list.
     */
    @GuardedBy({"mService", "mProcLock"})
    protected abstract void collectReachableProcessesLSP(
            @NonNull ArrayList<ProcessRecordInternal> reachables);

    /**
     * Collect the reachable processes from the given {@code apps}, the result will be
     * returned in the given {@code processes}, which will include the processes from
     * the given {@code apps}.
     */
    @GuardedBy("mService")
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
                ConnectionRecordInternal cr = psr.getConnectionAt(i);
                ProcessRecordInternal service = cr.hasFlag(ServiceInfo.FLAG_ISOLATED_PROCESS)
                        ? cr.getService().getIsolationHostProcess()
                        : cr.getService().getHostProcess();
                if (service == null || service == pr
                        || ((service.getMaxAdj() >= ProcessList.SYSTEM_ADJ)
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
                ContentProviderConnectionInternal cpc = ppr.getProviderConnectionAt(i);
                ProcessRecordInternal provider = cpc.getProvider().getHostProcess();
                if (provider == null || provider == pr
                        || ((provider.getMaxAdj() >= ProcessList.SYSTEM_ADJ)
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
            final List<ProcessRecord> sdkSandboxes =
                    mProcessList.getSdkSandboxProcessesForAppLocked(pr.uid);
            final int numSdkSandboxes = sdkSandboxes != null ? sdkSandboxes.size() : 0;
            for (int i = numSdkSandboxes - 1; i >= 0; i--) {
                ProcessRecord sdkSandbox = sdkSandboxes.get(i);
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
                    ServiceRecordInternal s = psr.getRunningServiceAt(is);
                    for (int conni = s.getConnectionsSize() - 1; conni >= 0; conni--) {
                        ArrayList<? extends ConnectionRecordInternal> clist =
                                s.getConnectionAt(conni);
                        for (int i = clist.size() - 1; i >= 0; i--) {
                            // TODO(b/425766486): Switch to use ConnectionRecordInternal.
                            ConnectionRecord cr = (ConnectionRecord) clist.get(i);
                            ProcessRecord attributedApp = cr.binding.attributedClient;
                            if (attributedApp == null || attributedApp == pr
                                    || ((attributedApp.getMaxAdj() >= ProcessList.SYSTEM_ADJ)
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
    @GuardedBy("mService")
    void enqueueOomAdjTargetLocked(ProcessRecord app) {
        if (app != null && app.getMaxAdj() > FOREGROUND_APP_ADJ) {
            mPendingProcessSet.add(app);
        }
    }

    @GuardedBy("mService")
    void removeOomAdjTargetLocked(ProcessRecord app, boolean procDied) {
        if (app != null) {
            mPendingProcessSet.remove(app);
            if (procDied) {
                PlatformCompatCache.getInstance().invalidate(app.info);
            }
        }
    }

    /**
     * Check if there is an ongoing oomAdjUpdate, enqueue the given process record
     * to {@link #mPendingProcessSet} if there is one.
     *
     * @param app The target app to get an oomAdjUpdate, or a full oomAdjUpdate if it's null.
     * @return {@code true} if there is an ongoing oomAdjUpdate.
     */
    @GuardedBy("mService")
    private boolean checkAndEnqueueOomAdjTargetLocked(@Nullable ProcessRecord app) {
        if (!mOomAdjUpdateOngoing) {
            return false;
        }
        if (app != null) {
            mPendingProcessSet.add(app);
        } else {
            mPendingFullOomAdjUpdate = true;
        }
        return true;
    }

    /**
     * Kick off an oom adj update pass for the pending targets which are enqueued via
     * {@link #enqueueOomAdjTargetLocked}.
     */
    @GuardedBy("mService")
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

    @GuardedBy("mService")
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

    @GuardedBy("mService")
    protected abstract void performUpdateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason);

    @GuardedBy({"mService", "mProcLock"})
    protected void postUpdateOomAdjInnerLSP(@OomAdjReason int oomAdjReason, ActiveUids activeUids,
            long now, long nowElapsed, long oldTime, boolean doingAll) {
        mNumNonCachedProcs = 0;
        mNumCachedHiddenProcs = 0;

        updateAndTrimProcessLSP(now, nowElapsed, oldTime, oomAdjReason, doingAll);
        mNumServiceProcs = mNewNumServiceProcs;

        if (mService.mAlwaysFinishActivities) {
            // Need to do this on its own message because the stack may not
            // be in a consistent state at this point.
            mService.mAtmInternal.scheduleDestroyAllActivities("always-finish");
        }

        updateUidsLSP(activeUids, nowElapsed);

        synchronized (mService.mProcessStats.mLock) {
            final long nowUptime = mInjector.getUptimeMillis();
            if (mService.mProcessStats.shouldWriteNowLocked(nowUptime)) {
                mService.mHandler.post(new ActivityManagerService.ProcStatsRunnable(mService,
                        mService.mProcessStats));
            }

            // Run this after making sure all procstates are updated.
            mService.mProcessStats.updateTrackingAssociationsLocked(mAdjSeq, nowUptime);
        }

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

    @GuardedBy({"mService", "mProcLock"})
    protected void applyLruAdjust(ArrayList<ProcessRecord> lruList) {
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

        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES
                                        - emptyProcessLimit;
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
            ProcessRecord app = lruList.get(i);
            final ProcessRecordInternal state = app;
            final int curAdj = state.getCurAdj();
            if (VISIBLE_APP_ADJ <= curAdj && curAdj <= VISIBLE_APP_MAX_ADJ) {
                state.setCurAdj(nextVisibleAppAdj);
                nextVisibleAppAdj = Math.min(nextVisibleAppAdj + 1, VISIBLE_APP_MAX_ADJ);
            } else if (PREVIOUS_APP_ADJ <= curAdj && curAdj <= PREVIOUS_APP_MAX_ADJ) {
                state.setCurAdj(nextPreviousAppAdj);
                nextPreviousAppAdj = Math.min(nextPreviousAppAdj + 1, PREVIOUS_APP_MAX_ADJ);
            } else if (!app.isKilledByAm() && app.isProcessRunning() && curAdj >= UNKNOWN_ADJ) {
                // If we haven't yet assigned the final cached adj to the process, do that now.
                final ProcessServiceRecordInternal psr = app.mServices;
                switch (state.getCurProcState()) {
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
                        state.setCurRawAdj(rawAdj);
                        state.setCurAdj(
                                applyBindAboveClientToAdj(psr.isHasAboveClient(), rawAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning activity LRU #" + i
                                    + " adj: " + state.getCurAdj()
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
                        state.setCurRawAdj(curEmptyAdj);
                        state.setCurAdj(applyBindAboveClientToAdj(psr.isHasAboveClient(),
                                curEmptyAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning empty LRU #" + i
                                    + " adj: " + state.getCurAdj()
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

    private static double getFreeSwapPercent() {
        return CachedAppOptimizer.getFreeSwapPercent();
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateAndTrimProcessLSP(final long now, final long nowElapsed,
            final long oldTime, @OomAdjReason int oomAdjReason,
            boolean doingAll) {
        ArrayList<ProcessRecord> lruList = mProcessList.getLruProcessesLOSP();
        final int numLru = lruList.size();

        final boolean doKillExcessiveProcesses = shouldKillExcessiveProcesses(now);
        if (!doKillExcessiveProcesses) {
            if (mNextNoKillDebugMessageTime < now) {
                mNextNoKillDebugMessageTime = now + 5000; // Every 5 seconds
            }
        }
        final int emptyProcessLimit = doKillExcessiveProcesses
                ? mConstants.CUR_MAX_EMPTY_PROCESSES : Integer.MAX_VALUE;
        final int cachedProcessLimit = doKillExcessiveProcesses
                ? (mConstants.CUR_MAX_CACHED_PROCESSES - emptyProcessLimit) : Integer.MAX_VALUE;
        int lastCachedGroup = 0;
        int lastCachedGroupUid = 0;
        int numCached = 0;
        int numCachedExtraGroup = 0;
        int numEmpty = 0;
        int numTrimming = 0;

        final boolean proactiveKillsEnabled = mConstants.PROACTIVE_KILLS_ENABLED;
        final double lowSwapThresholdPercent = mConstants.LOW_SWAP_THRESHOLD_PERCENT;
        final double freeSwapPercent = proactiveKillsEnabled ? getFreeSwapPercent() : 1.00;
        ProcessRecordInternal lruCachedApp = null;

        for (int i = numLru - 1; i >= 0; i--) {
            final ProcessRecordInternal app = lruList.get(i);
            if (!app.isKilledByAm() && app.isProcessRunning()) {
                if (!Flags.fixApplyOomadjOrder()) {
                    // We don't need to apply the update for the process which didn't get computed
                    if (app.getCompletedAdjSeq() == mAdjSeq) {
                        applyOomAdjLSP(app, doingAll, now, nowElapsed, oomAdjReason, true);
                    }
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
                        if (numEmpty > mConstants.CUR_TRIM_EMPTY_PROCESSES
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
                final ProcessRecord app = lruList.get(i);
                // We don't need to apply the update for the process which didn't get computed
                if (!app.isKilledByAm() && app.isProcessRunning()
                        && app.getCompletedAdjSeq() == mAdjSeq) {
                    applyOomAdjLSP(app, doingAll, now, nowElapsed, oomAdjReason, true);
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

        mService.mAppProfiler.updateLowMemStateLSP(numCached, numEmpty, numTrimming, now);
    }

    @GuardedBy({"mService", "mProcLock"})
    protected void updateAppUidRecIfNecessaryLSP(final ProcessRecord app) {
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

    @GuardedBy({"mService", "mProcLock"})
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

    @GuardedBy({"mService", "mProcLock"})
    protected void updateUidsLSP(ActiveUids activeUids, final long nowElapsed) {
        // This compares previously set procstate to the current procstate in regards to whether
        // or not the app's network access will be blocked. So, this needs to be called before
        // we update the UidRecord's procstate by calling {@link UidRecord#setSetProcState}.
        mProcessList.incrementProcStateSeqAndNotifyAppsLOSP(activeUids);

        ArrayList<UidRecordInternal> becameIdle = mTmpBecameIdle;
        becameIdle.clear();

        // Update from any uid changes.
        if (mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.startUidChanges();
        }
        for (int i = activeUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = activeUids.valueAt(i);
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
                            if (shouldLog) {
                                mLogger.logSetLastBackgroundTime(uidRec.getUid(), nowElapsed);
                            }
                            if (mService.mDeterministicUidIdle
                                    || !mService.mHandler.hasMessages(IDLE_UIDS_MSG)) {
                                // Note: the background settle time is in elapsed realtime, while
                                // the handler time base is uptime.  All this means is that we may
                                // stop background uids later than we had intended, but that only
                                // happens because the device was sleeping so we are okay anyway.
                                if (shouldLog) {
                                    mLogger.logScheduleUidIdle1(uidRec.getUid(),
                                            mConstants.BACKGROUND_SETTLE_TIME);
                                }
                                mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                                        mConstants.BACKGROUND_SETTLE_TIME); // XXX
                            }
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
                    if ((uidChange & UidRecord.CHANGE_PROCSTATE) != 0
                            || (uidChange & UidRecord.CHANGE_CAPABILITY) != 0) {
                        mService.mAtmInternal.onUidProcStateChanged(
                                uidRec.getUid(), uidRec.getSetProcState());
                    }
                    if (uidChange != 0) {
                        mService.enqueueUidChangeLocked(uidRec, -1, uidChange);
                    }
                    if ((uidChange & UidRecord.CHANGE_PROCSTATE) != 0
                            || (uidChange & UidRecord.CHANGE_CAPABILITY) != 0) {
                        mService.noteUidProcessStateAndCapability(uidRec.getUid(),
                                uidRec.getCurProcState(), uidRec.getCurCapability());
                    }
                    if ((uidChange & UidRecord.CHANGE_PROCSTATE) != 0) {
                        mService.noteUidProcessState(uidRec.getUid(), uidRec.getCurProcState());
                    }
                    if (uidRec.getHasForegroundServices()) {
                        mService.mServices.foregroundServiceProcStateChangedLocked(uidRec);
                    }
                }
            }
            mService.mInternal.deletePendingTopUid(uidRec.getUid(), nowElapsed);
        }
        if (mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.finishUidChanges();
        }

        int size = becameIdle.size();
        if (size > 0) {
            // If we have any new uids that became idle this time, we need to make sure
            // they aren't left with running services.
            for (int i = size - 1; i >= 0; i--) {
                mService.mServices.stopInBackgroundLocked(becameIdle.get(i).getUid());
            }
        }
    }

    /**
     * Return true if we should kill excessive cached/empty processes.
     */
    private boolean shouldKillExcessiveProcesses(long nowUptime) {
        final long lastUserUnlockingUptime = mService.mUserController.getLastUserUnlockingUptime();

        if (lastUserUnlockingUptime == 0) {
            // No users have been unlocked.
            return !mConstants.mNoKillCachedProcessesUntilBootCompleted;
        }
        final long noKillCachedProcessesPostBootCompletedDurationMillis =
                mConstants.mNoKillCachedProcessesPostBootCompletedDurationMillis;
        if ((lastUserUnlockingUptime + noKillCachedProcessesPostBootCompletedDurationMillis)
                > nowUptime) {
            return false;
        }
        return true;
    }

    protected final OomAdjWindowCalculator mTmpOomAdjWindowCalculator =
            new OomAdjWindowCalculator();

    /**
     * The computeOomAdjFromActivitiesIfNecessary method computes the initial importance of the
     * process which contains activities and is not the global top. The importance are stored at the
     * ProcessRecordInternal's cached fields.
     * The method is called during computeOomAdjLSP(), on the same thread.
     */
    final class OomAdjWindowCalculator {
        private ProcessRecordInternal mApp;
        private int mAdj;
        private boolean mForegroundActivities;
        private boolean mHasVisibleActivities;
        private int mProcState;
        private int mSchedGroup;
        private int mProcessStateCurTop;
        private String mAdjType;
        private boolean mReportDebugMsgs;

        @GuardedBy("this.OomAdjuster.mService")
        int getAdj() {
            return mAdj;
        }

        @GuardedBy("this.OomAdjuster.mService")
        void computeOomAdjFromActivitiesIfNecessary(ProcessRecordInternal app, int adj,
                boolean foregroundActivities, boolean hasVisibleActivities, int procState,
                int schedGroup, int processCurTop, boolean reportDebugMsgs) {
            if (app.getCachedAdj() != ProcessList.INVALID_ADJ) {
                return;
            }
            initialize(app, adj, foregroundActivities, hasVisibleActivities, procState,
                    schedGroup, processCurTop, reportDebugMsgs);

            final int flags;
            if (Flags.pushActivityStateToOomadjuster()) {
                flags = mApp.getActivityStateFlags();
            } else {
                flags = mApp.getActivityStateFlagsLegacy();
            }

            if ((flags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0) {
                onVisibleActivity(flags);
            } else if ((flags & ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED) != 0) {
                onPausedActivity();
            } else if ((flags & ACTIVITY_STATE_FLAG_IS_STOPPING) != 0) {
                onStoppingActivity((flags & ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING) != 0);
            } else {
                final long ts;
                if (Flags.pushActivityStateToOomadjuster()) {
                    ts = mApp.getPerceptibleTaskStoppedTimeMillis();
                } else {
                    ts = mApp.getPerceptibleTaskStoppedTimeMillisLegacy();
                }
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
                if (mAdj == ProcessList.VISIBLE_APP_ADJ) {
                    final int taskLayer = flags & ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
                    final int minLayer = Math.min(ProcessList.VISIBLE_APP_LAYER_MAX, taskLayer);
                    mAdj += minLayer;
                }
            }

            mApp.setCachedAdj(mAdj);
            mApp.setCachedForegroundActivities(mForegroundActivities);
            mApp.setCachedHasVisibleActivities(mHasVisibleActivities);
            mApp.setCachedProcState(mProcState);
            mApp.setCachedSchedGroup(mSchedGroup);
            mApp.setCachedAdjType(mAdjType);
        }

        void initialize(ProcessRecordInternal app, int adj, boolean foregroundActivities,
                boolean hasVisibleActivities, int procState, int schedGroup,
                int processStateCurTop, boolean reportDebugMsgs) {
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

        void onOtherActivity(long perceptibleTaskStoppedTimeMillis) {
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
        if (Flags.pushGlobalStateToOomadjuster()) {
            return mGlobalState.isAwake();
        } else {
            return mService.mWakefulness.get() == PowerManagerInternal.WAKEFULNESS_AWAKE;
        }
    }

    protected boolean isScreenOnOrAnimatingLocked(ProcessRecordInternal state) {
        return isDeviceFullyAwake() || state.isRunningRemoteAnimation();
    }

    protected boolean isBackupProcess(ProcessRecordInternal app) {
        if (Flags.pushGlobalStateToOomadjuster()) {
            return app == mGlobalState.getBackupTarget(app.userId);
        } else {
            final BackupRecord backupTarget = mService.mBackupTargets.get(app.userId);
            if (backupTarget == null) {
                return false;
            }
            return app == backupTarget.app;
        }
    }

    protected boolean isLastMemoryLevelNormal() {
        if (Flags.pushGlobalStateToOomadjuster()) {
            return mGlobalState.isLastMemoryLevelNormal();
        } else {
            return mService.mAppProfiler.isLastMemoryLevelNormal();
        }
    }

    protected boolean isReceivingBroadcast(ProcessRecordInternal app) {
        if (Flags.pushBroadcastStateToOomadjuster()) {
            return app.getReceivers().isReceivingBroadcast();
        } else {
            return app.getCachedIsReceivingBroadcast(mTmpSchedGroup);
        }
    }

    protected int getTopProcessState() {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getTopProcessState();
        } else {
            return mService.mAtmInternal.getTopProcessState();
        }
    }

    protected boolean useTopSchedGroupForTopProcess() {
        if (Flags.pushActivityStateToOomadjuster()) {
            if (mGlobalState.isUnlocking()) {
                // Keyguard is unlocking, suppress the top process priority for now.
                return false;
            }
            if (mGlobalState.hasExpandedNotificationShade()) {
                // The notification shade is occluding the top process, suppress top.
                return false;
            }
            return true;
        } else {
            return mService.mAtmInternal.useTopSchedGroupForTopProcess();
        }
    }

    protected ProcessRecordInternal getTopProcess() {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getTopProcess();
        } else {
            return mService.getTopApp();
        }
    }

    protected boolean isHomeProcess(ProcessRecordInternal proc) {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getHomeProcess() == proc;
        } else {
            return proc.getCachedIsHomeProcess();
        }
    }

    protected boolean isHeavyWeightProcess(ProcessRecordInternal proc) {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getHeavyWeightProcess() == proc;
        } else {
            return proc.getCachedIsHeavyWeight();
        }
    }

    protected boolean isVisibleDozeUiProcess(ProcessRecordInternal proc) {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getShowingUiWhileDozingProcess() == proc;
        } else {
            return proc.isShowingUiWhileDozing();
        }
    }

    protected boolean isPreviousProcess(ProcessRecordInternal proc) {
        if (Flags.pushActivityStateToOomadjuster()) {
            return mGlobalState.getPreviousProcess() == proc;
        } else {
            return proc.getCachedIsPreviousProcess();
        }
    }

    /**
     * @return The proposed change to the schedGroup.
     */
    @GuardedBy({"mService", "mProcLock"})
    protected int setIntermediateAdjLSP(ProcessRecordInternal app, int adj, int schedGroup) {
        app.setCurRawAdj(adj);

        adj = applyBindAboveClientToAdj(app.getServices().isHasAboveClient(), adj);
        if (adj > app.getMaxAdj()) {
            adj = app.getMaxAdj();
            if (adj <= PERCEPTIBLE_LOW_APP_ADJ) {
                schedGroup = SCHED_GROUP_DEFAULT;
            }
        }

        app.setCurAdj(adj);
        return schedGroup;
    }

    private static int applyBindAboveClientToAdj(boolean hasAboveClient, int adj) {
        if (hasAboveClient) {
            // If this process has bound to any services with BIND_ABOVE_CLIENT,
            // then we need to drop its adjustment to be lower than the service's
            // in order to honor the request.  We want to drop it by one adjustment
            // level...  but there is special meaning applied to various levels so
            // we will skip some of them.
            if (adj < ProcessList.FOREGROUND_APP_ADJ) {
                // System process will not get dropped, ever
            } else if (adj < ProcessList.VISIBLE_APP_ADJ) {
                adj = ProcessList.VISIBLE_APP_ADJ;
            } else if (adj < ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
            } else if (adj < ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
            } else if (adj < ProcessList.SERVICE_ADJ) {
                adj = ProcessList.SERVICE_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MIN_ADJ) {
                adj = ProcessList.CACHED_APP_MIN_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MAX_ADJ) {
                adj++;
            }
        }
        return adj;
    }

    @GuardedBy({"mService", "mProcLock"})
    protected void setIntermediateProcStateLSP(ProcessRecordInternal state, int procState) {
        state.setCurProcState(procState);
        state.setCurRawProcState(procState);
    }

    @GuardedBy({"mService", "mProcLock"})
    protected void setIntermediateSchedGroupLSP(ProcessRecordInternal state, int schedGroup) {
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
    @GuardedBy({"mService", "mProcLock"})
    public abstract boolean computeServiceHostOomAdjLSP(ConnectionRecordInternal cr,
            ProcessRecordInternal app, ProcessRecordInternal client, long now, boolean dryRun);

    /**
     * Computes the impact on {@code app} the provider connections from {@code client} has.
     */
    @GuardedBy({"mService", "mProcLock"})
    public abstract boolean computeProviderHostOomAdjLSP(ContentProviderConnectionInternal conn,
            ProcessRecordInternal app, ProcessRecordInternal client, boolean dryRun);

    protected int getDefaultCapability(ProcessRecordInternal app, int procState) {
        final int networkCapabilities =
                NetworkPolicyManager.getDefaultProcessNetworkCapabilities(procState);
        final int baseCapabilities;
        switch (procState) {
            case PROCESS_STATE_PERSISTENT:
            case PROCESS_STATE_PERSISTENT_UI:
            case PROCESS_STATE_TOP:
                baseCapabilities = PROCESS_CAPABILITY_ALL; // BFSL allowed
                break;
            case PROCESS_STATE_BOUND_TOP:
                if (app.hasActiveInstrumentation()) {
                    baseCapabilities = PROCESS_CAPABILITY_BFSL |
                            PROCESS_CAPABILITY_ALL_IMPLICIT;
                } else {
                    baseCapabilities = PROCESS_CAPABILITY_BFSL;
                }
                break;
            case PROCESS_STATE_FOREGROUND_SERVICE:
                if (app.hasActiveInstrumentation()) {
                    baseCapabilities = PROCESS_CAPABILITY_ALL_IMPLICIT;
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

    @CpuTimeReasons
    private static int getCpuTimeReasons(ProcessRecordInternal app,
            boolean hasForegroundActivities) {
        // Note: persistent processes always get CPU_TIME with reason CPU_TIME_REASON_OTHER.
        // Currently, we only cite CPU_TIME_REASON_OTHER for all reasons. More specific reasons
        // can be used when they become interesting to observe.
        final UidRecordInternal uidRec = app.getUidRecord();
        if (uidRec != null && uidRec.isCurAllowListed()) {
            // Process is in the power allowlist.
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

    protected static int getCpuCapability(ProcessRecordInternal app,
            boolean hasForegroundActivities) {
        final int reasons = getCpuTimeReasons(app, hasForegroundActivities);
        app.addCurCpuTimeReasons(reasons);
        return (reasons != CPU_TIME_REASON_NONE) ? PROCESS_CAPABILITY_CPU_TIME : 0;
    }

    // Grant PROCESS_CAPABILITY_IMPLICIT_CPU_TIME to processes based on oom adj score.
    protected int getImplicitCpuCapability(ProcessRecordInternal app, int adj) {
        if (adj < mConstants.FREEZER_CUTOFF_ADJ
                || app.getMaxAdj() < mConstants.FREEZER_CUTOFF_ADJ) {
            app.addCurImplicitCpuTimeReasons(IMPLICIT_CPU_TIME_REASON_OTHER);
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
        final int clientCpuCaps = client.getCurCapability() & ALL_CPU_TIME_CAPABILITIES;
        final @CpuTimeReasons int clientCpuReasons = client.getCurCpuTimeReasons();
        final @ImplicitCpuTimeReasons int clientImplicitCpuReasons =
                client.getCurImplicitCpuTimeReasons();

        final @OomAdjusterImpl.Connection.CpuTimeTransmissionType int transmissionType =
                (conn != null) ? conn.cpuTimeTransmissionType() : CPU_TIME_TRANSMISSION_NONE;

        if (transmissionType == CPU_TIME_TRANSMISSION_NONE) {
            // The binding does not transmit CPU_TIME capabilities in any way.
            return 0;
        }

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

    /** Inform the oomadj observer of changes to oomadj. Used by tests. */
    @GuardedBy("mService")
    protected void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        mService.reportOomAdjMessageLocked(msg);
    }

    /** Applies the computed oomadj, procstate and sched group values and freezes them in set* */
    @GuardedBy({"mService", "mProcLock"})
    protected boolean applyOomAdjLSP(ProcessRecordInternal state, boolean doingAll, long now,
            long nowElapsed, @OomAdjReason int oomAdjReson, boolean isBatchingOomAdj) {
        boolean success = true;
        final UidRecordInternal uidRec = state.getUidRecord();

        final boolean reportDebugMsgs = DEBUG_SWITCH || DEBUG_OOM_ADJ
                        || mService.mCurOomAdjUid == state.getApplicationUid();

        if (state.getCurRawAdj() != state.getSetRawAdj()) {
            state.setSetRawAdj(state.getCurRawAdj());
        }

        int changes = 0;

        if (state.getCurAdj() != state.getSetAdj()) {
            mCallback.onOomAdjustChanged(state.getSetAdj(), state.getCurAdj(), state);
        }

        final int oldOomAdj = state.getSetAdj();
        if (state.getCurAdj() != state.getSetAdj()) {
            if (isBatchingOomAdj && mConstants.ENABLE_BATCHING_OOM_ADJ) {
                mProcsToOomAdj.add(state);
            } else {
                mInjector.setOomAdj(state.getPid(), state.uid, state.getCurAdj());
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
            state.setVerifiedAdj(INVALID_ADJ);
        }

        final int curSchedGroup = state.getCurrentSchedulingGroup();
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
                            ActivityManagerService.setFifoPriority(state, true /* enable */);
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
                        ActivityManagerService.setFifoPriority(state, false /* enable */);
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
            changes |= ActivityManagerService.ProcessChangeItem.CHANGE_ACTIVITIES;
        }

        updateAppFreezeStateLSP(state, oomAdjReson, false, oldOomAdj);

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
        mCallback.onProcStateUpdated(state, now, forceUpdatePssTime);

        int oldProcState = state.getSetProcState();
        if (state.getSetProcState() != state.getCurProcState()) {
            if (reportDebugMsgs) {
                String msg = "Proc state change of " + state.processName
                        + " to " + ProcessList.makeProcStateString(state.getCurProcState())
                        + " (" + state.getCurProcState() + ")" + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            boolean setImportant = state.getSetProcState() < PROCESS_STATE_SERVICE;
            boolean curImportant = state.getCurProcState() < PROCESS_STATE_SERVICE;
            if (setImportant && !curImportant) {
                // This app is no longer something we consider important enough to allow to use
                // arbitrary amounts of battery power. Note its current CPU time to later know to
                // kill it if it is not behaving well.
                state.setWhenUnimportant(now);
                state.setLastCpuTime(0);
            }
            // Inform UsageStats of important process state change
            // Must be called before updating setProcState
            maybeUpdateUsageStatsLSP(state, nowElapsed);

            maybeUpdateLastTopTime(state, now);

            state.setSetProcState(state.getCurProcState());
            if (!doingAll) {
                synchronized (mService.mProcessStats.mLock) {
                    // TODO: b/441408003 - Decouple the AMS usage out of OomAdjuster.
                    mService.setProcessTrackerStateLOSP((ProcessRecord) state,
                            mService.mProcessStats.getMemFactorLocked());
                }
            }
        } else if (state.getHasReportedInteraction()) {
            final boolean fgsInteractionChangeEnabled = state.getCachedCompatChange(
                    CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME);
            final long interactionThreshold = fgsInteractionChangeEnabled
                    ? mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S
                    : mConstants.USAGE_STATS_INTERACTION_INTERVAL_PRE_S;
            // For apps that sit around for a long time in the interactive state, we need
            // to report this at least once a day so they don't go idle.
            if ((nowElapsed - state.getInteractionEventTime()) > interactionThreshold) {
                maybeUpdateUsageStatsLSP(state, nowElapsed);
            }
        } else {
            final boolean fgsInteractionChangeEnabled = state.getCachedCompatChange(
                    CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME);
            final long interactionThreshold = fgsInteractionChangeEnabled
                    ? mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S
                    : mConstants.SERVICE_USAGE_INTERACTION_TIME_PRE_S;
            // For foreground services that sit around for a long time but are not interacted with.
            if ((nowElapsed - state.getFgInteractionTime()) > interactionThreshold) {
                maybeUpdateUsageStatsLSP(state, nowElapsed);
            }
        }

        if (state.getCurCapability() != state.getSetCapability()) {
            state.setSetCapability(state.getCurCapability());
            state.setSetCpuTimeReasons(state.getCurCpuTimeReasons());
            state.setSetImplicitCpuTimeReasons(state.getCurImplicitCpuTimeReasons());
        }

        final boolean curBoundByNonBgRestrictedApp = state.isCurBoundByNonBgRestrictedApp();
        if (curBoundByNonBgRestrictedApp != state.isSetBoundByNonBgRestrictedApp()) {
            state.setSetBoundByNonBgRestrictedApp(curBoundByNonBgRestrictedApp);
            if (!curBoundByNonBgRestrictedApp && state.isBackgroundRestricted()) {
                mService.mHandler.post(() -> {
                    synchronized (mService) {
                        mService.mServices.stopAllForegroundServicesLocked(
                                state.uid, state.getPackageName());
                    }
                });
            }
        }

        if (changes != 0) {
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Changes in " + state + ": " + changes);
            mProcessList.enqueueProcessChangeItemLocked(state.getPid(), state.getApplicationUid(),
                    changes, state.getHasRepForegroundActivities());
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Enqueued process change item for "
                            + state.toShortString() + ": changes=" + changes
                            + " foreground=" + state.getHasRepForegroundActivities()
                            + " type=" + state.getAdjType() + " source=" + state.getAdjSource()
                            + " target=" + state.getAdjTarget());
        }

        if (state.isCached() && !state.isSetCached()) {
            // Cached procs are eligible to get killed when in an UID idle and bg restricted.
            // However, we want to debounce state changes to avoid thrashing. Mark down when this
            // process became eligible and then schedule a check for eligible processes after
            // a background settling time, if needed.
            state.setLastCachedTime(nowElapsed);
            if (mService.mDeterministicUidIdle
                    || !mService.mHandler.hasMessages(IDLE_UIDS_MSG)) {
                if (mLogger.shouldLog(state.uid)) {
                    mLogger.logScheduleUidIdle2(
                            uidRec.getUid(), state.getPid(),
                            mConstants.mKillBgRestrictedAndCachedIdleSettleTimeMs);
                }
                mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                        mConstants.mKillBgRestrictedAndCachedIdleSettleTimeMs);
            }
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

    @GuardedBy({"mService", "mProcLock"})
    void setAttachingProcessStatesLSP(ProcessRecordInternal app) {
        int initialSchedGroup = SCHED_GROUP_DEFAULT;
        int initialProcState = PROCESS_STATE_CACHED_EMPTY;
            // Avoid freezing a freshly attached process.
        int initialCapability = ALL_CPU_TIME_CAPABILITIES;
        final int prevProcState = app.getCurProcState();
        final int prevAdj = app.getCurRawAdj();
        // If the process has been marked as foreground, it is starting as the top app (with
        // Zygote#START_AS_TOP_APP_ARG), so boost the thread priority of its default UI thread.
        if (app.getHasForegroundActivities()) {
            try {
                // The priority must be the same as how does {@link #applyOomAdjLSP} set for
                // {@link SCHED_GROUP_TOP_APP}. We don't check render thread because it
                // is not ready when attaching.
                app.notifyTopProcChanged();
                if (app.useFifoUiScheduling()) {
                    mService.scheduleAsFifoPriority(app.getPid(), true);
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

        app.setCurAdj(ProcessList.FOREGROUND_APP_ADJ);
        app.setCurRawAdj(ProcessList.FOREGROUND_APP_ADJ);
        app.setForcingToImportant(null);
        app.setHasShownUi(false);

        onProcessStateChanged(app, prevProcState);
        onProcessOomAdjChanged(app, prevAdj);
    }

    // ONLY used for unit testing in OomAdjusterTests.java
    @VisibleForTesting
    void maybeUpdateUsageStats(ProcessRecord app, long nowElapsed) {
        synchronized (mService) {
            synchronized (mProcLock) {
                maybeUpdateUsageStatsLSP(app, nowElapsed);
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void maybeUpdateUsageStatsLSP(ProcessRecordInternal app, long nowElapsed) {
        if (DEBUG_USAGE_STATS) {
            Slog.d(TAG, "Checking proc [" + Arrays.toString(app.getProcessPackageNames())
                    + "] state changes: old = " + app.getSetProcState() + ", new = "
                    + app.getCurProcState());
        }
        if (mService.mUsageStatsService == null) {
            return;
        }
        final boolean fgsInteractionChangeEnabled = app.getCachedCompatChange(
                CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME);
        boolean isInteraction;
        // To avoid some abuse patterns, we are going to be careful about what we consider
        // to be an app interaction.  Being the top activity doesn't count while the display
        // is sleeping, nor do short foreground services.
        if (ActivityManager.isProcStateConsideredInteraction(app.getCurProcState())) {
            isInteraction = true;
            app.setFgInteractionTime(0);
        } else if (app.getCurProcState() <= PROCESS_STATE_FOREGROUND_SERVICE) {
            if (app.getFgInteractionTime() == 0) {
                app.setFgInteractionTime(nowElapsed);
                isInteraction = false;
            } else {
                final long interactionTime = fgsInteractionChangeEnabled
                        ? mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S
                        : mConstants.SERVICE_USAGE_INTERACTION_TIME_PRE_S;
                isInteraction = nowElapsed > app.getFgInteractionTime() + interactionTime;
            }
        } else {
            isInteraction =
                    app.getCurProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND;
            app.setFgInteractionTime(0);
        }
        final long interactionThreshold = fgsInteractionChangeEnabled
                ? mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S
                : mConstants.USAGE_STATS_INTERACTION_INTERVAL_PRE_S;
        if (isInteraction
                && (!app.getHasReportedInteraction()
                    || (nowElapsed - app.getInteractionEventTime()) > interactionThreshold)) {
            app.setInteractionEventTime(nowElapsed);
            final String[] packages = app.getProcessPackageNames();
            if (packages != null) {
                for (int i = 0; i < packages.length; i++) {
                    mService.mUsageStatsService.reportEvent(packages[i], app.userId,
                            UsageEvents.Event.SYSTEM_INTERACTION);
                }
            }
        }
        app.setHasReportedInteraction(isInteraction);
        if (!isInteraction) {
            app.setInteractionEventTime(0);
        }
    }

    private void maybeUpdateLastTopTime(ProcessRecordInternal state, long nowUptime) {
        if (state.getSetProcState() <= PROCESS_STATE_TOP
                && state.getCurProcState() > PROCESS_STATE_TOP) {
            state.setLastTopTime(nowUptime);
        }
    }

    /**
     * Look for recently inactive apps and mark them idle after a grace period. If idled, stop
     * any background services and inform listeners.
     */
    @GuardedBy("mService")
    void idleUidsLocked() {
        final int N = mActiveUids.size();
        mService.mHandler.removeMessages(IDLE_UIDS_MSG);
        if (N <= 0) {
            return;
        }
        final long nowElapsed = mInjector.getElapsedRealtimeMillis();
        final long maxBgTime = nowElapsed - mConstants.BACKGROUND_SETTLE_TIME;
        long nextTime = 0;
        if (mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.startUidChanges();
        }
        boolean shouldLogMisc = false;
        for (int i = N - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            final long bgTime = uidRec.getLastBackgroundTime();
            final long idleTime = uidRec.getLastIdleTimeIfStillIdle();
            if (bgTime > 0 && (!uidRec.isIdle() || idleTime == 0)) {
                if (bgTime <= maxBgTime) {
                    EventLogTags.writeAmUidIdle(uidRec.getUid());
                    synchronized (mProcLock) {
                        uidRec.setIdle(true);
                        uidRec.setSetIdle(true);
                        uidRec.setLastIdleTime(nowElapsed);
                    }
                    mService.doStopUidLocked(uidRec.getUid(), uidRec);
                } else {
                    if (nextTime == 0 || nextTime > bgTime) {
                        nextTime = bgTime;
                    }
                    if (mLogger.shouldLog(uidRec.getUid())) {
                        shouldLogMisc = true;
                    }
                }
            }
        }
        if (mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.finishUidChanges();
        }
        // Also check if there are any apps in cached and background restricted mode,
        // if so, kill it if it's been there long enough, or kick off a msg to check
        // it later.
        if (mService.mConstants.mKillBgRestrictedAndCachedIdle) {
            final ArraySet<ProcessRecord> apps = mProcessList.mAppsInBackgroundRestricted;
            for (int i = 0, size = apps.size(); i < size; i++) {
                // Check to see if needs to be killed.
                final long bgTime = mProcessList.killAppIfBgRestrictedAndCachedIdleLocked(
                        apps.valueAt(i), nowElapsed) - mConstants.BACKGROUND_SETTLE_TIME;
                if (bgTime > 0 && (nextTime == 0 || nextTime > bgTime)) {
                    nextTime = bgTime;
                }
            }
        }
        if (nextTime > 0) {
            long delay = nextTime + mConstants.BACKGROUND_SETTLE_TIME - nowElapsed;
            if (shouldLogMisc) {
                mLogger.logScheduleUidIdle3(delay);
            }
            mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG, delay);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    void setUidTempAllowlistStateLSP(int uid, boolean onAllowlist) {
        final UidRecord uidRec = mActiveUids.get(uid);
        if (uidRec != null && uidRec.isCurAllowListed() != onAllowlist) {
            uidRec.setCurAllowListed(onAllowlist);
            for (int i = uidRec.getNumOfProcs() - 1; i >= 0; i--) {
                enqueueOomAdjTargetLocked(uidRec.getProcessRecordByIndex(i));
            }
            updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_ALLOWLIST);
        }
    }

    @GuardedBy("mService")
    void dumpProcessListVariablesLocked(ProtoOutputStream proto) {
        proto.write(ActivityManagerServiceDumpProcessesProto.ADJ_SEQ, mAdjSeq);
        proto.write(ActivityManagerServiceDumpProcessesProto.LRU_SEQ, mProcessList.getLruSeqLOSP());
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_NON_CACHED_PROCS,
                mNumNonCachedProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_SERVICE_PROCS, mNumServiceProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NEW_NUM_SERVICE_PROCS,
                mNewNumServiceProcs);

    }

    @GuardedBy("mService")
    void dumpSequenceNumbersLocked(PrintWriter pw) {
        pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mProcessList.getLruSeqLOSP());
    }

    @GuardedBy("mService")
    void dumpProcCountsLocked(PrintWriter pw) {
        pw.println("  mNumNonCachedProcs=" + mNumNonCachedProcs
                + " (" + mProcessList.getLruSizeLOSP() + " total)"
                + " mNumCachedHiddenProcs=" + mNumCachedHiddenProcs
                + " mNumServiceProcs=" + mNumServiceProcs
                + " mNewNumServiceProcs=" + mNewNumServiceProcs);
    }

    /**
     * Return whether or not a process should be frozen.
     */
    static boolean getFreezePolicy(ProcessRecordInternal proc) {
        if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
            if ((proc.getCurCapability() & ALL_CPU_TIME_CAPABILITIES) != 0) {
                /// App is important enough (see {@link #getCpuCapability} and
                /// {@link #getImplicitCpuCapability}) or bound by something important enough to
                /// not be frozen (see {@link #getCpuCapabilitiesFromClient}).
                return false;
            }

            // Default, freeze a process.
            return true;
        } else {
            // The CPU capability handling covers all setShouldNotFreeze paths. Must check
            // shouldNotFreeze, if the CPU capability is not being used.
            if (proc.shouldNotFreeze()) {
                return false;
            }

            // Reasons to freeze:
            if (proc.getCurAdj() >= CACHED_APP_MIN_ADJ) {
                // Oomscore is in a high enough state, it is safe to freeze.
                return true;
            }

            // Default, do not freeze a process.
            return false;
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    void updateAppFreezeStateLSP(ProcessRecordInternal app, @OomAdjReason int oomAdjReason,
            boolean immediate, int oldOomAdj) {
        final boolean freezePolicy = getFreezePolicy(app);
        final boolean shouldNotFreezeChanged = app.shouldNotFreezeAdjSeq() == mAdjSeq;
        mCallback.onProcessFreezabilityChanged(app, freezePolicy, oomAdjReason, immediate,
                oldOomAdj, shouldNotFreezeChanged);
    }

    /**
     * Collects the given application process and all other processes reachable from it
     * (e.g., via service or content provider connections) into the provided list.
     *
     * @param app The initial application process from which to start collecting.
     * @param processesOut The list to populate with the collected ProcessRecordInternal objects.
     */
    @GuardedBy("mService")
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

    @GuardedBy("mService")
    abstract void onProcessEndLocked(@NonNull ProcessRecordInternal app);

    /**
     * Called when the process state is changed outside of the OomAdjuster.
     */
    @GuardedBy("mService")
    abstract void onProcessStateChanged(@NonNull ProcessRecordInternal app, int prevProcState);

    /**
     * Called when the oom adj is changed outside of the OomAdjuster.
     */
    @GuardedBy("mService")
    abstract void onProcessOomAdjChanged(@NonNull ProcessRecordInternal app, int prevAdj);

    @VisibleForTesting
    abstract void resetInternal();

    /**
     * Evaluate the service connection, return {@code true} if the client will change any state
     * (ie. ProcessState, oomAdj, capability, etc) of the service host process by the given
     * connection.
     */
    @GuardedBy("mService")
    boolean evaluateServiceConnectionAdd(ProcessRecordInternal client, ProcessRecordInternal app,
            ConnectionRecord cr) {
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
        } else if (Flags.cpuTimeCapabilityBasedFreezePolicy()
                && (client.getSetCapability() & ~app.getSetCapability()
                    & ALL_CPU_TIME_CAPABILITIES) != 0) {
            // The connection might grant CPU capability to the service.
            needDryRun = true;
        } else if (cr.hasFlag(Context.BIND_WAIVE_PRIORITY | Context.BIND_ALLOW_OOM_MANAGEMENT)) {
            // These bind flags can grant the shouldNotFreeze state to the service.
            needDryRun = true;
        } else if (client.shouldNotFreeze() && !app.shouldNotFreeze()) {
            // The shouldNotFreeze state can be propagated and needs to be checked.
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

    @GuardedBy("mService")
    boolean evaluateServiceConnectionRemoval(ProcessRecordInternal client,
            ProcessRecordInternal app, ConnectionRecord cr) {
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
        } else if (cr.hasFlag(Context.BIND_WAIVE_PRIORITY | Context.BIND_ALLOW_OOM_MANAGEMENT)) {
            return true;
        } else if (app.shouldNotFreeze() && client.shouldNotFreeze()) {
            // Process has shouldNotFreeze and it could have gotten it from the client.
            return true;
        } else if (Flags.cpuTimeCapabilityBasedFreezePolicy()
                && (client.getSetCapability() & app.getSetCapability()
                & ALL_CPU_TIME_CAPABILITIES) != 0) {
            return true;
        }
        return false;
    }

    @GuardedBy("mService")
    boolean evaluateProviderConnectionAdd(ProcessRecordInternal client, ProcessRecordInternal app) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        boolean needDryRun = false;
        if (app.getSetAdj() > client.getSetAdj()) {
            needDryRun = true;
        } else if (app.getSetProcState() > client.getSetProcState()) {
            needDryRun = true;
        } else if (client.shouldNotFreeze() && !app.shouldNotFreeze()) {
            needDryRun = true;
        } else if (Flags.cpuTimeCapabilityBasedFreezePolicy()
                && (client.getSetCapability() & ~app.getSetCapability()
                & ALL_CPU_TIME_CAPABILITIES) != 0) {
            // The connection might grant CPU capability to the provider.
            needDryRun = true;
        }

        if (needDryRun) {
            return computeProviderHostOomAdjLSP(null, app, client, true /* dryRun */);
        }
        return false;
    }

    @GuardedBy("mService")
    boolean evaluateProviderConnectionRemoval(ProcessRecordInternal client,
            ProcessRecordInternal app) {
        if (evaluateConnectionPrelude(client, app)) {
            return true;
        }

        if (app.getSetAdj() >= client.getSetAdj()) {
            return true;
        } else if (app.getSetProcState() >= client.getSetProcState()) {
            return true;
        } else if (app.shouldNotFreeze() && client.shouldNotFreeze()) {
            // Process has shouldNotFreeze and it could have gotten it from the client.
            return true;
        } else if (Flags.cpuTimeCapabilityBasedFreezePolicy()
                && (client.getSetCapability() & app.getSetCapability()
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

    @GuardedBy("mService")
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


    @GuardedBy("mService")
    private void scheduleFollowUpOomAdjusterUpdateLocked(long updateUptimeMs,
            long now) {
        if (updateUptimeMs + mConstants.FOLLOW_UP_OOMADJ_UPDATE_WAIT_DURATION
                >= mNextFollowUpUpdateUptimeMs) {
            // Update time is too close or later than the next follow up update.
            return;
        }
        if (updateUptimeMs < now + mConstants.FOLLOW_UP_OOMADJ_UPDATE_WAIT_DURATION) {
            // Use a minimum delay for the follow up to possibly batch multiple process
            // evaluations and avoid rapid updates.
            updateUptimeMs = now + mConstants.FOLLOW_UP_OOMADJ_UPDATE_WAIT_DURATION;
        }

        // Schedulate a follow up update. Don't bother deleting existing handler messages, they
        // will be cleared during the message while no locks are being held.
        mNextFollowUpUpdateUptimeMs = updateUptimeMs;
        mService.mHandler.sendEmptyMessageAtTime(FOLLOW_UP_OOMADJUSTER_UPDATE_MSG,
                mNextFollowUpUpdateUptimeMs);
    }
}
