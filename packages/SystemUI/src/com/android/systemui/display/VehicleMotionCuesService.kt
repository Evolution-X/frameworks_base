/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn

@SysUISingleton
class VehicleMotionCuesService @Inject constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val settings: VehicleMotionCuesSettings,
) : CoreStartable, SensorEventListener {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var motionCuesView: MotionCuesView? = null
    private var isSensorRegistered = false
    private var isScreenOn = powerManager.isInteractive
    private var isServiceStarted = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateSensorRegistration()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateSensorRegistration()
                }
            }
        }
    }

    override fun start() {
        settings.init()
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
        isServiceStarted = true

        combine(
            settings.isEnabled,
            settings.dotSize,
            settings.sensitivity,
            settings.dotCount,
        ) { enabled, dotSize, sensitivity, dotCount ->
            if (enabled) {
                startCues(dotSize, sensitivity, dotCount)
            } else {
                stopCues()
            }
        }
        .flowOn(mainDispatcher)
        .launchIn(applicationScope)
    }

    private fun startCues(dotSize: Float, sensitivity: Float, dotCount: Int) {
        if (motionCuesView == null) {
            motionCuesView = MotionCuesView(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            lp.privateFlags = lp.privateFlags or 
                0x20000000 or
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            runCatching {
                windowManager.addView(motionCuesView, lp)
            }
        }

        motionCuesView?.updatePreferences(dotSize, sensitivity, dotCount)
        updateSensorRegistration()
    }

    private fun stopCues() {
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }

        motionCuesView?.let { view ->
            runCatching {
                windowManager.removeView(view)
            }
            motionCuesView = null
        }
    }

    private fun updateSensorRegistration() {
        val enabled = settings.isEnabled.value

        if (enabled && isScreenOn) {
            if (!isSensorRegistered && linearAccelerometer != null) {
                sensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_UI)
                isSensorRegistered = true
            }
            motionCuesView?.visibility = View.VISIBLE
            motionCuesView?.setAnimationEnabled(true)
        } else {
            if (isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
            motionCuesView?.visibility = View.GONE
            motionCuesView?.setAnimationEnabled(false)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        
        val display = motionCuesView?.display ?: windowManager.defaultDisplay
        val rotation = display?.rotation ?: Surface.ROTATION_0
        
        val rawX = values[0]
        val rawY = values[1]
        
        val angleRad = when (rotation) {
            Surface.ROTATION_90 -> -Math.PI / 2.0
            Surface.ROTATION_180 -> Math.PI
            Surface.ROTATION_270 -> Math.PI / 2.0
            else -> 0.0
        }
        val cos = kotlin.math.cos(angleRad).toFloat()
        val sin = kotlin.math.sin(angleRad).toFloat()
        
        val rotatedX = rawX * cos - rawY * sin
        val rotatedY = rawX * sin + rawY * cos
        
        motionCuesView?.updateSensorTarget(rotatedX, rotatedY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private class MotionCuesView(context: Context) : View(context) {
        private val dotPaint = Paint().apply {
            color = Color.WHITE
            alpha = 110
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private var dotRadius = 10f
        private var scaleFactor = 15f
        private var isAnimationEnabled = true
        private var activeDotCount = 14

        private val density = context.resources.displayMetrics.density
        private val hMarginPx = 16f * density
        private val topMarginPx = 48f * density
        private val bottomMarginPx = 48f * density

        private var displacementX = 0f
        private var displacementY = 0f
        private var velocityX = 0f
        private var velocityY = 0f
        
        private var targetX = 0f
        private var targetY = 0f
        private var lastTime = 0L

        private val physicsK = 15.0f
        private val physicsC = 5.0f

        // Pre-allocate coordinates to avoid runtime allocations
        private val dots = Array(24) { PointF() }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun updatePreferences(radius: Float, sensitivity: Float, count: Int) {
            dotRadius = radius * density
            scaleFactor = sensitivity * density
            activeDotCount = count.coerceIn(0, 24)
            
            recalculateDots()
            invalidate()
        }

        fun setAnimationEnabled(enabled: Boolean) {
            if (isAnimationEnabled != enabled) {
                isAnimationEnabled = enabled
                if (enabled) {
                    lastTime = SystemClock.elapsedRealtime()
                    invalidate()
                }
            }
        }

        fun updateSensorTarget(x: Float, y: Float) {
            targetX = -x * scaleFactor
            targetY = y * scaleFactor
            if (isAnimationEnabled) {
                invalidate()
            }
        }

        private fun recalculateDots() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0 || activeDotCount <= 0) return

            val dotsPerSide = activeDotCount / 2
            if (dotsPerSide <= 0) return

            val usableHeight = h.toFloat() - topMarginPx - bottomMarginPx
            for (i in 0 until dotsPerSide) {
                val py = topMarginPx + usableHeight * (i.toFloat() / (dotsPerSide - 1).coerceAtLeast(1))
                
                dots[i].set(hMarginPx, py)
                dots[i + dotsPerSide].set(w.toFloat() - hMarginPx, py)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recalculateDots()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            if (w <= 0 || h <= 0 || activeDotCount <= 0) return

            val now = SystemClock.elapsedRealtime()
            val dt = if (lastTime == 0L) 0.016f else (now - lastTime) / 1000f
            lastTime = now
            val cappedDt = dt.coerceAtMost(0.033f)

            val forceX = targetX - displacementX
            val forceY = targetY - displacementY

            val accelX = forceX * physicsK - physicsC * velocityX
            val accelY = forceY * physicsK - physicsC * velocityY

            velocityX += accelX * cappedDt
            velocityY += accelY * cappedDt
            displacementX += velocityX * cappedDt
            displacementY += velocityY * cappedDt

            val time = SystemClock.uptimeMillis() / 1000f
            val driftAmplitude = 6f * density

            for (i in 0 until activeDotCount) {
                val dot = dots[i]
                
                val driftX = kotlin.math.sin(time * 1.5f + i) * driftAmplitude
                val driftY = kotlin.math.cos(time * 1.0f + i * 1.5f) * driftAmplitude

                canvas.drawCircle(
                    dot.x + displacementX + driftX,
                    dot.y + displacementY + driftY,
                    dotRadius,
                    dotPaint
                )
            }

            if (isAnimationEnabled) {
                postInvalidateOnAnimation()
            }
        }
    }
}
