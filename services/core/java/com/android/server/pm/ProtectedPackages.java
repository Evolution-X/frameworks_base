/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.app.supervision.SupervisionManager;
import android.content.Context;
import android.os.Binder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages package names that need special protection.
 *
 * TODO: This class should persist the information by itself, and also keeps track of device admin
 * packages for all users.  Then PMS.isPackageDeviceAdmin() should use it instead of talking
 * to DPMS.
 */
public class ProtectedPackages {
    static final String TAG = "PackageManager";

    private final Context mContext;

    @UserIdInt
    @GuardedBy("this")
    private int mDeviceOwnerUserId;

    @Nullable
    @GuardedBy("this")
    private String mDeviceOwnerPackage;

    @Nullable
    @GuardedBy("this")
    private SparseArray<String> mProfileOwnerPackages;

    @Nullable
    @GuardedBy("this")
    private SparseArray<String> mDevicePolicyControllerPackages;

    @Nullable
    @GuardedBy("this")
    private final String mDeviceProvisioningPackage;

    @Nullable
    @GuardedBy("this")
    private final SparseArray<Set<String>> mOwnerProtectedPackages = new SparseArray<>();

    public ProtectedPackages(Context context) {
        mContext = context;
        mDeviceProvisioningPackage = context.getResources().getString(
                R.string.config_deviceProvisioningPackage);
    }

    /**
     * Sets the device/profile owner information.
     */
    public synchronized void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwnerPackage,
            SparseArray<String> profileOwnerPackages) {
        mDeviceOwnerUserId = deviceOwnerUserId;
        mDeviceOwnerPackage =
                (deviceOwnerUserId == UserHandle.USER_NULL) ? null : deviceOwnerPackage;
        mProfileOwnerPackages = (profileOwnerPackages == null) ? null
                : profileOwnerPackages.clone();
    }

    /**
     * Sets the DPC packages. A DPC can be device owner, profile owner or device controller.
     */
    public synchronized void setDevicePolicyControllerPackages(
            @Nullable SparseArray<String> devicePolicyControllerPackages) {
        mDevicePolicyControllerPackages = (devicePolicyControllerPackages == null) ? null
                : devicePolicyControllerPackages.clone();
    }

    /** Sets packages protected by a device or profile owner or an admin. */
    public synchronized void setOwnerProtectedPackages(
            @UserIdInt int userId, @Nullable List<String> packageNames) {
        if (packageNames == null) {
            mOwnerProtectedPackages.remove(userId);
        } else {
            mOwnerProtectedPackages.put(userId, new ArraySet<>(packageNames));
        }
    }

    private synchronized boolean isDevicePolicyManagementPackage(int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        if (mDevicePolicyControllerPackages == null) {
            return false;
        }
        return packageName.equals(getDevicePolicyControllerPackage(userId));
    }

    /**
     * Returns the DPC package name for the given user if it exists, otherwise returns null. A
     * DPC can be profile owner, device owner, device controller. For a given user, there can only
     * one DPC package exist.
     */
    @Nullable
    public synchronized String getDevicePolicyControllerPackage(int userId) {
        if (mDevicePolicyControllerPackages == null) {
            return null;
        }
        return mDevicePolicyControllerPackages.get(userId);
    }

    private synchronized boolean hasDeviceOwnerOrProfileOwner(int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        if (mDeviceOwnerPackage != null) {
            if ((mDeviceOwnerUserId == userId)
                    && (packageName.equals(mDeviceOwnerPackage))) {
                return true;
            }
        }
        if (mProfileOwnerPackages != null) {
            if (packageName.equals(mProfileOwnerPackages.get(userId))) {
                return true;
            }
        }
        return false;
    }

    public synchronized String getDeviceOwnerOrProfileOwnerPackage(int userId) {
        if (mDeviceOwnerUserId == userId) {
            return mDeviceOwnerPackage;
        }
        if (mProfileOwnerPackages == null) {
            return null;
        }
        return mProfileOwnerPackages.get(userId);
    }

    /**
     * Returns {@code true} if a given package is protected. Otherwise, returns {@code false}.
     *
     * <p>A protected package means that, apart from the package owner, no system or privileged apps
     * can modify its data or package state.
     */
    private synchronized boolean isProtectedPackage(@UserIdInt int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        if (isDevicePolicyManagementPackage(userId, packageName)) {
            return true;
        }
        if (packageName.equals(mDeviceProvisioningPackage)
                || isOwnerProtectedPackage(userId, packageName)) {
            return true;
        }
        if (isSupervisionPackage(userId, packageName)) {
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the given package is a protected package set by any device or
     * profile owner.
     */
    private synchronized boolean isOwnerProtectedPackage(
            @UserIdInt int userId, String packageName) {
        return hasProtectedPackages(userId)
                ? isPackageProtectedForUser(userId, packageName)
                : isPackageProtectedForUser(UserHandle.USER_ALL, packageName);
    }

    private synchronized boolean isPackageProtectedForUser(
            @UserIdInt int userId, String packageName) {
        int userIdx = mOwnerProtectedPackages.indexOfKey(userId);
        return userIdx >= 0 && mOwnerProtectedPackages.valueAt(userIdx).contains(packageName);
    }

    private synchronized boolean hasProtectedPackages(@UserIdInt int userId) {
        return mOwnerProtectedPackages.indexOfKey(userId) >= 0;
    }

    /**
     * Returns {@code true} if a given package's state is protected. Otherwise, returns
     * {@code false}.
     *
     * <p>This is not applicable if the caller is the package owner.
     */
    public boolean isPackageStateProtected(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName)
                || isProtectedPackage(userId, packageName);
    }

    /**
     * Returns {@code true} if a given package's data is protected. Otherwise, returns
     * {@code false}.
     */
    public boolean isPackageDataProtected(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName)
                || isProtectedPackage(userId, packageName);
    }

    /**
     * Returns {@code true} if a given package is the device provisioning package. Otherwise,
     * returns {@code false}.
     */
    public synchronized boolean isDeviceProvisioningPackage(String packageName) {
        return !TextUtils.isEmpty(mDeviceProvisioningPackage) && Objects.equals(
                mDeviceProvisioningPackage, packageName);
    }

    /** Query the packages with supervision related roles. */
    private boolean isSupervisionPackage(@UserIdInt int userId, String packageName) {
        // Installing system providers can reach this before SupervisionService starts.
        if (ServiceManager.checkService(Context.SUPERVISION_SERVICE) == null) {
            return false;
        }
        SupervisionManager supervisionManager = mContext.getSystemService(SupervisionManager.class);
        if (supervisionManager == null) {
            Slog.w(TAG, "Failed to get SupervisionManager.");
            return false;
        }
        final RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        if (roleManager == null) {
            Slog.w(TAG, "Failed to get RoleManager. Assuming package isn't role holder.");
            return false;
        }
        return Binder.withCleanCallingIdentity(
                () -> {
                    if (!supervisionManager.isSupervisionEnabledForUser(userId)) {
                        return false;
                    }
                    List<String> systemSupervisionHolders =
                            roleManager.getRoleHoldersAsUser(
                                    RoleManager.ROLE_SYSTEM_SUPERVISION, UserHandle.of(userId));
                    List<String> supervisionHolders =
                            roleManager.getRoleHoldersAsUser(
                                    RoleManager.ROLE_SUPERVISION, UserHandle.of(userId));
                    return systemSupervisionHolders.contains(packageName)
                            || supervisionHolders.contains(packageName);
                });
    }
}
