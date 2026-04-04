/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.icon

import android.annotation.WorkerThread
import android.app.ActivityManager
import android.app.Flags
import android.app.Flags.notificationsRedesignThemedAppIcons
import android.content.Context
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.R
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.IconOptions
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.util.UserIconInfo
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.time.SystemClock
import dagger.Module
import dagger.Provides
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/** A provider used to cache and fetch app icons used by notifications. */
interface AppIconProvider {
    /**
     * Loads the icon corresponding to [packageName] into cache, or fetches it from there if already
     * present. This should only be called from the background.
     */
    @Throws(NameNotFoundException::class)
    @WorkerThread
    fun getOrFetchAppIcon(
        packageName: String,
        userHandle: UserHandle,
        instanceKey: String,
    ): Drawable

    /**
     * Loads the skeleton (black and white)-themed icon corresponding to [packageName] into cache,
     * or fetches it from there if already present. This should only be called from the background.
     *
     * @param packageName the name of the app's package
     * @param context the app's context (NOT SystemUI)
     *
     * TODO: b/416215382 - if we get the SystemUI context here instead of the app's, and the package
     *   is not installed on the main profile, this will throw a [NameNotFoundException]. We should
     *   update the API to take a userId directly to avoid such issues.
     */
    @Throws(NameNotFoundException::class)
    @WorkerThread
    fun getOrFetchSkeletonAppIcon(packageName: String, userHandle: UserHandle): Drawable

    /**
     * Mark all the entries in the cache that are NOT in [wantedPackages] to be cleared. If they're
     * still not needed on the next call of this method (made after a timeout of 1s, in case they
     * happen more frequently than that), they will be purged. This can be done from any thread.
     */
    fun purgeCache(wantedPackages: Collection<String>)
}

