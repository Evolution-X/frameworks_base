/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK;
import static android.app.ActivityManager.PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK;
import static android.app.ActivityManager.PROCESS_STATE_BACKUP;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;
import static android.content.Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.media.audio.Flags.roForegroundAudioControl;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_BACKUP;
import static com.android.server.am.ActivityManagerService.TAG_OOM_ADJ;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.ProcessCachedOptimizerRecord.SHOULD_NOT_FREEZE_REASON_BINDER_ALLOW_OOM_MANAGEMENT;
import static com.android.server.am.ProcessCachedOptimizerRecord.SHOULD_NOT_FREEZE_REASON_FREEZER_EXEMPT_PACKAGE;
import static com.android.server.am.ProcessCachedOptimizerRecord.SHOULD_NOT_FREEZE_REASON_NONE;
import static com.android.server.am.ProcessCachedOptimizerRecord.SHOULD_NOT_FREEZE_REASON_UID_ALLOWLISTED;
import static com.android.server.am.ProcessList.BACKUP_APP_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.HEAVY_WEIGHT_APP_ADJ;
import static com.android.server.am.ProcessList.HOME_APP_ADJ;
import static com.android.server.am.ProcessList.NATIVE_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_PROC_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_SERVICE_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.ProcessList.SCHED_GROUP_DEFAULT;
import static com.android.server.am.ProcessList.SCHED_GROUP_RESTRICTED;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP_BOUND;
import static com.android.server.am.ProcessList.SERVICE_ADJ;
import static com.android.server.am.ProcessList.SERVICE_B_ADJ;
import static com.android.server.am.ProcessList.SYSTEM_ADJ;
import static com.android.server.am.ProcessList.UNKNOWN_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_MAX_ADJ;
import static com.android.server.am.psc.PlatformCompatCache.CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY;
import static com.android.server.am.psc.PlatformCompatCache.CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.am.psc.ActiveUidsInternal;
import com.android.server.am.psc.ConnectionRecordInternal;
import com.android.server.am.psc.ContentProviderConnectionInternal;
import com.android.server.am.psc.ContentProviderRecordInternal;
import com.android.server.am.psc.ProcessProviderRecordInternal;
import com.android.server.am.psc.ProcessRecordInternal;
import com.android.server.am.psc.ProcessServiceRecordInternal;
import com.android.server.am.psc.ServiceRecordInternal;
import com.android.server.am.psc.UidRecordInternal;
import com.android.server.wm.ActivityServiceConnectionsHolder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * The implementation of the oom adjuster traversal algorithm and policies.
 */
public class OomAdjusterImpl extends OomAdjuster {
    static final String TAG = "OomAdjusterImpl";

    // The ADJ_SLOT_INVALID is NOT an actual slot.
    static final int ADJ_SLOT_INVALID = -1;
    static final int ADJ_SLOT_NATIVE = 0;
    static final int ADJ_SLOT_SYSTEM = 1;
    static final int ADJ_SLOT_PERSISTENT_PROC = 2;
    static final int ADJ_SLOT_PERSISTENT_SERVICE = 3;
    static final int ADJ_SLOT_FOREGROUND_APP = 4;
    static final int ADJ_SLOT_PERCEPTIBLE_RECENT_FOREGROUND_APP = 5;
    static final int ADJ_SLOT_VISIBLE_APP = 6;
    static final int ADJ_SLOT_PERCEPTIBLE_APP = 7;
    static final int ADJ_SLOT_PERCEPTIBLE_MEDIUM_APP = 8;
    static final int ADJ_SLOT_PERCEPTIBLE_LOW_APP = 9;
    static final int ADJ_SLOT_BACKUP_APP = 10;
    static final int ADJ_SLOT_HEAVY_WEIGHT_APP = 11;
    static final int ADJ_SLOT_SERVICE = 12;
    static final int ADJ_SLOT_HOME_APP = 13;
    static final int ADJ_SLOT_PREVIOUS_APP = 14;
    static final int ADJ_SLOT_SERVICE_B = 15;
    static final int ADJ_SLOT_CACHED_APP = 16;
    static final int ADJ_SLOT_UNKNOWN = 17;

