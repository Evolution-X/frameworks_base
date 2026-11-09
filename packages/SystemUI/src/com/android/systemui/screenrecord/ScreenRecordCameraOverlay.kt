/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.screenrecord

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.android.systemui.Prefs
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draggable circular front-camera preview drawn over a fullscreen screen recording.
 *
 * MediaProjection records the display, so the bubble is part of the video. Callers must only show
 * it for fullscreen capture: an app-window projection does not include this overlay, and the
 * bubble would steal touches from the recorded app. The camera is released while the screen is
 * off, the keyguard is showing, or the camera privacy toggle is on, and it is opened again when
 * it is safe to show. A camera failure never stops the recording.
 */
class ScreenRecordCameraOverlay(private val context: Context, private val displayId: Int) {
    private val handler = Handler(Looper.getMainLooper())
    private var session = 0
    private var active = false
    private var cameraGeneration = 0
    private var pendingOpen = 0
    private var viewAdded = false
    private var listenersRegistered = false
    private var dragging = false
    private var privacyToastShown = false
    private var appliedX = Int.MIN_VALUE
    private var appliedY = Int.MIN_VALUE

    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var privacyManager: SensorPrivacyManager? = null
    private var container: FrameLayout? = null
    private var texture: TextureView? = null
    private var layout: WindowManager.LayoutParams? = null

    private var cameraId: String? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraSurface: ReleasableSurface? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var streamSize = Size(640, 480)
    private var sensorOrientation = 0
    // Null until the first preview frame reports its transform. Until then the buffer is
    // treated as if that transform were the sensor rotation.
    private var producerRotation: Int? = null
    private var producerMirrored = false
    private var loggedOrientation: String? = null
    private val textureMatrix = FloatArray(16)

