/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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
package com.android.systemui.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import android.widget.FrameLayout
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class ChargingAnimationViewController
@Inject
constructor(
    private val context: Context,
) : ScrimUtils.ScrimEventListener {

    private val chargingView = ChargingAnimationView(context)
    private val settingsRepo = ChargingAnimationSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentSettings = settingsRepo.currentSettings()
    private var settingsJob: Job? = null

    private var isCharging: Boolean = false
    private var batteryLevel: Int = 0
    private var isKeyguardShowing: Boolean = false
    private var isDozing: Boolean = false
    private var wasCharging: Boolean = false

    private var shouldShowAnimation: Boolean = false
    private var animationShownThisChargingSession: Boolean = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateBatteryStatus(intent)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    animationShownThisChargingSession = false
                    shouldShowAnimation = true
                    updateVisibility()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    shouldShowAnimation = false
                    animationShownThisChargingSession = false
                    updateVisibility()
                }
            }
        }
    }

    init {
        INSTANCE = this
        
        try {
            ScrimUtils.get()?.addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ScrimUtils listener", e)
        }
        
        observeSettings()
        registerBatteryReceiver()
        queryInitialBatteryState()
    }

    fun getChargingView(): FrameLayout = chargingView

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settingsFlow
                .catch { e -> 
                    Log.e(TAG, "Error observing settings", e)
                }
                .collect { settings ->
                    currentSettings = settings
                    updateViewWithSettings(settings)
                }
        }
    }

    private fun updateViewWithSettings(settings: ChargingAnimationSettings) {
        val accentColor = Utils.getColorAccentDefaultColor(context)
        
        chargingView.apply {
            defaultChargingColor = 0xFF4CAF50.toInt()
            this.accentColor = accentColor
            colorMode = settings.colorMode
            animationStyle = settings.animationStyle
            rippleOpacity = settings.rippleOpacity
            glowIntensity = settings.glowIntensity
            arcCount = settings.arcCount
        }
        
        updateVisibility()
    }

    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering battery receiver", e)
        }
    }

    private fun queryInitialBatteryState() {
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatus?.let { 
                updateBatteryStatus(it)
                if (isCharging) {
                    shouldShowAnimation = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying initial battery state", e)
        }
    }

    private fun updateBatteryStatus(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        
        val wasChargingBefore = isCharging
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == BatteryManager.BATTERY_STATUS_FULL
        
        batteryLevel = (level * 100 / scale).coerceIn(0, 100)
        chargingView.batteryLevel = batteryLevel
        
        if (!wasChargingBefore && isCharging) {
            wasCharging = false
            shouldShowAnimation = true
            animationShownThisChargingSession = false
            updateVisibility()
        } else if (wasChargingBefore && !isCharging) {
            wasCharging = true
            shouldShowAnimation = false
            animationShownThisChargingSession = false
            updateVisibility()
        } else if (isCharging && shouldShowAnimation) {
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        if (!currentSettings.isEnabled) {
            chargingView.hide()
            return
        }

        if (!isCharging || !shouldShowAnimation) {
            chargingView.hide()
            return
        }

        val isPanelCollapsed = try {
            ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking panel state", e)
            true
        }

        val shouldShow = when {
            !isPanelCollapsed -> false
            isDozing -> currentSettings.showOnAod
            isKeyguardShowing -> currentSettings.showOnLockscreen
            else -> false
        }

        if (shouldShow && !animationShownThisChargingSession) {
            chargingView.show()
            animationShownThisChargingSession = true
        } else if (shouldShow && animationShownThisChargingSession) {
            if (!chargingView.visible) {
                chargingView.show()
            }
        } else if (!shouldShow) {
            chargingView.hide()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        isKeyguardShowing = showing
        updateVisibility()
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateVisibility()
    }

    override fun setPulsing(pulsing: Boolean) {
        if (pulsing && currentSettings.showOnAod && isCharging && shouldShowAnimation) {
            updateVisibility()
        }
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateVisibility()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateVisibility()
    }

    override fun onBarStateChanged(state: Int) {
        updateVisibility()
    }

    override fun onStartedWakingUp() {
        updateVisibility()
    }

    override fun onScreenTurnedOff() {
        updateVisibility()
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(batteryReceiver)
            ScrimUtils.get()?.removeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        settingsJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ChargingAnimViewController"
        
        @Volatile
        private var INSTANCE: ChargingAnimationViewController? = null
        
        @JvmStatic
        fun get(context: Context): ChargingAnimationViewController {
            return INSTANCE ?: throw IllegalStateException(
                "ChargingAnimationViewController not initialized"
            )
        }
    }
}
