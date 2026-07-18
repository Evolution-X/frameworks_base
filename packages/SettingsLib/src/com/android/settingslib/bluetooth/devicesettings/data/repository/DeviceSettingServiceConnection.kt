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

package com.android.settingslib.bluetooth.devicesettings.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.text.TextUtils
import android.util.Log
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingConfigOptions
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfigServiceStatus
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService
import com.android.settingslib.bluetooth.devicesettings.IGetDeviceSettingsConfigCallback
import com.android.settingslib.bluetooth.devicesettings.data.model.ServiceConnectionStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSettingServiceConnection(
    private val cachedDevice: CachedBluetoothDevice,
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
) {
    data class EndPoint(
        private val packageName: String?,
        private val className: String?,
        private val intentAction: String?,
        private val isOptional: Boolean,
    ) {
        fun toIntent(): Intent? {
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(intentAction)) {
                return null
            }
            return Intent().apply {
                if (className.isNullOrBlank()) {
                    setPackage(packageName)
                } else {
                    setClassName(packageName!!, className)
                }
                setAction(intentAction)
            }
        }

        fun isOptional(): Boolean {
            return isOptional
        }
    }

    private var isServiceEnabled =
        coroutineScope.async(backgroundCoroutineContext, start = CoroutineStart.LAZY) {
            val states =
                getSettingsProviderServices(includeOptional = false)?.values ?: return@async false
            if (states.isEmpty()) {
                return@async true
            }
            combine(states) { it.toList() }
                .mapNotNull { allStatus ->
                    if (allStatus.any { it is ServiceConnectionStatus.Failed }) {
                        false
                    } else if (allStatus.all { it is ServiceConnectionStatus.Connected }) {
                        allStatus
                            .filterIsInstance<
                                ServiceConnectionStatus.Connected<IDeviceSettingsProviderService>
                            >()
                            .all { it.service.serviceStatus?.enabled == true }
                    } else {
                        null
                    }
                }
                .retryWhen { cause, attempt -> cause is DeadObjectException || attempt < 2 }
                .catch { e ->
                    if (e is DeadObjectException) {
                        Log.e(TAG, "DeadObjectException happens when try to get service status.", e)
                        emit(false)
                    } else {
                        throw e
                    }
                }
                .firstOrNull() ?: false
        }

    private var config =
        coroutineScope.async(backgroundCoroutineContext, start = CoroutineStart.LAZY) {
            val intent =
                tryGetEndpointFromMetadata(cachedDevice)?.toIntent()
                    ?: run {
                        Log.i(TAG, "Unable to read device setting metadata from $cachedDevice")
                        return@async null
                    }
            getService(intent, IDeviceSettingsConfigProviderService.Stub::asInterface)
                .flatMapConcat {
                    when (it) {
                        is ServiceConnectionStatus.Connected ->
                            flowOf(
                                getDeviceSettingsConfigFromService(
                                        deviceInfo { setBluetoothAddress(cachedDevice.address) },
                                        it.service,
                                        DeviceSettingConfigOptions.Builder()
                                            .setOptionalItemSupported(true)
                                            .build(),
                                    )
                                    .also { config ->
                                        Log.i(
                                            TAG,
                                            "device setting config for $cachedDevice is ${
                                                config?.run {
                                                    (mainContentItems + moreSettingsItems +
                                                            listOfNotNull(
                                                                moreSettingsHelpItem
                                                            )).joinToString {
                                                        item -> item.settingId.toString()
                                                    }
                                                } ?: "null"
                                            }",
                                        )
                                    }
                            )
                        is ServiceConnectionStatus.Connecting -> flowOf()
                        is ServiceConnectionStatus.Failed -> flowOf(null)
                    }
                }
                .first()
        }

    private val services =
        ConcurrentHashMap<
            EndPoint,
            StateFlow<ServiceConnectionStatus<IDeviceSettingsProviderService>>,
        >()
    private val serviceDeviceSettingsMapping =
        ConcurrentHashMap<IDeviceSettingsProviderService, Flow<List<DeviceSetting>>>()

    private suspend fun getDeviceSettingsConfigFromService(
        deviceInfo: DeviceInfo,
        service: IDeviceSettingsConfigProviderService,
        options: DeviceSettingConfigOptions,
    ): DeviceSettingsConfig? = suspendCancellableCoroutine { continuation ->
        try {
            service.getDeviceSettingsConfigWithOptions(
                deviceInfo,
                object : IGetDeviceSettingsConfigCallback.Stub() {
                    override fun onResult(
                        status: DeviceSettingsConfigServiceStatus,
                        config: DeviceSettingsConfig?,
                    ) {
                        if (!status.success) {
                            continuation.resume(null)
                        } else {
                            continuation.resume(config)
                        }
                    }
                },
                options,
            )
        } catch (e: RemoteException) {
            Log.i(TAG, "Fail to get config", e)
            continuation.resume(null)
        } catch (e: RuntimeException) {
            // GMS Fast Pair rejects unsanctioned callers with SecurityException across the binder —
            // an unchecked RuntimeException, not RemoteException. Unhandled it escapes the coroutine
            // and tears SystemUI down, so treat a rejecting provider as "no config".
            Log.w(TAG, "Provider rejected config request, treating as no config", e)
            continuation.resume(null)
        }
    }

    private suspend fun waitUntilNonOptionalProviderReady() {
        if (!isServiceEnabled.await()) {
            Log.w(TAG, "Service is disabled")
            return
        }
        val services = getSettingsProviderServices(includeOptional = false)?.values
        if (services == null || services.isEmpty()) {
            return
        }
        services
            .map { getSettingsFromStatus(it) }
            .let { items -> combine(items) { it.toList().flatten() } }
            .first()
    }

    private val settingIdToItemMapping =
        flow {
                if (!isServiceEnabled.await()) {
                    Log.w(TAG, "Service is disabled")
                    return@flow
                }
                val services = getSettingsProviderServices(includeOptional = true)?.values
                if (services == null || services.isEmpty()) {
                    emit(mapOf())
                    return@flow
                }
                services
                    .map { getSettingsFromStatus(it).onStart { emit(emptyList()) } }
                    .let { items -> combine(items) { it.toList().flatten() } }
                    .map { items -> items.associateBy { it.settingId } }
                    .let { emitAll(it) }
            }
            .shareIn(scope = coroutineScope, started = SharingStarted.WhileSubscribed(), replay = 1)

    /** Gets [DeviceSettingsConfig] for the device, return null when failed. */
    suspend fun getDeviceSettingsConfig(): DeviceSettingsConfig? {
        if (!isServiceEnabled.await()) {
            Log.w(TAG, "Service is disabled")
            return null
        }
        // Wait until all settings providers are ready.
        waitUntilNonOptionalProviderReady()
        return readConfig()
    }

    /** Gets the device settings with the ID for the device. */
    fun getDeviceSetting(@DeviceSettingId deviceSettingId: Int): Flow<DeviceSetting?> =
        settingIdToItemMapping.map { it[deviceSettingId] }

    /** Updates the device setting state for the device. */
    suspend fun updateDeviceSettings(
        @DeviceSettingId deviceSettingId: Int,
        deviceSettingPreferenceState: DeviceSettingPreferenceState,
    ) {
        if (!isServiceEnabled.await()) {
            Log.w(TAG, "Service is disabled")
            return
        }
        readConfig()?.let { config ->
            (config.mainContentItems + config.moreSettingsItems)
                .find { it.settingId == deviceSettingId }
                ?.let {
                    getSettingsProviderServices(includeOptional = true)
                        ?.get(
                            EndPoint(it.packageName, it.className, it.intentAction, it.isOptional)
                        )
                        ?.filterIsInstance<
                            ServiceConnectionStatus.Connected<IDeviceSettingsProviderService>
                        >()
                        ?.first()
                }
                ?.service
                ?.updateDeviceSettings(
                    deviceInfo { setBluetoothAddress(cachedDevice.address) },
                    DeviceSettingState.Builder()
                        .setSettingId(deviceSettingId)
                        .setPreferenceState(deviceSettingPreferenceState)
                        .build(),
                )
        }
    }

    private suspend fun readConfig(): DeviceSettingsConfig? = config.await()

    private var settingsProviderServices =
        coroutineScope.async(backgroundCoroutineContext, start = CoroutineStart.LAZY) {
            distinctEndPoints()
                ?.mapNotNull { endpoint ->
                    endpoint.toIntent()?.let { intent ->
                        Pair(
                            endpoint,
                            services.computeIfAbsent(endpoint) {
                                getService(intent, IDeviceSettingsProviderService.Stub::asInterface)
                                    .stateIn(
                                        coroutineScope.plus(backgroundCoroutineContext),
                                        SharingStarted.WhileSubscribed(
                                            stopTimeoutMillis = SERVICE_CONNECTION_STOP_MILLIS
                                        ),
                                        ServiceConnectionStatus.Connecting,
                                    )
                            },
                        )
                    }
                }
                ?.toMap()
        }

    private suspend fun getSettingsProviderServices(
        includeOptional: Boolean
    ): Map<EndPoint, StateFlow<ServiceConnectionStatus<IDeviceSettingsProviderService>>>? =
        settingsProviderServices.await()?.filter { includeOptional || !it.key.isOptional() }

    private suspend fun distinctEndPoints(): List<EndPoint>? {
        val endPoints =
            readConfig()?.let { config ->
                (config.mainContentItems + config.moreSettingsItems).map {
                    EndPoint(
                        packageName = it.packageName,
                        className = it.className,
                        intentAction = it.intentAction,
                        isOptional = it.isOptional,
                    )
                }
            }
        if (endPoints == null) {
            return null
        }
        // If an item is non-optional, then its service should be non-optional even if other items
        // of the same service may be optional.
        val nonOptionalEndPointIntents =
            endPoints.stream().filter { !it.isOptional() }.map { it.toIntent() }.toList().toSet()
        return endPoints
            .filter { !it.isOptional() || !nonOptionalEndPointIntents.contains(it.toIntent()) }
            .distinct()
    }

    private fun getSettingsFromStatus(
        statusFlow: Flow<ServiceConnectionStatus<IDeviceSettingsProviderService>>
    ): Flow<List<DeviceSetting>> =
        statusFlow
            .filterIsInstance<ServiceConnectionStatus.Connected<IDeviceSettingsProviderService>>()
            .flatMapLatest { getDeviceSettingsFromService(it.service) }

    private fun getDeviceSettingsFromService(
        service: IDeviceSettingsProviderService
    ): Flow<List<DeviceSetting>> {
        return serviceDeviceSettingsMapping.computeIfAbsent(service) {
            callbackFlow {
                    val listener =
                        object : IDeviceSettingsListener.Stub() {
                            override fun onDeviceSettingsChanged(settings: List<DeviceSetting>) {
                                Log.i(TAG, "Receive setting ids ${settings.map { it.settingId }}")
                                launch { send(settings) }
                            }
                        }
                    val deviceInfo = deviceInfo {
                        setBluetoothAddress(cachedDevice.address)
                        setExtras(
                            Bundle().apply {
                                putLong(
                                    BluetoothUtils.CONNECTION_FAILURE_TIME_KEY,
                                    cachedDevice.connectionFailureTimeMillis,
                                )
                            }
                        )
                    }
                    service.registerDeviceSettingsListener(deviceInfo, listener)
                    awaitClose { service.unregisterDeviceSettingsListener(deviceInfo, listener) }
                }
                .catch { e ->
                    if (e is DeadObjectException) {
                        Log.e(TAG, "DeadObjectException happens when registering listener.", e)
                        emit(listOf())
                    } else {
                        throw e
                    }
                }
                .shareIn(
                    coroutineScope,
                    SharingStarted.WhileSubscribed(
                        stopTimeoutMillis = SERVICE_CONNECTION_STOP_MILLIS
                    ),
                    replay = 1,
                )
        }
    }

    private fun <T : IInterface> getService(
        intent: Intent,
        transform: ((IBinder) -> T),
    ): Flow<ServiceConnectionStatus<T>> {
        return callbackFlow {
                val serviceConnection =
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            Log.i(TAG, "Service connected for $intent")
                            launch { send(ServiceConnectionStatus.Connected(transform(service))) }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            Log.i(TAG, "Service disconnected for $intent")
                            launch { send(ServiceConnectionStatus.Connecting) }
                        }
                    }
                Log.i(TAG, "Try to bind service for $intent")
                if (
                    !context.bindService(
                        intent,
                        Context.BIND_AUTO_CREATE,
                        { launch { it.run() } },
                        serviceConnection,
                    )
                ) {
                    Log.w(TAG, "Fail to bind service $intent")
                    launch { send(ServiceConnectionStatus.Failed) }
                }
                awaitClose {
                    try {
                        context.unbindService(serviceConnection)
                    } catch (e: IllegalArgumentException) {
                        // The binding may already be gone (racing provider teardown), so
                        // unbindService reports it as never registered. Benign.
                        Log.w(TAG, "Service already unbound for $intent", e)
                    }
                }
            }
            .flowOn(backgroundCoroutineContext)
    }

    private suspend fun tryGetEndpointFromMetadata(cachedDevice: CachedBluetoothDevice): EndPoint? =
        withContext(backgroundCoroutineContext) {
            val packageName =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_PACKAGE_NAME,
                ) ?: return@withContext null
            val className =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_CLASS_NAME,
                ) ?: return@withContext null
            val intentAction =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_INTENT_ACTION,
                ) ?: return@withContext null
            EndPoint(packageName, className, intentAction, false)
        }

    private inline fun deviceInfo(block: DeviceInfo.Builder.() -> Unit): DeviceInfo {
        return DeviceInfo.Builder().apply { block() }.build()
    }

    companion object {
        const val TAG = "DeviceSettingSrvConn"
        const val METADATA_FAST_PAIR_CUSTOMIZED_FIELDS: Int = 25
        const val CONFIG_SERVICE_PACKAGE_NAME = "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME"
        const val CONFIG_SERVICE_CLASS_NAME = "DEVICE_SETTINGS_CONFIG_CLASS"
        const val CONFIG_SERVICE_INTENT_ACTION = "DEVICE_SETTINGS_CONFIG_ACTION"
        const val SERVICE_CONNECTION_STOP_MILLIS = 1000L
    }
}