    @IntDef(prefix = { "ADJ_SLOT_" }, value = {
        ADJ_SLOT_INVALID,
        ADJ_SLOT_NATIVE,
        ADJ_SLOT_SYSTEM,
        ADJ_SLOT_PERSISTENT_PROC,
        ADJ_SLOT_PERSISTENT_SERVICE,
        ADJ_SLOT_FOREGROUND_APP,
        ADJ_SLOT_PERCEPTIBLE_RECENT_FOREGROUND_APP,
        ADJ_SLOT_VISIBLE_APP,
        ADJ_SLOT_PERCEPTIBLE_APP,
        ADJ_SLOT_PERCEPTIBLE_MEDIUM_APP,
        ADJ_SLOT_PERCEPTIBLE_LOW_APP,
        ADJ_SLOT_BACKUP_APP,
        ADJ_SLOT_HEAVY_WEIGHT_APP,
        ADJ_SLOT_SERVICE,
        ADJ_SLOT_HOME_APP,
        ADJ_SLOT_PREVIOUS_APP,
        ADJ_SLOT_SERVICE_B,
        ADJ_SLOT_CACHED_APP,
        ADJ_SLOT_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AdjSlot{}

    static final int[] ADJ_SLOT_VALUES = new int[] {
        NATIVE_ADJ,
        SYSTEM_ADJ,
        PERSISTENT_PROC_ADJ,
        PERSISTENT_SERVICE_ADJ,
        FOREGROUND_APP_ADJ,
        PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ,
        VISIBLE_APP_ADJ,
        PERCEPTIBLE_APP_ADJ,
        PERCEPTIBLE_MEDIUM_APP_ADJ,
        PERCEPTIBLE_LOW_APP_ADJ,
        BACKUP_APP_ADJ,
        HEAVY_WEIGHT_APP_ADJ,
        SERVICE_ADJ,
        HOME_APP_ADJ,
        PREVIOUS_APP_ADJ,
        SERVICE_B_ADJ,
        CACHED_APP_MIN_ADJ,
        UNKNOWN_ADJ,
    };

    /**
     * Note: Always use the raw adj to call this API.
     */
    static @AdjSlot int adjToSlot(int adj) {
        if (adj >= ADJ_SLOT_VALUES[0] && adj <= ADJ_SLOT_VALUES[ADJ_SLOT_VALUES.length - 1]) {
            // Conduct a binary search, in most of the cases it'll get a hit.
            final int index = Arrays.binarySearch(ADJ_SLOT_VALUES, adj);
            if (index >= 0) {
                return index;
            }
            // If not found, the returned index above should be (-(insertion point) - 1),
            // let's return the first slot that's less than the adj value.
            return -(index + 1) - 1;
        }
        return ADJ_SLOT_VALUES.length - 1;
    }

    static final int[] PROC_STATE_SLOTS = new int[] {
        PROCESS_STATE_PERSISTENT, // 0
        PROCESS_STATE_PERSISTENT_UI,
        PROCESS_STATE_TOP,
        PROCESS_STATE_BOUND_TOP,
        PROCESS_STATE_FOREGROUND_SERVICE,
        PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
        PROCESS_STATE_IMPORTANT_FOREGROUND,
        PROCESS_STATE_IMPORTANT_BACKGROUND,
        PROCESS_STATE_TRANSIENT_BACKGROUND,
        PROCESS_STATE_BACKUP,
        PROCESS_STATE_SERVICE,
        PROCESS_STATE_RECEIVER,
        PROCESS_STATE_TOP_SLEEPING,
        PROCESS_STATE_HEAVY_WEIGHT,
        PROCESS_STATE_HOME,
        PROCESS_STATE_LAST_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY_CLIENT,
        PROCESS_STATE_CACHED_RECENT,
        PROCESS_STATE_CACHED_EMPTY,
        PROCESS_STATE_UNKNOWN, // -1
    };

    static int processStateToSlot(@ActivityManager.ProcessState int state) {
        if (state >= PROCESS_STATE_PERSISTENT && state <= PROCESS_STATE_CACHED_EMPTY) {
            return state;
        }
        return PROC_STATE_SLOTS.length - 1;
    }

    /**
     * A container node in the {@link LinkedProcessRecordList},
     * holding the references to {@link ProcessRecord}.
     * TODO(b/425766486): Change to package-private after moving the class to the psc package.
     */
    public static class ProcessRecordNode {
        static final int NODE_TYPE_PROC_STATE = 0;
        static final int NODE_TYPE_ADJ = 1;

        @IntDef(prefix = { "NODE_TYPE_" }, value = {
            NODE_TYPE_PROC_STATE,
            NODE_TYPE_ADJ,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface NodeType {}

        public static final int NUM_NODE_TYPE = NODE_TYPE_ADJ + 1;

        @Nullable ProcessRecordNode mPrev;
        @Nullable ProcessRecordNode mNext;
        final @Nullable ProcessRecord mApp;

        ProcessRecordNode(@Nullable ProcessRecord app) {
            mApp = app;
        }

        void unlink() {
            if (mPrev != null) {
                mPrev.mNext = mNext;
            }
            if (mNext != null) {
                mNext.mPrev = mPrev;
            }
            mPrev = mNext = null;
        }

        boolean isLinked() {
            return mPrev != null && mNext != null;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("ProcessRecordNode{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            sb.append(mApp);
            sb.append(' ');
            sb.append(mApp != null ? mApp.getCurProcState() : PROCESS_STATE_UNKNOWN);
            sb.append(' ');
            sb.append(mApp != null ? mApp.getCurAdj() : UNKNOWN_ADJ);
            sb.append(' ');
            sb.append(Integer.toHexString(System.identityHashCode(mPrev)));
            sb.append(' ');
            sb.append(Integer.toHexString(System.identityHashCode(mNext)));
            sb.append('}');
            return sb.toString();
        }
    }

    private class ProcessRecordNodes {
        private final @ProcessRecordNode.NodeType int mType;

        private final LinkedProcessRecordList[] mProcessRecordNodes;

        private final ToIntFunction<ProcessRecordInternal> mSlotFunction;
        // Cache of the most important slot with a node in it.
        private int mFirstPopulatedSlot = 0;

        ProcessRecordNodes(@ProcessRecordNode.NodeType int type, int size) {
            mType = type;
            final ToIntFunction<ProcessRecordInternal> valueFunction;
            switch (mType) {
                case ProcessRecordNode.NODE_TYPE_PROC_STATE:
                    valueFunction = (proc) -> proc.getCurProcState();
                    mSlotFunction = (proc) -> processStateToSlot(proc.getCurProcState());
                    break;
                case ProcessRecordNode.NODE_TYPE_ADJ:
                    valueFunction = (proc) -> proc.getCurRawAdj();
                    mSlotFunction = (proc) -> adjToSlot(proc.getCurRawAdj());
                    break;
                default:
                    valueFunction = (proc) -> 0;
                    mSlotFunction = (proc) -> 0;
                    break;
            }

            mProcessRecordNodes = new LinkedProcessRecordList[size];
            for (int i = 0; i < size; i++) {
                mProcessRecordNodes[i] = new LinkedProcessRecordList(valueFunction);
            }
            reset();
        }

        @VisibleForTesting
        void reset() {
            for (int i = 0; i < mProcessRecordNodes.length; i++) {
                mProcessRecordNodes[i].reset();
            }
        }

        ProcessRecord poll() {
            ProcessRecordNode node = null;
            final int size = mProcessRecordNodes.length;
            // Find the next node.
            while (node == null && mFirstPopulatedSlot < size) {
                node = mProcessRecordNodes[mFirstPopulatedSlot].poll();
                if (node == null) {
                    // This slot is now empty, move on to the next.
                    mFirstPopulatedSlot++;
                }
            }
            if (node == null) return null;
            return node.mApp;
        }

        void offer(ProcessRecordInternal proc) {
            ProcessRecordNode node = proc.mLinkedNodes[mType];
            // Find which slot to add the node to.
            final int newSlot = mSlotFunction.applyAsInt(proc);
            if (newSlot < mFirstPopulatedSlot) {
                // node is being added to a more important slot.
                mFirstPopulatedSlot = newSlot;
            }
            node.unlink();
            mProcessRecordNodes[newSlot].offer(node);
        }

        void unlink(@NonNull ProcessRecordInternal app) {
            final ProcessRecordNode node = app.mLinkedNodes[mType];
            node.unlink();
        }

        /**
         * A simple version of {@link java.util.LinkedList}, as here we don't allocate new node
         * while adding an object to it.
         */
        private static class LinkedProcessRecordList {
            // Sentinel head/tail, to make bookkeeping work easier.
            final ProcessRecordNode HEAD = new ProcessRecordNode(null);
            final ProcessRecordNode TAIL = new ProcessRecordNode(null);
            final ToIntFunction<ProcessRecordInternal> mValueFunction;

            LinkedProcessRecordList(ToIntFunction<ProcessRecordInternal> valueFunction) {
                HEAD.mNext = TAIL;
                TAIL.mPrev = HEAD;
                mValueFunction = valueFunction;
            }

            ProcessRecordNode poll() {
                final ProcessRecordNode next = HEAD.mNext;
                if (next == TAIL) return null;
                next.unlink();
                return next;
            }

            void offer(@NonNull ProcessRecordNode node) {
                final int newValue = mValueFunction.applyAsInt(node.mApp);

                // Find the last node with less than or equal value to the new node.
                ProcessRecordNode curNode = TAIL.mPrev;
                while (curNode != HEAD && mValueFunction.applyAsInt(curNode.mApp) > newValue) {
                    curNode = curNode.mPrev;
                }

                // Insert the new node after the found node.
                node.mPrev = curNode;
                node.mNext = curNode.mNext;
                curNode.mNext.mPrev = node;
                curNode.mNext = node;
            }

            @VisibleForTesting
            void reset() {
                if (HEAD.mNext != TAIL) {
                    HEAD.mNext.mPrev = TAIL.mPrev.mNext = null;
                }
                HEAD.mNext = TAIL;
                TAIL.mPrev = HEAD;
            }
        }
    }

    /**
     * A data class for holding the parameters in computing oom adj.
     */
    private class OomAdjusterArgs {
        ProcessRecordInternal mApp;
        ProcessRecordInternal mTopApp;
        long mNow;
        int mCachedAdj;
        @OomAdjReason int mOomAdjReason;
        @NonNull
        ActiveUidsInternal mUids;
        boolean mFullUpdate;

        void update(ProcessRecordInternal topApp, long now, int cachedAdj,
                @OomAdjReason int oomAdjReason, @NonNull ActiveUidsInternal uids,
                boolean fullUpdate) {
            mTopApp = topApp;
            mNow = now;
            mCachedAdj = cachedAdj;
            mOomAdjReason = oomAdjReason;
            mUids = uids;
            mFullUpdate = fullUpdate;
        }
    }

    /**
     * A {@link Connection} represents any connection between two processes that can cause a
     * change in importance in the host process based on the client process and connection state.
     */
    public interface Connection {
        int CPU_TIME_TRANSMISSION_NONE = 0;
        int CPU_TIME_TRANSMISSION_NORMAL = 1;
        int CPU_TIME_TRANSMISSION_LEGACY = 2;

        @IntDef(prefix = "CPU_TIME_TRANSMISSION_", value = {
                CPU_TIME_TRANSMISSION_NONE,
                CPU_TIME_TRANSMISSION_NORMAL,
                CPU_TIME_TRANSMISSION_LEGACY,
        })
        @interface CpuTimeTransmissionType {
        }

        /**
         * Compute the impact this connection has on the host's importance values.
         */
        void computeHostOomAdjLSP(OomAdjuster oomAdjuster, ProcessRecordInternal host,
                ProcessRecordInternal client, long now, ProcessRecordInternal topApp,
                boolean doingAll, int oomAdjReason, int cachedAdj);

        /**
         * Returns true if this connection can propagate capabilities.
         */
        boolean canAffectCapabilities();

        /**
         * Returns the type of transmission of ALL_CPU_TIME_CAPABILITIES to the host, if the client
         * possesses it.
         */
        @CpuTimeTransmissionType
        default int cpuTimeTransmissionType() {
            return CPU_TIME_TRANSMISSION_NORMAL;
        }
    }

    /**
     * A helper consumer for marking and collecting reachable processes.
     */
    private static class ReachableCollectingConsumer implements
            BiConsumer<Connection, ProcessRecordInternal> {
        ArrayList<ProcessRecordInternal> mReachables = null;

        public void init(ArrayList<ProcessRecordInternal> reachables) {
            mReachables = reachables;
        }

        @Override
        public void accept(Connection unused, ProcessRecordInternal host) {
            if (host.isReachable()) {
                return;
            }
            host.setReachable(true);
            mReachables.add(host);
        }
    }

    private final ReachableCollectingConsumer mReachableCollectingConsumer =
            new ReachableCollectingConsumer();

    /**
     * A helper consumer for computing the importance of a connection from a client.
     * Connections for clients marked reachable will be ignored.
     */
    private class ComputeConnectionIgnoringReachableClientsConsumer implements
            BiConsumer<Connection, ProcessRecordInternal> {
        private OomAdjusterArgs mArgs = null;
        public boolean hasReachableClient = false;

        public void init(OomAdjusterArgs args) {
            mArgs = args;
            hasReachableClient = false;
        }

        @Override
        public void accept(Connection conn, ProcessRecordInternal client) {
            final ProcessRecordInternal host = mArgs.mApp;
            final ProcessRecordInternal topApp = mArgs.mTopApp;
            final long now = mArgs.mNow;
            final @OomAdjReason int oomAdjReason = mArgs.mOomAdjReason;

            if (client.isReachable()) {
                hasReachableClient = true;
                return;
            }

            if (unimportantConnectionLSP(conn, host, client)) {
                return;
            }

            conn.computeHostOomAdjLSP(OomAdjusterImpl.this, host, client, now, topApp, false,
                    oomAdjReason, UNKNOWN_ADJ);
        }
    }

    private final ComputeConnectionIgnoringReachableClientsConsumer
            mComputeConnectionIgnoringReachableClientsConsumer =
            new ComputeConnectionIgnoringReachableClientsConsumer();

    /**
     * A helper consumer for computing host process importance from a connection from a client app.
     */
    private class ComputeHostConsumer implements BiConsumer<Connection, ProcessRecordInternal> {
        public OomAdjusterArgs args = null;

        @Override
        public void accept(Connection conn, ProcessRecordInternal host) {
            final ProcessRecordInternal client = args.mApp;
            final int cachedAdj = args.mCachedAdj;
            final ProcessRecordInternal topApp = args.mTopApp;
            final long now = args.mNow;
            final @OomAdjReason int oomAdjReason = args.mOomAdjReason;
            final boolean fullUpdate = args.mFullUpdate;

            final int prevProcState = host.getCurProcState();
            final int prevAdj = host.getCurRawAdj();

            if (unimportantConnectionLSP(conn, host, client)) {
                return;
            }

            conn.computeHostOomAdjLSP(OomAdjusterImpl.this, host, client, now, topApp,
                    fullUpdate, oomAdjReason, cachedAdj);

            updateProcStateSlotIfNecessary(host, prevProcState);
            updateAdjSlotIfNecessary(host, prevAdj);
        }
    }
    private final ComputeHostConsumer mComputeHostConsumer = new ComputeHostConsumer();

    /**
     * A helper consumer for computing all connections from an app.
     */
    private class ComputeConnectionsConsumer implements Consumer<OomAdjusterArgs> {
        @Override
        public void accept(OomAdjusterArgs args) {
            final ProcessRecordInternal app = args.mApp;
            final ActiveUidsInternal uids = args.mUids;

            // This process was updated in some way, mark that it was last calculated this sequence.
            app.setCompletedAdjSeq(mAdjSeq);
            if (uids != null) {
                final UidRecordInternal uidRec = app.getUidRecord();
                if (uidRec != null) {
                    uids.put(uidRec.getUid(), uidRec);
                }
            }
            mComputeHostConsumer.args = args;
            forEachConnectionLSP(app, mComputeHostConsumer);
        }
    }
    private final ComputeConnectionsConsumer mComputeConnectionsConsumer =
            new ComputeConnectionsConsumer();

    OomAdjusterImpl(ActivityManagerService service, ProcessList processList,
            ActiveUids activeUids, ServiceThread adjusterThread, GlobalState globalState,
            Injector injector, Callback callback) {
        super(service, processList, activeUids, adjusterThread, globalState, injector, callback);
    }

    private final ProcessRecordNodes mProcessRecordProcStateNodes = new ProcessRecordNodes(
            ProcessRecordNode.NODE_TYPE_PROC_STATE, PROC_STATE_SLOTS.length);
    private final ProcessRecordNodes mProcessRecordAdjNodes = new ProcessRecordNodes(
            ProcessRecordNode.NODE_TYPE_ADJ, ADJ_SLOT_VALUES.length);
    private final OomAdjusterArgs mTmpOomAdjusterArgs = new OomAdjusterArgs();

    void unlinkProcessRecordFromList(@NonNull ProcessRecordInternal app) {
        mProcessRecordProcStateNodes.unlink(app);
        mProcessRecordAdjNodes.unlink(app);
    }

    @Override
    @VisibleForTesting
    void resetInternal() {
        mProcessRecordProcStateNodes.reset();
        mProcessRecordAdjNodes.reset();
    }

    @GuardedBy("mService")
    @Override
    void onProcessEndLocked(@NonNull ProcessRecordInternal app) {
        if (app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE] != null
                && app.mLinkedNodes[ProcessRecordNode.NODE_TYPE_PROC_STATE].isLinked()) {
            unlinkProcessRecordFromList(app);
        }
    }

    @GuardedBy("mService")
    @Override
    void onProcessStateChanged(@NonNull ProcessRecordInternal app, int prevProcState) {
        updateProcStateSlotIfNecessary(app, prevProcState);
    }

    @GuardedBy("mService")
    void onProcessOomAdjChanged(@NonNull ProcessRecordInternal app, int prevAdj) {
        updateAdjSlotIfNecessary(app, prevAdj);
    }

    private void updateAdjSlotIfNecessary(ProcessRecordInternal app, int prevRawAdj) {
        if (app.getCurRawAdj() != prevRawAdj) {
            mProcessRecordAdjNodes.offer(app);
        }
    }

    private void updateProcStateSlotIfNecessary(ProcessRecordInternal app, int prevProcState) {
        if (app.getCurProcState() != prevProcState) {
            mProcessRecordProcStateNodes.offer(app);
        }
    }

    @Override
    protected void performUpdateOomAdjLSP(@OomAdjReason int oomAdjReason) {
        mProcessStateCurTop = getTopProcessState();
        // Clear any pending ones because we are doing a full update now.
        mPendingProcessSet.clear();

        mLastReason = oomAdjReason;
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));

        fullUpdateLSP(oomAdjReason);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected boolean performUpdateOomAdjLSP(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        mPendingProcessSet.add(app);
        performUpdateOomAdjPendingTargetsLocked(oomAdjReason);
        return true;
    }

    @GuardedBy("mService")
    @Override
    protected void performUpdateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason) {
        mLastReason = oomAdjReason;
        mProcessStateCurTop = enqueuePendingTopAppIfNecessaryLSP();
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReasonToString(oomAdjReason));

        synchronized (mProcLock) {
            partialUpdateLSP(oomAdjReason, mPendingProcessSet);
        }
        mPendingProcessSet.clear();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Perform a full update on the entire process list.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void fullUpdateLSP(@OomAdjReason int oomAdjReason) {
        final ProcessRecordInternal topApp = getTopProcess();
        final long now = mInjector.getUptimeMillis();
        final long nowElapsed = mInjector.getElapsedRealtimeMillis();
        final long oldTime = now - mConstants.mMaxEmptyTimeMillis;

        mAdjSeq++;

        mNewNumServiceProcs = 0;
        mNewNumAServiceProcs = 0;

        // Clear the priority queues.
        mProcessRecordProcStateNodes.reset();
        mProcessRecordAdjNodes.reset();

        final ArrayList<ProcessRecord> lru = mProcessList.getLruProcessesLOSP();
        for (int i = lru.size() - 1; i >= 0; i--) {
            final ProcessRecordInternal app = lru.get(i);
            app.resetCachedInfo();
            final UidRecordInternal uidRec = app.getUidRecord();
            if (uidRec != null) {
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
                }
                uidRec.reset();
            }

            // Compute initial values, the procState and adj priority queues will be populated here.
            computeOomAdjLSP(app, topApp, true, now);

            // Just add to the procState priority queue. The adj priority queue should be
            // empty going into the traversal step.
            mProcessRecordProcStateNodes.offer(app);
        }

        // Set adj last nodes now, this way a process will only be reevaluated during the adj node
        // iteration if they adj score changed during the procState node iteration.
        mProcessRecordAdjNodes.reset();
        mTmpOomAdjusterArgs.update(topApp, now, UNKNOWN_ADJ, oomAdjReason, null, true);
        computeConnectionsLSP();

        applyLruAdjust(mProcessList.getLruProcessesLOSP());
        postUpdateOomAdjInnerLSP(oomAdjReason, mActiveUids, now, nowElapsed, oldTime, true);
    }

    /**
     * Traverse the process graph and update processes based on changes in connection importances.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void computeConnectionsLSP() {
        // 1st pass, iterate all nodes in order of procState importance.
        ProcessRecord proc = mProcessRecordProcStateNodes.poll();
        while (proc != null) {
            mTmpOomAdjusterArgs.mApp = proc;
            mComputeConnectionsConsumer.accept(mTmpOomAdjusterArgs);
            proc = mProcessRecordProcStateNodes.poll();
        }

        // 2nd pass, iterate all nodes in order of adj importance.
        proc = mProcessRecordAdjNodes.poll();
        while (proc != null) {
            mTmpOomAdjusterArgs.mApp = proc;
            mComputeConnectionsConsumer.accept(mTmpOomAdjusterArgs);
            proc = mProcessRecordAdjNodes.poll();
        }
    }

    /**
     * Perform a partial update on the target processes and their reachable processes.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void partialUpdateLSP(@OomAdjReason int oomAdjReason,
            ArraySet<ProcessRecordInternal> targets) {
        final ProcessRecordInternal topApp = getTopProcess();
        final long now = mInjector.getUptimeMillis();
        final long nowElapsed = mInjector.getElapsedRealtimeMillis();
        final long oldTime = now - mConstants.mMaxEmptyTimeMillis;

        ActiveUids activeUids = mTmpUidRecords;
        activeUids.clear();
        mTmpOomAdjusterArgs.update(topApp, now, UNKNOWN_ADJ, oomAdjReason, activeUids, false);

        mAdjSeq++;

        final ArrayList<ProcessRecordInternal> reachables = mTmpProcessList;
        reachables.clear();

        for (int i = 0, size = targets.size(); i < size; i++) {
            final ProcessRecordInternal target = targets.valueAtUnchecked(i);
            target.resetCachedInfo();
            target.setReachable(true);
            reachables.add(target);
        }

        // Collect all processes that are reachable.
        // Any process not found in this step will not change in importance during this update.
        collectAndMarkReachableProcessesLSP(reachables);

        // Initialize the reachable processes based on their own values plus any
        // connections from processes not found in the previous step. Since those non-reachable
        // processes cannot change as a part of this update, their current values can be used
        // right now.
        mProcessRecordProcStateNodes.reset();
        initReachableStatesLSP(reachables, targets.size(), mTmpOomAdjusterArgs);

        // Set adj last nodes now, this way a process will only be reevaluated during the adj node
        // iteration if they adj score changed during the procState node iteration.
        mProcessRecordAdjNodes.reset();
        // Now traverse and compute the connections of processes with changed importance.
        computeConnectionsLSP();

        boolean needLruAdjust = false;
        for (int i = 0, size = reachables.size(); i < size; i++) {
            final ProcessRecordInternal state = reachables.get(i);
            state.setReachable(false);
            state.setCompletedAdjSeq(mAdjSeq);
            final int curAdj = state.getCurAdj();
            // Processes assigned the PREV oomscore will have a laddered oomscore with respect to
            // their positions in the LRU list. i.e. prev+0, prev+1, prev+2, etc.
            final boolean isPrevApp = Flags.oomadjusterPrevLaddering()
                    && PREVIOUS_APP_ADJ <= curAdj && curAdj <= PREVIOUS_APP_MAX_ADJ;
            final boolean isVisApp = Flags.oomadjusterVisLaddering()
                    && Flags.removeLruSpamPrevention()
                    && VISIBLE_APP_ADJ <= curAdj && curAdj <= VISIBLE_APP_MAX_ADJ;
            if (curAdj >= UNKNOWN_ADJ || isPrevApp || isVisApp) {
                needLruAdjust = true;
            }
        }

        // If all processes have an assigned adj, no need to calculate and assign cached adjs.
        if (needLruAdjust) {
            // TODO: b/319163103 - optimize cache adj assignment to not require the whole lru list.
            applyLruAdjust(mProcessList.getLruProcessesLOSP());
        }

        // Repopulate any uid record that may have changed.
        for (int i = 0, size = activeUids.size(); i < size; i++) {
            final UidRecord ur = activeUids.valueAt(i);
            ur.reset();
            for (int j = ur.getNumOfProcs() - 1; j >= 0; j--) {
                final ProcessRecord proc = ur.getProcessRecordByIndex(j);
                updateAppUidRecIfNecessaryLSP(proc);
            }
        }

        postUpdateOomAdjInnerLSP(oomAdjReason, activeUids, now, nowElapsed, oldTime, false);
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    protected void collectReachableProcessesLSP(
            @NonNull ArrayList<ProcessRecordInternal> reachables) {
        collectAndMarkReachableProcessesLSP(reachables);
        for (int i = 0, size = reachables.size(); i < size; i++) {
            final ProcessRecordInternal state = reachables.get(i);
            state.setReachable(false);
        }
    }

    /**
     * Mark all processes reachable from the {@code reachables} processes and add them to the
     * provided {@code reachables} list (targets excluded).
     */
    @GuardedBy({"mService", "mProcLock"})
    private void collectAndMarkReachableProcessesLSP(ArrayList<ProcessRecordInternal> reachables) {
        mReachableCollectingConsumer.init(reachables);
        for (int i = 0; i < reachables.size(); i++) {
            ProcessRecordInternal pr = reachables.get(i);
            forEachConnectionLSP(pr, mReachableCollectingConsumer);
        }
    }

    /**
     * Calculate initial importance states for {@code reachables} and update their slot position
     * if necessary.
     */
    private void initReachableStatesLSP(ArrayList<ProcessRecordInternal> reachables,
            int targetCount, OomAdjusterArgs args) {
        int i = 0;
        boolean initReachables = !Flags.skipUnimportantConnections();
        for (; i < targetCount && !initReachables; i++) {
            final ProcessRecordInternal target = reachables.get(i);
            final int prevProcState = target.getCurProcState();
            final int prevAdj = target.getCurRawAdj();
            final int prevCapability = target.getCurCapability();
            final boolean prevShouldNotFreeze = target.shouldNotFreeze();

            args.mApp = target;
            // If target client is a reachable, reachables need to be reinited in case this
            // client is important enough to change this target in the computeConnection step.
            initReachables |= computeOomAdjIgnoringReachablesLSP(args);
            // If target lowered in importance, reachables need to be reinited because this
            // target may have been the source of a reachable's current importance.
            initReachables |= selfImportanceLoweredLSP(target, prevProcState, prevAdj,
                    prevCapability, prevShouldNotFreeze);

            mProcessRecordProcStateNodes.offer(target);
            mProcessRecordAdjNodes.offer(target);
        }

        if (!initReachables) {
            return;
        }

        for (int size = reachables.size(); i < size; i++) {
            final ProcessRecordInternal reachable = reachables.get(i);
            args.mApp = reachable;
            computeOomAdjIgnoringReachablesLSP(args);

            // Just add to the procState priority queue. The adj priority queue should be
            // empty going into the traversal step.
            mProcessRecordProcStateNodes.offer(reachable);
        }
    }

    /**
     * Calculate initial importance states for {@code app}.
     * Processes not marked reachable cannot change as a part of this update, so connections from
     * those process can be calculated now.
     *
     * Returns true if any client connection was skipped due to a reachablity cycle.
     */
    @GuardedBy({"mService", "mProcLock"})
    private boolean computeOomAdjIgnoringReachablesLSP(OomAdjusterArgs args) {
        final ProcessRecordInternal app = args.mApp;
        final ProcessRecordInternal topApp = args.mTopApp;
        final long now = args.mNow;

        computeOomAdjLSP(app, topApp, false, now);

        mComputeConnectionIgnoringReachableClientsConsumer.init(args);
        forEachClientConnectionLSP(app, mComputeConnectionIgnoringReachableClientsConsumer);
        return mComputeConnectionIgnoringReachableClientsConsumer.hasReachableClient;
    }

    /**
     * Stream the connections with {@code app} as a client to
     * {@code connectionConsumer}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static void forEachConnectionLSP(ProcessRecordInternal app,
            BiConsumer<Connection, ProcessRecordInternal> connectionConsumer) {
        final ProcessServiceRecordInternal psr =  app.getServices();
        for (int i = psr.numberOfConnections() - 1; i >= 0; i--) {
            ConnectionRecordInternal cr = psr.getConnectionAt(i);
            ProcessRecordInternal service = cr.hasFlag(ServiceInfo.FLAG_ISOLATED_PROCESS)
                    ? cr.getService().getIsolationHostProcess()
                    : cr.getService().getHostProcess();
            if (service == null || service == app || isSandboxAttributedConnection(cr, service)) {
                continue;
            }
            // If the host is high priority, skip the connection recompute unless the connection has
            // flags, which needs extra consideration. e.g. BIND_SCHEDULE_LIKE_TOP_APP
            if (isHighPriorityProcess(service) && allowSkipForBindScheduleLikeTopApp(cr, service)) {
                continue;
            }
            connectionConsumer.accept(cr, service);
        }

        for (int i = psr.numberOfSdkSandboxConnections() - 1; i >= 0; i--) {
            final ConnectionRecordInternal cr = psr.getSdkSandboxConnectionAt(i);
            final ProcessRecordInternal service = cr.getService().getHostProcess();
            if (service == null || service == app) {
                continue;
            }
            if (isHighPriorityProcess(service) && allowSkipForBindScheduleLikeTopApp(cr, service)) {
                continue;
            }
            connectionConsumer.accept(cr, service);
        }

        final ProcessProviderRecordInternal ppr = app.getProviders();
        for (int i = ppr.numberOfProviderConnections() - 1; i >= 0; i--) {
            ContentProviderConnectionInternal cpc = ppr.getProviderConnectionAt(i);
            ProcessRecordInternal provider = cpc.getProvider().getHostProcess();
            if (provider == null || provider == app || isHighPriorityProcess(provider)) {
                continue;
            }
            connectionConsumer.accept(cpc, provider);
        }
    }

    /**
     * This is one of the condition that blocks the skipping of connection evaluation. This method
     * returns false when the given connection has flag {@link Context#BIND_SCHEDULE_LIKE_TOP_APP}
     * but the host process has not set the corresponding flag,
     * {@link ProcessRecordInternal#mScheduleLikeTopApp}.
     */
    private static boolean allowSkipForBindScheduleLikeTopApp(ConnectionRecordInternal cr,
            ProcessRecordInternal host) {
        // If feature flag for optionally blocking skipping is disabled. Always allow skipping.
        if (!Flags.notSkipConnectionRecomputeForBindScheduleLikeTopApp()) {
            return true;
        }

        // Need to check shouldScheduleLikeTopApp otherwise, there will be too many recompute which
        // leads to OOM.
        return !(cr.hasFlag(Context.BIND_SCHEDULE_LIKE_TOP_APP) && !host.getScheduleLikeTopApp());
    }

    private static boolean isSandboxAttributedConnection(ConnectionRecordInternal cr,
            ProcessRecordInternal host) {
        return host.isSdkSandbox && cr.getAttributedClient() != null;
    }

    private static boolean isHighPriorityProcess(ProcessRecordInternal proc) {
        final boolean isPersistentSystemProcess = proc.getMaxAdj() >= SYSTEM_ADJ
                && proc.getMaxAdj() < FOREGROUND_APP_ADJ;

        final boolean isEffectivelyForeground = proc.getCurAdj() <= FOREGROUND_APP_ADJ
                && proc.getCurrentSchedulingGroup() > SCHED_GROUP_BACKGROUND
                && proc.getCurProcState() <= PROCESS_STATE_TOP;

        return isPersistentSystemProcess || isEffectivelyForeground;
    }

    /**
     * Stream the connections from clients with {@code app} as the host to {@code
     * connectionConsumer}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static void forEachClientConnectionLSP(ProcessRecordInternal app,
            BiConsumer<Connection, ProcessRecordInternal> connectionConsumer) {
        final ProcessServiceRecordInternal psr = app.getServices();

        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            final ServiceRecordInternal s = psr.getRunningServiceAt(i);
            for (int j = s.getConnectionsSize() - 1; j >= 0; j--) {
                final ArrayList<? extends ConnectionRecordInternal> clist =
                        s.getConnectionAt(j);
                for (int k = clist.size() - 1; k >= 0; k--) {
                    final ConnectionRecordInternal cr = clist.get(k);
                    final ProcessRecordInternal client;
                    if (app.isSdkSandbox && cr.getAttributedClient() != null) {
                        client = cr.getAttributedClient();
                    } else {
                        client = cr.getClient();
                    }
                    if (client == null || client == app) continue;
                    connectionConsumer.accept(cr, client);
                }
            }
        }

        final ProcessProviderRecordInternal ppr = app.getProviders();
        for (int i = ppr.numberOfProviders() - 1; i >= 0; i--) {
            final ContentProviderRecordInternal cpr = ppr.getProviderAt(i);
            for (int j = cpr.numberOfConnections() - 1; j >= 0; j--) {
                final ContentProviderConnectionInternal conn = cpr.getConnectionsAt(j);
                connectionConsumer.accept(conn, conn.getClient());
            }
        }
    }

    /**
     * Returns true if at least one the provided values is more important than those in {@code app}.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static boolean selfImportanceLoweredLSP(ProcessRecordInternal app, int prevProcState,
            int prevAdj, int prevCapability, boolean prevShouldNotFreeze) {
        if (app.getCurProcState() > prevProcState) {
            return true;
        }
        if (app.getCurRawAdj() > prevAdj)  {
            return true;
        }
        if ((app.getCurCapability() & prevCapability) != prevCapability)  {
            return true;
        }
        if (!app.shouldNotFreeze() && prevShouldNotFreeze) {
            // No long marked as should not freeze.
            return true;
        }
        return false;
    }

    /**
     * Returns whether a host connection evaluation can be skipped due to lack of importance.
     * Note: the client and host need to be provided as well for the isolated and sandbox
     * scenarios.
     */
    @GuardedBy({"mService", "mProcLock"})
    private static boolean unimportantConnectionLSP(Connection conn,
            ProcessRecordInternal host, ProcessRecordInternal client) {
        if (!Flags.skipUnimportantConnections()) {
            // Feature not enabled, just return false so the connection is evaluated.
            return false;
        }
        if (host.getCurProcState() > client.getCurProcState()) {
            return false;
        }
        if (host.getCurRawAdj() > client.getCurRawAdj())  {
            return false;
        }
        final int serviceCapability = host.getCurCapability();
        final int clientCapability = client.getCurCapability();
        if ((serviceCapability & clientCapability) != clientCapability) {
            // Client has a capability the host does not have.
            if ((clientCapability & PROCESS_CAPABILITY_BFSL) == PROCESS_CAPABILITY_BFSL
                    && (serviceCapability & PROCESS_CAPABILITY_BFSL) == 0) {
                // The BFSL capability does not need a flag to propagate.
                return false;
            }
            if (conn.canAffectCapabilities()) {
                // One of these bind flags may propagate that capability.
                return false;
            }
        }

        if (!host.shouldNotFreeze() && client.shouldNotFreeze()) {
            // If the client is marked as should not freeze, so should the host.
            return false;
        }
        return true;
    }

    @GuardedBy({"mService", "mProcLock"})
    private void computeOomAdjLSP(ProcessRecordInternal app, ProcessRecordInternal topApp,
            boolean doingAll, long now) {
        // We'll evaluate the reasons within getCpuCapability and getImplicitCpuCapability later.
        app.clearCurCpuTimeReasons();
        app.clearCurImplicitCpuTimeReasons();

        // Remove any follow up update this process might have. It will be rescheduled if still
        // needed.
        app.setFollowupUpdateUptimeMs(NO_FOLLOW_UP_TIME);

        if (!app.isProcessRunning()) {
            app.setAdjSeq(mAdjSeq);
            app.setCurrentSchedulingGroup(SCHED_GROUP_BACKGROUND);
            app.setCurProcState(PROCESS_STATE_CACHED_EMPTY);
            app.setCurRawProcState(PROCESS_STATE_CACHED_EMPTY);
            app.setCurAdj(CACHED_APP_MAX_ADJ);
            app.setCurRawAdj(CACHED_APP_MAX_ADJ);
            app.setCompletedAdjSeq(app.getAdjSeq());
            app.setCurCapability(PROCESS_CAPABILITY_NONE);
            return;
        }

        app.setAdjTypeCode(ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN);
        app.setAdjSource(null);
        app.setAdjTarget(null);

        // Some processes must stay unfrozen even when their oom_adj otherwise allows freezing.
        final UidRecordInternal uidRec = app.getUidRecord();
        final boolean uidAllowlisted = uidRec != null && uidRec.isCurAllowListed();
        final boolean freezerExemptPackage =
                mService.mConstants.CACHED_APP_FREEZER_EXEMPT_PACKAGES.contains(
                        app.getPackageName());
        int shouldNotFreezeReason = SHOULD_NOT_FREEZE_REASON_NONE;
        if (uidAllowlisted) {
            shouldNotFreezeReason |= SHOULD_NOT_FREEZE_REASON_UID_ALLOWLISTED;
        }
        if (freezerExemptPackage) {
            shouldNotFreezeReason |= SHOULD_NOT_FREEZE_REASON_FREEZER_EXEMPT_PACKAGE;
        }
        app.setShouldNotFreeze(uidAllowlisted || freezerExemptPackage, false /* dryRun */,
                shouldNotFreezeReason, mAdjSeq);

        final boolean reportDebugMsgs =
                DEBUG_OOM_ADJ_REASON || mService.mCurOomAdjUid == app.getApplicationUid();

        // TODO: b/425766486 - Use ProcessServiceRecordInternal directly.
        final ProcessServiceRecord psr = (ProcessServiceRecord) app.getServices();

        if (app.getMaxAdj() <= FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making fixed: " + app);
            }
            app.setAdjType("fixed");
            app.setAdjSeq(mAdjSeq);
            app.setCurRawAdj(app.getMaxAdj());
            app.setHasForegroundActivities(false);
            app.setCurrentSchedulingGroup(SCHED_GROUP_DEFAULT);
            app.setCurCapability(PROCESS_CAPABILITY_ALL); // BFSL allowed
            app.addCurCpuTimeReasons(CPU_TIME_REASON_OTHER);
            app.addCurImplicitCpuTimeReasons(IMPLICIT_CPU_TIME_REASON_OTHER);
            app.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT);
            // System processes can do UI, and when they do we want to have
            // them trim their memory after the user leaves the UI.  To
            // facilitate this, here we need to determine whether or not it
            // is currently showing UI.
            app.setSystemNoUi(true);
            if (app == topApp) {
                app.setSystemNoUi(false);
                app.setCurrentSchedulingGroup(SCHED_GROUP_TOP_APP);
                app.setAdjType("pers-top-activity");
            } else if (app.getHasTopUi()) {
                // sched group/proc state adjustment is below
                app.setSystemNoUi(false);
                app.setAdjType("pers-top-ui");
            } else if (app.getHasVisibleActivities()) {
                app.setSystemNoUi(false);
            }
            if (!app.isSystemNoUi()) {
                if (isScreenOnOrAnimatingLocked(app)) {
                    // screen on or animating, promote UI
                    app.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT_UI);
                    app.setCurrentSchedulingGroup(SCHED_GROUP_TOP_APP);
                } else if (!isVisibleDozeUiProcess(app)) {
                    // screen off, restrict UI scheduling
                    app.setCurProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
                    app.setCurrentSchedulingGroup(SCHED_GROUP_RESTRICTED);
                }
            }
            app.setCurRawProcState(app.getCurProcState());
            app.setCurAdj(app.getMaxAdj());
            app.setCompletedAdjSeq(app.getAdjSeq());
            return;
        }

        app.setSystemNoUi(false);

        final int PROCESS_STATE_CUR_TOP = mProcessStateCurTop;

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        int procState;
        int capability = PROCESS_CAPABILITY_NONE;

        boolean hasVisibleActivities = false;
        if (app == topApp && PROCESS_STATE_CUR_TOP == PROCESS_STATE_TOP) {
            // The last app on the list is the foreground app.
            adj = FOREGROUND_APP_ADJ;
            if (useTopSchedGroupForTopProcess()) {
                schedGroup = SCHED_GROUP_TOP_APP;
                app.setAdjType("top-activity");
            } else {
                // Demote the scheduling group to avoid CPU contention if there is another more
                // important process which also uses top-app, such as if SystemUI is animating.
                schedGroup = SCHED_GROUP_DEFAULT;
                app.setAdjType("intermediate-top-activity");
            }
            hasVisibleActivities = true;
            procState = PROCESS_STATE_TOP;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making top: " + app);
            }
        } else if (app.isRunningRemoteAnimation()) {
            adj = VISIBLE_APP_ADJ;
            schedGroup = SCHED_GROUP_TOP_APP;
            app.setAdjType("running-remote-anim");
            procState = PROCESS_STATE_CUR_TOP;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making running remote anim: " + app);
            }
        } else if (app.hasActiveInstrumentation()) {
            // Don't want to kill running instrumentation.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = SCHED_GROUP_DEFAULT;
            app.setAdjType("instrumentation");
            procState = PROCESS_STATE_FOREGROUND_SERVICE;
            capability |= PROCESS_CAPABILITY_BFSL;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making instrumentation: " + app);
            }
        } else if (isReceivingBroadcast(app)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground for OOM killer purposes.
            // It's placed in a sched group based on the nature of the
            // broadcast as reflected by which queue it's active in.
            adj = FOREGROUND_APP_ADJ;
            if (Flags.pushBroadcastStateToOomadjuster()) {
                schedGroup = app.getReceivers().getBroadcastReceiverSchedGroup();
            } else {
                /// Priority was stored in mTmpSchedGroup by {@link #isReceivingBroadcast)
                schedGroup = mTmpSchedGroup[0];
            }
            app.setAdjType("broadcast");
            procState = ActivityManager.PROCESS_STATE_RECEIVER;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making broadcast: " + app);
            }
        } else if (psr.hasExecutingServices()) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = psr.isExecServicesFg() ? SCHED_GROUP_DEFAULT : SCHED_GROUP_BACKGROUND;
            app.setAdjType("exec-service");
            procState = PROCESS_STATE_SERVICE;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making exec-service: " + app);
            }
        } else if (app == topApp) {
            adj = FOREGROUND_APP_ADJ;
            schedGroup = SCHED_GROUP_BACKGROUND;
            app.setAdjType("top-sleeping");
            procState = PROCESS_STATE_CUR_TOP;
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making top (sleeping): " + app);
            }
        } else {
            // As far as we know the process is empty.  We may change our mind later.
            schedGroup = SCHED_GROUP_BACKGROUND;
            // At this point we don't actually know the adjustment, assign to UNKNOWN_ADJ for now.
            adj = ProcessList.UNKNOWN_ADJ;
            procState = PROCESS_STATE_CACHED_EMPTY;
            app.setAdjType("cch-empty");
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making empty: " + app);
            }
        }

        // Examine all non-top activities.
        boolean foregroundActivities = app == topApp;
        if (!foregroundActivities && app.getHasActivities()) {
            mTmpOomAdjWindowCalculator.computeOomAdjFromActivitiesIfNecessary(app, adj,
                    foregroundActivities, hasVisibleActivities, procState, schedGroup,
                    PROCESS_STATE_CUR_TOP, reportDebugMsgs);

            adj = app.getCachedAdj();
            foregroundActivities = app.getCachedForegroundActivities();
            hasVisibleActivities = app.getHasVisibleActivities();
            procState = app.getCachedProcState();
            schedGroup = app.getCachedSchedGroup();
            app.setAdjType(app.getCachedAdjType());
        }

        if (procState > PROCESS_STATE_CACHED_RECENT && app.getHasRecentTasks()) {
            procState = PROCESS_STATE_CACHED_RECENT;
            app.setAdjType("cch-rec");
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to cached recent: " + app);
            }
        }

        int capabilityFromFGS = 0; // capability from foreground service.

        final boolean hasForegroundServices = psr.hasForegroundServices();
        final boolean hasNonShortForegroundServices = psr.hasNonShortForegroundServices();
        final boolean hasShortForegroundServices = hasForegroundServices
                && !psr.areAllShortForegroundServicesProcstateTimedOut(now);

        // Adjust for FGS or "has-overlay-ui".
        if (adj > PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_FOREGROUND_SERVICE) {
            String adjType = null;
            int newAdj = 0;
            int newProcState = 0;

            if (hasForegroundServices && hasNonShortForegroundServices) {
                // For regular (non-short) FGS.
                adjType = "fg-service";
                newAdj = PERCEPTIBLE_APP_ADJ;
                newProcState = PROCESS_STATE_FOREGROUND_SERVICE;
                capabilityFromFGS |= PROCESS_CAPABILITY_BFSL;

            } else if (hasShortForegroundServices) {

                // For short FGS.
                adjType = "fg-service-short";

                // We use MEDIUM_APP_ADJ + 1 so we can tell apart EJ
                // (which uses MEDIUM_APP_ADJ + 1)
                // from short-FGS.
                // (We use +1 and +2, not +0 and +1, to be consistent with the following
                // RECENT_FOREGROUND_APP_ADJ tweak)
                newAdj = PERCEPTIBLE_MEDIUM_APP_ADJ + 1;

                // We give the FGS procstate, but not PROCESS_CAPABILITY_BFSL, so
                // short-fgs can't start FGS from the background.
                newProcState = PROCESS_STATE_FOREGROUND_SERVICE;

            } else if (app.getHasOverlayUi()) {
                adjType = "has-overlay-ui";
                newAdj = PERCEPTIBLE_APP_ADJ;
                newProcState = PROCESS_STATE_IMPORTANT_FOREGROUND;
            }

            if (adjType != null) {
                adj = newAdj;
                procState = newProcState;
                app.setAdjType(adjType);
                schedGroup = SCHED_GROUP_DEFAULT;

                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType + ": "
                            + app + " ");
                }
            }
        }

        // If the app was recently in the foreground and moved to a foreground service status,
        // allow it to get a higher rank in memory for some time, compared to other foreground
        // services so that it can finish performing any persistence/processing of in-memory state.
        if (psr.hasForegroundServices() && adj > PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ
                && (app.getLastTopTime() + mConstants.TOP_TO_FGS_GRACE_DURATION > now
                || app.getSetProcState() <= PROCESS_STATE_TOP)) {
            if (psr.hasNonShortForegroundServices()) {
                adj = PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
                app.setAdjType("fg-service-act");
            } else {
                // For short-service FGS, we +1 the value, so we'll be able to detect it in
                // various dashboards.
                adj = PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 1;
                app.setAdjType("fg-service-short-act");
            }
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to recent fg: " + app);
            }
            maybeSetProcessFollowUpUpdateLocked(app,
                    app.getLastTopTime() + mConstants.TOP_TO_FGS_GRACE_DURATION, now);
        }

        // If the app was recently in the foreground and has expedited jobs running,
        // allow it to get a higher rank in memory for some time, compared to other EJS and even
        // foreground services so that it can finish performing any persistence/processing of
        // in-memory state.
        if (psr.hasTopStartedAlmostPerceptibleServices()
                && (adj > PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 2)
                && (app.getLastTopTime()
                + mConstants.TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION > now
                || app.getSetProcState() <= PROCESS_STATE_TOP)) {
            // For EJ, we +2 the value, so we'll be able to detect it in
            // various dashboards.
            adj = PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 2;
            // This shall henceforth be called the "EJ" exemption, despite utilizing the
            // ALMOST_PERCEPTIBLE flag to work.
            app.setAdjType("top-ej-act");
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to recent fg for EJ: " + app);
            }
            maybeSetProcessFollowUpUpdateLocked(app,
                    app.getLastTopTime() + mConstants.TOP_TO_ALMOST_PERCEPTIBLE_GRACE_DURATION,
                    now);
        }

        if (adj > PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
            if (app.getForcingToImportant() != null) {
                // This is currently used for toasts...  they are not interactive, and
                // we don't want them to cause the app to become fully foreground (and
                // thus out of background check), so we yes the best background level we can.
                adj = PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                app.setAdjType("force-imp");
                app.setAdjSource(app.getForcingToImportant());
                schedGroup = SCHED_GROUP_DEFAULT;
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to force imp: " + app);
                }
            }
        }

        if (isHeavyWeightProcess(app)) {
            if (adj > HEAVY_WEIGHT_APP_ADJ) {
                // We don't want to kill the current heavy-weight process.
                adj = HEAVY_WEIGHT_APP_ADJ;
                schedGroup = SCHED_GROUP_BACKGROUND;
                app.setAdjType("heavy");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to heavy: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                procState = ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
                app.setAdjType("heavy");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to heavy: " + app);
                }
            }
        }

        if (isHomeProcess(app)) {
            if (adj > HOME_APP_ADJ) {
                // This process is hosting what we currently consider to be the
                // home app, so we don't want to let it go into the background.
                adj = HOME_APP_ADJ;
                schedGroup = SCHED_GROUP_BACKGROUND;
                app.setAdjType("home");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to home: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HOME) {
                procState = ActivityManager.PROCESS_STATE_HOME;
                app.setAdjType("home");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to home: " + app);
                }
            }
        }
        if (isPreviousProcess(app) && app.getHasActivities()) {
            // This was the previous process that showed UI to the user.  We want to
            // try to keep it around more aggressively, to give a good experience
            // around switching between two apps. However, we don't want to keep the
            // process in this privileged state indefinitely. Eventually, allow the
            // app to be demoted to cached.
            if (procState >= PROCESS_STATE_LAST_ACTIVITY
                    && app.getSetProcState() == PROCESS_STATE_LAST_ACTIVITY
                    && (app.getLastStateTime() + mConstants.MAX_PREVIOUS_TIME) <= now) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                schedGroup = SCHED_GROUP_BACKGROUND;
                app.setAdjType("previous-expired");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Expire prev adj: " + app);
                }
            } else {
                if (adj > PREVIOUS_APP_ADJ) {
                    adj = PREVIOUS_APP_ADJ;
                    schedGroup = SCHED_GROUP_BACKGROUND;
                    app.setAdjType("previous");
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to prev: " + app);
                    }
                }
                if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                    procState = PROCESS_STATE_LAST_ACTIVITY;
                    app.setAdjType("previous");
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to prev: " + app);
                    }
                }
                final long lastStateTime;
                if (app.getSetProcState() == PROCESS_STATE_LAST_ACTIVITY) {
                    lastStateTime = app.getLastStateTime();
                } else {
                    lastStateTime = now;
                }
                maybeSetProcessFollowUpUpdateLocked(app,
                        lastStateTime + mConstants.MAX_PREVIOUS_TIME, now);
            }
        }

        app.setCurRawAdj(adj);
        app.setCurRawProcState(procState);

        app.setHasStartedServices(false);
        app.setAdjSeq(mAdjSeq);

        if (isBackupProcess(app)) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "oom BACKUP_APP_ADJ for " + app);
                adj = BACKUP_APP_ADJ;
                if (procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
                    procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                }
                app.setAdjType("backup");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to backup: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_BACKUP) {
                procState = ActivityManager.PROCESS_STATE_BACKUP;
                app.setAdjType("backup");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to backup: " + app);
                }
            }
        }

        app.setCurBoundByNonBgRestrictedApp(false);

        app.setScheduleLikeTopApp(false);

        for (int is = psr.numberOfRunningServices() - 1;
                is >= 0 && (adj > FOREGROUND_APP_ADJ
                        || schedGroup == SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                is--) {
            ServiceRecordInternal s = psr.getRunningServiceAt(is);
            if (s.isStartRequested()) {
                app.setHasStartedServices(true);
                if (procState > PROCESS_STATE_SERVICE) {
                    procState = PROCESS_STATE_SERVICE;
                    app.setAdjType("started-services");
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to started service: " + app);
                    }
                }
                if (!s.isKeepWarming() && app.getHasShownUi() && !isHomeProcess(app)) {
                    // If this process has shown some UI, let it immediately
                    // go to the LRU list because it may be pretty heavy with
                    // UI stuff.  We'll tag it with a label just to help
                    // debug and understand what is going on.
                    if (adj > SERVICE_ADJ) {
                        app.setAdjType("cch-started-ui-services");
                    }
                } else {
                    if (s.isKeepWarming()
                            || now < (s.getLastActivity() + mConstants.MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes. This does not apply
                        // to the SDK sandbox process since it should never
                        // be more important than its corresponding app.
                        if (!app.isSdkSandbox && adj > SERVICE_ADJ) {
                            adj = SERVICE_ADJ;
                            app.setAdjType("started-services");
                            if (reportDebugMsgs) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                        "Raise adj to started service: " + app);
                            }
                            maybeSetProcessFollowUpUpdateLocked(app,
                                    s.getLastActivity() + mConstants.MAX_SERVICE_INACTIVITY, now);
                        }
                    }
                    // If we have let the service slide into the background
                    // state, still have some text describing what it is doing
                    // even though the service no longer has an impact.
                    if (adj > SERVICE_ADJ) {
                        app.setAdjType("cch-started-services");
                    }
                }
            }

            if (s.isForeground()) {
                final int fgsType = s.getForegroundServiceType();
                if (s.isFgsAllowedWiu_forCapabilities()) {
                    capabilityFromFGS |=
                            (fgsType & FOREGROUND_SERVICE_TYPE_LOCATION)
                                    != 0 ? PROCESS_CAPABILITY_FOREGROUND_LOCATION : 0;

                    if (roForegroundAudioControl()) { // flag check
                        // TODO(b/335373208) - revisit restriction of FOREGROUND_AUDIO_CONTROL
                        //  when it can be limited to specific FGS types
                        capabilityFromFGS |= PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
                    }

                    final boolean enabled = app.getCachedCompatChange(
                            CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY);
                    if (enabled) {
                        capabilityFromFGS |=
                                (fgsType & FOREGROUND_SERVICE_TYPE_CAMERA)
                                        != 0 ? PROCESS_CAPABILITY_FOREGROUND_CAMERA : 0;
                        capabilityFromFGS |=
                                (fgsType & FOREGROUND_SERVICE_TYPE_MICROPHONE)
                                        != 0 ? PROCESS_CAPABILITY_FOREGROUND_MICROPHONE : 0;
                    } else {
                        capabilityFromFGS |= PROCESS_CAPABILITY_FOREGROUND_CAMERA
                                | PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
                    }
                }
            }
        }

        final ProcessProviderRecordInternal ppr = app.getProviders();
        for (int provi = ppr.numberOfProviders() - 1;
                provi >= 0 && (adj > FOREGROUND_APP_ADJ
                        || schedGroup == SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                provi--) {
            ContentProviderRecordInternal cpr = ppr.getProviderAt(provi);
            // If the provider has external (non-framework) process
            // dependencies, ensure that its adjustment is at least
            // FOREGROUND_APP_ADJ.
            if (cpr.hasExternalProcessHandles()) {
                if (adj > FOREGROUND_APP_ADJ) {
                    adj = FOREGROUND_APP_ADJ;
                    app.setCurRawAdj(adj);
                    schedGroup = SCHED_GROUP_DEFAULT;
                    app.setAdjType("ext-provider");
                    app.setAdjTarget(cpr.name);
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise adj to external provider: " + app);
                    }
                }
                if (procState > PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    procState = PROCESS_STATE_IMPORTANT_FOREGROUND;
                    app.setCurRawProcState(procState);
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to external provider: " + app);
                    }
                }
            }
        }

        if ((ppr.getLastProviderTime() + mConstants.CONTENT_PROVIDER_RETAIN_TIME) > now) {
            if (adj > PREVIOUS_APP_ADJ) {
                adj = PREVIOUS_APP_ADJ;
                schedGroup = SCHED_GROUP_BACKGROUND;
                app.setAdjType("recent-provider");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise adj to recent provider: " + app);
                }
                maybeSetProcessFollowUpUpdateLocked(app,
                        ppr.getLastProviderTime() + mConstants.CONTENT_PROVIDER_RETAIN_TIME, now);
            }
            if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                app.setAdjType("recent-provider");
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to recent provider: " + app);
                }
                maybeSetProcessFollowUpUpdateLocked(app,
                        ppr.getLastProviderTime() + mConstants.CONTENT_PROVIDER_RETAIN_TIME, now);
            }
        }

        if (procState >= PROCESS_STATE_CACHED_EMPTY) {
            if (psr.hasClientActivities()) {
                // This is a cached process, but with client activities.  Mark it so.
                procState = PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
                app.setAdjType("cch-client-act");
            } else if (psr.isTreatLikeActivity()) {
                // This is a cached process, but somebody wants us to treat it like it has
                // an activity, okay!
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                app.setAdjType("cch-as-act");
            }
        }

        if (adj == SERVICE_ADJ) {
            if (doingAll) {
                app.setServiceB(mNewNumAServiceProcs > (mNumServiceProcs / 3));
                mNewNumServiceProcs++;
                if (!app.isServiceB()) {
                    // This service isn't far enough down on the LRU list to
                    // normally be a B service, but if we are low on RAM and it
                    // is large we want to force it down since we would prefer to
                    // keep launcher over it.
                    long lastPssOrRss = mService.mAppProfiler.isProfilingPss()
                            ? app.getLastPss() : app.getLastRss();

                    // RSS is larger than PSS, but the RSS/PSS ratio varies per-process based on how
                    // many shared pages a process uses. The threshold is increased if the flag for
                    // reading RSS instead of PSS is enabled.
                    //
                    // TODO(b/296454553): Tune the second value so that the relative number of
                    // service B is similar before/after this flag is enabled.
                    double thresholdModifier = mService.mAppProfiler.isProfilingPss()
                            ? 1 : mConstants.PSS_TO_RSS_THRESHOLD_MODIFIER;
                    double cachedRestoreThreshold =
                            mProcessList.getCachedRestoreThresholdKb() * thresholdModifier;

                    if (!isLastMemoryLevelNormal() && lastPssOrRss >= cachedRestoreThreshold) {
                        app.setServiceHighRam(true);
                        app.setServiceB(true);
                        //Slog.i(TAG, "ADJ " + app + " high ram!");
                    } else {
                        mNewNumAServiceProcs++;
                        //Slog.i(TAG, "ADJ " + app + " not high ram!");
                    }
                } else {
                    app.setServiceHighRam(false);
                }
            }
            if (app.isServiceB()) {
                adj = SERVICE_B_ADJ;
            }
        }

        // apply capability from FGS.
        if (psr.hasForegroundServices()) {
            capability |= capabilityFromFGS;
        }

        capability |= getDefaultCapability(app, procState);
        capability |= getCpuCapability(app, foregroundActivities);
        capability |= getImplicitCpuCapability(app, adj);

        // Procstates below BFGS should never have this capability.
        if (procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            capability &= ~PROCESS_CAPABILITY_BFSL;
        }

        if (app.isPendingFinishAttach()) {
            // If the app is still starting up. We reset the computations to the
            // hardcoded values in setAttachingProcessStatesLSP. This ensures that the app keeps
            // hard-coded default 'startup' oom scores while starting up. When it finishes startup,
            // we'll recompute oom scores based on it's actual hosted components.
            setAttachingProcessStatesLSP(app);
            app.setAdjSeq(mAdjSeq);
            app.setCompletedAdjSeq(app.getAdjSeq());
            return;
        }

        // Do final modification to adj.  Everything we do between here and applying
        // the final setAdj must be done in this function, because we will also use
        // it when computing the final cached adj later.  Note that we don't need to
        // worry about this for max adj above, since max adj will always be used to
        // keep it out of the cached values.
        app.setCurCapability(capability);
        app.updateLastInvisibleTime(hasVisibleActivities);
        app.setHasForegroundActivities(foregroundActivities);
        app.setCompletedAdjSeq(mAdjSeq);

        schedGroup = setIntermediateAdjLSP(app, adj, schedGroup);
        setIntermediateProcStateLSP(app, procState);
        setIntermediateSchedGroupLSP(app, schedGroup);
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    public boolean computeServiceHostOomAdjLSP(ConnectionRecordInternal cr,
            ProcessRecordInternal app, ProcessRecordInternal client, long now, boolean dryRun) {
        if (app.isPendingFinishAttach()) {
            // We've set the attaching process state in the computeInitialOomAdjLSP. Skip it here.
            return false;
        }

        boolean updated = false;

        int clientAdj = client.getCurRawAdj();
        int clientProcState = client.getCurRawProcState();

        final boolean clientIsSystem = clientProcState < PROCESS_STATE_TOP;

        int adj = app.getCurRawAdj();
        int procState = app.getCurRawProcState();
        int schedGroup = app.getCurrentSchedulingGroup();
        int capability = app.getCurCapability();

        final int prevRawAdj = adj;
        final int prevProcState = procState;
        final int prevSchedGroup = schedGroup;
        final int prevCapability = capability;

        final boolean reportDebugMsgs =
                DEBUG_OOM_ADJ_REASON || mService.mCurOomAdjUid == app.getApplicationUid();

        if (!dryRun) {
            app.setCurBoundByNonBgRestrictedApp(app.isCurBoundByNonBgRestrictedApp()
                    || client.isCurBoundByNonBgRestrictedApp()
                    || clientProcState <= PROCESS_STATE_BOUND_TOP
                    || (clientProcState == PROCESS_STATE_FOREGROUND_SERVICE
                    && !client.isBackgroundRestricted()));
        }

        if (client.shouldNotFreeze()) {
            // Propagate the shouldNotFreeze flag down the bindings.
            if (app.setShouldNotFreeze(true, dryRun,
                    app.shouldNotFreezeReason() | client.shouldNotFreezeReason(), mAdjSeq)) {
                if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                    // Do nothing, capability updated check will handle the dryrun output.
                } else {
                    // Bail out early, as we only care about the return value for a dryrun.
                    return true;
                }
            }
        }

        boolean trackedProcState = false;

        // We always propagate PROCESS_CAPABILITY_BFSL over bindings here,
        // but, right before actually setting it to the process,
        // we check the final procstate, and remove it if the procsate is below BFGS.
        capability |= getBfslCapabilityFromClient(client);

        capability |= getCpuCapabilitiesFromClient(app, client, cr);

        if (cr.notHasFlag(Context.BIND_WAIVE_PRIORITY)) {
            if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                capability |= client.getCurCapability();
            }

            // If an app has network capability by default
            // (by having procstate <= BFGS), then the apps it binds to will get
            // elevated to a high enough procstate anyway to get network unless they
            // request otherwise, so don't propagate the network capability by default
            // in this case unless they explicitly request it.
            if ((client.getCurCapability()
                    & PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK) != 0) {
                if (clientProcState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                    // This is used to grant network access to Expedited Jobs.
                    if (cr.hasFlag(Context.BIND_BYPASS_POWER_NETWORK_RESTRICTIONS)) {
                        capability |= PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK;
                    }
                } else {
                    capability |= PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK;
                }
            }
            if ((client.getCurCapability()
                    & PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK) != 0) {
                if (clientProcState <= PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    // This is used to grant network access to User Initiated Jobs.
                    if (cr.hasFlag(Context.BIND_BYPASS_USER_NETWORK_RESTRICTIONS)) {
                        capability |= PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK;
                    }
                }
            }

            // Sandbox should be able to control audio only when bound client
            // has this capability.
            if ((client.getCurCapability()
                    & PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL) != 0) {
                if (app.isSdkSandbox) {
                    capability |= PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;
                }
            }

            if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                // If the other app is cached for any reason, for purposes here
                // we are going to consider it empty.  The specific cached state
                // doesn't propagate except under certain conditions.
                clientProcState = PROCESS_STATE_CACHED_EMPTY;
            }
            String adjType = null;
            if (cr.hasFlag(Context.BIND_ALLOW_OOM_MANAGEMENT)) {
                // Similar to BIND_WAIVE_PRIORITY, keep it unfrozen.
                if (clientAdj < CACHED_APP_MIN_ADJ) {
                    if (app.setShouldNotFreeze(true, dryRun,
                            app.shouldNotFreezeReason()
                                    | SHOULD_NOT_FREEZE_REASON_BINDER_ALLOW_OOM_MANAGEMENT,
                            mAdjSeq)) {
                        if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                            // Do nothing, capability updated check will handle the dryrun output.
                        } else {
                            // Bail out early, as we only care about the return value for a dryrun.
                            return true;
                        }
                    }
                }
                // Not doing bind OOM management, so treat
                // this guy more like a started service.
                if (app.getHasShownUi() && !isHomeProcess(app)) {
                    // If this process has shown some UI, let it immediately
                    // go to the LRU list because it may be pretty heavy with
                    // UI stuff.  We'll tag it with a label just to help
                    // debug and understand what is going on.
                    if (adj > clientAdj) {
                        adjType = "cch-bound-ui-services";
                    }

                    if (app.isCached() && dryRun) {
                        // Bail out early, as we only care about the return value for a dryrun.
                        return true;
                    }

                    clientAdj = adj;
                    clientProcState = procState;
                } else {
                    if (now >= (cr.getService().getLastActivity()
                            + mConstants.MAX_SERVICE_INACTIVITY)) {
                        // This service has not seen activity within
                        // recent memory, so allow it to drop to the
                        // LRU list if there is no other reason to keep
                        // it around.  We'll also tag it with a label just
                        // to help debug and undertand what is going on.
                        if (adj > clientAdj) {
                            adjType = "cch-bound-services";
                        }
                        clientAdj = adj;
                    }
                }
            }
            if (adj > clientAdj) {
                // If this process has recently shown UI, and the process that is binding to it
                // is less important than a state that can be actively running, then we don't
                // care about the binding as much as we care about letting this process get into
                // the LRU list to be killed and restarted if needed for memory.
                if (app.getHasShownUi() && !isHomeProcess(app)
                        && clientAdj > CACHING_UI_SERVICE_CLIENT_ADJ_THRESHOLD) {
                    if (adj >= CACHED_APP_MIN_ADJ) {
                        adjType = "cch-bound-ui-services";
                    }
                } else {
                    int newAdj;
                    int lbAdj = VISIBLE_APP_ADJ; // lower bound of adj.
                    if (cr.hasFlag(Context.BIND_ABOVE_CLIENT
                            | Context.BIND_IMPORTANT)) {
                        if (clientAdj >= PERSISTENT_SERVICE_ADJ) {
                            newAdj = clientAdj;
                        } else {
                            // make this service persistent
                            newAdj = PERSISTENT_SERVICE_ADJ;
                            schedGroup = SCHED_GROUP_DEFAULT;
                            procState = ActivityManager.PROCESS_STATE_PERSISTENT;
                            if (!dryRun) {
                                cr.trackProcState(procState, mAdjSeq);
                            }
                            trackedProcState = true;
                        }
                    } else if (cr.hasFlag(Context.BIND_NOT_PERCEPTIBLE)
                            && clientAdj <= PERCEPTIBLE_APP_ADJ
                            && adj >= (lbAdj = PERCEPTIBLE_LOW_APP_ADJ)) {
                        newAdj = PERCEPTIBLE_LOW_APP_ADJ;
                    } else if (cr.hasFlag(Context.BIND_ALMOST_PERCEPTIBLE)
                            && cr.notHasFlag(Context.BIND_NOT_FOREGROUND)
                            && clientAdj < PERCEPTIBLE_APP_ADJ
                            && adj >= (lbAdj = PERCEPTIBLE_APP_ADJ)) {
                        // This is for user-initiated jobs.
                        // We use APP_ADJ + 1 here, so we can tell them apart from FGS.
                        newAdj = PERCEPTIBLE_APP_ADJ + 1;
                    } else if (cr.hasFlag(Context.BIND_ALMOST_PERCEPTIBLE)
                            && cr.hasFlag(Context.BIND_NOT_FOREGROUND)
                            && clientAdj < PERCEPTIBLE_APP_ADJ
                            && adj >= (lbAdj = (PERCEPTIBLE_MEDIUM_APP_ADJ + 2))) {
                        // This is for expedited jobs.
                        // We use MEDIUM_APP_ADJ + 2 here, so we can tell apart
                        // EJ and short-FGS.
                        newAdj = PERCEPTIBLE_MEDIUM_APP_ADJ + 2;
                    } else if (cr.hasFlag(Context.BIND_NOT_VISIBLE)
                            && clientAdj < PERCEPTIBLE_APP_ADJ
                            && adj >= (lbAdj = PERCEPTIBLE_APP_ADJ)) {
                        newAdj = PERCEPTIBLE_APP_ADJ;
                    } else if (clientAdj >= PERCEPTIBLE_APP_ADJ) {
                        newAdj = clientAdj;
                    } else if (cr.hasFlag(BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE)
                            && clientAdj <= VISIBLE_APP_ADJ
                            && adj > VISIBLE_APP_ADJ) {
                        newAdj = VISIBLE_APP_ADJ;
                    } else {
                        if (adj > VISIBLE_APP_ADJ) {
                            // TODO: Is this too limiting for apps bound from TOP?
                            newAdj = Math.max(clientAdj, lbAdj);
                        } else {
                            newAdj = adj;
                        }
                    }

                    if (!client.isCached()) {
                        if (app.isCached() && dryRun) {
                            // Bail out early, as we only care about the return value for a dryrun.
                            return true;
                        }
                    }

                    if (newAdj == clientAdj && app.isolated) {
                        // Make bound isolated processes have slightly worse score than their client
                        newAdj = clientAdj + 1;
                    }

                    if (adj >  newAdj) {
                        adj = newAdj;
                        if (app.setCurRawAdj(adj, dryRun)) {
                            // Bail out early, as we only care about the return value for a dryrun.
                        }
                        adjType = "service";
                    }
                }
            }
            if (cr.notHasFlag(Context.BIND_NOT_FOREGROUND
                    | Context.BIND_IMPORTANT_BACKGROUND)) {
                // This will treat important bound services identically to
                // the top app, which may behave differently than generic
                // foreground work.
                final int curSchedGroup = client.getCurrentSchedulingGroup();
                if (curSchedGroup > schedGroup) {
                    if (cr.hasFlag(Context.BIND_IMPORTANT)) {
                        schedGroup = curSchedGroup;
                    } else {
                        schedGroup = SCHED_GROUP_DEFAULT;
                    }
                }
                if (clientProcState < PROCESS_STATE_TOP) {
                    // Special handling for above-top states (persistent
                    // processes).  These should not bring the current process
                    // into the top state, since they are not on top.  Instead
                    // give them the best bound state after that.
                    if (cr.hasFlag(BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE)) {
                        clientProcState = PROCESS_STATE_FOREGROUND_SERVICE;
                    } else if (cr.hasFlag(Context.BIND_FOREGROUND_SERVICE)) {
                        clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                    } else if (isDeviceFullyAwake()
                            && cr.hasFlag(Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE)) {
                        clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                    } else {
                        clientProcState =
                                PROCESS_STATE_IMPORTANT_FOREGROUND;
                    }
                } else if (clientProcState == PROCESS_STATE_TOP) {
                    // Go at most to BOUND_TOP, unless requested to elevate
                    // to client's state.
                    clientProcState = PROCESS_STATE_BOUND_TOP;
                    final boolean enabled = client.getCachedCompatChange(
                            CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY);
                    if (enabled) {
                        if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                            // TOP process passes all capabilities to the service.
                            capability |= client.getCurCapability();
                        } else {
                            // TOP process passes no capability to the service.
                        }
                    } else {
                        // TOP process passes all capabilities to the service.
                        capability |= client.getCurCapability();
                    }
                }
            } else if (cr.notHasFlag(Context.BIND_IMPORTANT_BACKGROUND)) {
                if (clientProcState < PROCESS_STATE_TRANSIENT_BACKGROUND) {
                    clientProcState =
                            PROCESS_STATE_TRANSIENT_BACKGROUND;
                }
            } else {
                if (clientProcState < PROCESS_STATE_IMPORTANT_BACKGROUND) {
                    clientProcState =
                            PROCESS_STATE_IMPORTANT_BACKGROUND;
                }
            }

            if (cr.hasFlag(Context.BIND_SCHEDULE_LIKE_TOP_APP) && clientIsSystem) {
                schedGroup = SCHED_GROUP_TOP_APP;
                if (dryRun) {
                    if (prevSchedGroup < schedGroup) {
                        // Bail out early, as we only care about the return value for a dryrun.
                        return true;
                    }
                } else {
                    app.setScheduleLikeTopApp(true);
                }
            }

            if (!trackedProcState && !dryRun) {
                cr.trackProcState(clientProcState, mAdjSeq);
            }

            if (procState > clientProcState) {
                procState = clientProcState;
                if (app.setCurRawProcState(procState, dryRun)) {
                    // Bail out early, as we only care about the return value for a dryrun.
                    return true;
                }
                if (adjType == null) {
                    adjType = "service";
                }
            }
            if (procState < PROCESS_STATE_IMPORTANT_BACKGROUND
                    && cr.hasFlag(Context.BIND_SHOWING_UI) && !dryRun) {
                app.setPendingUiClean(true);
            }
            if (adjType != null && !dryRun) {
                app.setAdjType(adjType);
                app.setAdjTypeCode(ActivityManager.RunningAppProcessInfo.REASON_SERVICE_IN_USE);
                app.setAdjSource(client);
                app.setAdjSourceProcState(clientProcState);
                app.setAdjTarget(cr.getService().instanceName);
                if (reportDebugMsgs) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType
                            + ": " + app + ", due to " + client
                            + " adj=" + adj + " procState="
                            + ProcessList.makeProcStateString(procState));
                }
            }
        } else { // BIND_WAIVE_PRIORITY == true
            // BIND_WAIVE_PRIORITY bindings are special when it comes to the
            // freezer. Processes bound via WPRI are expected to be running,
            // but they are not promoted in the LRU list to keep them out of
            // cached. As a result, they can freeze based on oom_adj alone.
            // Normally, bindToDeath would fire when a cached app would die
            // in the background, but nothing will fire when a running process
            // pings a frozen process. Accordingly, any cached app that is
            // bound by an unfrozen app via a WPRI binding has to remain
            // unfrozen.
            if (clientAdj < CACHED_APP_MIN_ADJ) {
                if (app.setShouldNotFreeze(true, dryRun,
                        app.shouldNotFreezeReason()
                                | ProcessCachedOptimizerRecord
                                .SHOULD_NOT_FREEZE_REASON_BIND_WAIVE_PRIORITY, mAdjSeq)) {
                    if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                        // Do nothing, capability updated check will handle the dryrun output.
                    } else {
                        // Bail out early, as we only care about the return value for a dryrun.
                        return true;
                    }
                }
            }
        }
        if (cr.hasFlag(Context.BIND_TREAT_LIKE_ACTIVITY)) {
            if (!dryRun) {
                app.getServices().setTreatLikeActivity(true);
            }
            if (clientProcState <= PROCESS_STATE_CACHED_ACTIVITY
                    && procState > PROCESS_STATE_CACHED_ACTIVITY) {
                // This is a cached process, but somebody wants us to treat it like it has
                // an activity, okay!
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                app.setAdjType("cch-as-act");
            }
        }
        final ActivityServiceConnectionsHolder a = cr.getActivity();
        if (cr.hasFlag(Context.BIND_ADJUST_WITH_ACTIVITY)) {
            if (a != null && adj > FOREGROUND_APP_ADJ
                    && a.isActivityVisible()) {
                adj = FOREGROUND_APP_ADJ;
                if (app.setCurRawAdj(adj, dryRun)) {
                    return true;
                }
                if (cr.notHasFlag(Context.BIND_NOT_FOREGROUND)) {
                    if (cr.hasFlag(Context.BIND_IMPORTANT)) {
                        schedGroup = SCHED_GROUP_TOP_APP_BOUND;
                    } else {
                        schedGroup = SCHED_GROUP_DEFAULT;
                    }
                }

                if (!dryRun) {
                    app.setAdjType("service");
                    app.setAdjTypeCode(ActivityManager.RunningAppProcessInfo.REASON_SERVICE_IN_USE);
                    app.setAdjSource(a);
                    app.setAdjSourceProcState(procState);
                    app.setAdjTarget(cr.getService().instanceName);
                    if (reportDebugMsgs) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise to service w/activity: " + app);
                    }
                }
            }
        }

        capability |= getDefaultCapability(app, procState);

        // Procstates below BFGS should never have this capability.
        if (procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            capability &= ~PROCESS_CAPABILITY_BFSL;
        }
        if (!updated) {
            if (adj < prevRawAdj || procState < prevProcState || schedGroup > prevSchedGroup) {
                updated = true;
            }

            if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                if ((capability != prevCapability)
                        && ((capability & prevCapability) == prevCapability)) {
                    updated = true;
                }
            } else {
                // Ignore CPU related capabilities in comparison
                final int curFiltered = capability & ~ALL_CPU_TIME_CAPABILITIES;
                final int prevFiltered = prevCapability & ~ALL_CPU_TIME_CAPABILITIES;
                if ((curFiltered != prevFiltered)
                        && ((curFiltered & prevFiltered) == prevFiltered)) {
                    updated = true;
                }
            }
        }

        if (dryRun) {
            return updated;
        }
        if (adj < prevRawAdj) {
            schedGroup = setIntermediateAdjLSP(app, adj, schedGroup);
        }
        if (procState < prevProcState) {
            setIntermediateProcStateLSP(app, procState);
        }
        if (schedGroup > prevSchedGroup) {
            setIntermediateSchedGroupLSP(app, schedGroup);
        }
        app.setCurCapability(capability);

        return updated;
    }

    @GuardedBy({"mService", "mProcLock"})
    @Override
    public boolean computeProviderHostOomAdjLSP(ContentProviderConnectionInternal conn,
            ProcessRecordInternal app, ProcessRecordInternal client, boolean dryRun) {
        if (app.isPendingFinishAttach()) {
            // We've set the attaching process state in the computeInitialOomAdjLSP. Skip it here.
            return false;
        }

        if (client == app) {
            // Being our own client is not interesting.
            return false;
        }

        int clientAdj = client.getCurRawAdj();
        int clientProcState = client.getCurRawProcState();

        int adj = app.getCurRawAdj();
        int procState = app.getCurRawProcState();
        int schedGroup = app.getCurrentSchedulingGroup();
        int capability = app.getCurCapability();

        final int prevRawAdj = adj;
        final int prevProcState = procState;
        final int prevSchedGroup = schedGroup;
        final int prevCapability = capability;

        final boolean reportDebugMsgs =
                DEBUG_OOM_ADJ_REASON || mService.mCurOomAdjUid == app.getApplicationUid();

        // We always propagate PROCESS_CAPABILITY_BFSL to providers here,
        // but, right before actually setting it to the process,
        // we check the final procstate, and remove it if the procsate is below BFGS.
        capability |= getBfslCapabilityFromClient(client);
        capability |= getCpuCapabilitiesFromClient(app, client, conn);

        if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
            // If the other app is cached for any reason, for purposes here
            // we are going to consider it empty.
            clientProcState = PROCESS_STATE_CACHED_EMPTY;
        }
        if (client.shouldNotFreeze()) {
            // Propagate the shouldNotFreeze flag down the bindings.
            if (app.setShouldNotFreeze(true, dryRun,
                    app.shouldNotFreezeReason() | client.shouldNotFreezeReason(), mAdjSeq)) {
                if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                    // Do nothing, capability updated check will handle the dryrun output.
                } else {
                    // Bail out early, as we only care about the return value for a dryrun.
                    return true;
                }
            }
        }

        if (!dryRun) {
            app.setCurBoundByNonBgRestrictedApp(app.isCurBoundByNonBgRestrictedApp()
                    || client.isCurBoundByNonBgRestrictedApp()
                    || clientProcState <= PROCESS_STATE_BOUND_TOP
                    || (clientProcState == PROCESS_STATE_FOREGROUND_SERVICE
                    && !client.isBackgroundRestricted()));
        }

        String adjType = null;
        if (adj > clientAdj) {
            if (app.getHasShownUi() && !isHomeProcess(app)
                    && clientAdj > PERCEPTIBLE_APP_ADJ) {
                adjType = "cch-ui-provider";
            } else {
                adj = Math.max(clientAdj, FOREGROUND_APP_ADJ);
                if (app.setCurRawAdj(adj, dryRun)) {
                    // Bail out early, as we only care about the return value for a dryrun.
                    return true;
                }
                adjType = "provider";
            }

            if (app.isCached() && !client.isCached() && dryRun) {
                // Bail out early, as we only care about the return value for a dryrun.
                return true;
            }
        }

        if (clientProcState <= PROCESS_STATE_FOREGROUND_SERVICE) {
            if (adjType == null) {
                adjType = "provider";
            }
            if (clientProcState == PROCESS_STATE_TOP) {
                clientProcState = PROCESS_STATE_BOUND_TOP;
            } else {
                clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
            }
        }

        if (!dryRun) {
            conn.trackProcState(clientProcState, mAdjSeq);
        }
        if (procState > clientProcState) {
            procState = clientProcState;
            if (app.setCurRawProcState(procState, dryRun)) {
                // Bail out early, as we only care about the return value for a dryrun.
                return true;
            }
        }
        if (client.getCurrentSchedulingGroup() > schedGroup) {
            schedGroup = SCHED_GROUP_DEFAULT;
        }
        if (adjType != null && !dryRun) {
            app.setAdjType(adjType);
            app.setAdjTypeCode(ActivityManager.RunningAppProcessInfo.REASON_PROVIDER_IN_USE);
            app.setAdjSource(client);
            app.setAdjSourceProcState(clientProcState);
            app.setAdjTarget(conn.getProvider().name);
            if (reportDebugMsgs) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType
                        + ": " + app + ", due to " + client
                        + " adj=" + adj + " procState="
                        + ProcessList.makeProcStateString(procState));
            }
        }

        // Procstates below BFGS should never have this capability.
        if (procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            capability &= ~PROCESS_CAPABILITY_BFSL;
        }

        if (dryRun) {
            if (adj < prevRawAdj || procState < prevProcState || schedGroup > prevSchedGroup) {
                return true;
            }

            if (Flags.cpuTimeCapabilityBasedFreezePolicy()) {
                if ((capability != prevCapability)
                        && ((capability & prevCapability) == prevCapability)) {
                    return true;
                }
            } else {
                // Ignore CPU related capabilities in comparison
                final int curFiltered = capability & ~ALL_CPU_TIME_CAPABILITIES;
                final int prevFiltered = prevCapability & ~ALL_CPU_TIME_CAPABILITIES;
                if ((curFiltered != prevFiltered)
                        && ((curFiltered & prevFiltered) == prevFiltered)) {
                    return true;
                }
            }
        }

        if (adj < prevRawAdj) {
            schedGroup = setIntermediateAdjLSP(app, adj, schedGroup);
        }
        if (procState < prevProcState) {
            setIntermediateProcStateLSP(app, procState);
        }
        if (schedGroup > prevSchedGroup) {
            setIntermediateSchedGroupLSP(app, schedGroup);
        }
        app.setCurCapability(capability);

        return false;
    }
}