    private val lifecycleReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_SWITCHED -> hide()
                    Intent.ACTION_SCREEN_OFF -> detachPreview()
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> attachIfAllowed()
                }
            }
        }

    private val privacyListener =
        SensorPrivacyManager.OnSensorPrivacyChangedListener { sensor, enabled ->
            if (sensor != SensorPrivacyManager.Sensors.CAMERA) return@OnSensorPrivacyChangedListener
            if (enabled) {
                detachPreview()
                toastPrivacyBlocked()
            } else {
                attachIfAllowed()
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}

            override fun onDisplayRemoved(id: Int) {
                if (id == displayId) hide()
            }

            override fun onDisplayChanged(id: Int) {
                if (id != displayId || !active) return
                if (!dragging && hasSavedPosition()) {
                    layout?.let { applySavedPosition(it) }
                }
                updateTransform()
                updatePosition()
            }
        }

    fun show() {
        val id = ++session
        onMain {
            if (id != session || active) return@onMain
            active = true
            privacyToastShown = false
            try {
                startSession(id)
            } catch (error: Exception) {
                fail(id, error)
            }
        }
    }

    fun hide() {
        session++
        onMain {
            active = false
            dragging = false
            teardown()
        }
    }

    private fun startSession(id: Int) {
        if (id != session) return
        check(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "CAMERA permission is not granted"
        }
        val cameraManager =
            checkNotNull(context.getSystemService(CameraManager::class.java)) {
                "CameraManager is unavailable"
            }
        val frontCameraId =
            checkNotNull(findFrontCameraId(cameraManager)) { "No front camera is available" }
        cameraId = frontCameraId
        readStreamConfiguration(cameraManager, frontCameraId)
        if (id != session) return

        displayManager = context.getSystemService(DisplayManager::class.java)
        val display =
            checkNotNull(displayManager?.getDisplay(displayId)) { "Display $displayId is gone" }
        val overlayContext =
            context.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                /* options= */ null,
            )
        windowContext = overlayContext
        windowManager = overlayContext.getSystemService(WindowManager::class.java)
        privacyManager = context.getSystemService(SensorPrivacyManager::class.java)

        // Set this before registering so a failure still unregisters anything that succeeded.
        listenersRegistered = true
        displayManager?.registerDisplayListener(displayListener, handler)
        context.registerReceiver(
            lifecycleReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        privacyManager?.addSensorPrivacyListener(
            SensorPrivacyManager.Sensors.CAMERA,
            context.mainExecutor,
            privacyListener,
        )
        if (id != session) {
            teardown()
            return
        }
        if (isCameraPrivacyEnabled()) {
            toastPrivacyBlocked()
        } else {
            attachIfAllowed()
        }
    }

    private fun attachIfAllowed() {
        if (!active || viewAdded || !canShowPreview()) return
        val ownerSession = session
        val overlayContext = windowContext ?: return
        val manager = windowManager ?: return
        val cameraManager = context.getSystemService(CameraManager::class.java) ?: return
        val id = cameraId ?: return
        try {
            val density = overlayContext.resources.displayMetrics.density
            val stroke = (STROKE_DP * density).roundToInt().coerceAtLeast(1)
            val size = (PREVIEW_SIZE_DP * density).roundToInt() + stroke * 2
            val params =
                WindowManager.LayoutParams(
                        size,
                        size,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    )
                    .apply {
                        // Physical left, so a saved fraction stays put across RTL changes.
                        gravity = Gravity.TOP or Gravity.LEFT
                        setFitInsetsTypes(0)
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        // Untrusted overlays mark every window below as obscured, so apps drop
                        // touches for the whole recording.
                        setTrustedOverlay()
                        title = "Screen recording selfie camera"
                    }
            layout = params
            if (hasSavedPosition()) {
                applySavedPosition(params)
            } else {
                applyDefaultPosition(params, density)
            }
            clampPosition(params)

            val preview =
                TextureView(overlayContext).apply {
                    isOpaque = false
                    outlineProvider = ovalOutline
                    clipToOutline = true
                    surfaceTextureListener = surfaceListener(cameraManager, id)
                }
            val view =
                FrameLayout(overlayContext).apply {
                    contentDescription = context.getString(R.string.screenrecord_camera_preview)
                    isClickable = true
                    setPadding(stroke, stroke, stroke, stroke)
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(stroke, Color.WHITE)
                        }
                    addView(
                        preview,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            val dragListener = dragListener(params, view)
            view.setOnTouchListener(dragListener)
            preview.setOnTouchListener(dragListener)
            container = view
            texture = preview
            manager.addView(view, params)
            viewAdded = true
            appliedX = params.x
            appliedY = params.y
        } catch (error: Exception) {
            detachPreview()
            fail(ownerSession, error)
        }
    }

    private fun surfaceListener(cameraManager: CameraManager, id: String) =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                openCamera(cameraManager, id, surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                // TextureView puts the buffer back to the view size on layout. A square view
                // with a 4:3 buffer is what makes the face look stretched.
                surfaceTexture.setDefaultBufferSize(streamSize.width, streamSize.height)
                updateTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // Called after TextureView latches the frame, so this matrix includes the
                // producer transform hwui is about to draw.
                surfaceTexture.getTransformMatrix(textureMatrix)
                val decoded = bufferOrientation(textureMatrix) ?: return
                if (decoded.rotation == producerRotation && decoded.mirrored == producerMirrored) {
                    return
                }
                producerRotation = decoded.rotation
                producerMirrored = decoded.mirrored
                updateTransform()
            }
        }

    private fun detachPreview() {
        dragging = false
        producerRotation = null
        producerMirrored = false
        loggedOrientation = null
        val view = container
        texture?.setOnTouchListener(null)
        texture?.surfaceTextureListener = null
        texture = null
        container = null
        // Drop the listener before removing the view so destruction does not open another camera.
        closeCamera()
        view?.setOnTouchListener(null)
        if (view != null && viewAdded) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to remove selfie preview", error)
            }
        }
        viewAdded = false
        layout = null
        appliedX = Int.MIN_VALUE
        appliedY = Int.MIN_VALUE
    }

    private fun teardown() {
        detachPreview()
        if (listenersRegistered) {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to unregister display listener", error)
            }
            try {
                context.unregisterReceiver(lifecycleReceiver)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to unregister selfie receiver", error)
            }
            try {
                privacyManager?.removeSensorPrivacyListener(
                    SensorPrivacyManager.Sensors.CAMERA,
                    privacyListener,
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to remove camera privacy listener", error)
            }
        }
        listenersRegistered = false
        windowManager = null
        windowContext = null
        displayManager = null
        privacyManager = null
        cameraCharacteristics = null
        cameraId = null
    }

    private fun openCamera(manager: CameraManager, id: String, surfaceTexture: SurfaceTexture) {
        if (!active) return
        // Captured up front. hide() advances session before the camera callbacks run, and those
        // callbacks must not treat that as a new failure.
        val ownerSession = session
        val generation = ++cameraGeneration
        pendingOpen = generation
        val output =
            try {
                surfaceTexture.setDefaultBufferSize(streamSize.width, streamSize.height)
                updateTransform()
                ReleasableSurface(Surface(surfaceTexture)).also { cameraSurface = it }
            } catch (error: Exception) {
                pendingOpen = 0
                fail(ownerSession, error)
                return
            }
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (generation == pendingOpen) pendingOpen = 0
                        if (!active || generation != cameraGeneration) {
                            closeQuietly(device)
                            return
                        }
                        camera = device
                        try {
                            val request =
                                device
                                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(output.surface) }
                                    .build()
                            device.createCaptureSession(
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(output.surface)),
                                    context.mainExecutor,
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(configured: CameraCaptureSession) {
                                            if (!active || generation != cameraGeneration) {
                                                closeQuietly(configured)
                                                return
                                            }
                                            captureSession = configured
                                            try {
                                                configured.setRepeatingRequest(
                                                    request,
                                                    /* listener= */ null,
                                                    handler,
                                                )
                                            } catch (error: Exception) {
                                                fail(ownerSession, error)
                                            }
                                        }

                                        override fun onConfigureFailed(
                                            configured: CameraCaptureSession
                                        ) {
                                            closeQuietly(configured)
                                            if (generation == cameraGeneration) {
                                                fail(
                                                    ownerSession,
                                                    IllegalStateException("Camera session failed"),
                                                )
                                            }
                                        }
                                    },
                                )
                            )
                        } catch (error: Exception) {
                            fail(ownerSession, error)
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        if (generation == pendingOpen) pendingOpen = 0
                        closeQuietly(device)
                        if (generation == cameraGeneration) {
                            fail(ownerSession, IllegalStateException("Camera disconnected"))
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        if (generation == pendingOpen) pendingOpen = 0
                        closeQuietly(device)
                        if (generation == cameraGeneration) {
                            fail(ownerSession, IllegalStateException("Camera error $error"))
                        }
                    }

                    override fun onClosed(device: CameraDevice) {
                        if (generation == pendingOpen) pendingOpen = 0
                        releaseOutput(output)
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            pendingOpen = 0
            releaseOutput(output)
            fail(ownerSession, error)
        }
    }

    private fun closeCamera() {
        cameraGeneration++
        val sessionToClose = captureSession
        val cameraToClose = camera
        captureSession = null
        camera = null
        closeQuietly(sessionToClose)
        closeQuietly(cameraToClose)
        // An open that has not produced a device yet releases its surface from onClosed. Releasing
        // it here races that open and can fail the replacement session.
        if (cameraToClose == null && pendingOpen == 0) {
            cameraSurface?.release()
            cameraSurface = null
        }
    }

    private fun releaseOutput(output: ReleasableSurface) {
        if (cameraSurface === output) cameraSurface = null
        output.release()
    }

    private fun updateTransform() {
        val view = texture ?: return
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth == 0 || viewHeight == 0) return
        val sensor =
            cameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientation
        sensorOrientation = sensor
        val deviceDegrees = displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
        // No frame yet: behave as if the producer had already applied the sensor rotation.
        // That is upright in the natural orientation, and the first frame corrects it.
        val bufferRotation = producerRotation ?: sensor
        val bufferMirrored = producerRotation != null && producerMirrored
        val correction = previewCorrection(sensor, deviceDegrees, bufferRotation, bufferMirrored)
        // The window transform has already turned the buffer. Swap for that first, then for
        // whatever rotation this matrix still has to add. A square stream makes both no-ops.
        val presentedSwaps = bufferRotation % 180 != 0
        val presentedWidth = if (presentedSwaps) streamSize.height else streamSize.width
        val presentedHeight = if (presentedSwaps) streamSize.width else streamSize.height
        val appSwaps = correction.rotation % 180 != 0
        val contentWidth = (if (appSwaps) presentedHeight else presentedWidth).toFloat()
        val contentHeight = (if (appSwaps) presentedWidth else presentedHeight).toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val bufferRect = RectF(0f, 0f, contentWidth, contentHeight)
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        val scale = max(viewWidth / contentWidth, viewHeight / contentHeight)
        val orientation =
            "sensor=$sensor display=$deviceDegrees buffer=${producerRotation ?: "pending"} " +
                "bufferMirrored=$bufferMirrored rotation=${correction.rotation} " +
                "mirror=${correction.mirror}"
        if (orientation != loggedOrientation) {
            loggedOrientation = orientation
            Log.i(TAG, "Selfie preview $orientation")
        }
        view.setTransform(
            Matrix().apply {
                setRectToRect(
                    RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()),
                    bufferRect,
                    Matrix.ScaleToFit.FILL,
                )
                postScale(scale, scale, centerX, centerY)
                postRotate(correction.rotation.toFloat(), centerX, centerY)
                if (correction.mirror) {
                    postScale(-1f, 1f, centerX, centerY)
                }
            }
        )
    }

    private fun dragListener(params: WindowManager.LayoutParams, view: View): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        return View.OnTouchListener { touched, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        dragging = dragging || abs(dx) > slop || abs(dy) > slop
                        if (dragging) {
                            params.x = startX + dx.roundToInt()
                            params.y = startY + dy.roundToInt()
                            updatePosition()
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            savePosition(params)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            touched.performClick()
                        }
                        dragging = false
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to drag selfie preview", error)
            }
            true
        }
    }

    private fun availableBounds(): Rect {
        val manager = windowManager
        val metrics = windowContext?.resources?.displayMetrics
        val fallback =
            Rect(0, 0, metrics?.widthPixels ?: 0, metrics?.heightPixels ?: 0)
        if (manager == null) return fallback
        return try {
            val windowMetrics = manager.currentWindowMetrics
            val insets =
                windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            Rect(
                insets.left,
                insets.top,
                windowMetrics.bounds.width() - insets.right,
                windowMetrics.bounds.height() - insets.bottom,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read selfie bounds", error)
            fallback
        }
    }

    private fun applyDefaultPosition(params: WindowManager.LayoutParams, density: Float) {
        val bounds = availableBounds()
        val margin = (EDGE_MARGIN_DP * density).roundToInt()
        val bottomMargin = (BOTTOM_MARGIN_DP * density).roundToInt()
        val rtl =
            windowContext?.resources?.configuration?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        params.x =
            if (rtl) {
                bounds.left + margin
            } else {
                bounds.right - params.width - margin
            }
        params.y = bounds.bottom - params.height - bottomMargin
    }

    private fun applySavedPosition(params: WindowManager.LayoutParams) {
        val bounds = availableBounds()
        val spanX = (bounds.width() - params.width).coerceAtLeast(1)
        val spanY = (bounds.height() - params.height).coerceAtLeast(1)
        params.x = bounds.left + Prefs.getInt(context, PREF_POSITION_X, 0) * spanX / POSITION_SCALE
        params.y = bounds.top + Prefs.getInt(context, PREF_POSITION_Y, 0) * spanY / POSITION_SCALE
    }

    private fun savePosition(params: WindowManager.LayoutParams) {
        val bounds = availableBounds()
        val spanX = (bounds.width() - params.width).coerceAtLeast(1)
        val spanY = (bounds.height() - params.height).coerceAtLeast(1)
        val x = ((params.x - bounds.left) * POSITION_SCALE / spanX).coerceIn(0, POSITION_SCALE)
        val y = ((params.y - bounds.top) * POSITION_SCALE / spanY).coerceIn(0, POSITION_SCALE)
        Prefs.putInt(context, PREF_POSITION_X, x)
        Prefs.putInt(context, PREF_POSITION_Y, y)
    }

    private fun hasSavedPosition(): Boolean =
        Prefs.getInt(context, PREF_POSITION_X, -1) >= 0 &&
            Prefs.getInt(context, PREF_POSITION_Y, -1) >= 0

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val bounds = availableBounds()
        params.x = params.x.coerceIn(bounds.left, max(bounds.left, bounds.right - params.width))
        params.y = params.y.coerceIn(bounds.top, max(bounds.top, bounds.bottom - params.height))
    }

    /**
     * [appliedX] is the last position given to WindowManager. Comparing against [layout] after a
     * drag has already written it would skip the move whenever the bubble is still in bounds.
     */
    private fun updatePosition() {
        val view = container ?: return
        val params = layout ?: return
        if (!view.isAttachedToWindow) return
        try {
            clampPosition(params)
            if (params.x == appliedX && params.y == appliedY) return
            windowManager?.updateViewLayout(view, params)
            appliedX = params.x
            appliedY = params.y
        } catch (error: Exception) {
            Log.w(TAG, "Failed to move selfie preview", error)
        }
    }

    private fun canShowPreview(): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return interactive && !locked && !isCameraPrivacyEnabled()
    }

    private fun isCameraPrivacyEnabled(): Boolean =
        privacyManager?.areAnySensorPrivacyTogglesEnabled(SensorPrivacyManager.Sensors.CAMERA) ==
            true

    private fun toastPrivacyBlocked() {
        if (!active || privacyToastShown) return
        privacyToastShown = true
        toastUnavailable()
    }

    private fun toastUnavailable() {
        Toast.makeText(context, R.string.screenrecord_camera_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun fail(id: Int, error: Throwable? = null) {
        handler.post {
            if (id != session) return@post
            Log.w(TAG, "Selfie camera unavailable", error)
            hide()
            toastUnavailable()
        }
    }

    private fun readStreamConfiguration(manager: CameraManager, id: String) {
        val characteristics = manager.getCameraCharacteristics(id)
        cameraCharacteristics = characteristics
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sizes =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
        streamSize = chooseStreamSize(sizes)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }

    private class ReleasableSurface(val surface: Surface) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            try {
                surface.release()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to release camera surface", error)
            }
        }
    }

    private companion object {
        const val TAG = "ScreenRecordCamera"
        const val PREVIEW_SIZE_DP = 120
        const val STROKE_DP = 2
        const val EDGE_MARGIN_DP = 16
        const val BOTTOM_MARGIN_DP = 96
        const val POSITION_SCALE = 1000
        const val PREF_POSITION_X = "screenrecord_camera_pos_x"
        const val PREF_POSITION_Y = "screenrecord_camera_pos_y"

        val ovalOutline =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }

        fun displayRotationDegrees(rotation: Int): Int =
            when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

        /**
         * Clockwise view rotation still required, and whether to mirror afterwards.
         *
         * [sensorOrientation] is the clockwise rotation that makes an unmirrored buffer upright
         * in the device's natural orientation. [deviceDegrees] is the display rotation.
         * [bufferRotation] is the clockwise rotation TextureView has already applied from the
         * buffer transform, and [bufferMirrored] is that transform's reflection. A mirrored
         * transform reflects the sensor rotation and is already the selfie mirror, so the view
         * matrix must not mirror a second time.
         */
        fun previewCorrection(
            sensorOrientation: Int,
            deviceDegrees: Int,
            bufferRotation: Int,
            bufferMirrored: Boolean,
        ): Correction {
            val needed = normalizeDegrees(sensorOrientation - deviceDegrees)
            val buffer = normalizeDegrees(bufferRotation)
            val worldUp =
                if (bufferMirrored) {
                    normalizeDegrees(buffer + needed)
                } else {
                    normalizeDegrees(buffer - needed)
                }
            return Correction(
                rotation = normalizeDegrees(-worldUp),
                mirror = !bufferMirrored,
            )
        }

        /**
         * Rotation and reflection TextureView will apply to this frame on its own.
         *
         * [SurfaceTexture.getTransformMatrix] maps GL texture coordinates through the producer
         * transform and always ends with a Y flip. The sign of its determinant distinguishes
         * that mandatory flip from a real mirror. A camera crop is axis-aligned, so the
         * rotation lands on a quadrant; anything else is ignored.
         */
        fun bufferOrientation(matrix: FloatArray): BufferOrientation? {
            if (matrix.size != 16) return null
            val m00 = matrix[0]
            val m10 = matrix[1]
            val m01 = matrix[4]
            val m11 = matrix[5]
            val det = m00 * m11 - m01 * m10
            if (abs(det) < 1e-4f) return null
            // Screen direction of the buffer's top edge: x right, y down.
            val vx = m01 / det
            val vy = m00 / det
            val degrees =
                normalizeDegrees(Math.toDegrees(atan2(vx.toDouble(), (-vy).toDouble())).roundToInt())
            val snapped = normalizeDegrees(((degrees + 45) / 90) * 90)
            val distance = min(abs(degrees - snapped), 360 - abs(degrees - snapped))
            if (distance > 20) return null
            return BufferOrientation(rotation = snapped, mirrored = det > 0f)
        }

        class Correction(val rotation: Int, val mirror: Boolean)

        class BufferOrientation(val rotation: Int, val mirrored: Boolean)

        fun chooseStreamSize(sizes: Array<Size>?): Size {
            if (sizes.isNullOrEmpty()) return Size(640, 480)
            val preferred =
                sizes.filter { size ->
                    val shortSide = min(size.width, size.height)
                    val longSide = max(size.width, size.height)
                    shortSide >= 480 && longSide <= 1280
                }
            val pool =
                preferred.ifEmpty {
                    val atLeastVga = sizes.filter { min(it.width, it.height) >= 480 }
                    if (atLeastVga.isNotEmpty()) atLeastVga else sizes.toList()
                }
            // The bubble is square. Match that shape first so the preview is cropped, not
            // stretched; area only breaks a tie.
            return pool.minWith(
                compareBy<Size> { size ->
                    val shortSide = min(size.width, size.height).coerceAtLeast(1).toFloat()
                    val longSide = max(size.width, size.height).toFloat()
                    longSide / shortSide
                }.thenBy { it.width.toLong() * it.height }
            )
        }

        fun findFrontCameraId(manager: CameraManager): String? {
            val front =
                manager.cameraIdList.mapNotNull { id ->
                    val characteristics =
                        try {
                            manager.getCameraCharacteristics(id)
                        } catch (error: Exception) {
                            Log.w(TAG, "Skipping camera $id", error)
                            return@mapNotNull null
                        }
                    if (
                        characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    ) {
                        id to characteristics
                    } else {
                        null
                    }
                }
            val compatible =
                front.filter { (_, characteristics) ->
                    val capabilities =
                        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    capabilities == null ||
                        capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                        )
                }
            return (if (compatible.isNotEmpty()) compatible else front).firstOrNull()?.first
        }

        fun closeQuietly(device: CameraDevice?) {
            if (device == null) return
            try {
                device.close()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to close camera", error)
            }
        }

        fun closeQuietly(session: CameraCaptureSession?) {
            if (session == null) return
            try {
                session.close()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to close camera session", error)
            }
        }
    }
}
