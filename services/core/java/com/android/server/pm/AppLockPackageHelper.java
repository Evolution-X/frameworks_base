/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.supervision.SupervisionManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AppLockActivity;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.mutate.PackageStateMutator;
import com.android.server.pm.pkg.mutate.PackageUserStateWrite;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Helper class for PackageManager to handle logic related to App Lock */
public final class AppLockPackageHelper {

    private static final String TAG = "AppLockPackageHelper";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final PackageManagerService mPms;
    private final BroadcastHelper mBroadcastHelper;
    private final Injector mInjector;

    private static Boolean sIsSupportedDeviceForAppLock = null;
    private static ArraySet<String> sAppLockExemptPackages = null;

    @VisibleForTesting
    @SuppressWarnings("StaticAssignmentInConstructor") // Test constructor only
    AppLockPackageHelper(Context context, PackageManagerService packageManagerService,
            BroadcastHelper broadcastHelper, Injector injector) {
        mContext = context;
        mPms = packageManagerService;
        mBroadcastHelper = broadcastHelper;
        mInjector = injector;
        // Reset the static cached value to ensure that each test starts with a clean slate.
        sIsSupportedDeviceForAppLock = null;
    }

    public AppLockPackageHelper(Context context, PackageManagerService packageManagerService,
            BroadcastHelper broadcastHelper) {
        this(context, packageManagerService, broadcastHelper, new InjectorImpl());
    }

    // Use the static version to set the ApplicationInfo field
    private boolean isAppLockSupported(String packageName, int userId, Computer snapshot) {
        final PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
        if (ps == null) {
            return false;
        }
        final AndroidPackage pkg = ps.getPkg();
        if (pkg == null) {
            return false;
        }

        return isAppLockSupported(packageName, userId, pkg.getActivities());
    }

    /**
     * Checks if a package has been explicitly prohibited by the system from enabling App Lock.
     *
     * @param pkgName    Name of the package to check the App Lock supported state
     * @param userId     User Id to check for the package's App Lock supported state
     * @param activities A list of activities associated with the package
     * @return {@code true} if App Lock is supported for this package, {@code false} otherwise
     */
    public static boolean isAppLockSupported(String pkgName, int userId,
            List<ParsedActivity> activities) {
        // App Lock is not supported on certain form factors (e.g. watches, cars, TVs).
        if (!isSupportedDeviceForAppLock()) {
            return false;
        }

        // App Lock is not supported for packages explicitly exempted in the system configuration.
        if (isPackageExemptInSystemConfig(pkgName)) {
            return false;
        }

        // App Lock is not supported for profile users.
        if (!isFullUser(userId)) {
            return false;
        }

        // App Lock is not supported for supervised users.
        if (isSupervisedUser(userId)) {
            return false;
        }

        // App Lock is not supported for headless apps.
        return InstallPackageHelper.hasLauncherEntry(activities);
    }

    private static boolean isSupportedDeviceForAppLock() {
        if (sIsSupportedDeviceForAppLock == null) {
            sIsSupportedDeviceForAppLock = !hasSystemFeature(PackageManager.FEATURE_WATCH)
                    && !hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                    && !hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        }
        return sIsSupportedDeviceForAppLock;
    }

    private static boolean hasSystemFeature(String featureName) {
        FeatureInfo feature = SystemConfig.getInstance().getAvailableFeatures().get(featureName);
        if (feature == null) {
            return false;
        } else {
            return feature.version >= 0;
        }
    }

    private static boolean isPackageExemptInSystemConfig(String packageName) {
        if (sAppLockExemptPackages == null) {
            sAppLockExemptPackages = SystemConfig.getInstance().getAppLockExemptPackages();
        }
        return sAppLockExemptPackages.contains(packageName);
    }

    private static boolean isFullUser(int userId) {
        final UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
        if (umInternal == null) {
            return false;
        }
        UserInfo userInfo = umInternal.getUserInfo(userId);
        return userInfo != null && userInfo.isFull();
    }