@SysUISingleton
class AppIconProviderImpl
@Inject
constructor(
    @ShadeDisplayAware private val sysuiContext: Context,
    dumpManager: DumpManager,
    systemClock: SystemClock,
) : AppIconProvider, Dumpable {
    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    private val iconSize: Int
        get() =
            sysuiContext.resources.getDimensionPixelSize(
                if (ActivityManager.isLowRamDeviceStatic()) {
                    R.dimen.notification_small_icon_size_low_ram
                } else {
                    R.dimen.notification_small_icon_size
                }
            )

    private val densityDpi: Int
        get() = sysuiContext.resources.configuration.densityDpi

    private fun shouldUseThemedIcons(context: Context): Boolean =
        notificationsRedesignThemedAppIcons() &&
            Settings.System.getInt(
                context.contentResolver,
                "notification_use_themed_app_icons",
                0
            ) == 1

    private val standardIconFactory: BaseIconFactory
        get() =
            BaseIconFactory(
                context = sysuiContext,
                fullResIconDpi = densityDpi,
                iconBitmapSize = iconSize,
                // Initialize the controller so that we can support themed icons.
                themeController =
                    if (shouldUseThemedIcons(sysuiContext))
                        MonoIconThemeController(
                            shouldForceThemeIcon = true,
                            colorProvider = { ctx ->
                                val res = ctx.resources
                                intArrayOf(
                                    /* background */ res.getColor(R.color.materialColorPrimary),
                                    /* icon */ res.getColor(
                                        R.color.materialColorSurfaceContainerHigh
                                    ),
                                    /* adaptive background */ res.getColor(
                                        R.color.materialColorPrimary
                                    ),
                                )
                            },
                        )
                    else null,
            )

    private val skeletonIconFactory: BaseIconFactory
        get() =
            BaseIconFactory(
                context = sysuiContext,
                fullResIconDpi = densityDpi,
                iconBitmapSize = iconSize,
                themeController =
                    MonoIconThemeController(
                        shouldForceThemeIcon = true,
                        colorProvider = { _ ->
                            intArrayOf(
                                /* background */ Color.BLACK, /* icon */
                                Color.WHITE,

                                /* adaptive background */ Color.BLACK,
                            )
                        },
                    ),
            )

    /** Cache of standard-appearance icons as used in the notification row and guts */
    private val standardCache = AppIconCache(systemClock = systemClock)

    /** Cache of black and white icons for use on AOD */
    private val skeletonCache = AppIconCache(systemClock = systemClock)

    override fun getOrFetchAppIcon(
        packageName: String,
        userHandle: UserHandle,
        instanceKey: String,
    ): Drawable =
        standardCache.getOrFetchAppIcon(
            packageName = packageName,
            userHandle = userHandle,
            drawableInstanceKey = instanceKey,
            createDrawable = {
                it.createIconDrawable(themed = shouldUseThemedIcons(sysuiContext))
            },
        ) {
            fetchAppIconBitmapInfo(
                standardIconFactory,
                packageName,
                userHandle,
                allowProfileBadge = true,
            )
        }

    override fun getOrFetchSkeletonAppIcon(packageName: String, userHandle: UserHandle): Drawable =
        skeletonCache.getOrFetchAppIcon(
            packageName = packageName,
            userHandle = null, // these aren't badged, so they don't need to be sharded by user
            drawableInstanceKey = "SKELETON",
            createDrawable = { it.createIconDrawable(themed = true) },
        ) {
            fetchAppIconBitmapInfo(
                skeletonIconFactory,
                packageName,
                userHandle,
                allowProfileBadge = false,
            )
        }

    @WorkerThread
    private fun fetchAppIconBitmapInfo(
        iconFactory: BaseIconFactory,
        packageName: String,
        userHandle: UserHandle,
        allowProfileBadge: Boolean,
    ): BitmapInfo {
        val pm = sysuiContext.packageManager
        val userId = userHandle.identifier
        val icon =
            pm.getApplicationInfoAsUser(packageName, MATCH_UNINSTALLED_PACKAGES, userId)
                .loadUnbadgedIcon(pm)
        val options = iconOptions(userHandle, allowProfileBadge = allowProfileBadge)
        return iconFactory.createBadgedIconBitmap(icon, options)
    }

    @VisibleForTesting
    fun createAppIconForTest(packageName: String, @UserIconInfo.UserType userType: Int): Drawable {
        val pm = sysuiContext.packageManager
        val userHandle = UserHandle.of(pm.userId)
        val icon = pm.getApplicationInfo(packageName, 0).loadUnbadgedIcon(pm)
        val options = iconOptions(UserIconInfo(userHandle, userType))
        val bitmapInfo = standardIconFactory.createBadgedIconBitmap(icon, options)
        return bitmapInfo.createIconDrawable(themed = false)
    }

    private fun BitmapInfo.createIconDrawable(themed: Boolean): Drawable =
        newIcon(context = sysuiContext, creationFlags = if (themed) BitmapInfo.FLAG_THEMED else 0)
            .apply { isAnimationEnabled = false }

    private fun iconOptions(userHandle: UserHandle, allowProfileBadge: Boolean): IconOptions =
        iconOptions(userIconInfo(userHandle, allowProfileBadge = allowProfileBadge))

    private fun iconOptions(userIconInfo: UserIconInfo): IconOptions {
        return IconOptions().apply {
            setUser(userIconInfo)
            setBitmapGenerationMode(BaseIconFactory.MODE_HARDWARE)
            // This color will not be used, but we're just setting it so that the icon factory
            // doesn't try to extract colors from our bitmap (since it won't work, given it's a
            // hardware bitmap).
            setExtractedColor(Color.BLUE)
        }
    }

    private fun userIconInfo(userHandle: UserHandle, allowProfileBadge: Boolean): UserIconInfo =
        if (allowProfileBadge) {
            // Look up the user to determine if it is a profile, and if so which badge to use
            Utils.fetchUserIconInfo(sysuiContext, userHandle)
        } else {
            // For a main user the IconFactory does not add a badge
            UserIconInfo(/* user= */ userHandle, /* type= */ UserIconInfo.TYPE_MAIN)
        }

    override fun purgeCache(wantedPackages: Collection<String>) {
        standardCache.purgeCache(wantedPackages)
        skeletonCache.purgeCache(wantedPackages)
    }

    override fun dump(pwOrig: PrintWriter, args: Array<out String>) {
        val pw = pwOrig.asIndenting()
        pw.printSection("standard cache") { standardCache.dump(pw, args) }
        pw.printSection("skeleton cache") { skeletonCache.dump(pw, args) }
        pw.printSection("icon factory info") {
            val standardIconFactory = standardIconFactory
            pw.println("fullResIconDpi = ${standardIconFactory.fullResIconDpi}")
            pw.println("iconSize = ${standardIconFactory.iconBitmapSize}")
        }
    }

    companion object {
        const val TAG = "AppIconProviderImpl"
        const val WORK_SUFFIX = "|WORK"
    }
}

class NoOpIconProvider : AppIconProvider {
    companion object {
        const val TAG = "NoOpIconProvider"
    }

    override fun getOrFetchAppIcon(
        packageName: String,
        userHandle: UserHandle,
        instanceKey: String,
    ): Drawable {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
        return ColorDrawable(Color.WHITE)
    }

    override fun getOrFetchSkeletonAppIcon(packageName: String, userHandle: UserHandle): Drawable {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
        return ColorDrawable(Color.BLACK)
    }

    override fun purgeCache(wantedPackages: Collection<String>) {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
    }
}

@Module
class AppIconProviderModule {
    @Provides
    @SysUISingleton
    fun provideImpl(realImpl: Provider<AppIconProviderImpl>): AppIconProvider =
        if (Flags.notificationsRedesignAppIcons()) {
            realImpl.get()
        } else {
            NoOpIconProvider()
        }
}
