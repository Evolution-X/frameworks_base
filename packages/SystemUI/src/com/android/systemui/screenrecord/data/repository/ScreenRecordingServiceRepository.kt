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

package com.android.systemui.screenrecord.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.media.projection.StopReason
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.Display
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.flow.stateInTraced
import com.android.app.tracing.coroutines.launchInTraced
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screencapture.record.shared.ScreenRecordingLogger
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.service.IScreenRecordingService
import com.android.systemui.screenrecord.service.IScreenRecordingServiceCallback
import com.android.systemui.screenrecord.service.ScreenRecordingService
import com.android.systemui.screenrecord.shared.model.ScreenRecording
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus.Started
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus.Starting
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus.Stopped
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.pairwiseBy
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

private val startingStatusUpdateInterval: Duration = 1.seconds
private const val TAG = "ScreenRecordingServiceRepository"

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ScreenRecordingServiceRepository
@VisibleForTesting
constructor(
    coroutineScope: CoroutineScope,
    private val screenRecordUxController: ScreenRecordUxController,
    private val serviceFlow: Flow<IScreenRecordingService?>,
    private val logger: ScreenRecordingLogger,
) : ScreenRecordingStartStopRepository, ScreenRecordRepository {

    @Inject
    constructor(
        context: Context,
        @Background coroutineScope: CoroutineScope,
        userRepository: UserRepository,
        screenRecordUxController: ScreenRecordUxController,
        logger: ScreenRecordingLogger,
    ) : this(
        coroutineScope = coroutineScope,
        screenRecordUxController = screenRecordUxController,
        serviceFlow = bindServiceAsAFlow(context, userRepository),
        logger = logger,
    )

    private val serviceCallback = ServiceCallback()
    private val isServiceBound = MutableStateFlow(false)
    private val service: Flow<RecordingService?> =
        isServiceBound
            .onEach { currentIsServiceBound ->
                if (!currentIsServiceBound) {
                    stopRecording(StopReason.STOP_ERROR)
                }
            }
            .flatMapLatest { currentIsServiceBound ->
                if (currentIsServiceBound) serviceFlow else flowOf(null)
            }
            .pairwiseBy { old: IScreenRecordingService?, new: IScreenRecordingService? ->
                old?.safeBinderCall { setCallback(null) }
                if (new == null) {
                    // The service died. Update isServiceBound to match its state
                    isServiceBound.value = false
                    statusUpdates.update { currentStatus ->
                        if (currentStatus is Started) {
                            Stopped(StopReason.STOP_ERROR)
                        } else {
                            currentStatus
                        }
                    }
                } else {
                    if (!new.safeBinderCall { setCallback(serviceCallback) }) {
                        isServiceBound.value = false
                    }
                }
                new
            }
            .map { it?.let(::RecordingService) }
            .stateInTraced(
                "ScreenRecordingServiceInteractor#service",
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                null,
            )

    private val statusUpdates =
        MutableStateFlow<ScreenRecordingStatus>(Stopped(Stopped.STOP_REASON_NOT_STARTED))

    /** @see ScreenRecordingStatus */
    val status: StateFlow<ScreenRecordingStatus> =
        statusUpdates
            .flatMapLatest { status ->
                if (status is Starting) {
                    logger.startingScreenRecording(status.parameters, status.untilStarted)
                    countDownFlow(
                        duration = status.untilStarted,
                        onTick = { status.copy(untilStarted = it) },
                        onFinished = { startRecording(status.parameters) },
                    )
                } else {
                    flowOf(status)
                }
            }
            .stateInTraced(
                name = "ScreenRecordingServiceInteractor#status",
                scope = coroutineScope,
                started = SharingStarted.Eagerly,
                initialValue = Stopped(Stopped.STOP_REASON_NOT_STARTED),
            )

    @Deprecated(message = "Use status")
    override val screenRecordState: Flow<ScreenRecordModel> =
        status.map {
            when (it) {
                is Starting -> ScreenRecordModel.Starting(it.untilStarted.inWholeMilliseconds)
                is Started -> ScreenRecordModel.Recording
                is Stopped -> ScreenRecordModel.DoingNothing
            }
        }

    private val _screenRecording = MutableStateFlow<ScreenRecording?>(null)
    val screenRecordings: Flow<ScreenRecording> = _screenRecording.filterNotNull()

    init {
        combine(
                status.onEach { currentStatus ->
                    if (currentStatus is Started) {
                        isServiceBound.value = true
                    }
                },
                service,
            ) { currentStatus, currentService ->
                RecordingContext(status = currentStatus, service = currentService)
            }
            .onEach { recordingContext ->
                with(recordingContext) {
                    if (service != null) {
                        when (status) {
                            is Started -> {
                                val parameters = status.parameters
                                if (service.isRecording) {
                                    service.updateParameters(parameters)
                                } else {
                                    service.startRecording(parameters)
                                    screenRecordUxController.updateState(true)
                                    logger.startScreenRecording(parameters)
                                }
                            }
                            is Stopped -> {
                                service.stopRecording(status.reason)
                                screenRecordUxController.updateState(false)
                                logger.stopScreenRecording(status.reason)
                            }
                            is Starting -> {
                                /* do nothing */
                            }
                        }
                    }
                }
            }
            .catch { Log.e(TAG, "Couldn't reach the service", it) }
            .launchInTraced("ScreenRecordingServiceInteractor#_status", coroutineScope)
    }

    /** Starts the recording after the [delay]. */
    fun startRecordingDelayed(parameters: ScreenRecordingParameters, delay: Duration) {
        statusUpdates.update { currentStatus ->
            if (currentStatus is Starting || currentStatus is Started) {
                currentStatus
            } else {
                Starting(untilStarted = delay, parameters = parameters)
            }
        }
    }

    override fun startRecording(parameters: ScreenRecordingParameters) {
        require(parameters.displayId != Display.INVALID_DISPLAY) { "Provide a valid displayId" }
        statusUpdates.update { currentStatus -> currentStatus as? Started ?: Started(parameters) }
    }

    override fun stopRecording(@StopReason reason: Int) {
        statusUpdates.update { currentStatus -> currentStatus as? Stopped ?: Stopped(reason) }
    }

    /** Update parameters of an ongoing recording */
    fun updateParameters(update: ScreenRecordingParameters.() -> ScreenRecordingParameters) {
        statusUpdates.update { currentStatus ->
            if (currentStatus is Started) {
                currentStatus.copy(parameters = currentStatus.parameters.update())
            } else {
                currentStatus
            }
        }
    }

    private inner class ServiceCallback : IScreenRecordingServiceCallback.Stub() {

        override fun onRecordingStarted() {}

        override fun onRecordingInterrupted(userId: Int, reason: Int) {
            stopRecording(reason)
        }

        override fun onSavingRecording(recordingUri: Uri, notificationId: Int) {
            _screenRecording.value =
                ScreenRecording.Saving(uri = recordingUri, notificationId = notificationId)
        }

        override fun onRecordingSaved(recordingUri: Uri, thumbnail: Icon, notificationId: Int) {
            _screenRecording.value =
                ScreenRecording.Saved(
                    uri = recordingUri,
                    thumbnail = thumbnail,
                    notificationId = notificationId,
                )
            isServiceBound.value = false
        }

        override fun onRecordingSaveError(recordingUri: Uri?, notificationId: Int) {
            _screenRecording.value =
                ScreenRecording.NotSaved(uri = recordingUri, notificationId = notificationId)
            isServiceBound.value = false
        }
    }

    companion object {

        /**
         * @return a cold flow that binds to the [IScreenRecordingService] on each collection.
         */
        @VisibleForTesting
        fun bindServiceAsAFlow(
            context: Context,
            userRepository: UserRepository,
            mapServiceBinder: (name: ComponentName?, service: IBinder?) -> IScreenRecordingService =
                { _, service ->
                    IScreenRecordingService.Stub.asInterface(service)
                },
        ): Flow<IScreenRecordingService?> = conflatedCallbackFlow {
            val userHandle = userRepository.selectedUser.value.userInfo.userHandle
            val userContext = context.createContextAsUser(userHandle, 0)
            val newIntent = Intent(userContext, ScreenRecordingService::class.java)
            val connection = ProducingConnection(mapServiceBinder)
            val isBound =
                userContext.bindService(
                    newIntent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            awaitClose {
                if (isBound) {
                    userContext.unbindService(connection)
                }
            }
        }
    }

    private data class RecordingService(private val service: IScreenRecordingService?) {

        var isRecording: Boolean = false
            private set

        fun updateParameters(parameters: ScreenRecordingParameters) {
            require(isRecording)
            service?.updateParameters(parameters)
        }

        fun startRecording(parameters: ScreenRecordingParameters) {
            service?.startRecording(parameters)
            isRecording = true
        }

        fun stopRecording(reason: Int) {
            service?.stopRecording(reason)
            isRecording = false
        }
    }

    private data class RecordingContext(
        val status: ScreenRecordingStatus,
        val service: RecordingService?,
    )
}

/**
 * Returns a flow that emits a [onTick] value every [intervalDuration] for the [duration] amount of
 * time and then finishes with [onFinished] value.
 */
private fun <T> countDownFlow(
    duration: Duration,
    intervalDuration: Duration = startingStatusUpdateInterval,
    onTick: (millisLeft: Duration) -> T,
    onFinished: () -> Unit,
): Flow<T> = flow {
    val intervalInMillis = intervalDuration.inWholeMilliseconds
    var millisLeft = duration.inWholeMilliseconds
    while (millisLeft > 0 && currentCoroutineContext().isActive) {
        emit(onTick(millisLeft.milliseconds))
        val delayDuration = millisLeft.coerceAtMost(intervalInMillis)
        millisLeft -= delayDuration
        delay(delayDuration)
    }
    emit(onTick(0.milliseconds))
    onFinished()
}

/**
 * Executes an IPC call safely, catching [RemoteException]. Adding this try-catch to fix
 * b/483091461.
 *
 * @return true if the call succeeded, false if a RemoteException occurred or the object was null.
 */
private inline fun <T> T.safeBinderCall(block: T.() -> Unit): Boolean {
    if (this == null) return false
    return try {
        block()
        true
    } catch (e: RemoteException) {
        Log.e(TAG, "Binder process died during call", e)
        false
    }
}

private fun ProducerScope<IScreenRecordingService?>.ProducingConnection(
    mapServiceBinder: (name: ComponentName?, service: IBinder?) -> IScreenRecordingService
) = ProducingConnection(this, mapServiceBinder)

private class ProducingConnection(
    private val scope: ProducerScope<IScreenRecordingService?>,
    private val mapServiceBinder:
        (name: ComponentName?, service: IBinder?) -> IScreenRecordingService,
) : ServiceConnection {

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        scope.trySend(mapServiceBinder(name, service))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        scope.trySend(null)
    }

    override fun onBindingDied(name: ComponentName?) {
        scope.trySend(null)
    }
}
