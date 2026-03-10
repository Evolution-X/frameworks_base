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

package com.android.systemui.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewTreeObserver
import com.android.systemui.Dependency
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.statusbar.phone.ScrimController
import java.util.concurrent.atomic.AtomicBoolean

/* Scrim - aka testing utils */
class ScrimUtils private constructor(context: Context?) {

    interface ScrimEventListener {
        fun onKeyguardShowingChanged(showing: Boolean) {}
        fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {}
        fun onKeyguardGoingAwayChanged(goingAway: Boolean) {}
        fun onPrimaryBouncerShowingChanged(showing: Boolean) {}
        fun onDozingChanged(dozing: Boolean) {}
        fun onExpandedFractionChanged(expandedFraction: Float) {}
        fun onBarStateChanged(state: Int) {}
        fun onQsVisibilityChanged(visible: Boolean) {}
        fun onStartedWakingUp() {}
        fun onScreenTurnedOff() {}
        fun onUserChanged() {}
        fun setPulsing(pulsing: Boolean) {}
        fun onNotificationPosted(sbn: StatusBarNotification) {}
        fun onNotificationRemoved(sbn: StatusBarNotification) {}
        fun onKeyguardLayoutChanged() {}
        fun onKeyguardAlphaChanged(alpha: Float) {}
    }

    private val listeners = WeakListenerManager<ScrimEventListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mQsVisible = AtomicBoolean()
    private val mPulsing = AtomicBoolean()
    private val mFadingAwayDuration = 500L

    private val mContext: Context by lazy {
        context ?: throw IllegalStateException("ScrimUtils requires a valid Context")
    }

    private val mScrimController: ScrimController? by lazy {
        try {
            Dependency.get(ScrimController::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private var mWallpaperDepthUtils: WallpaperDepthUtils? = null

    fun setWallpaperDepthUtils(utils: WallpaperDepthUtils) {
        mWallpaperDepthUtils = utils
    }

    @Volatile private var mIsDozing: Boolean? = null
    @Volatile private var mKeyguardShowing: Boolean? = null
    @Volatile private var mExpandedFraction: Float? = null
    @Volatile private var mBarState: Int? = null
    @Volatile private var mAwake: Boolean? = null

    private val mStateIsKeyguard get() = mBarState == SHADE_LOCKED || mBarState == KEYGUARD

    private var keyguardRetryRunnable: Runnable? = null

    companion object {
        private const val LAYOUT_STABLE_DELAY = 350L

        @Volatile private var instance: ScrimUtils? = null

        @JvmStatic
        fun get(): ScrimUtils =
            instance ?: synchronized(this) {
                instance ?: ScrimUtils(null).also { instance = it }
            }

        @JvmStatic
        fun get(context: Context): ScrimUtils =
            instance ?: synchronized(this) {
                instance ?: ScrimUtils(context).also { instance = it }
            }
    }

    fun addListener(listener: ScrimEventListener) = listeners.addListener(listener)
    fun removeListener(listener: ScrimEventListener) = listeners.removeListener(listener)

    fun setKeyguardShowing(showing: Boolean) {
        if (mKeyguardShowing == null || mKeyguardShowing != showing) {
            mKeyguardShowing = showing
            listeners.notifyOnMain { it.onKeyguardShowingChanged(showing) }
            if (showing) {
                mainHandler.postDelayed({
                    mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
                    mWallpaperDepthUtils?.updateDepthWallpaper()
                }, 120)
            }
        }
    }

    fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        listeners.notifyOnMain { it.onKeyguardFadingAwayChanged(fadingAway) }
        postKeyguardRetry()
        if (fadingAway) {
            mWallpaperDepthUtils?.hideDepthWallpaper()
        }
    }

    fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        listeners.notifyOnMain { it.onKeyguardGoingAwayChanged(goingAway) }
        postKeyguardRetry()
        if (goingAway) {
            mWallpaperDepthUtils?.hideDepthWallpaper()
        }
    }

    fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        listeners.notifyOnMain { it.onPrimaryBouncerShowingChanged(showing) }
        postKeyguardRetry()
        mWallpaperDepthUtils?.onBouncerShowingChanged(showing)
    }

    private fun postKeyguardRetry() {
        keyguardRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        keyguardRetryRunnable = Runnable {
            listeners.notifyOnMain { it.onKeyguardShowingChanged(mKeyguardShowing == true) }
        }
        mainHandler.postDelayed(keyguardRetryRunnable!!, mFadingAwayDuration)
    }

    fun setExpandedFraction(fraction: Float) {
        if (mExpandedFraction == null ||
            ((fraction == 0.0f || fraction == 1.0f) && mExpandedFraction != fraction)) {
            mExpandedFraction = fraction
            listeners.notifyOnBackground { it.onExpandedFractionChanged(fraction) }
        }
    }

    fun onDozingChanged(dozing: Boolean) {
        if (mIsDozing == null || mIsDozing != dozing) {
            mIsDozing = dozing
            listeners.notify { it.onDozingChanged(dozing) }
            mWallpaperDepthUtils?.onDozingChanged(dozing)
            // Additional refresh when exiting doze to ensure depth wallpaper appears
            if (!dozing && mStateIsKeyguard) {
                mainHandler.postDelayed({
                    mWallpaperDepthUtils?.updateDepthWallpaper()
                }, 200)
            }
        }
    }

