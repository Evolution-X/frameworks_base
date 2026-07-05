/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Color.colorToHSV
import android.graphics.Color.HSVToColor
import android.graphics.Typeface
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextClock
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService

class ClockStyle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs), TunerService.Tunable {

    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val random = java.util.Random()

    private var currentClockView: View? = null
    private var clockStub: ViewStub? = null
    private var clockContainer: ViewGroup? = null

    private val textClocks = ArrayList<TextClock>()
    private val styledTextViews = ArrayList<TextView>()

    private var clockStyle = DEFAULT_STYLE
    private var colorMode = COLOR_MODE_DEFAULT
    private var customColor = DEFAULT_CUSTOM_COLOR
    private var clockOpacity = DEFAULT_OPACITY
    private var clockFrameMarginTop = DEFAULT_MARGIN_TOP
    private var clockSizeScale = DEFAULT_CLOCK_SIZE
    private var clockFrameMarginStart = DEFAULT_MARGIN_START

    private var aodAnimEnabled = true
    private var albumArtColorEnabled = false
    private var currentAlbumColor: Int? = null

    private var lastUpdateTimeMillis = 0L
    private var isDozing = false
    private var callbacksRegistered = false

    private var currentShiftX = 0
    private var currentShiftY = 0

    private var naturalClockHeight = 0

    private var pendingLayoutListener: View.OnLayoutChangeListener? = null