    private static boolean isSupervisedUser(int userId) {
        final SupervisionManagerInternal smInternal = LocalServices.getService(
                SupervisionManagerInternal.class);
        final long token = Binder.clearCallingIdentity();
        try {
            return smInternal != null && smInternal.isSupervisionEnabledForUser(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns a {@link PendingIntent} to launch an activity that allows the caller to set App Lock
     * for the specified package. Returns null if the App Lock state of the package cannot be set,
     * either because App Lock is not supported for that package, or because it is already set to
     * that value.
     *
     * <p> Before calling this API to avoid getting a null PendingIntent, callers should first
     * verify that App Lock is supported for the specified package by first checking
     * {@link ApplicationInfo#isAppLockSupported}, or if it's already at the target state for that
     * package by checking {@link ApplicationInfo#isAppLockEnabled}. The PendingIntent resolves to
     * an activity, which allows the user to enroll a device credential if one isn't enrolled, and
     * then requires authentication before setting the App Lock enablement state as enabled or
     * disabled.
     *
     * @param snapshot    Computer snapshot.
     * @param packageName the package to enable or disable App Lock.
     * @param userId      the user to enable or disable App Lock for the package.
     * @param enabled     {@code true} when the user would like to enable App Lock for the given
     *                    package, {@code false} otherwise
     * @return a {@link PendingIntent} to launch an activity to set App Lock for the passed in
     *         package. If the package does not support App Lock or the package's App Lock state is
     *         already in the passed in state, return null.
     */
    @Nullable
    public PendingIntent getEnableAppLockIntentForPackage(@NonNull Computer snapshot,
            String packageName, int userId, boolean enabled) {
        // If the package doesn't support App Lock, return null.
        if (!isAppLockSupported(packageName, userId, snapshot)) {
            if (DEBUG) {
                Slog.d(TAG, "App Lock not supported for the package, no PendingIntent to return");
            }
            return null;
        }

        // If the package already has App Lock set to the desired state, we should return null
        if (isPackageAppLockEnabled(snapshot, packageName, userId) == enabled) {
            if (DEBUG) {
                Slog.d(TAG,
                        "App Lock enabled state is already set to the passed in value, no "
                                + "PendingIntent to return");
            }
            return null;
        }

        Intent intent = AppLockActivity.createAppLockActivityIntent(packageName, enabled);
        // We use {@link Intent#setIdentifier(String)} to ensure that the Intents within the created
        // {@link PendingIntent} are unique. This is needed because {@link Intent#filterEquals
        // (Intent)} doesn't compare Intent extras, so two PendingIntents that only differ in their
        // extras would be considered the same, leading to unexpected overwrites.
        intent.setIdentifier("unique:" + System.currentTimeMillis());

        final long token = Binder.clearCallingIdentity();
        try {
            // The following activity options prevent the sender of the PendingIntent from using
            // Background Activity Launch (BAL) privileges of the creator, in this case the system.
            ActivityOptions options =
                    ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);
            // request code is 0 because we don't call setResult in AppLockActivity     .
            return PendingIntent.getActivityAsUser(mContext, /*requestCode=*/ 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, options.toBundle(),
                    UserHandle.of(userId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Checks if the package has App Lock enabled. This can only be called by the system.
     *
     * @param snapshot    Computer snapshot
     * @param packageName Name of the package to set the App Lock enablement state for
     * @param userId      User Id to set the App Lock enablement state for
     * @param callingUid  The Uid of the caller
     * @return {@code true} if App Lock is enabled for the package and user, {@code false} otherwise
     */
    public boolean isPackageAppLockEnabled(@NonNull Computer snapshot, String packageName,
            int userId, int callingUid) {
        if (!UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                && !UserHandle.isSameApp(callingUid, Process.ROOT_UID)) {
            throw new SecurityException("isPackageAppLockEnabled can only be called by the system");
        }
        return isPackageAppLockEnabled(snapshot, packageName, userId);
    }

    private boolean isPackageAppLockEnabled(@NonNull Computer snapshot, String packageName,
            int userId) {
        PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        return packageState != null && packageState.getUserStateOrDefault(
                userId).isAppLockEnabled();
    }

    /**
     * Returns a list of packages that have App Lock enabled.
     *
     * @param snapshot    Computer snapshot
     * @param userId      User Id to set the App Lock enablement state for
     * @return {@link List} of packages names that have App Lock enabled for the user
     *
     */
    public List<String> getAppLockEnabledPackages(@NonNull Computer snapshot, int userId) {
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                snapshot.getPackageStates();
        final List<String> packages = new ArrayList<>();
        for (int i = packageStates.size() - 1; i >= 0; i--) {
            final PackageStateInternal ps = packageStates.valueAt(i);
            final PackageUserStateInternal userState = ps.getUserStateOrDefault(userId);
            final AndroidPackage pkg = ps.getPkg();
            if (userState.isAppLockEnabled() && pkg != null && pkg.getPackageName() != null) {
                packages.add(pkg.getPackageName());
            }
        }
        return packages;
    }

    /**
     * Called when the lock credential has been changed or removed for a user. If the device is no
     * longer secure for that user, then disable App Lock for all packages that have App Lock
     * enabled for that user.
     *
     * @param snapshot Computer snapshot
     * @param userId   User Id that had the credential changed or removed.
     */
    public void reportLockCredentialChanged(@NonNull Computer snapshot, int userId) {
        if (mInjector.isDeviceSecure(mContext, userId)) {
            // If the device is secure, no need to do anything
            return;
        }
        // Otherwise, disable App Lock for all packages for the user
        final List<String> packages = getAppLockEnabledPackages(snapshot, userId);
        writeMultiplePackagesAppLockStateToDisk(packages, userId, /* enabled= */ false);
    }

    /**
     * Sets the App Lock enablement state for a given package and user.
     *
     * <p>If the caller is neither {@link Process#SYSTEM_UID} nor {@link Process#ROOT_UID}, and
     * does not have {@link Manifest.permission#TEST_LOCK_APPS} this method will throw a
     * {@link SecurityException} since only tests or the system are allowed to change the App Lock
     * enabled state.
     *
     * <p>This method will write the new state to disk if the caller has the necessary
     * permissions and the new enablement state is different from the current state.
     *
     * <p>If the caller is trying to disable App Lock for an app that currently has App Lock enabled
     * this method will disable App Lock and return {@code true}. This will happen even if App Lock
     * is not supported for an app, since unsupported apps should not have App Lock enabled.
     *
     * <p>If the caller is trying to enable App Lock, but it can't be enabled, either because
     * {@link #isAppLockSupported(String, int, Computer)} is {@code false} or because the device is
     * not currently secured with a device credential (e.g., PIN, pattern, or password), this method
     * will not set the target App Lock state and will return {@code false}.
     *
     * <p>If the App Lock state is already set to the desired value, and the caller is permitted to
     * make this change, this method will do nothing and return {@code true}.
     *
     * @param snapshotSupplier    Computer snapshot supplier
     * @param packageName Name of the package to write App Lock enablement state
     * @param userId      User Id to write App Lock enablement state
     * @param enabled     The target App Lock enablement state
     * @param callingUid  The Uid of the caller
     * @return {@code true} if the App Lock enablement state was set to the passed in value for the
     *                      package and user, {@code false} otherwise
     * @throws SecurityException if the caller is neither {@link Process#SYSTEM_UID} nor
     * {@link Process#ROOT_UID} and does not have {@link Manifest.permission#TEST_LOCK_APPS}
     */
    public boolean setPackageAppLockEnabled(@NonNull Supplier<Computer> snapshotSupplier,
            String packageName, int userId, boolean enabled, int callingUid, int callingPid) {
        if (!UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                && !UserHandle.isSameApp(callingUid, Process.ROOT_UID)) {
            mContext.enforcePermission(Manifest.permission.TEST_LOCK_APPS, callingPid,
                    callingUid, "setPackageAppLockEnabled can only be called by the system");
        }

        Computer snapshot = snapshotSupplier.get();
        boolean currentEnabled = isPackageAppLockEnabled(snapshot, packageName, userId);
        boolean canSetPackageAppLockEnabledState = isAppLockSupported(packageName, userId, snapshot)
                && mInjector.isDeviceSecure(mContext, userId);

        // TODO(b/454876719): proactively listen to events that could disable App Lock, e.g. user
        // removal, device credential removal, an app becoming headless, etc. and update App Lock
        // enablement state accordingly
        if (!canSetPackageAppLockEnabledState) {
            // Disable App Lock for the package, because the conditions to keep App Lock enabled are
            // no longer satisfied.
            if (currentEnabled) {
                writeAppLockStateToDisk(packageName, userId, /* enabled= */ false);
            }
            return !enabled;
        }

        if (enabled != currentEnabled) {
            writeAppLockStateToDisk(packageName, userId, enabled);
        }
        return true;
    }

    private void writeAppLockStateToDisk(String packageName, int userId, boolean enabled) {
        PackageStateMutator.InitialState state = mPms.recordInitialState();
        mPms.commitPackageStateMutation(state, mutator -> {
            final PackageUserStateWrite userState = mutator.forPackage(packageName).userState(
                    userId);
            userState.setAppLockEnabled(enabled);
        });
        mPms.scheduleWritePackageRestrictions(userId);
        mBroadcastHelper.sendPackageAppLockStateChangedForUser(packageName, userId, enabled);
    }

    private void writeMultiplePackagesAppLockStateToDisk(@NonNull List<String> packages, int userId,
            boolean enabled) {
        if (packages.isEmpty()) {
            return;
        }
        PackageStateMutator.InitialState state = mPms.recordInitialState();
        mPms.commitPackageStateMutation(state, mutator -> {
            for (int i = packages.size() - 1; i >= 0; i--) {
                final PackageUserStateWrite userState = mutator.forPackage(
                        packages.get(i)).userState(userId);
                userState.setAppLockEnabled(enabled);
            }
        });
        mPms.scheduleWritePackageRestrictions(userId);
        for (int i = packages.size() - 1; i >= 0; i--) {
            mBroadcastHelper.sendPackageAppLockStateChangedForUser(packages.get(i), userId,
                    enabled);
        }
    }

    @VisibleForTesting
    interface Injector {
        boolean isDeviceSecure(Context context, int userId);
    }

    private static final class InjectorImpl implements Injector {
        @Override
        public boolean isDeviceSecure(Context context, int userId) {
            return com.android.internal.app.AppLockCredentialUtils.isAppLockSecure(context, userId);
        }
    }
}
