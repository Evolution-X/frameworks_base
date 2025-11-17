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
package com.android.systemui.nowplaying

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import com.android.systemui.res.R

class NowPlayingView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "NowPlayingView"
        const val ICON_STYLE_DISABLED = 0
        const val ICON_STYLE_APP = 1
        const val ICON_STYLE_MUSIC = 2
        private const val MARQUEE_SPEED_PX_PER_SEC = 30f
        private const val MARQUEE_DELAY_MS = 1500L
        private const val MARQUEE_GAP_MULTIPLIER = 3f
    }

    private val trackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    var trackTitle: String = ""
        set(value) {
            field = value
            resetMarquee()
            invalidate()
        }

    var artistName: String = ""
        set(value) {
            field = value
            resetMarquee()
            invalidate()
        }

    var textColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            trackPaint.color = value
            artistPaint.color = value and 0x00FFFFFF or 0xB3000000.toInt()
            updateIconTint()
            invalidate()
        }

    var verticalPosition: Float = 0.98f
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
            invalidate()
        }

    var iconStyle: Int = ICON_STYLE_DISABLED
        set(value) {
            field = value.coerceIn(ICON_STYLE_DISABLED, ICON_STYLE_MUSIC)
            invalidate()
        }

    var iconSizeDp: Int = 24
        set(value) {
            field = value.coerceIn(5, 40)
            updateIconSize()
            invalidate()
        }

    var useCompactStyle: Boolean = false
        set(value) {
            field = value
            resetMarquee()
            invalidate()
        }

    var appPackageName: String = ""
        set(value) {
            field = value
            updateAppIcon()
        }

    private var appIconBitmap: Bitmap? = null
    private var defaultIconBitmap: Bitmap? = null
    private var defaultIconTintedBitmap: Bitmap? = null
    private var iconSize: Int = 0
    private val iconPaddingCompact: Float by lazy { 
        resources.getDimensionPixelSize(R.dimen.nowplaying_icon_padding_compact).toFloat()
    }
    private val iconPaddingNormal: Float by lazy { 
        resources.getDimensionPixelSize(R.dimen.nowplaying_icon_padding_normal).toFloat()
    }
    private val titleArtistGap: Float by lazy {
        resources.getDimensionPixelSize(R.dimen.nowplaying_title_artist_gap).toFloat()
    }

    private var fadeAnimator: ValueAnimator? = null
    private var marqueeAnimator: ValueAnimator? = null
    private var currentAlpha: Float = 0f
    private var marqueeOffset: Float = 0f
    private var needsMarquee: Boolean = false

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false
        val density = resources.displayMetrics.density
        trackPaint.textSize = 14f * density
        artistPaint.textSize = 12f * density
        textColor = 0xFFFFFFFF.toInt()
        updateFontFamily()
        updateIconSize()
        loadDefaultIcon()
    }

    private fun updateFontFamily() {
        try {
            val fontFamily = context.resources.getString(
                com.android.internal.R.string.config_bodyFontFamily
            )
            val typeface = Typeface.create(fontFamily, Typeface.NORMAL)
            trackPaint.typeface = typeface
            artistPaint.typeface = typeface
        } catch (e: Exception) {
            Log.e(TAG, "Error loading system font, using default", e)
            val typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            trackPaint.typeface = typeface
            artistPaint.typeface = typeface
        }
    }

    private fun updateIconSize() {
        iconSize = (iconSizeDp * resources.displayMetrics.density).toInt()
        
        loadDefaultIcon()
        if (appPackageName.isNotEmpty()) {
            updateAppIcon()
        }
    }

    private fun loadDefaultIcon() {
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_music_note_24dp)
            drawable?.let {
                defaultIconBitmap?.recycle()
                defaultIconBitmap = it.toBitmap(iconSize, iconSize)
                updateIconTint()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading default icon", e)
        }
    }

    private fun updateIconTint() {
        defaultIconBitmap?.let { original ->
            try {
                defaultIconTintedBitmap?.recycle()
                val tintedBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
                val paint = Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        textColor,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
                
                val canvas = Canvas(tintedBitmap)
                canvas.drawBitmap(tintedBitmap, 0f, 0f, paint)
                defaultIconTintedBitmap = tintedBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error tinting icon", e)
            }
        }
    }

    private fun updateAppIcon() {
        if (appPackageName.isEmpty()) {
            appIconBitmap?.recycle()
            appIconBitmap = null
            invalidate()
            return
        }

        try {
            val pm = context.packageManager
            val appIcon = pm.getApplicationIcon(appPackageName)
            appIconBitmap?.recycle()
            appIconBitmap = appIcon.toBitmap(iconSize, iconSize)
            invalidate()
        } catch (e: PackageManager.NameNotFoundException) {
            appIconBitmap?.recycle()
            appIconBitmap = null
            invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app icon", e)
        }
    }

    fun show() {
        if (trackTitle.isEmpty()) return
        
        visible = true
        fadeAnimator?.cancel()
        
        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
            duration = 300
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                alpha = currentAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeAnimator = null
                    startMarqueeIfNeeded()
                }
            })
            start()
        }
    }

    fun hide() {
        fadeAnimator?.cancel()
        stopMarquee()
        
        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
            duration = 300
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                alpha = currentAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visible = false
                    fadeAnimator = null
                }
            })
            start()
        }
    }

    private fun resetMarquee() {
        stopMarquee()
        marqueeOffset = 0f
        needsMarquee = false
    }

    private fun stopMarquee() {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
    }

    private fun startMarqueeIfNeeded() {
        if (!visible || !needsMarquee || useCompactStyle) return
        
        postDelayed({
            if (visible && needsMarquee) {
                startMarqueeAnimation()
            }
        }, MARQUEE_DELAY_MS)
    }

    private fun startMarqueeAnimation() {
        if (marqueeAnimator?.isRunning == true) return
        
        val maxWidth = width * 0.55f
        val separator = if (artistName.isNotEmpty()) " ~ " else ""
        val fullText = "$trackTitle$separator$artistName"
        val textWidth = trackPaint.measureText(fullText)
        
        if (textWidth <= maxWidth) return
        
        val gapWidth = trackPaint.measureText(" ") * MARQUEE_GAP_MULTIPLIER
        val totalDistance = textWidth + gapWidth
        val duration = ((totalDistance / MARQUEE_SPEED_PX_PER_SEC) * 1000).toLong()
        
        marqueeAnimator = ValueAnimator.ofFloat(0f, totalDistance).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            
            addUpdateListener { animator ->
                marqueeOffset = animator.animatedValue as Float
                invalidate()
            }
            
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
        fadeAnimator = null
        stopMarquee()
        appIconBitmap?.recycle()
        appIconBitmap = null
        defaultIconBitmap?.recycle()
        defaultIconBitmap = null
        defaultIconTintedBitmap?.recycle()
        defaultIconTintedBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (trackTitle.isEmpty()) return

        val centerX = width / 2f
        val yPosition = height * verticalPosition

        val maxWidth = width * 0.55f

        if (useCompactStyle) {
            drawCompactStyleWithMarquee(canvas, centerX, yPosition, maxWidth)
        } else {
            drawNormalStyle(canvas, centerX, yPosition, maxWidth)
        }
    }

    private fun drawCompactStyleWithMarquee(canvas: Canvas, centerX: Float, yPosition: Float, maxWidth: Float) {
        val separator = if (artistName.isNotEmpty()) " ~ " else ""
        val fullText = "$trackTitle$separator$artistName"
        
        val compactPaint = TextPaint(trackPaint).apply {
            textSize = trackPaint.textSize
            textAlign = Paint.Align.LEFT
        }
        
        val iconToDraw = when (iconStyle) {
            ICON_STYLE_APP -> appIconBitmap
            ICON_STYLE_MUSIC -> defaultIconTintedBitmap
            else -> null
        }
        
        val iconWidth = if (iconToDraw != null) iconSize + iconPaddingCompact else 0f
        val availableTextWidth = maxWidth - iconWidth
        val textWidth = compactPaint.measureText(fullText)
        
        needsMarquee = textWidth > availableTextWidth
        
        if (needsMarquee) {
            val gapWidth = compactPaint.measureText(" ") * MARQUEE_GAP_MULTIPLIER
            val totalWidth = textWidth + gapWidth
            
            val startX = centerX - (maxWidth / 2f)
            
            if (iconToDraw != null) {
                val iconX = startX
                val iconY = yPosition - (iconSize / 2f) - (compactPaint.textSize / 4f)
                canvas.drawBitmap(iconToDraw, iconX, iconY, null)
            }
            
            val textStartX = startX + iconWidth
            canvas.save()
            canvas.clipRect(textStartX, 0f, startX + maxWidth, height.toFloat())
            
            val offset1 = -marqueeOffset
            val offset2 = offset1 + totalWidth
            
            canvas.drawText(fullText, textStartX + offset1, yPosition, compactPaint)
            canvas.drawText(fullText, textStartX + offset2, yPosition, compactPaint)
            canvas.restore()

            if (visible && marqueeAnimator?.isRunning != true) {
                postDelayed({ startMarqueeAnimation() }, MARQUEE_DELAY_MS)
            }
        } else {
            stopMarquee()
            val totalContentWidth = iconWidth + textWidth
            val startX = centerX - (totalContentWidth / 2f)
            
            if (iconToDraw != null) {
                val iconX = startX
                val iconY = yPosition - (iconSize / 2f) - (compactPaint.textSize / 4f)
                canvas.drawBitmap(iconToDraw, iconX, iconY, null)
            }
            
            val textX = startX + iconWidth
            canvas.drawText(fullText, textX, yPosition, compactPaint)
        }
    }

    private fun drawNormalStyle(canvas: Canvas, centerX: Float, yPosition: Float, maxWidth: Float) {
        var currentY = yPosition
        
        val iconToDraw = when (iconStyle) {
            ICON_STYLE_APP -> appIconBitmap
            ICON_STYLE_MUSIC -> defaultIconTintedBitmap
            else -> null
        }
        
        if (iconToDraw != null) {
            val iconX = centerX - (iconSize / 2f)
            val iconY = currentY - iconSize - iconPaddingNormal
            canvas.drawBitmap(iconToDraw, iconX, iconY, null)
        }
        
        val trackWidth = trackPaint.measureText(trackTitle)
        needsMarquee = trackWidth > maxWidth
        
        if (needsMarquee) {
            val gapWidth = trackPaint.measureText(" ") * MARQUEE_GAP_MULTIPLIER
            val totalWidth = trackWidth + gapWidth
            
            canvas.save()
            canvas.clipRect(centerX - maxWidth/2f, 0f, centerX + maxWidth/2f, height.toFloat())
            
            val offset1 = centerX - maxWidth/2f - marqueeOffset
            val offset2 = offset1 + totalWidth
            
            val tempPaint = TextPaint(trackPaint).apply {
                textAlign = Paint.Align.LEFT
            }
            
            canvas.drawText(trackTitle, offset1, currentY, tempPaint)
            canvas.drawText(trackTitle, offset2, currentY, tempPaint)
            
            canvas.restore()
            
            if (visible && marqueeAnimator?.isRunning != true) {
                postDelayed({ startMarqueeAnimation() }, MARQUEE_DELAY_MS)
            }
        } else {
            stopMarquee()
            val truncatedTrack = TextUtils.ellipsize(
                trackTitle,
                trackPaint,
                maxWidth,
                TextUtils.TruncateAt.END
            ).toString()
            
            canvas.drawText(truncatedTrack, centerX, currentY, trackPaint)
        }

        if (artistName.isNotEmpty()) {
            val truncatedArtist = TextUtils.ellipsize(
                artistName,
                artistPaint,
                maxWidth,
                TextUtils.TruncateAt.END
            ).toString()
            
            val artistY = currentY + trackPaint.textSize + titleArtistGap
            canvas.drawText(truncatedArtist, centerX, artistY, artistPaint)
        }
    }

    fun updateTextSize(trackSize: Float, artistSize: Float) {
        val density = resources.displayMetrics.density
        trackPaint.textSize = trackSize * density
        artistPaint.textSize = artistSize * density
        resetMarquee()
        invalidate()
    }
}