    private var clockWobbleOnChargeEnabled = true
    private var wobbleAnimator: ObjectAnimator? = null
    private var wobbleGlowAnimator: android.animation.ValueAnimator? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> forceTimeUpdate()
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                DOZE_PULSE_ACTION -> onTimeChanged()
                Intent.ACTION_POWER_CONNECTED -> {
                    if (clockWobbleOnChargeEnabled && !isDozing) {
                        startWobbleAnimation()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> cancelWobbleAnimation()
            }
        }
    }

    private val aodTickRunnable = object : Runnable {
        override fun run() {
            if (!isDozing || clockStyle == 0) return
            forceTimeUpdate()
            val now = System.currentTimeMillis()
            val nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS
            handler.postDelayed(this, nextMinute - now)
        }
    }

    private val burnInProtectionRunnable = object : Runnable {
        override fun run() {
            if (!isDozing) return
            currentShiftX = random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT
            currentShiftY = random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT
            currentClockView?.apply {
                translationX = currentShiftX.toFloat()
                translationY = currentShiftY.toFloat()
            }
            invalidate()
            handler.postDelayed(this, BURN_IN_PROTECTION_INTERVAL)
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}

        override fun onDozingChanged(dozing: Boolean) {
            if (isDozing == dozing) return
            applyDozing(dozing)
        }
    }

    private val mediaDataListener = object : MediaSessionManager.MediaDataListener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state != PlaybackState.STATE_PLAYING) {
                currentAlbumColor = null
                if (albumArtColorEnabled) applyClockColors()
            }
        }

        override fun onMediaColorsChanged(color: Int) {
            currentAlbumColor = color
            if (albumArtColorEnabled) applyClockColors()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        clockStub = findViewById(R.id.clock_view_stub)
        updateClockView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (callbacksRegistered) return

        tunerService.addTunable(
            this,
            CLOCK_STYLE_KEY,
            CLOCK_COLOR_MODE_KEY,
            CLOCK_CUSTOM_COLOR_KEY,
            CLOCK_TEXT_OPACITY_KEY,
            CLOCK_FRAME_MARGIN_TOP_KEY,
            CLOCK_SIZE_KEY,
            CLOCK_AOD_ANIM_KEY,
            CLOCK_ALBUM_ART_COLOR_KEY,
            CLOCK_WOBBLE_ON_CHARGE_KEY,
            CLOCK_FRAME_MARGIN_START_KEY,
        )
        statusBarStateController.addCallback(statusBarStateListener)
        if (albumArtColorEnabled) {
            MediaSessionManager.get().addListener(mediaDataListener)
        }

        isDozing = statusBarStateController.isDozing
        if (isDozing) {
            applyClockAlpha()
            applyClockColors()
            startBurnInProtection()
            startAodTick()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(DOZE_PULSE_ACTION)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        callbacksRegistered = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!callbacksRegistered) return

        statusBarStateController.removeCallback(statusBarStateListener)
        tunerService.removeTunable(this)
        runCatching { MediaSessionManager.get().removeListener(mediaDataListener) }
        handler.removeCallbacks(burnInProtectionRunnable)
        handler.removeCallbacks(aodTickRunnable)
        currentClockView?.animate()?.cancel()
        cancelWobbleAnimation()
        removePendingLayoutListener()
        runCatching { context.unregisterReceiver(screenReceiver) }
        callbacksRegistered = false
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        when (key) {
            CLOCK_STYLE_KEY -> {
                clockStyle = TunerService.parseInteger(newValue, DEFAULT_STYLE)
                if (clockStyle != 0) {
                    Settings.Secure.putIntForUser(
                        context.contentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        0,
                        UserHandle.USER_CURRENT,
                    )
                }
                updateClockView()
            }
            CLOCK_COLOR_MODE_KEY -> {
                colorMode = newValue ?: COLOR_MODE_DEFAULT
                applyClockColors()
            }
            CLOCK_CUSTOM_COLOR_KEY -> {
                customColor = TunerService.parseInteger(newValue, DEFAULT_CUSTOM_COLOR)
                if (colorMode == COLOR_MODE_CUSTOM) applyClockColors()
            }
            CLOCK_TEXT_OPACITY_KEY -> {
                clockOpacity = TunerService.parseInteger(newValue, DEFAULT_OPACITY)
                    .coerceIn(0, 100)
                applyClockAlpha()
            }
            CLOCK_FRAME_MARGIN_TOP_KEY -> {
                clockFrameMarginTop = TunerService.parseInteger(newValue, DEFAULT_MARGIN_TOP)
                    .coerceIn(0, 200)
                updateClockFrameMargin()
            }
            CLOCK_SIZE_KEY -> {
                clockSizeScale = TunerService.parseInteger(newValue, DEFAULT_CLOCK_SIZE)
                    .coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE)
                applyClockScale()
            }
            CLOCK_AOD_ANIM_KEY -> {
                aodAnimEnabled = TunerService.parseInteger(newValue, 1) != 0
            }
            CLOCK_ALBUM_ART_COLOR_KEY -> {
                val wasEnabled = albumArtColorEnabled
                albumArtColorEnabled = TunerService.parseInteger(newValue, 0) != 0
                when {
                    albumArtColorEnabled && !wasEnabled ->
                        MediaSessionManager.get().addListener(mediaDataListener)
                    !albumArtColorEnabled && wasEnabled -> {
                        runCatching { MediaSessionManager.get().removeListener(mediaDataListener) }
                        currentAlbumColor = null
                    }
                }
                applyClockColors()
            }
            CLOCK_WOBBLE_ON_CHARGE_KEY -> {
                clockWobbleOnChargeEnabled = TunerService.parseInteger(newValue, 1) != 0
                if (!clockWobbleOnChargeEnabled) cancelWobbleAnimation()
            }
            CLOCK_FRAME_MARGIN_START_KEY -> {
                clockFrameMarginStart = TunerService.parseInteger(newValue, DEFAULT_MARGIN_START)
                    .coerceIn(-200, 200)
                updateClockFrameMargin()
            }
        }
    }

    private fun applyDozing(dozing: Boolean) {
        isDozing = dozing
        animateAodTransition(dozing)
        applyClockAlpha()
        applyClockColors()
        if (dozing) {
            startBurnInProtection()
            startAodTick()
        } else {
            stopBurnInProtection()
            stopAodTick()
            forceTimeUpdate()
        }
    }

    private fun startAodTick() {
        if (clockStyle == 0) return
        handler.removeCallbacks(aodTickRunnable)
        val now = System.currentTimeMillis()
        val nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS
        handler.postDelayed(aodTickRunnable, nextMinute - now)
    }

    private fun stopAodTick() {
        handler.removeCallbacks(aodTickRunnable)
    }

    private fun startBurnInProtection() {
        if (clockStyle == 0) return
        handler.removeCallbacks(burnInProtectionRunnable)
        handler.postDelayed(burnInProtectionRunnable, BURN_IN_PROTECTION_INTERVAL)
    }

    private fun stopBurnInProtection() {
        if (clockStyle == 0) return
        handler.removeCallbacks(burnInProtectionRunnable)
        currentClockView?.apply {
            translationX = 0f
            translationY = 0f
        }
    }

    private fun animateAodTransition(toAod: Boolean) {
        val view = currentClockView ?: return
        if (!aodAnimEnabled) return
        view.animate().cancel()
        if (toAod) {
            view.animate()
                .scaleX(AOD_SCALE_DOWN)
                .scaleY(AOD_SCALE_DOWN)
                .alpha(0f)
                .setDuration(AOD_ANIM_OUT_MS)
                .setInterpolator(AccelerateInterpolator(1.5f))
                .withEndAction {
                    applyClockAlpha()
                    view.scaleX = AOD_SCALE_DOWN
                    view.scaleY = AOD_SCALE_DOWN
                }
                .start()
        } else {
            view.scaleX = AOD_SCALE_DOWN
            view.scaleY = AOD_SCALE_DOWN
            val targetScale = getScaleFactor()
            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(clockOpacity / 100f)
                .setDuration(AOD_ANIM_IN_MS)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    fun onTimeChanged() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTimeMillis >= UPDATE_INTERVAL_MILLIS) {
            forceTimeUpdate()
        }
    }

    private fun forceTimeUpdate() {
        if (currentClockView == null) return
        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            (tv.getTag(R.id.original_typeface) as? Typeface)?.let { tv.typeface = it }
        }
        for (i in textClocks.indices) {
            textClocks[i].refreshTime()
        }
        lastUpdateTimeMillis = System.currentTimeMillis()
    }

    private fun updateClockView() {
        removePendingLayoutListener()
        currentClockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentClockView = null
        textClocks.clear()
        styledTextViews.clear()
        naturalClockHeight = 0
        clockContainer?.minimumHeight = 0

        if (clockStyle in 1 until CLOCK_LAYOUTS.size) {
            val stub = clockStub
            if (stub != null) {
                stub.layoutResource = CLOCK_LAYOUTS[clockStyle]
                val inflated = stub.inflate()
                currentClockView = inflated
                clockContainer = inflated.parent as? ViewGroup
                clockStub = null
            } else {
                clockContainer?.let { container ->
                    val inflated = LayoutInflater.from(context)
                        .inflate(CLOCK_LAYOUTS[clockStyle], container, false)
                    currentClockView = inflated
                    container.addView(inflated)
                }
            }

            currentClockView?.let { view ->
                cacheClockViews(view)
                disableClippingOnParents(view)

                view.findViewById<ImageView?>(R.id.user_profile_icon)?.setImageDrawable(
                    UserProfileUtils.getUserProfileIcon(context)
                )
                view.findViewById<TextView?>(R.id.user_name)?.text =
                    UserProfileUtils.getUsername(context)
                view.findViewById<TextView?>(R.id.device_name)?.text =
                    UserProfileUtils.getDeviceName()

                updateClockAppearance()
                updateClockFrameMargin()
            }
        }

        forceTimeUpdate()
        visibility = if (clockStyle != 0) View.VISIBLE else View.GONE
        if (isDozing && clockStyle != 0) startAodTick()
    }

    private fun cacheClockViews(root: View) {
        when (root) {
            is TextClock -> {
                root.setTag(R.id.original_typeface, root.typeface)
                root.setTag(R.id.original_text_color, root.currentTextColor)
                root.setTag(R.id.original_alpha, root.alpha)
                textClocks.add(root)
                styledTextViews.add(root)
            }
            is TextView -> {
                root.setTag(R.id.original_typeface, root.typeface)
                root.setTag(R.id.original_text_color, root.currentTextColor)
                root.setTag(R.id.original_alpha, root.alpha)
                styledTextViews.add(root)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                cacheClockViews(root.getChildAt(i))
            }
        }
    }

    private fun updateClockFrameMargin() {
        val clockFrame = findViewById<View?>(R.id.clock_frame) ?: return
        val params = clockFrame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        params.topMargin = (clockFrameMarginTop * density).toInt()
        if (!isNoHorizontalMarginClock(clockStyle)) {
            params.marginStart = (clockFrameMarginStart * density).toInt()
        } else {
            params.marginStart = 0
        }
        clockFrame.layoutParams = params
    }

    private fun updateClockAppearance() {
        if (currentClockView == null) return
        applyClockAlpha()
        applyClockColors()
        applyClockScaleAfterLayout(currentClockView!!)
    }

    private fun applyClockAlpha() {
        val view = currentClockView ?: return
        val effective = if (isDozing && clockOpacity > AOD_OPACITY_CAP) AOD_OPACITY_CAP else clockOpacity
        view.alpha = effective / 100f
    }

    private fun resolveClockColor(): Int {
        if (albumArtColorEnabled) {
            currentAlbumColor?.let {
                return boostSaturation(it)
            }
        }
        return when (colorMode) {
            COLOR_MODE_ACCENT -> context.getColor(
                resources.getIdentifier("system_accent1_100", "color", "android")
            )
            COLOR_MODE_CUSTOM -> customColor
            else -> context.getColor(android.R.color.white)
        }
    }

    private fun boostSaturation(color: Int, amount: Float = 0.20f): Int {
        val hsv = FloatArray(3)
        colorToHSV(color, hsv)
        hsv[1] = (hsv[1] + amount).coerceAtMost(1.0f)
        return HSVToColor(hsv)
    }

    private fun applyClockColors() {
        if (isNoColorClock(clockStyle) || textClocks.isEmpty()) return
        val whiteColor = context.getColor(android.R.color.white)
        for (i in textClocks.indices) {
            val tc = textClocks[i]
            (tc.getTag(R.id.original_typeface) as? Typeface)?.let { tc.typeface = it }
            val originalColor = tc.getTag(R.id.original_text_color) as? Int ?: tc.currentTextColor
            val isWhiteOriginal = (originalColor and 0x00FFFFFF) == (whiteColor and 0x00FFFFFF)
            tc.setTextColor(
                when {
                    isDozing -> whiteColor
                    !isWhiteOriginal -> originalColor
                    else -> resolveClockColor()
                }
            )
        }

        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            if (tv is TextClock) continue
            val originalColor = tv.getTag(R.id.original_text_color) as? Int ?: tv.currentTextColor
            val isWhiteOriginal = (originalColor and 0x00FFFFFF) == (whiteColor and 0x00FFFFFF)
            tv.setTextColor(
                when {
                    isDozing -> whiteColor
                    !isWhiteOriginal -> originalColor
                    else -> resolveClockColor()
                }
            )
        }
    }

    private fun getScaleFactor(): Float =
        clockSizeScale.coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE) / 100f

    private fun applyClockScale() {
        val view = currentClockView ?: return
        val scale = getScaleFactor()
        view.scaleX = scale
        view.scaleY = scale
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        disableClippingOnParents(view)
        if (naturalClockHeight == 0 && view.height > 0) {
            naturalClockHeight = view.height
        }

        if (naturalClockHeight > 0) {
            val scaledHeight = (naturalClockHeight * scale).toInt()
            val container = clockContainer ?: return
            if (scale >= 1.0f) {
                container.minimumHeight = scaledHeight
                val lp = container.layoutParams
                if (lp != null) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.layoutParams = lp
                }
            } else {
                container.minimumHeight = 0
                val lp = container.layoutParams
                if (lp != null) {
                    lp.height = scaledHeight
                    container.layoutParams = lp
                }
            }
            container.requestLayout()
        }
    }

    private fun applyClockScaleAfterLayout(view: View) {
        if (view.width > 0) {
            applyClockScale()
            return
        }
        removePendingLayoutListener()
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, oR: Int, ob: Int,
            ) {
                v.removeOnLayoutChangeListener(this)
                if (pendingLayoutListener === this) pendingLayoutListener = null
                if (naturalClockHeight == 0) {
                    naturalClockHeight = v.height
                }
                applyClockScale()
            }
        }
        pendingLayoutListener = listener
        view.addOnLayoutChangeListener(listener)
    }

    private fun removePendingLayoutListener() {
        pendingLayoutListener?.let { currentClockView?.removeOnLayoutChangeListener(it) }
        pendingLayoutListener = null
    }

    private fun disableClippingOnParents(view: View) {
        clipChildren = false
        clipToPadding = false
        var current: View? = view
        while (current != null && current !== this) {
            (current as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            val parent = current.parent
            if (parent !is View) break
            current = parent
        }
    }

    private fun isNoColorClock(style: Int): Boolean = NO_COLOR_CLOCKS.contains(style)
    private fun isNoHorizontalMarginClock(style: Int): Boolean = NO_HORIZONTAL_MARGIN_CLOCKS.contains(style)

    private fun startWobbleAnimation() {
        val view = currentClockView ?: return
        cancelWobbleAnimation()

        val base = getScaleFactor()
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val riseAmp = WOBBLE_RISE_DP * density * base
        val ty0 = Keyframe.ofFloat(0.00f,  0f)
        val ty1 = Keyframe.ofFloat(0.18f, -riseAmp)
        val ty2 = Keyframe.ofFloat(0.36f, -riseAmp * 0.44f)
        val ty3 = Keyframe.ofFloat(0.52f, -riseAmp * 0.21f)
        val ty4 = Keyframe.ofFloat(0.66f, -riseAmp * 0.09f)
        val ty5 = Keyframe.ofFloat(0.78f, -riseAmp * 0.04f)
        val ty6 = Keyframe.ofFloat(1.00f,  0f)   
        val pvhY = PropertyValuesHolder.ofKeyframe("translationY", ty0, ty1, ty2, ty3, ty4, ty5, ty6)

        val sx0 = Keyframe.ofFloat(0.00f, base * 1.000f)
        val sx1 = Keyframe.ofFloat(0.18f, base * 0.940f)
        val sx2 = Keyframe.ofFloat(0.36f, base * 1.018f)
        val sx3 = Keyframe.ofFloat(0.52f, base * 0.968f)
        val sx4 = Keyframe.ofFloat(0.66f, base * 1.008f)
        val sx5 = Keyframe.ofFloat(1.00f, base * 1.000f)
        val pvhSX = PropertyValuesHolder.ofKeyframe("scaleX", sx0, sx1, sx2, sx3, sx4, sx5)

        val sy0 = Keyframe.ofFloat(0.00f, base * 1.000f)
        val sy1 = Keyframe.ofFloat(0.18f, base * 1.038f)
        val sy2 = Keyframe.ofFloat(0.36f, base * 0.984f)
        val sy3 = Keyframe.ofFloat(0.52f, base * 1.018f)
        val sy4 = Keyframe.ofFloat(0.66f, base * 0.994f)
        val sy5 = Keyframe.ofFloat(1.00f, base * 1.000f)
        val pvhSY = PropertyValuesHolder.ofKeyframe("scaleY", sy0, sy1, sy2, sy3, sy4, sy5)

        wobbleAnimator = ObjectAnimator.ofPropertyValuesHolder(view, pvhY, pvhSX, pvhSY).apply {
            duration = WOBBLE_DURATION_MS
            interpolator = null
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.translationY = 0f
                    view.scaleX = getScaleFactor()
                    view.scaleY = getScaleFactor()
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    clearWobbleGlow()
                    wobbleAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    view.translationY = 0f
                    view.scaleX = getScaleFactor()
                    view.scaleY = getScaleFactor()
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    clearWobbleGlow()
                    wobbleAnimator = null
                }
            })
            start()
        }

        val baseGlowColor = resolveClockColor()
        val baseHsv = FloatArray(3)
        Color.colorToHSV(baseGlowColor, baseHsv)
        val warmS = 1.0f
        val warmV = 1.0f

        val userAlphaNorm = (clockOpacity.coerceIn(0, 100) / 100f)

        wobbleGlowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WOBBLE_DURATION_MS
            interpolator = null
            addUpdateListener { anim ->
                val animFrac = anim.animatedFraction
                val currentTY = view.translationY
                val fraction = (-currentTY / riseAmp).coerceIn(0f, 1f)
                val flashAlpha: Float = when {
                    animFrac < 0.06f -> userAlphaNorm + (1f - userAlphaNorm) * (animFrac / 0.06f)
                    animFrac < 0.12f -> 1f - (1f - userAlphaNorm) * ((animFrac - 0.06f) / 0.06f)
                    else -> userAlphaNorm
                }

                val lerpedHsv = floatArrayOf(
                    baseHsv[0],
                    baseHsv[1] + (warmS - baseHsv[1]) * fraction,
                    baseHsv[2] + (warmV - baseHsv[2]) * fraction,
                )

                val shiftedBaseColor = Color.HSVToColor(lerpedHsv)
                val radius = WOBBLE_GLOW_MAX_RADIUS * fraction
                val dy = WOBBLE_GLOW_MAX_DY * (1f - fraction)
                val alpha = (WOBBLE_GLOW_MAX_ALPHA * fraction).toInt().coerceIn(0, 255)
                val currentGlowColor = Color.argb(
                    alpha,
                    Color.red(shiftedBaseColor),
                    Color.green(shiftedBaseColor),
                    Color.blue(shiftedBaseColor),
                )

                for (i in textClocks.indices) {
                    val tc = textClocks[i]
                    val origAlpha = tc.getTag(R.id.original_alpha) as? Float ?: 1f
                    tc.alpha = flashAlpha * origAlpha
                    tc.setShadowLayer(radius, 0f, dy, currentGlowColor)
                }
                for (i in styledTextViews.indices) {
                    val tv = styledTextViews[i]
                    if (tv !is android.widget.TextClock) {
                        val origAlpha = tv.getTag(R.id.original_alpha) as? Float ?: 1f
                        tv.alpha = flashAlpha * origAlpha
                        tv.setShadowLayer(radius, 0f, dy, currentGlowColor)
                    }
                }
            }
            start()
        }
    }

    private fun cancelWobbleAnimation() {
        wobbleAnimator?.cancel()
        wobbleAnimator = null
        wobbleGlowAnimator?.cancel()
        wobbleGlowAnimator = null
        clearWobbleGlow()
    }

    private fun clearWobbleGlow() {
        for (i in textClocks.indices) {
            val tc = textClocks[i]
            val origAlpha = tc.getTag(R.id.original_alpha) as? Float ?: 1f
            tc.alpha = (clockOpacity / 100f) * origAlpha
            tc.setShadowLayer(0f, 0f, 0f, 0)
        }
        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            if (tv !is android.widget.TextClock) {
                val origAlpha = tv.getTag(R.id.original_alpha) as? Float ?: 1f
                tv.alpha = (clockOpacity / 100f) * origAlpha
                tv.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    companion object {
        private val CLOCK_LAYOUTS = intArrayOf(
            0,
            R.layout.keyguard_clock_oos,            // 1
            R.layout.keyguard_clock_oos2,           // 2
            R.layout.keyguard_clock_center,         // 3
            R.layout.keyguard_clock_simple,         // 4
            R.layout.keyguard_clock_miui,           // 5
            R.layout.keyguard_clock_ide,            // 6
            R.layout.keyguard_clock_moto,           // 7
            R.layout.keyguard_clock_stylish,        // 8
            R.layout.keyguard_clock_stylish2,       // 9
            R.layout.keyguard_clock_stylish3,       // 10
            R.layout.keyguard_clock_stylish4,       // 11
            R.layout.keyguard_clock_stylish5,       // 12
            R.layout.keyguard_clock_stylish6,       // 13
            R.layout.keyguard_clock_stylish7,       // 14
            R.layout.keyguard_clock_stylish8,       // 15
            R.layout.keyguard_clock_stylish9,       // 16
            R.layout.keyguard_clock_stylish10,      // 17
            R.layout.keyguard_clock_word,           // 18
            R.layout.keyguard_clock_life,           // 19
            R.layout.keyguard_clock_a9,             // 20
            R.layout.keyguard_clock_nos1,           // 21
            R.layout.keyguard_clock_nos2,           // 22
            R.layout.keyguard_clock_num,            // 23
            R.layout.keyguard_clock_accent,         // 24
            R.layout.keyguard_clock_analog,         // 25
            R.layout.keyguard_clock_block,          // 26
            R.layout.keyguard_clock_bubble,         // 27
            R.layout.keyguard_clock_label,          // 28
            R.layout.keyguard_clock_ios,            // 29
            R.layout.keyguard_clock_taden,          // 30
            R.layout.keyguard_clock_mont,           // 31
            R.layout.keyguard_clock_encode,         // 32
            R.layout.keyguard_clock_nos3,           // 33
            R.layout.keyguard_anci_clock_outline,   // 34
            R.layout.keyguard_anci_clock_ovalium,   // 35
            R.layout.keyguard_anci_clock_rectangle, // 36
            R.layout.keyguard_anci_clock_wallet,    // 37
            R.layout.keyguard_anci_clockdate_clavicula, // 38
            R.layout.keyguard_anci_clockdate_kln,   // 39
            R.layout.keyguard_anci_clockdate_miring, // 40
            R.layout.keyguard_anci_clockdate_scapula, // 41
            R.layout.keyguard_anci_clockdate_sternum, // 42
            R.layout.keyguard_sparkCircle,          // 43
            R.layout.keyguard_sparkList,            // 44
            R.layout.keyguard_clock_miui2,          // 45
            R.layout.keyguard_clock_ios2,           // 46
            R.layout.keyguard_clock_ios3,           // 47
            R.layout.keyguard_clock_ios4,           // 48
            R.layout.keyguard_clock_ios5,           // 49
            R.layout.keyguard_clock_ios6,           // 50
            R.layout.keyguard_clock_ios7,           // 51
            R.layout.keyguard_clock_ios8,           // 52
            R.layout.keyguard_clock_ios9,           // 53
            R.layout.keyguard_clock_ios10,          // 54
            R.layout.keyguard_clock_ios11,          // 55
            R.layout.keyguard_clock_ios12,          // 56
            R.layout.keyguard_clock_ios13,          // 57
            R.layout.keyguard_clock_ios14,          // 58
            R.layout.keyguard_clock_ios15,          // 59
            R.layout.keyguard_clock_ios16,          // 60
            R.layout.keyguard_clock_ios17,          // 61
            R.layout.keyguard_clock_ios18,          // 62
            R.layout.keyguard_clock_ios19,          // 63
            R.layout.keyguard_clock_big1,           // 64
            R.layout.keyguard_clock_big2,           // 65
            R.layout.keyguard_clock_big3,           // 66
            R.layout.keyguard_clock_big4,           // 67
            R.layout.keyguard_clock_sweet,          // 68
            R.layout.keyguard_clock_pixel,          // 69
            R.layout.keyguard_clock_samurai,        // 70
            R.layout.keyguard_clock_gateway,        // 71
            R.layout.keyguard_clock_tall,           // 72
            R.layout.keyguard_clock_gobold,         // 73
            R.layout.keyguard_clock_gobold2,        // 74
            R.layout.keyguard_clock_delirium,       // 75
            R.layout.keyguard_clock_deliriumdual,   // 76
            R.layout.keyguard_clock_skewrom,        // 77
            R.layout.keyguard_clock_skewrom2,       // 78
            R.layout.keyguard_clock_taller,         // 79
            R.layout.keyguard_clock_taller2,        // 80
            R.layout.keyguard_clock_taller3,        // 81
            R.layout.keyguard_clock_modak,          // 82
            R.layout.keyguard_clock_galada,         // 83
            R.layout.keyguard_clock_cos1,           // 84
            R.layout.keyguard_clock_cos2,           // 85
            R.layout.keyguard_clock_neu,            // 86
            R.layout.keyguard_clock_neu2,           // 87
            R.layout.keyguard_clock_neu3,           // 88
            R.layout.keyguard_clock_badeen,         // 89
        )

        private val NO_COLOR_CLOCKS = hashSetOf(1, 2, 25, 26)
        private val NO_HORIZONTAL_MARGIN_CLOCKS = hashSetOf(45, 73, 74)

        @JvmField val CLOCK_STYLE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE
        @JvmField val CLOCK_COLOR_MODE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_COLOR_MODE
        @JvmField val CLOCK_CUSTOM_COLOR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_CUSTOM_COLOR
        @JvmField val CLOCK_TEXT_OPACITY_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_OPACITY
        @JvmField val CLOCK_FRAME_MARGIN_TOP_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_TOP
        @JvmField val CLOCK_SIZE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SIZE
        @JvmField val CLOCK_AOD_ANIM_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_AOD_ANIM
        @JvmField val CLOCK_ALBUM_ART_COLOR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_ALBUM_ART_COLOR
        @JvmField val CLOCK_WOBBLE_ON_CHARGE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_WOBBLE_ON_CHARGE
        @JvmField val CLOCK_FRAME_MARGIN_START_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_START

        const val COLOR_MODE_DEFAULT = "default"
        const val COLOR_MODE_ACCENT = "accent"
        const val COLOR_MODE_CUSTOM = "custom"

        private const val DOZE_PULSE_ACTION = "com.android.systemui.doze.pulse"

        private const val DEFAULT_STYLE = 0
        private const val DEFAULT_OPACITY = 100
        private const val DEFAULT_MARGIN_TOP = 15
        private const val DEFAULT_CUSTOM_COLOR = Color.WHITE
        private const val AOD_OPACITY_CAP = 70
        private const val DEFAULT_CLOCK_SIZE = 100
        private const val MIN_CLOCK_SIZE = 50
        private const val MAX_CLOCK_SIZE = 150
        private const val DEFAULT_MARGIN_START = 0

        private const val AOD_UPDATE_INTERVAL_MILLIS = 60_000L
        private const val UPDATE_INTERVAL_MILLIS = 15_000L

        private const val BURN_IN_PROTECTION_INTERVAL = 10_000L
        private const val BURN_IN_PROTECTION_MAX_SHIFT = 4

        private const val AOD_SCALE_DOWN = 0.85f
        private const val AOD_ANIM_OUT_MS = 300L
        private const val AOD_ANIM_IN_MS = 400L

        private const val WOBBLE_RISE_DP = 26f
        private const val WOBBLE_DURATION_MS = 1150L
        private const val WOBBLE_GLOW_MAX_RADIUS = 28f
        private const val WOBBLE_GLOW_MAX_DY = 6f
        private const val WOBBLE_GLOW_MAX_ALPHA = 160
    }
}