    fun setBarState(state: Int) {
        if (mBarState == null || mBarState != state) {
            mBarState = state
            listeners.notifyOnMain { it.onBarStateChanged(state) }
        }
        // hack 4 bug:
        // 1. user is on keyguard but is mBarState == SHADE
        // 2. keyguard update monitor wrong state when dozing
        val shouldShowKeyguard = mStateIsKeyguard || mIsDozing == true || mPulsing.get()
        setKeyguardShowing(shouldShowKeyguard)
    }

    fun setQsVisible(visible: Boolean) {
        if (mQsVisible.getAndSet(visible) != visible) {
            listeners.notifyOnMain { it.onQsVisibilityChanged(visible) }
            if (!visible && mStateIsKeyguard) {
                mainHandler.postDelayed({
                    mWallpaperDepthUtils?.updateDepthWallpaper()
                    mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
                }, 100)
            }
        }
    }

    fun setPulsing(pulsing: Boolean) {
        if (mPulsing.getAndSet(pulsing) != pulsing) {
            listeners.notify { it.setPulsing(pulsing) }
            if (!pulsing && mStateIsKeyguard) {
                mainHandler.postDelayed({
                    mWallpaperDepthUtils?.updateDepthWallpaper()
                    mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
                }, 120)
            }
        }
    }

    fun onStartedWakingUp() {
        mAwake = true
        listeners.notify { it.onStartedWakingUp() }
        if (mStateIsKeyguard) {
            mainHandler.postDelayed({
                mWallpaperDepthUtils?.updateDepthWallpaper()
                mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
            }, 150)
        }
    }

    fun onScreenTurnedOff() {
        mAwake = false
        listeners.notify { it.onScreenTurnedOff() }
    }

    fun onUserChanged() {
        listeners.notify { it.onUserChanged() }
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        listeners.notifyOnMain { it.onNotificationPosted(sbn) }
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        listeners.notifyOnMain { it.onNotificationRemoved(sbn) }
    }

    private var keyguardRootView: View? = null
    private var layoutChangePending = false
    private var layoutStableRunnable: Runnable? = null
    private val preDrawActions = mutableListOf<Runnable>()

    private val keyguardPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val root = keyguardRootView ?: return@OnPreDrawListener true

        for (action in preDrawActions) {
            action.run()
        }

        if (mKeyguardShowing == true && root.isDirty) {
            if (!layoutChangePending) {
                layoutChangePending = true
                listeners.notifyOnMain { it.onKeyguardLayoutChanged() }
            }
            layoutStableRunnable?.let { mainHandler.removeCallbacks(it) }
            layoutStableRunnable = Runnable { layoutChangePending = false }
            mainHandler.postDelayed(layoutStableRunnable!!, LAYOUT_STABLE_DELAY)
        }
        true
    }

    fun addKeyguardPreDrawAction(action: Runnable) {
        if (!preDrawActions.contains(action)) preDrawActions.add(action)
    }

    fun removeKeyguardPreDrawAction(action: Runnable) {
        preDrawActions.remove(action)
    }

    fun attachKeyguardView(view: View) {
        detachKeyguardView()
        keyguardRootView = view
        view.viewTreeObserver.addOnPreDrawListener(keyguardPreDrawListener)
    }

    fun detachKeyguardView() {
        keyguardRootView?.viewTreeObserver?.removeOnPreDrawListener(keyguardPreDrawListener)
        keyguardRootView = null
        layoutStableRunnable?.let { mainHandler.removeCallbacks(it) }
        layoutStableRunnable = null
        layoutChangePending = false
    }

    fun setKeyguardAlpha(alpha: Float) {
        listeners.notifyOnMain { it.onKeyguardAlphaChanged(alpha) }
    }

    fun isDozing(): Boolean = mIsDozing == true
    fun isAwake(): Boolean = mAwake == true
    fun isPulsing(): Boolean = mPulsing.get()
    fun isKeyguardShowing(): Boolean = mKeyguardShowing == true

    fun isPanelFullyCollapsed(): Boolean =
        if (mStateIsKeyguard) {
            !mQsVisible.get()
        } else {
            (mExpandedFraction ?: 0.0f) <= 0.0f
        }

    // Depth Wallpaper control methods
    fun setViewAlpha(subjectAlpha: Float) {
        mWallpaperDepthUtils?.setSubjectAlpha(subjectAlpha)
    }

    fun setQsExpansion(expansion: Float) {
        val fullyCollapsed = expansion <= 0f
        if (fullyCollapsed) {
            if (mStateIsKeyguard) {
                mWallpaperDepthUtils?.updateDepthWallpaper()
                mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
            }
        } else {
            mWallpaperDepthUtils?.hideDepthWallpaper()
        }
    }

    fun onScreenStateChange() {
        updateDepthWallpaperElements()
        mainHandler.postDelayed({
            updateDepthWallpaperElements()
        }, 250)
    }

    private fun updateDepthWallpaperElements() {
        mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
    }

    fun onScrimDispatched() {
        mWallpaperDepthUtils?.updateDepthWallpaper()
        mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
    }

    fun updateDepthWallpaper() {
        mWallpaperDepthUtils?.updateDepthWallpaper()
    }

    fun updateDepthWallpaperVisibility() {
        mWallpaperDepthUtils?.updateDepthWallpaperVisibility()
    }

    fun hideDepthWallpaper() {
        mWallpaperDepthUtils?.hideDepthWallpaper()
    }

    fun getScrimBehindAlphaKeyguard(): Float {
        return mScrimController?.getScrimBehindAlpha() ?: 0f
    }
}
