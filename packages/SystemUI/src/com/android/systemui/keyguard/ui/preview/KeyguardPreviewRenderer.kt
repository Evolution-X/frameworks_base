/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.preview

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.content.theming.ThemeStyle
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.DisplayInfo
import android.view.LayoutInflater
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.InputTransferToken
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.view.isInvisible
import com.android.app.tracing.coroutines.runBlockingTraced as runBlocking
import com.android.keyguard.ClockEventController
import com.android.systemui.Flags
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewClockViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewSmartspaceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.wallpapers.domain.interactor.WallpaperFocalAreaInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

@AssistedFactory
interface KeyguardPreviewRendererFactory {
    fun create(
        previewViewModel: KeyguardPreviewViewModel,
        clockViewModel: KeyguardPreviewClockViewModel,
        smartspaceViewModel: KeyguardPreviewSmartspaceViewModel,
    ): KeyguardPreviewRenderer
}

/** Renders the preview of the lock screen. */
class KeyguardPreviewRenderer
@AssistedInject
constructor(
    @Application private val context: Context,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Assisted private val previewViewModel: KeyguardPreviewViewModel,
    @Assisted private val clockViewModel: KeyguardPreviewClockViewModel,
    @Assisted private val smartspaceViewModel: KeyguardPreviewSmartspaceViewModel,
    private val quickAffordancesCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel,
    displayManager: DisplayManager,
    private val windowManager: WindowManager,
    private val clockController: ClockEventController,
    private val clockRegistry: ClockRegistry,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
    private val indicationController: KeyguardIndicationController,
    private val secureSettings: SecureSettings,
    private val defaultShortcutsSection: DefaultShortcutsSection,
    private val keyguardQuickAffordanceViewBinder: KeyguardQuickAffordanceViewBinder,
    private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor,
) {
    private lateinit var host: SurfaceControlViewHost

    private var _surfacePackage: SurfaceControlViewHost.SurfacePackage? = null
    val surfacePackage: SurfaceControlViewHost.SurfacePackage
        get() = _surfacePackage ?: checkNotNull(host.surfacePackage).also { _surfacePackage = it }

    private var smartSpaceView: View? = null
    private var largeDateView: View? = null
    private var smallDateView: View? = null

    private val disposables = DisposableHandles()
    private var isDestroyed = false

    private val shortcutsBindings = mutableSetOf<KeyguardQuickAffordanceViewBinder.Binding>()

    @ThemeStyle.Type private var themeStyle: Int? = null

    init {
        clockController.isPreview = true
        clockController.registerListeners()
        disposables += DisposableHandle { clockController.unregisterListeners() }
        clockController.setFallbackWeatherData(WeatherData.getPlaceholderWeatherData())

        quickAffordancesCombinedViewModel.enablePreviewMode(
            initiallySelectedSlotId =
                previewViewModel.request.getString(KEY_INITIALLY_SELECTED_SLOT_ID)
                    ?: KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
            shouldHighlightSelectedAffordance = previewViewModel.shouldHighlightSelectedAffordance,
        )

        if (Flags.doNotUseRunBlocking()) {
            mainHandler.post { provideSurfaceControlViewHost(displayManager) }
        } else {
            runBlocking(context = mainDispatcher) { provideSurfaceControlViewHost(displayManager) }
        }
    }

    private fun provideSurfaceControlViewHost(displayManager: DisplayManager) {
        host =
            SurfaceControlViewHost(
                context,
                displayManager.getDisplay(DEFAULT_DISPLAY),
                previewViewModel.hostToken?.let { InputTransferToken(it) },
                TAG,
            )
        disposables += DisposableHandle {
            _surfacePackage?.release()
            _surfacePackage = null
            host.release()
        }
    }

    fun render() {
        mainHandler.post {
            val previewContext = previewViewModel.previewContext
            val rootView = ConstraintLayout(previewContext)

            setupKeyguardRootView(previewContext, rootView)

            val displayInfo =
                previewViewModel.display?.let {
                    val displayInfo = DisplayInfo()
                    it.getDisplayInfo(displayInfo)
                    displayInfo
                }

            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(
                    displayInfo?.logicalWidth ?: windowManager.currentWindowMetrics.bounds.width(),
                    View.MeasureSpec.EXACTLY,
                ),
                View.MeasureSpec.makeMeasureSpec(
                    displayInfo?.logicalHeight
                        ?: windowManager.currentWindowMetrics.bounds.height(),
                    View.MeasureSpec.EXACTLY,
                ),
            )
            rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

            // This aspect scales the view to fit in the surface and centers it
            val scale: Float =
                (previewViewModel.targetWidth / rootView.measuredWidth.toFloat()).coerceAtMost(
                    previewViewModel.targetHeight / rootView.measuredHeight.toFloat()
                )

            rootView.scaleX = scale
            rootView.scaleY = scale
            rootView.pivotX = 0f
            rootView.pivotY = 0f
            rootView.translationX = (previewViewModel.targetWidth - scale * rootView.width) / 2
            rootView.translationY = (previewViewModel.targetHeight - scale * rootView.height) / 2

            if (isDestroyed) {
                return@post
            }

            host.setView(rootView, rootView.measuredWidth, rootView.measuredHeight)
        }
    }

    fun onStartCustomizingQuickAffordances(initiallySelectedSlotId: String?) {
        quickAffordancesCombinedViewModel.enablePreviewMode(
            initiallySelectedSlotId = initiallySelectedSlotId,
            shouldHighlightSelectedAffordance = true,
        )
    }

    fun onSlotSelected(slotId: String) {
        quickAffordancesCombinedViewModel.onPreviewSlotSelected(slotId = slotId)
    }

    fun onPreviewQuickAffordanceSelected(slotId: String, quickAffordanceId: String) {
        quickAffordancesCombinedViewModel.onPreviewQuickAffordanceSelected(
            slotId,
            quickAffordanceId,
        )
    }

    fun onDefaultPreview() {
        quickAffordancesCombinedViewModel.onClearPreviewQuickAffordances()
        quickAffordancesCombinedViewModel.enablePreviewMode(
            initiallySelectedSlotId = null,
            shouldHighlightSelectedAffordance = false,
        )
    }

    fun onClockSizeSelected(clockSize: ClockSizeSetting) {
        if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
            when (clockSize) {
                ClockSizeSetting.DYNAMIC -> {
                    if (clockViewModel.shouldSmallDateWeatherBeBelowLargeClock()) {
                        largeDateView?.post {
                            smallDateView?.visibility = View.GONE
                            largeDateView?.visibility = View.VISIBLE
                        }
                    } else {
                        largeDateView?.post {
                            smallDateView?.visibility = View.VISIBLE
                            largeDateView?.visibility = View.GONE
                        }
                    }
                }

                ClockSizeSetting.SMALL -> {
                    largeDateView?.post {
                        smallDateView?.visibility = View.VISIBLE
                        largeDateView?.visibility = View.GONE
                    }
                }
            }
            smartSpaceView?.post { smartSpaceView?.visibility = View.GONE }
        }
    }

    fun destroy() {
        isDestroyed = true
        lockscreenSmartspaceController.disconnect()
        disposables.dispose()
        shortcutsBindings.forEach { it.destroy() }
    }

    /**
     * Hides or shows smartspace
     *
     * @param hide TRUE hides smartspace, FALSE shows smartspace
     */
    fun hideSmartspace(hide: Boolean) {
        mainHandler.post {
            smartSpaceView?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
            if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
                clockViewModel.setShowClock(!hide)
            }
        }
    }

    /**
     * This sets up and shows a non-interactive smart space
     *
     * The top padding is as follows: Status bar height + clock top margin + keyguard smart space
     * top offset
     *
     * The start padding is as follows: Clock padding start + Below clock padding start
     *
     * The end padding is as follows: Below clock padding end
     */
    private fun setUpSmartspace(previewContext: Context, parentView: ConstraintLayout) {
        if (
            !lockscreenSmartspaceController.isEnabled ||
                !lockscreenSmartspaceController.isDateWeatherDecoupled
        ) {
            return
        }

        if (smartSpaceView != null) {
            parentView.removeView(smartSpaceView)
        }

        if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
            val cs = ConstraintSet()
            cs.clone(parentView)
            cs.apply {
                largeDateView =
                    lockscreenSmartspaceController.buildAndConnectDateView(previewContext, true)

                smallDateView =
                    lockscreenSmartspaceController.buildAndConnectDateView(previewContext, false)
                largeDateView?.let { parentView.addView(it) }
                smallDateView?.let { parentView.addView(it) }
            }
            cs.applyTo(parentView)
        } else {
            smartSpaceView =
                lockscreenSmartspaceController.buildAndConnectDateView(
                    previewContext,
                    isLargeClock = false,
                )

            val topPadding: Int =
                smartspaceViewModel.getLargeClockSmartspaceTopPadding(
                    previewContext,
                    previewViewModel.buildPreviewConfig(),
                )
            val startPadding: Int = smartspaceViewModel.getDateWeatherStartPadding(previewContext)
            val endPadding: Int = smartspaceViewModel.getDateWeatherEndPadding(previewContext)

            smartSpaceView?.let {
                it.setPaddingRelative(startPadding, topPadding, endPadding, 0)
                it.isClickable = false
                it.isInvisible = true
                parentView.addView(
                    it,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                it.alpha = smartspaceViewModel.previewAlpha
            }
        }
    }

    private fun setupKeyguardRootView(previewContext: Context, rootView: ConstraintLayout) {
        val keyguardRootView = KeyguardRootView(previewContext, null)
        rootView.addView(
            keyguardRootView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setUpUdfps(previewContext, keyguardRootView)

        setupShortcuts(keyguardRootView)

        if (!clockViewModel.shouldHideClock) {
            setUpClock(previewContext, rootView)
            if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
                setUpSmartspace(previewContext, keyguardRootView)
                KeyguardPreviewSmartspaceViewBinder.bind(
                    keyguardRootView,
                    smartspaceViewModel,
                    previewViewModel,
                )
            }
            KeyguardPreviewClockViewBinder.bind(
                keyguardRootView,
                clockViewModel,
                clockRegistry,
                ::updateClockAppearance,
                previewViewModel.buildPreviewConfig(),
            )
        }

        if (!com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
            setUpSmartspace(previewContext, keyguardRootView)
            smartSpaceView?.let {
                KeyguardPreviewSmartspaceViewBinder.bind(
                    it,
                    smartspaceViewModel,
                    previewViewModel.buildPreviewConfig(),
                )
            }
        }
    }

    private fun setupShortcuts(keyguardRootView: ConstraintLayout) {
        // Add shortcuts
        val cs = ConstraintSet()
        cs.clone(keyguardRootView)
        defaultShortcutsSection.addViews(keyguardRootView)
        defaultShortcutsSection.applyConstraints(cs)
        cs.applyTo(keyguardRootView)

        keyguardRootView.findViewById<LaunchableImageView?>(R.id.start_button)?.let { imageView ->
            shortcutsBindings.add(
                keyguardQuickAffordanceViewBinder.bind(
                    view = imageView,
                    viewModel = quickAffordancesCombinedViewModel.startButton,
                    alpha = flowOf(1f),
                ) { message ->
                    indicationController.showTransientIndication(message)
                }
            )
        }

        keyguardRootView.findViewById<LaunchableImageView?>(R.id.end_button)?.let { imageView ->
            shortcutsBindings.add(
                keyguardQuickAffordanceViewBinder.bind(
                    view = imageView,
                    viewModel = quickAffordancesCombinedViewModel.endButton,
                    alpha = flowOf(1f),
                ) { message ->
                    indicationController.showTransientIndication(message)
                }
            )
        }
    }

    private fun setUpUdfps(previewContext: Context, parentView: ViewGroup) {
        val sensorBounds = udfpsOverlayInteractor.udfpsOverlayParams.value.sensorBounds

        // If sensorBounds are default rect, then there is no UDFPS
        if (sensorBounds == Rect()) {
            return
        }

        val finger =
            LayoutInflater.from(previewContext)
                .inflate(R.layout.udfps_keyguard_preview, parentView, false) as View

        // Place the UDFPS view in the proper sensor location
        val lockViewId = KeyguardPreviewClockViewBinder.lockViewId
        finger.id = lockViewId
        parentView.addView(finger)
        ConstraintSet().apply {
            clone(parentView as ConstraintLayout)
            constrainWidth(lockViewId, sensorBounds.width())
            constrainHeight(lockViewId, sensorBounds.height())
            connect(lockViewId, TOP, PARENT_ID, TOP, sensorBounds.top)
            connect(lockViewId, START, PARENT_ID, START, sensorBounds.left)
            applyTo(parentView)
        }
    }

    private fun setUpClock(previewContext: Context, parentView: ViewGroup) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    clockController.clock?.run {
                        smallClock.events.onTimeTick()
                        largeClock.events.onTimeTick()
                    }
                }
            }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
        )
        disposables += DisposableHandle { broadcastDispatcher.unregisterReceiver(receiver) }
    }

    private suspend fun updateClockAppearance(clock: ClockController, resources: Resources) {
        val colors = previewViewModel.wallpaperColors
        if (clockRegistry.seedColor == null && colors != null) {
            // Seed color null means users do not override any color on the clock. The default
            // color will need to use wallpaper's extracted color and consider if the
            // wallpaper's color is dark or light.
            @ThemeStyle.Type
            val style = themeStyle ?: fetchThemeStyleFromSetting().also { themeStyle = it }
            val wallpaperColorScheme = ColorScheme(colors, false, style)
            val lightClockColor = wallpaperColorScheme.accent1.s100
            val darkClockColor = wallpaperColorScheme.accent2.s600

            // Note that when [wallpaperColors] is null, isWallpaperDark is true.
            val isWallpaperDark: Boolean =
                (colors.colorHints.and(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)) == 0
            val theme =
                ThemeConfig(
                    isWallpaperDark,
                    if (isWallpaperDark) lightClockColor else darkClockColor,
                )
            clock.smallClock.events.onThemeChanged(theme)
            clock.largeClock.events.onThemeChanged(theme)
        }
        // In clock preview, we should have a seed color for clock
        // before setting clock to clockEventController to avoid updateColor with seedColor == null
        // So in update colors, it should already have the correct theme in clockFaceController
        clockController.clock = clock
        // When set clock to clockController,it will reset fontsize based on context.resources
        // We need to override it with overlaid resources
        clock.largeClock.events.onFontSettingChanged(
            resources.getDimensionPixelSize(clocksR.dimen.large_clock_text_size).toFloat()
        )
    }

    @ThemeStyle.Type
    private suspend fun fetchThemeStyleFromSetting(): Int {
        val overlayPackageJson =
            withContext(backgroundDispatcher) {
                secureSettings.getString(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES)
            }
        return if (!overlayPackageJson.isNullOrEmpty()) {
            try {
                val jsonObject = JSONObject(overlayPackageJson)
                ThemeStyle.valueOf(jsonObject.getString(OVERLAY_CATEGORY_THEME_STYLE))
            } catch (e: (JSONException)) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e)
                ThemeStyle.TONAL_SPOT
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e)
                ThemeStyle.TONAL_SPOT
            }
        } else {
            ThemeStyle.TONAL_SPOT
        }
    }

    companion object {
        private const val TAG = "KeyguardPreviewRenderer"
        private const val OVERLAY_CATEGORY_THEME_STYLE = "android.theme.customization.theme_style"
    }
}
