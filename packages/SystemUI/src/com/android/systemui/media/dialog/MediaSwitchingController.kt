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
package com.android.systemui.media.dialog

import android.app.KeyguardManager
import android.app.Notification
import android.app.WallpaperColors
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.INearbyMediaDevicesUpdateCallback
import android.media.MediaMetadata
import android.media.MediaRoute2Info
import android.media.NearbyDevice
import android.media.RouteListingPreference
import android.media.RoutingChangeInfo
import android.media.RoutingSessionInfo
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.PowerExemptionManager
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.permission.flags.Flags.accessLocalNetworkPermissionEnabled
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.android.media.flags.Flags
import com.android.media.flags.Flags.fixOutputSwitcherMultiuserSupport
import com.android.settingslib.RestrictedLockUtilsInternal
import com.android.settingslib.Utils
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.InfoMediaManager
import com.android.settingslib.media.InputMediaDevice
import com.android.settingslib.media.InputRouteManager
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.MissingPermissionsInfo
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.dialog.MediaItem.DeviceGroupMediaItem
import com.android.systemui.media.dialog.MediaItem.DeviceMediaItem
import com.android.systemui.media.dialog.MediaItem.GroupDividerMediaItem
import com.android.systemui.media.dialog.MediaOutputColorScheme.Factory.fromDynamicColors
import com.android.systemui.media.dialog.MediaOutputColorScheme.Factory.fromSystemColors
import com.android.systemui.media.nearby.NearbyMediaDevicesManager
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.time.SystemClock
import com.android.systemui.volume.dialog.domain.interactor.ExpandedAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.Job

/**
 * Controller for a dialog that allows users to switch media output and input devices, control
 * volume, connect to new devices, etc.
 */
