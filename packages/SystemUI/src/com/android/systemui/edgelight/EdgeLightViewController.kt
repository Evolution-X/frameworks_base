/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.app.Notification
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.UserHandle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import com.android.internal.util.ContrastColorUtil
import com.android.internal.view.OneShotPreDrawListener
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ScrimUtils
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@SysUISingleton
class EdgeLightViewController @Inject constructor(
    private val context: Context,
    private val listener: NotificationListener,
    private val settingsRepo: EdgeLightSettingsRepository,
    private val windowManager: WindowManager,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: StatusBarStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val userTracker: UserTracker,
    configurationController: ConfigurationController,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    @Main mainExecutor: Executor,
) : ScrimUtils.ScrimEventListener {
    private val edgeLightView = EdgeLightView(context)
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!
    private var currentSettings = settingsRepo.currentSettings()
    private var lockscreenContainer: ViewGroup? = null
    private var overlayAdded = false
    private var preDrawListener: OneShotPreDrawListener? = null
    private var ambientPulse = false
    private var requestGeneration = 0
    private var lastNotifColor = Utils.getColorAccentDefaultColor(context)
    private var activeNotificationKey: String? = null
    private val notificationContents = LinkedHashMap<String, List<String?>>()
    private var keyguardWasShowing = keyguardStateController.isShowing
    private var unlockPending = false

    private val finishPulse = Runnable { stopPulse() }
    private val unlockPulse = Runnable { maybePulseOnUnlock() }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            val showing = keyguardStateController.isShowing
            if (showing != keyguardWasShowing) {
                stopPulse()
                unlockPending = keyguardWasShowing && !showing
                keyguardWasShowing = showing
            }
            scheduleUnlockPulse()
        }

        override fun onKeyguardFadingAwayChanged() {
            if (keyguardStateController.isKeyguardFadingAway) stopPulse()
            scheduleUnlockPulse()
        }

        override fun onKeyguardGoingAwayChanged() {
            if (keyguardStateController.isKeyguardGoingAway) stopPulse()
            scheduleUnlockPulse()
        }
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {
            stopPulse()
            scheduleUnlockPulse()
        }

        override fun onDozingChanged(isDozing: Boolean) {
            stopPulse()
            if (isDozing) unlockPending = false
            scheduleUnlockPulse()
        }

        override fun onExpandedChanged(isExpanded: Boolean) {
            if (isExpanded && !keyguardStateController.isShowing) {
                stopPulse()
            }
            if (!isExpanded) scheduleUnlockPulse()
        }

        override fun onDreamingChanged(isDreaming: Boolean) {
            if (isDreaming) {
                unlockPending = false
                stopPulse()
            }
        }
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            unlockPending = false
            stopPulse()
        }

        override fun onStartedWakingUp() {
            stopPulse()
            if (!keyguardStateController.isShowing) unlockPending = true
        }

        override fun onFinishedWakingUp() {
            scheduleUnlockPulse()
        }
    }

    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanging(newUser: Int, userContext: Context) {
            unlockPending = false
            stopPulse()
        }

        override fun onUserChanged(newUser: Int, userContext: Context) {
            unlockPending = false
            stopPulse()
            notificationContents.clear()
            lastNotifColor = Utils.getColorAccentDefaultColor(context)
            applySettings(settingsRepo.currentSettings())
        }
    }

    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration) {
            stopPulse()
            scheduleUnlockPulse()
        }
    }

    init {
        INSTANCE = this
        edgeLightView.onPulseFinished = { mainHandler.post(finishPulse) }
        edgeLightView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}

            override fun onViewDetachedFromWindow(view: View) {
                if (overlayAdded) mainHandler.post(finishPulse)
            }
        })
        ScrimUtils.get().addListener(this)
        keyguardStateController.addCallback(keyguardCallback)
        statusBarStateController.addCallback(stateListener)
        wakefulnessLifecycle.addObserver(wakefulnessObserver)
        userTracker.addCallback(userCallback, mainExecutor)
        configurationController.addCallback(configurationListener)
        applySettings(currentSettings)
        scope.launch(mainDispatcher) { settingsRepo.settingsFlow.collect { applySettings(it) } }
    }

    fun attachToLockscreen(container: ViewGroup) {
        stopPulse()
        lockscreenContainer = container
        attachLockscreenView()
    }

    private fun attachLockscreenView() {
        val container = lockscreenContainer ?: return
        if (edgeLightView.parent === container) return
        (edgeLightView.parent as? ViewGroup)?.removeView(edgeLightView)
        container.addView(edgeLightView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun applySettings(settings: EdgeLightSettings) {
        if (settings != currentSettings) {
            unlockPending = false
            stopPulse()
        }
        currentSettings = settings
        edgeLightView.apply {
            userPulseCount = settings.pulseCount
            userStrokeWidth = settings.strokeWidth
            edgeStyle = settings.edgeStyle
            animationEffect = settings.animationEffect
            userSpread = settings.spread
            userIntensity = settings.intensity
        }
        updateColor()
    }

    private fun getColor(): Int = when (currentSettings.colorMode) {
        COLOR_MODE_CUSTOM -> currentSettings.customColor
        COLOR_MODE_WALLPAPER -> wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
        COLOR_MODE_NOTIFICATION -> lastNotifColor
        else -> Utils.getColorAccentDefaultColor(context)
    }

    private fun updateColor() {
        edgeLightView.paintColor = getColor()
        edgeLightView.rainbow = currentSettings.colorMode == COLOR_MODE_RAINBOW
    }

    private fun isUnlockedContext(): Boolean =
        currentSettings.showWhenUnlocked &&
            wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE &&
            !keyguardStateController.isShowing &&
            !keyguardStateController.isKeyguardGoingAway &&
            !keyguardStateController.isKeyguardFadingAway &&
            !statusBarStateController.isDozing &&
            !statusBarStateController.isDreaming &&
            statusBarStateController.state == StatusBarState.SHADE &&
            !statusBarStateController.isExpanded

    private fun scheduleUnlockPulse() {
        mainHandler.removeCallbacks(unlockPulse)
        if (unlockPending) mainHandler.postDelayed(unlockPulse, UNLOCK_SETTLE_DELAY_MS)
    }

    private fun maybePulseOnUnlock() {
        if (!unlockPending) return
        if (!currentSettings.showWhenUnlocked) {
            unlockPending = false
            return
        }
        if (!isUnlockedContext()) return
        requestUnlockedPulse()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.userId != UserHandle.USER_ALL &&
            userTracker.userProfiles.none { it.id == sbn.userId }) return
        if (currentSettings.triggerUnlockOnly) {
            return
        }
        val notification = sbn.notification
        val contents = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        val previous = notificationContents.put(sbn.key, contents)
        if (notificationContents.size > MAX_TRACKED_NOTIFICATIONS) {
            notificationContents.remove(notificationContents.keys.first())
        }
        if (!currentSettings.isEnabled) return

        val color = notification.color
        lastNotifColor = if (color == Color.TRANSPARENT || ContrastColorUtil.isColorDark(color)) {
            Utils.getColorAccentDefaultColor(context)
        } else color

        if (statusBarStateController.isDozing) {
            if (currentSettings.showOnLockscreen &&
                currentSettings.colorMode == COLOR_MODE_NOTIFICATION) {
                updateColor()
            }
            return
        }
        if (previous == contents ||
            (previous != null && notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) ||
            sbn.isOngoing || notification.isGroupSummary) return
        val ranking = Ranking()
        if (listener.currentRanking?.getRanking(sbn.key, ranking) != true ||
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT || ranking.isSuspended ||
            !ranking.matchesInterruptionFilter() ||
            ranking.suppressedVisualEffects and (
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                    NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
            ) != 0) return
        if (isUnlockedContext()) {
            requestUnlockedPulse(notificationKey = sbn.key)
        } else if (wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE) {
            startLockscreenPulse(sbn.key)
        }
    }

    private fun requestUnlockedPulse(notificationKey: String? = null) {
        if (!isUnlockedContext()) return
        unlockPending = false
        mainHandler.removeCallbacks(unlockPulse)
        activeNotificationKey = notificationKey
        if (overlayAdded) {
            if (preDrawListener == null && edgeLightView.isAttachedToWindow) startPulse()
            return
        }
        showUnlockedPulse(requestGeneration)
    }

    private fun showUnlockedPulse(generation: Int) {
        (edgeLightView.parent as? ViewGroup)?.removeView(edgeLightView)
        edgeLightView.alpha = 0f
        edgeLightView.visible = true
        preDrawListener = OneShotPreDrawListener.add(edgeLightView) {
            preDrawListener = null
            if (generation == requestGeneration) {
                if (overlayAdded && edgeLightView.isAttachedToWindow && isUnlockedContext()) {
                    startPulse()
                } else {
                    stopPulse()
                }
            }
        }
        edgeLightView.requestLayout()
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "EdgeLighting"
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTrustedOverlay()
        }
        try {
            windowManager.addView(edgeLightView, params)
            overlayAdded = true
            mainHandler.removeCallbacks(finishPulse)
            mainHandler.postDelayed(finishPulse, WINDOW_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to add edge lighting window", e)
            stopPulse()
            attachLockscreenView()
        }
    }

    private fun stopPulse() {
        requestGeneration++
        mainHandler.removeCallbacks(unlockPulse)
        mainHandler.removeCallbacks(finishPulse)
        preDrawListener?.removeListener()
        preDrawListener = null
        edgeLightView.pulseRunning = false
        ambientPulse = false
        activeNotificationKey = null
        if (overlayAdded) {
            overlayAdded = false
            try {
                windowManager.removeViewImmediate(edgeLightView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Edge lighting window was already removed", e)
            }
            attachLockscreenView()
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!pulsing) {
            if (ambientPulse) stopPulse()
        } else if (statusBarStateController.isDozing) {
            startLockscreenPulse()
        }
    }

    private fun startLockscreenPulse(notificationKey: String? = null) {
        if (!currentSettings.showOnLockscreen ||
            (!keyguardStateController.isShowing && !statusBarStateController.isDozing) ||
            keyguardStateController.isKeyguardGoingAway ||
            keyguardStateController.isKeyguardFadingAway ||
            (statusBarStateController.isDreaming && !statusBarStateController.isDozing) ||
            overlayAdded ||
            edgeLightView.parent !== lockscreenContainer || !edgeLightView.isAttachedToWindow) return
        activeNotificationKey = notificationKey
        ambientPulse = statusBarStateController.isDozing
        startPulse()
    }

    private fun startPulse() {
        mainHandler.removeCallbacks(finishPulse)
        edgeLightView.pulseRunning = false
        updateColor()
        edgeLightView.pulseRunning = true
    }

    override fun onScreenTurnedOff() {
        unlockPending = false
        stopPulse()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notificationContents.remove(sbn.key)
        if (activeNotificationKey == sbn.key) stopPulse()
    }

    companion object {
        private const val TAG = "EdgeLightViewController"
        private const val MAX_TRACKED_NOTIFICATIONS = 64
        private const val UNLOCK_SETTLE_DELAY_MS = 200L
        private const val WINDOW_TIMEOUT_MS = 2000L
        private const val COLOR_MODE_NOTIFICATION = "notification"
        private const val COLOR_MODE_WALLPAPER = "wallpaper"
        private const val COLOR_MODE_CUSTOM = "custom"
        private const val COLOR_MODE_RAINBOW = "rainbow"

        @Volatile private var INSTANCE: EdgeLightViewController? = null

        @JvmStatic
        fun get(context: Context): EdgeLightViewController = INSTANCE
            ?: throw IllegalStateException("EdgeLightViewController not initialized")
    }
}