class MediaSwitchingController
@AssistedInject
constructor(
    private val mContext: Context,
    @Assisted private val mPackageName: String?,
    @Assisted private val userHandle: UserHandle?,
    @Assisted private val mToken: MediaSession.Token?,
    @Assisted mediaSwitchingType: MediaSwitchingType?,
    private val mMediaSessionManager: MediaSessionManager,
    private val mLocalBluetoothManager: LocalBluetoothManager?,
    private val mActivityStarter: ActivityStarter,
    private val mNotifCollection: CommonNotifCollection,
    private val mDialogTransitionAnimator: DialogTransitionAnimator,
    private val mNearbyMediaDevicesManager: NearbyMediaDevicesManager?,
    private val mAudioManager: AudioManager,
    private val mPowerExemptionManager: PowerExemptionManager?,
    private val mKeyGuardManager: KeyguardManager?,
    private val mClock: SystemClock,
    @Background private val mBackgroundExecutor: Executor,
    private val mVolumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
    private val mUserTracker: UserTracker,
    private val mJavaAdapter: JavaAdapter,
    private val mAudioSharingRepository: AudioSharingRepository,
    private val mExpandedAudioTileDetailsFeatureInteractor:
        ExpandedAudioTileDetailsFeatureInteractor,
) : LocalMediaManager.DeviceCallback, INearbyMediaDevicesUpdateCallback {

    private val mMediaDevicesLock: Any = Any()
    private val mInputMediaDevicesLock: Any = Any()
    @VisibleForTesting val mCachedMediaDevices: MutableList<MediaDevice> = CopyOnWriteArrayList()
    private val mOutputMediaItemListProxy: OutputMediaItemListProxy =
        OutputMediaItemListProxy(mContext)
    private val mInputMediaItemList: MutableList<MediaItem> = CopyOnWriteArrayList()
    private val mNearbyDeviceInfoMap: MutableMap<String, Int> = ConcurrentHashMap()
    private val mMediaSwitchingType: MediaSwitchingType

    private var mIsRefreshing: Boolean = false

    @JvmField @VisibleForTesting var mNeedRefresh: Boolean = false
    private var mMediaController: MediaController? = null

    @JvmField @VisibleForTesting var mInputRouteManager: InputRouteManager? = null

    @VisibleForTesting lateinit var mCallback: Callback

    private val mInfoMediaManager: InfoMediaManager =
        InfoMediaManager.createInstance(
            mContext,
            mPackageName,
            userHandle,
            mLocalBluetoothManager,
            mToken,
        )

    @JvmField
    @VisibleForTesting
    var mLocalMediaManager: LocalMediaManager =
        LocalMediaManager(mContext, mLocalBluetoothManager, mInfoMediaManager, mPackageName)

    @JvmField
    @VisibleForTesting
    var mMetricLogger: MediaOutputMetricLogger = MediaOutputMetricLogger(mContext, mPackageName)
    private var mCurrentState = 0
    private var mMediaOutputColorScheme: MediaOutputColorScheme = fromSystemColors(mContext)

    private var mIsGroupListCollapsed: Boolean = true
    private var mHasAdjustVolumeUserRestriction = false
    private var mStartTime: Long = 0
    private var mGroupSelectedItems: Boolean? = null // Unset until the first render.
    private var mInAudioSharing = false
    private var mAudioShareJob: Job? = null

    @JvmField
    @VisibleForTesting
    val mInputDeviceCallback: InputRouteManager.InputDeviceCallback =
        InputRouteManager.InputDeviceCallback { devices ->
            synchronized(mInputMediaDevicesLock) {
                buildInputMediaItems(devices)
                mCallback.onDeviceListChanged()
            }
        }

    init {
        if (DEBUG) Log.d(TAG, "Controller for $userHandle | $mPackageName | $mToken")
        mMediaSwitchingType =
            mediaSwitchingType
                ?: if (enableInputRouting()) {
                    MediaSwitchingType.ALL
                } else {
                    MediaSwitchingType.OUTPUT
                }

        if (mMediaSwitchingType != MediaSwitchingType.OUTPUT) {
            mInputRouteManager = InputRouteManager(mContext, mAudioManager, mInfoMediaManager)
        }
    }

    @AssistedFactory
    interface Factory {
        /** Construct a MediaSwitchingController */
        fun create(
            packageName: String?,
            userHandle: UserHandle?,
            token: MediaSession.Token?,
            mediaSwitchingType: MediaSwitchingType?,
        ): MediaSwitchingController
    }

    /**
     * Initializes the variables and starts this controller.
     *
     * @param cb the callback associated with this controller.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    fun start(cb: Callback) {
        mStartTime = mClock.elapsedRealtime()
        synchronized(mMediaDevicesLock) {
            mCachedMediaDevices.clear()
            mOutputMediaItemListProxy.clear()
        }
        if (!mPackageName.isNullOrEmpty()) {
            mMediaController =
                getMediaController()?.apply {
                    unregisterCallback(mCb)
                    playbackState?.let { mCurrentState = it.state }
                    registerCallback(mCb)
                }
        }
        if (mMediaController == null) {
            if (DEBUG) {
                Log.d(TAG, "No media controller for $mPackageName")
            }
        }
        mCallback = cb
        mNearbyDeviceInfoMap.clear()
        if (mMediaSwitchingType != MediaSwitchingType.INPUT) {
            mNearbyMediaDevicesManager?.registerNearbyDevicesCallback(this)
            mLocalMediaManager.registerCallback(this)
            mLocalMediaManager.startScan()
        }

        if (mMediaSwitchingType != MediaSwitchingType.OUTPUT) {
            mInputRouteManager?.registerCallback(mInputDeviceCallback)
        }
        mHasAdjustVolumeUserRestriction = checkIfAdjustVolumeRestrictionEnforced()

        mAudioShareJob =
            mJavaAdapter.alwaysCollectFlow(mAudioSharingRepository.inAudioSharing) { inAudioSharing
                ->
                mInAudioSharing = inAudioSharing
                mCallback.onQuickAccessButtonsChanged()
            }
    }

    fun isRefreshing(): Boolean = mIsRefreshing

    fun setRefreshing(refreshing: Boolean) {
        mIsRefreshing = refreshing
    }

    fun stop() {
        mMediaController?.unregisterCallback(mCb)
        if (mMediaSwitchingType != MediaSwitchingType.INPUT) {
            mLocalMediaManager.unregisterCallback(this)
            mLocalMediaManager.stopScan()
            mNearbyMediaDevicesManager?.unregisterNearbyDevicesCallback(this)
        }
        synchronized(mMediaDevicesLock) {
            mCachedMediaDevices.clear()
            mOutputMediaItemListProxy.clear()
        }
        mNearbyDeviceInfoMap.clear()

        if (mMediaSwitchingType != MediaSwitchingType.OUTPUT) {
            mInputRouteManager?.unregisterCallback(mInputDeviceCallback)
            synchronized(mInputMediaDevicesLock) { mInputMediaItemList.clear() }
        }

        mAudioShareJob?.cancel(CancellationException("MediaSwitchingController stopped"))
    }

    private fun getMediaController(): MediaController? {
        if (mToken != null) {
            return MediaController(mContext, mToken)
        } else {
            for (entry in mNotifCollection.getAllNotifs()) {
                val notification = entry.sbn.notification
                if (notification.isMediaNotification() && entry.sbn.packageName == mPackageName) {
                    val token =
                        notification.extras.getParcelable<MediaSession.Token>(
                            Notification.EXTRA_MEDIA_SESSION
                        )
                    if (token != null) return MediaController(mContext, token)
                }
            }
            for (controller in
                mMediaSessionManager.getActiveSessionsForUser(null, mUserTracker.userHandle)) {
                if (controller.packageName == mPackageName) {
                    return controller
                }
            }
            return null
        }
    }

    override fun onDeviceListUpdate(devices: MutableList<MediaDevice>) {
        val isListEmpty = mOutputMediaItemListProxy.isEmpty()
        if (isListEmpty || !mIsRefreshing) {
            buildMediaItems(devices)
            if (mGroupSelectedItems == null) {
                // Decide whether to group devices only during the initial render.
                // Avoid grouping broadcast devices because grouped volume control is not
                // available for broadcast session.
                mGroupSelectedItems = hasGroupPlayback() && isVolumeControlEnabledForSession()
            }
            mCallback.onDeviceListChanged()
        } else {
            synchronized(mMediaDevicesLock) {
                mNeedRefresh = true
                mCachedMediaDevices.clear()
                mCachedMediaDevices.addAll(devices)
            }
        }
    }

    override fun onSelectedDeviceStateChanged(device: MediaDevice, @MediaDeviceState state: Int) {
        mCallback.onRouteChanged()
        mMetricLogger.logOutputItemSuccess(
            device.toString(),
            mOutputMediaItemListProxy.getOutputMediaItemList().toList(),
        )
    }

    override fun onDeviceAttributesChanged() {
        mCallback.onRouteChanged()
    }

    override fun onMissingPermissionsUpdated(info: MissingPermissionsInfo?) {
        if (accessLocalNetworkPermissionEnabled()) {
            mCallback.onRouteChanged()
        }
    }

    override fun onRequestFailed(reason: Int) {
        mCallback.onRouteChanged()
        mMetricLogger.logOutputItemFailure(
            mOutputMediaItemListProxy.getOutputMediaItemList().toList(),
            reason,
        )
    }

    /** Checks if there's any muting expected devices in the current MediaItem list. */
    fun hasMutingExpectedDevice(): Boolean {
        return mOutputMediaItemListProxy.getOutputMediaItemList().any {
            it is DeviceMediaItem && it.mediaDevice.isMutingExpectedDevice
        }
    }

    /** Checks if there's any muting expected device in the provided device list. */
    private fun containsMutingExpectedDevice(devices: MutableList<MediaDevice>): Boolean {
        return devices.any { it.isMutingExpectedDevice }
    }

    /** Cancels mute await connection action in follow up request */
    fun cancelMuteAwaitConnection() {
        val mutingExpectedDevice = mAudioManager.mutingExpectedDevice
        if (mutingExpectedDevice == null) {
            return
        }
        try {
            synchronized(mMediaDevicesLock) {
                mOutputMediaItemListProxy.removeMutingExpectedDevices()
            }
            mAudioManager.cancelMuteAwaitConnection(mutingExpectedDevice)
        } catch (_: Exception) {
            Log.d(TAG, "Unable to cancel mute await connection")
        }
    }

    private fun getAppSourceIconFromPackage(): Drawable? {
        val packageName = mPackageName
        if (packageName.isNullOrEmpty()) {
            return null
        }
        try {
            Log.d(TAG, "try to get app icon")
            return if (fixOutputSwitcherMultiuserSupport() && userHandle != null) {
                getPackageManagerForUser(userHandle).getApplicationIcon(packageName)
            } else {
                mContext.getPackageManager().getApplicationIcon(packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d(TAG, "icon not found")
            return null
        }
    }

    private fun getAppName(pm: PackageManager, packageName: String, defaultName: String): String {
        val applicationInfo =
            try {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        return if (applicationInfo != null) pm.getApplicationLabel(applicationInfo).toString()
        else defaultName
    }

    fun getAppSourceName(): String? {
        val packageName = mPackageName
        if (packageName.isNullOrEmpty()) {
            return null
        }
        return getAppName(
            mContext.packageManager,
            packageName,
            defaultName = mContext.getString(R.string.media_output_dialog_unknown_launch_app_name),
        )
    }

    private fun getPackageManagerForUser(userHandle: UserHandle): PackageManager {
        return mContext.createContextAsUser(userHandle, /* flags= */ 0).packageManager
    }

    fun getAppLaunchIntent(): Intent? {
        val packageName = mPackageName
        if (packageName.isNullOrEmpty()) {
            return null
        }
        val packageManager =
            if (fixOutputSwitcherMultiuserSupport() && userHandle != null) {
                getPackageManagerForUser(userHandle)
            } else {
                mContext.getPackageManager()
            }
        return packageManager.getLaunchIntentForPackage(packageName)
    }

    fun tryToLaunchInAppRoutingIntent(routeId: String?, view: View) {
        val componentName = mLocalMediaManager.getLinkedItemComponentName()
        if (componentName != null) {
            val controller = mDialogTransitionAnimator.createActivityTransitionController(view)
            val launchIntent = Intent(RouteListingPreference.ACTION_TRANSFER_MEDIA)
            launchIntent.setComponent(componentName)
            launchIntent.putExtra(RouteListingPreference.EXTRA_ROUTE_ID, routeId)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mCallback.dismissDialog()
            if (fixOutputSwitcherMultiuserSupport()) {
                startMediaAppActivity(launchIntent, animationController = controller)
            } else {
                startActivity(launchIntent, controller)
            }
        }
    }

    fun tryToLaunchMediaApplication(view: View) {
        val controller = mDialogTransitionAnimator.createActivityTransitionController(view)
        getAppLaunchIntent()?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mCallback.dismissDialog()
            if (fixOutputSwitcherMultiuserSupport()) {
                startMediaAppActivity(intent = this, animationController = controller)
            } else {
                startActivity(this, controller)
            }
        }
    }

    fun tryToLaunchMissingPermissionsResolveIntent() {
        getMissingPermissionsResolveIntent()?.apply {
            mCallback.dismissDialog()
            if (fixOutputSwitcherMultiuserSupport()) {
                startMediaAppActivity(intent = this)
            } else {
                try {
                    mContext.startActivityAsUser(this, mLocalMediaManager.userHandle)
                } catch (_: ActivityNotFoundException) {
                    // Checks for the intent to match an activity in the calling app are done at
                    // registration time, but in theory the app could be uninstalled just before
                    // this
                    // code runs.
                    Log.e(
                        TAG,
                        "No activity found to handle intent $this on user " +
                            mLocalMediaManager.userHandle,
                    )
                }
            }
        }
    }

    fun getHeaderTitle(): CharSequence =
        mMediaController?.metadata?.description?.title
            ?: mContext.getText(R.string.controls_media_title)

    fun getHeaderSubTitle(): CharSequence? = mMediaController?.metadata?.description?.subtitle

    fun getHeaderIcon(): IconCompat? {
        mMediaController?.metadata?.description?.iconBitmap?.let { bitmap ->
            val roundBitmap =
                Utils.convertCornerRadiusBitmap(
                    mContext,
                    bitmap,
                    mContext
                        .getResources()
                        .getDimensionPixelSize(R.dimen.media_output_dialog_icon_corner_radius)
                        .toFloat(),
                )
            return IconCompat.createWithBitmap(roundBitmap)
        }
        if (DEBUG) {
            Log.d(TAG, "Media meta data does not contain icon information")
        }
        return getNotificationIcon()
    }

    fun getDeviceIconDrawable(device: MediaDevice): Drawable? {
        var drawable = device.getIcon()
        if (drawable == null) {
            if (DEBUG) {
                Log.d(TAG, "getDeviceIconCompat() device : ${device.getName()}, drawable is null")
            }
            // Use default Bluetooth device icon to handle getIcon() is null case.
            drawable = mContext.getDrawable(com.android.internal.R.drawable.ic_bt_headphones_a2dp)
        }
        return drawable
    }

    fun getDeviceIconCompat(device: MediaDevice): IconCompat {
        return BluetoothUtils.createIconWithDrawable(getDeviceIconDrawable(device))
    }

    fun setGroupListCollapsed(isCollapsed: Boolean) {
        mIsGroupListCollapsed = isCollapsed
    }

    fun isGroupListCollapsed(): Boolean = mIsGroupListCollapsed

    fun getAppIcon(): Drawable? = getNotificationSmallIcon() ?: getAppSourceIconFromPackage()

    private fun getNotificationSmallIcon(): Drawable? {
        if (mPackageName.isNullOrEmpty()) {
            return null
        }
        for (entry in mNotifCollection.getAllNotifs()) {
            val notification = entry.sbn.notification
            if (notification.isMediaNotification() && entry.sbn.packageName == mPackageName) {
                val icon = notification.smallIcon
                if (icon == null) {
                    break
                }
                return icon.loadDrawable(mContext)
            }
        }
        return null
    }

    fun getNotificationIcon(): IconCompat? {
        if (mPackageName.isNullOrEmpty()) {
            return null
        }
        for (entry in mNotifCollection.getAllNotifs()) {
            val notification = entry.sbn.notification
            if (notification.isMediaNotification() && entry.sbn.packageName == mPackageName) {
                val icon = notification.getLargeIcon()
                if (icon == null) {
                    break
                }
                return IconCompat.createFromIcon(icon)
            }
        }
        return null
    }

    fun updateCurrentColorScheme(wallpaperColors: WallpaperColors?, isDarkTheme: Boolean) {
        val currentColorScheme = ColorScheme(wallpaperColors, isDarkTheme)
        mMediaOutputColorScheme = fromDynamicColors(currentColorScheme)
    }

    fun getColorScheme(): MediaOutputColorScheme = mMediaOutputColorScheme

    fun refreshDataSetIfNeeded() {
        if (mNeedRefresh) {
            buildMediaItems(mCachedMediaDevices)
            mCallback.onDeviceListChanged()
            mNeedRefresh = false
        }
    }

    private fun buildMediaItems(devices: MutableList<MediaDevice>) {
        synchronized(mMediaDevicesLock) {
            if (!mLocalMediaManager.isPreferenceRouteListingExist()) {
                attachRangeInfo(devices)
                val selectedDevices = devices.filter { it.isSelected() }
                devices.removeAll(selectedDevices)
                devices.sortWith(Comparator.naturalOrder())
                devices.addAll(0, selectedDevices)
            }
            // For the first time building list, to make sure the top device is the connected
            // device.
            val needToHandleMutingExpectedDevice =
                containsMutingExpectedDevice(devices) && !isCurrentConnectedDeviceRemote()
            val connectedMediaDevice =
                if (needToHandleMutingExpectedDevice) null else getCurrentConnectedMediaDevice()
            if (isDeviceListRearrangementAllowed()) {
                // We erase all the items from the previous render so that the sorting and
                // categorization are run from a clean slate.
                mOutputMediaItemListProxy.clear()
            }
            mOutputMediaItemListProxy.updateMediaDevices(
                devices,
                connectedMediaDevice,
                needToHandleMutingExpectedDevice,
            )
        }
    }

    /** Whether it's allowed to change device list order and categories. */
    private fun isDeviceListRearrangementAllowed(): Boolean =
        mClock.elapsedRealtime() - mStartTime <= LIST_CHANGE_ALLOWED_TIMEOUT_MS

    private fun enableInputRouting(): Boolean =
        Flags.enableAudioInputDeviceRoutingAndVolumeControl() &&
            mContext.getResources().getBoolean(R.bool.config_enableInputRouting)

    private fun buildInputMediaItems(devices: List<MediaDevice>) {
        synchronized(mInputMediaDevicesLock) {
            val updatedInputMediaItems = devices.map { device -> DeviceMediaItem(device) }
            mInputMediaItemList.clear()
            mInputMediaItemList.addAll(updatedInputMediaItems)
        }
    }

    fun getConnectedSpeakersExpandableGroupDivider(): MediaItem =
        GroupDividerMediaItem(
            title = mContext.getString(R.string.media_output_group_title_connected_speakers),
            isExpandable = true,
        )

    fun hasGroupPlayback(): Boolean = getSelectedDeviceItems().size > 1

    private fun getSelectedDeviceItems(): List<MediaItem> {
        return mOutputMediaItemListProxy.getOutputMediaItemList().filter { item ->
            item is DeviceMediaItem && item.mediaDevice.isSelected
        }
    }

    fun hasConnectDeviceButton(): Boolean {
        // Show the "Connect Device" button only when current output is not remote and not a group.
        return !isCurrentConnectedDeviceRemote() && !hasGroupPlayback()
    }

    private fun attachRangeInfo(devices: List<MediaDevice>) {
        for (mediaDevice in devices) {
            mNearbyDeviceInfoMap[mediaDevice.id]?.let { mediaDevice.rangeZone = it }
        }
    }

    fun isCurrentConnectedDeviceRemote(): Boolean {
        val currentConnectedMediaDevice = getCurrentConnectedMediaDevice()
        return currentConnectedMediaDevice != null &&
            isActiveRemoteDevice(currentConnectedMediaDevice)
    }

    fun connectDevice(device: MediaDevice) {
        mInfoMediaManager.setDeviceState(device, MediaDeviceState.STATE_CONNECTING)
        // If input routing is supported and the device is an input device, call mInputRouteManager
        // to handle routing.
        if (device is InputMediaDevice) {
            mBackgroundExecutor.execute { mInputRouteManager?.selectDevice(device) }
            return
        }

        mMetricLogger.updateOutputEndPoints(getCurrentConnectedMediaDevice(), device)
        mBackgroundExecutor.execute {
            mLocalMediaManager.connectDevice(
                device,
                RoutingChangeInfo(
                    RoutingChangeInfo.ENTRY_POINT_SYSTEM_OUTPUT_SWITCHER,
                    device.isSuggestedDevice,
                ),
            )
        }
    }

    private fun getOutputDeviceList(): MutableList<MediaItem> {
        val mediaItems: MutableList<MediaItem> =
            mOutputMediaItemListProxy.getOutputMediaItemList().toMutableList()
        addSeparatorForTheFirstGroupDivider(mediaItems)
        coalesceSelectedDevices(mediaItems)
        return mediaItems
    }

    private fun addSeparatorForTheFirstGroupDivider(outputList: MutableList<MediaItem>) {
        for ((i, item) in outputList.withIndex()) {
            if (item is GroupDividerMediaItem) {
                outputList[i] = item.copy(hasTopSeparator = true)
                break
            }
        }
    }

    /**
     * If there are 2+ selected devices, adds an "Connected speakers" expandable group divider and
     * displays a single session control instead of individual device controls.
     */
    private fun coalesceSelectedDevices(outputList: MutableList<MediaItem>) {
        val selectedDevices = getSelectedDeviceItems()

        if (mGroupSelectedItems == true && hasGroupPlayback()) {
            outputList.removeAll(selectedDevices)
            if (isGroupListCollapsed()) {
                outputList.add(0, DeviceGroupMediaItem)
            } else {
                outputList.addAll(0, selectedDevices)
            }
            outputList.add(0, getConnectedSpeakersExpandableGroupDivider())
        }
    }

    private fun addInputDevices(mediaItems: MutableList<MediaItem>) {
        mediaItems.add(
            GroupDividerMediaItem(title = mContext.getString(R.string.media_input_group_title))
        )
        mediaItems.addAll(mInputMediaItemList)
    }

    private fun addOutputDevices(mediaItems: MutableList<MediaItem>) {
        mediaItems.add(
            GroupDividerMediaItem(title = mContext.getString(R.string.media_output_group_title))
        )
        mediaItems.addAll(getOutputDeviceList())
    }

    /** Returns a list of media items to be rendered in the device list. */
    fun getMediaItemList(): List<MediaItem> {
        return when (mMediaSwitchingType) {
            MediaSwitchingType.OUTPUT -> getOutputDeviceList()
            MediaSwitchingType.INPUT -> mInputMediaItemList
            MediaSwitchingType.ALL -> {
                val mediaItems: MutableList<MediaItem> = mutableListOf()
                addOutputDevices(mediaItems)
                addInputDevices(mediaItems)
                mediaItems
            }
        }
    }

    private fun getMissingPermissionsInfo(): MissingPermissionsInfo? {
        if (!accessLocalNetworkPermissionEnabled()) {
            return null
        }
        val permissionsInfo = mLocalMediaManager.getMissingPermissionsInfo()
        if (permissionsInfo == null || permissionsInfo.permissions.isEmpty()) {
            return null
        }
        return permissionsInfo
    }

    /**
     * Returns info on a warning message to resolve missing permissions if UI should be shown to
     * prompt the user to resolve permissions, or null if the UI should not be shown.
     */
    fun getMissingPermissionsWarning(): MissingPermissionsWarning? {
        val permissionsInfo = getMissingPermissionsInfo() ?: return null
        val pm =
            if (fixOutputSwitcherMultiuserSupport() && userHandle != null) {
                getPackageManagerForUser(userHandle)
            } else {
                val userHandle = mLocalMediaManager.userHandle
                mContext.createContextAsUser(userHandle, 0).packageManager
            }
        val appName =
            getAppName(
                pm,
                permissionsInfo.componentName.packageName,
                defaultName = permissionsInfo.componentName.packageName,
            )
        return MissingPermissionsWarning(appName)
    }

    private fun getMissingPermissionsResolveIntent(): Intent? {
        val permissionsInfo = getMissingPermissionsInfo() ?: return null
        return Intent(RouteListingPreference.ACTION_RESOLVE_MISSING_PERMISSIONS).apply {
            component = permissionsInfo.componentName
            putStringArrayListExtra(
                RouteListingPreference.EXTRA_MISSING_PERMISSIONS,
                ArrayList<String?>(permissionsInfo.permissions),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getCurrentConnectedMediaDevice(): MediaDevice? =
        mLocalMediaManager.getCurrentConnectedDevice()

    /**
     * Returns whether the input device is reported by system as connected or if it's selected in a
     * non group playback context.
     */
    fun isSingleConnectedDevice(device: MediaDevice): Boolean =
        (device.getId() == getCurrentConnectedMediaDevice()?.getId()) ||
            (!hasGroupPlayback() && device.isSelected())

    @VisibleForTesting
    fun clearMediaItemList() {
        mOutputMediaItemListProxy.clear()
    }

    fun addDeviceToPlayMedia(device: MediaDevice) {
        mMetricLogger.logInteractionExpansion(device)
        val routingChangeInfo =
            RoutingChangeInfo(
                RoutingChangeInfo.ENTRY_POINT_SYSTEM_OUTPUT_SWITCHER,
                device.isSuggestedDevice,
            )
        if (Flags.moveOutputSwitcherCallsToBackgroundThread()) {
            mBackgroundExecutor.execute {
                mLocalMediaManager.addDeviceToPlayMedia(device, routingChangeInfo)
            }
        } else {
            mLocalMediaManager.addDeviceToPlayMedia(device, routingChangeInfo)
        }
    }

    fun removeDeviceFromPlayMedia(device: MediaDevice) {
        mMetricLogger.logInteractionContraction(device)
        val routingChangeInfo =
            RoutingChangeInfo(
                RoutingChangeInfo.ENTRY_POINT_SYSTEM_OUTPUT_SWITCHER,
                device.isSuggestedDevice,
            )
        if (Flags.moveOutputSwitcherCallsToBackgroundThread()) {
            mBackgroundExecutor.execute {
                mLocalMediaManager.removeDeviceFromPlayMedia(device, routingChangeInfo)
            }
        } else {
            mLocalMediaManager.removeDeviceFromPlayMedia(device, routingChangeInfo)
        }
    }

    fun adjustSessionVolume(volume: Int) {
        if (Flags.moveOutputSwitcherCallsToBackgroundThread()) {
            mBackgroundExecutor.execute { mLocalMediaManager.adjustSessionVolume(volume) }
        } else {
            mLocalMediaManager.adjustSessionVolume(volume)
        }
    }

    fun getSessionVolumeMax(): Int = mLocalMediaManager.getSessionVolumeMax()

    fun getSessionVolume(): Int = mLocalMediaManager.getSessionVolume()

    fun getSessionName(): CharSequence? = mLocalMediaManager.getSessionName()

    @RoutingSessionInfo.ReleaseType
    fun getSessionReleaseType(): Int = mLocalMediaManager.getSessionReleaseType()

    fun releaseSession() {
        if (getSessionReleaseType() == RoutingSessionInfo.RELEASE_TYPE_SHARING) {
            mMetricLogger.logInteractionStopSharing()
        } else {
            mMetricLogger.logInteractionStopCasting()
        }
        if (Flags.moveOutputSwitcherCallsToBackgroundThread()) {
            mBackgroundExecutor.execute { mLocalMediaManager.releaseSession() }
        } else {
            mLocalMediaManager.releaseSession()
        }
    }

    fun getActiveRemoteMediaDevices(): List<RoutingSessionInfo?> =
        mLocalMediaManager.getRemoteRoutingSessions().toList()

    fun adjustVolume(device: MediaDevice, volume: Int) {
        mBackgroundExecutor.execute { mLocalMediaManager.adjustDeviceVolume(device, volume) }
    }

    fun logInteractionAdjustVolume(device: MediaDevice) {
        mMetricLogger.logInteractionAdjustVolume(device)
    }

    fun hasAdjustVolumeUserRestriction(): Boolean = mHasAdjustVolumeUserRestriction

    private fun checkIfAdjustVolumeRestrictionEnforced(): Boolean {
        if (
            RestrictedLockUtilsInternal.checkIfRestrictionEnforced(
                mContext,
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserHandle.myUserId(),
            ) != null
        ) {
            return true
        }
        val um = mContext.getSystemService<UserManager>()
        return um?.hasBaseUserRestriction(
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserHandle.of(UserHandle.myUserId()),
        ) == true
    }

    fun isAnyDeviceTransferring(): Boolean {
        synchronized(mMediaDevicesLock) {
            for (item in mOutputMediaItemListProxy.getOutputMediaItemList()) {
                if (
                    item is DeviceMediaItem &&
                        item.mediaDevice.state == MediaDeviceState.STATE_CONNECTING
                ) {
                    return true
                }
            }
        }
        return false
    }

    fun launchBluetoothPairing(view: View) {
        val controller = mDialogTransitionAnimator.createActivityTransitionController(view)

        if (controller == null || (mKeyGuardManager?.isKeyguardLocked == true)) {
            mCallback.dismissDialog()
        }

        val launchIntent =
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val deepLinkIntent = Intent(Settings.ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)
        val packageManager =
            if (fixOutputSwitcherMultiuserSupport()) {
                getPackageManagerForUser(mUserTracker.userHandle)
            } else {
                mContext.getPackageManager()
            }
        if (deepLinkIntent.resolveActivity(packageManager) != null) {
            Log.d(TAG, "Device support split mode, launch page with deep link")
            with(deepLinkIntent) {
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI,
                    launchIntent.toUri(Intent.URI_INTENT_SCHEME),
                )
                putExtra(
                    Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY,
                    PAGE_CONNECTED_DEVICES_KEY,
                )
            }
            startActivity(deepLinkIntent, controller)
            return
        }
        startActivity(launchIntent, controller)
    }

    fun launchAudioSharing(view: View) {
        val controller = mDialogTransitionAnimator.createActivityTransitionController(view)

        if (controller == null || (mKeyGuardManager?.isKeyguardLocked == true)) {
            mCallback.dismissDialog()
        }

        val launchIntent = Intent(ACTION_AUDIO_SHARING)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val bundle = Bundle()
        bundle.putBoolean(LocalBluetoothLeBroadcast.EXTRA_START_LE_AUDIO_SHARING, true)
        launchIntent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        startActivity(launchIntent, controller)
    }

    fun setTemporaryAllowListExceptionIfNeeded() {
        if (mPowerExemptionManager == null || mPackageName == null) {
            Log.w(TAG, "powerExemptionManager or package name is null")
            return
        }
        mPowerExemptionManager.addToTemporaryAllowList(
            mPackageName,
            PowerExemptionManager.REASON_MEDIA_NOTIFICATION_TRANSFER,
            ALLOWLIST_REASON,
            ALLOWLIST_DURATION_MS,
        )
    }

    fun isActiveRemoteDevice(device: MediaDevice): Boolean {
        val features = device.getFeatures()
        return (features.contains(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK) ||
            features.contains(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK) ||
            features.contains(MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK) ||
            features.contains(MediaRoute2Info.FEATURE_REMOTE_GROUP_PLAYBACK))
    }

    fun isPlaying(): Boolean = mMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun isVolumeControlEnabled(device: MediaDevice): Boolean = !device.isVolumeFixed()

    fun isVolumeControlEnabledForSession(): Boolean =
        mLocalMediaManager.isMediaSessionAvailableForVolumeControl()

    fun isExpandedAudioTileDetailsFeatureEnabled(): Boolean =
        mExpandedAudioTileDetailsFeatureInteractor.isEnabled()

    /**
     * Determines and gets the audio sharing button state.
     *
     * This function indicates visible status only when the device is audio sharing (broadcasting)
     * or has a remote Bluetooth device connected on Bluetooth LE Audio Assistant profile.
     *
     * @return non-null [AudioSharingButtonState] if the device is in audio sharing or ready for
     *   audio sharing, else null.
     */
    fun getAudioSharingButtonState(): AudioSharingButtonState? {
        if (mMediaSwitchingType == MediaSwitchingType.INPUT) {
            return null
        }
        if (mInAudioSharing) {
            return AudioSharingButtonState(
                resId = R.string.media_output_dialog_button_sharing_audio,
                isActive = true,
            )
        } else if (
            mLocalBluetoothManager != null &&
                BluetoothUtils.hasConnectedBroadcastAssistantDevice(mLocalBluetoothManager)
        ) {
            return AudioSharingButtonState(
                resId = R.string.media_output_dialog_button_share_audio,
                isActive = false,
            )
        }

        return null
    }

    @StringRes
    fun getStopButtonStringRes(): Int? {
        if (mMediaSwitchingType == MediaSwitchingType.INPUT) {
            return null
        }

        return when (getSessionReleaseType()) {
            RoutingSessionInfo.RELEASE_TYPE_SHARING ->
                R.string.media_output_dialog_button_stop_sharing
            RoutingSessionInfo.RELEASE_TYPE_CASTING ->
                R.string.media_output_dialog_button_stop_casting
            else -> null
        }
    }

    /**
     * Starts an activity within the media application. It explicitly sets the userHandle of that
     * application.
     */
    private fun startMediaAppActivity(
        intent: Intent,
        animationController: ActivityTransitionAnimator.Controller? = null,
    ) {
        startActivity(intent, animationController, userHandle)
    }

    private fun startActivity(
        intent: Intent,
        animationController: ActivityTransitionAnimator.Controller? = null,
        userHandle: UserHandle? = null,
    ) {
        // Media Output dialog can be shown from the volume panel. This makes sure the panel is
        // closed when navigating to another activity, so it doesn't stay on top of it
        mVolumePanelGlobalStateInteractor.setVisible(false)
        if (fixOutputSwitcherMultiuserSupport()) {
            mActivityStarter.startActivity(
                intent,
                /* dismissShade= */ true,
                animationController,
                /* showOverLockscreenWhenLocked= */ false,
                userHandle,
            )
        } else {
            mActivityStarter.startActivity(intent, /* dismissShade= */ true, animationController)
        }
    }

    @Throws(RemoteException::class)
    override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {
        mNearbyDeviceInfoMap.clear()
        for (nearbyDevice in nearbyDevices) {
            mNearbyDeviceInfoMap.put(nearbyDevice.mediaRoute2Id, nearbyDevice.rangeZone)
        }
        mNearbyMediaDevicesManager?.unregisterNearbyDevicesCallback(this)
    }

    override fun asBinder(): IBinder? {
        return null
    }

    @JvmField
    @VisibleForTesting
    val mCb: MediaController.Callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                mCallback.onMediaChanged()
            }

            override fun onPlaybackStateChanged(playbackState: PlaybackState?) {
                val newState = playbackState?.state ?: PlaybackState.STATE_STOPPED
                if (mCurrentState == newState) {
                    return
                }

                // Ignore the initial delivery: with a null playbackState at registration
                // mCurrentState stays STATE_NONE, and dismissing here would close the dialog for a
                // session already stopped rather than one that stopped while it was open.
                if (newState == PlaybackState.STATE_STOPPED &&
                        mCurrentState != PlaybackState.STATE_NONE
                ) {
                    mCallback.onMediaStoppedOrPaused()
                }
                mCurrentState = newState
            }
        }

    interface Callback {
        /** Override to handle the media content updating. */
        fun onMediaChanged()

        /** Override to handle the media state updating. */
        fun onMediaStoppedOrPaused()

        /** Override to handle the device status or attributes updating. */
        fun onRouteChanged()

        /** Override to handle the devices set updating. */
        fun onDeviceListChanged()

        /** Override to dismiss dialog. */
        fun dismissDialog()

        /** Override to handle quick access button changes. */
        fun onQuickAccessButtonsChanged()
    }

    companion object {
        private const val TAG = "MediaSwitchingController"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val PAGE_CONNECTED_DEVICES_KEY = "top_level_connected_devices"
        private const val ALLOWLIST_DURATION_MS: Long = 20000
        private const val LIST_CHANGE_ALLOWED_TIMEOUT_MS: Long = 2000
        private const val ALLOWLIST_REASON = "mediaoutput:remote_transfer"
        private const val ACTION_AUDIO_SHARING =
            "com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    }
}
