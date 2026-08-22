/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
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
package com.android.systemui.nowplaying.ambient

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.monet.ColorScheme
import com.android.systemui.res.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A non-Pixel, self-contained port of Google's AmbientIndicationContainer visual/animation
 * language, driven entirely by NowPlayingViewController's own MediaSessionManager data rather
 * than com.google.android.as broadcasts. Only started on devices where
 * PixelAmbientIndicationDetector.shouldUseNativeAmbientIndication() returns false.
 *
 * Supports collapsed-pill <-> expanded-card transitions with album-art backgrounds, since that
 * data comes from plain MediaMetadata bitmaps rather than any Google-specific broadcast. Does
 * NOT support like/play action buttons, since those require dmpIntent/dmpPackageName/favoriting
 * data this fork has no source for.
 */
class NowPlayingAmbientContainer(context: Context, attrs: AttributeSet?) :
    FrameLayout(context, attrs) {

    companion object {
        private const val ICON_STYLE_DISABLED = 0
        private const val ICON_STYLE_APP = 1
        private const val ICON_STYLE_MUSIC = 2
        private const val MIN_LYRIC_UPDATE_INTERVAL_MS = 250L

        fun inflate(context: Context): NowPlayingAmbientContainer {
            return LayoutInflater.from(context)
                .inflate(R.layout.nowplaying_ambient_indication, null) as NowPlayingAmbientContainer
        }
    }

    private lateinit var wrapperContainer: FrameLayout
    private lateinit var background: ImageView
    private lateinit var extendedContainer: FrameLayout
    private lateinit var collapsedContainer: LinearLayout
    private lateinit var iconView: ImageView
    private lateinit var textContainer: FrameLayout
    private lateinit var realTextSet: LinearLayout
    private lateinit var textView: TextView
    private lateinit var textViewExtended: TextView
    private lateinit var tempTextSet: LinearLayout
    private lateinit var tempTextView: TextView
    private lateinit var tempTextViewExtended: TextView

    private var initialized = false

    private var iconStyle: Int = ICON_STYLE_DISABLED
    private var appPackageName: String = ""
    private var appIconCache: LruCache<String, Drawable> = LruCache(5)
    private var musicNoteIcon: Drawable? = null
    private var iconSizePx: Int = dpToPx(24)

    private var trackText: CharSequence = ""
    private var artistText: CharSequence = ""
    private var lyricLine: CharSequence? = null
    private var lyricsModeEnabled: Boolean = false
    private var lyricFadeOutAnim: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var lyricFadeInAnim: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var lastLyricUpdateMs: Long = 0L

    private var textColor: Int = 0xFFFFFFFF.toInt()

    private var isVisibleAmbient: Boolean = false
    private var isExpanded: Boolean = false
    private var expandOnTap: Boolean = false

    private var currentAlbumArt: Bitmap? = null
    private var currentAlbumArtBitmapKey: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by NowPlayingViewController. Optional — taps still work without them, just unguarded. */
    var falsingManager: FalsingManager? = null
    var powerInteractor: PowerInteractor? = null

    /** True while the ambient pill/card is showing (post-animation-complete). */
    val isAmbientVisible: Boolean
        get() = isVisibleAmbient

    override fun onFinishInflate() {
        super.onFinishInflate()
        wrapperContainer = findViewById(R.id.nowplaying_ambient_wrapper_container)
        background = findViewById(R.id.nowplaying_ambient_background)
        extendedContainer = findViewById(R.id.nowplaying_ambient_extended_container)
        collapsedContainer = findViewById(R.id.nowplaying_ambient_collapsed_container)
        iconView = findViewById(R.id.nowplaying_ambient_icon)
        textContainer = findViewById(R.id.nowplaying_ambient_text_container)
        realTextSet = findViewById(R.id.nowplaying_ambient_text_set_real)
        textView = findViewById(R.id.nowplaying_ambient_text)
        textViewExtended = findViewById(R.id.nowplaying_ambient_text_extended)
        tempTextSet = findViewById(R.id.nowplaying_ambient_text_set_temp)
        tempTextView = findViewById(R.id.nowplaying_ambient_text_temp)
        tempTextViewExtended = findViewById(R.id.nowplaying_ambient_text_extended_temp)

        applyRoundedOutline(wrapperContainer, 32)
        applyRoundedOutline(iconView, 12)

        alpha = 0f
        visibility = View.INVISIBLE
        initialized = true

        collapsedContainer.setOnClickListener { onCollapsedContainerClick() }

        updateColors()
        renderCurrentState()
    }

    private fun applyRoundedOutline(view: View, radiusDp: Int) {
        val radiusPx = dpToPx(radiusDp).toFloat()
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
        view.clipToOutline = true
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // region Public API — called from NowPlayingViewController

    fun setExpandOnTap(enabled: Boolean) {
        expandOnTap = enabled
        if (!enabled && isExpanded) performCollapse()
    }

    fun setTrackAndArtist(track: String, artist: String, albumArt: Bitmap?) {
        trackText = track
        artistText = artist
        val artChanged = albumArt !== currentAlbumArt
        currentAlbumArt = albumArt
        if (artChanged) currentAlbumArtBitmapKey++
        if (initialized) {
            renderCurrentState()
            if (isExpanded && artChanged) bindArtworkAsync()
        }
    }

    fun setAppPackageName(packageName: String) {
        appPackageName = packageName
        if (initialized && iconStyle == ICON_STYLE_APP) renderCurrentState()
    }

    fun setIconStyle(style: Int, sizeDp: Int) {
        iconStyle = style.coerceIn(ICON_STYLE_DISABLED, ICON_STYLE_MUSIC)
        val newSizePx = dpToPx(sizeDp)
        if (newSizePx != iconSizePx) {
            iconSizePx = newSizePx
            appIconCache.evictAll()
        }
        applyIconSize()
        if (initialized) renderCurrentState()
    }

    /**
     * Intentionally does not resize the ImageView's own layout bounds. Mirrors
     * AmbientIndicationContainer's ambient_indication_icon: a fixed 48dp box with
     * scaleType="center", so the glyph sits centered with breathing room around it
     * rather than flush against the box edge. The glyph itself is sized to iconSizePx
     * via wrapToIconSize() in updateIconDrawable().
     */
    private fun applyIconSize() {
        appIconCache.evictAll()
    }

    fun setTextColor(color: Int) {
        textColor = color
        if (initialized) updateColors()
    }

    fun setTrackAndArtistTextSize(trackSp: Float, artistSp: Float) {
        if (!initialized) return
        textView.textSize = trackSp
        tempTextView.textSize = trackSp
        textViewExtended.textSize = artistSp
        tempTextViewExtended.textSize = artistSp
    }

    fun setLyricsModeEnabled(enabled: Boolean) {
        lyricsModeEnabled = enabled
        if (!enabled) lyricLine = null
        if (initialized) renderCurrentState()
    }

    /**
     * Cheap update path for lyric line changes. Deliberately does NOT go through
     * renderCurrentState()'s song-change slide animation — lyric lines can update every
     * few seconds independent of the track itself, and routing that through the same
     * spring-animated slide used for track changes would fire an expensive transition on
     * every lyric tick.
     */
    fun setLyricLine(line: String?) {
        if (!lyricsModeEnabled || !initialized) {
            lyricLine = line
            return
        }
        val previous = lyricLine
        val normalized = line?.trim()
        val previousNormalized = previous?.trim()
        if (TextUtils.equals(previousNormalized, normalized)) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLyricUpdateMs < MIN_LYRIC_UPDATE_INTERVAL_MS) return
        lastLyricUpdateMs = now

        lyricLine = line

        if (!isVisibleAmbient) {
            textViewExtended.text = if (line.isNullOrEmpty()) artistText else line
            return
        }

        lyricFadeOutAnim?.cancel()
        lyricFadeInAnim?.cancel()

        val targetLine = line
        lyricFadeOutAnim = NowPlayingAmbientAnimationUtils.animateAlpha(
            textViewExtended,
            0f,
            null,
            Runnable {
                if (targetLine != lyricLine) return@Runnable
                textViewExtended.text = if (targetLine.isNullOrEmpty()) artistText else targetLine
                lyricFadeInAnim = NowPlayingAmbientAnimationUtils.animateAlpha(
                    textViewExtended,
                    1f,
                    null,
                    null,
                    NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
                )
            },
            NowPlayingAmbientAnimationUtils.fastEffectsSpec,
        )
    }

    fun showAmbient() {
        if (isVisibleAmbient) return
        isVisibleAmbient = true
        visibility = View.VISIBLE
        renderCurrentState()
        NowPlayingAmbientAnimationUtils.animateAlpha(
            this,
            1f,
            null,
            null,
            NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
        )
        translationY = dpToPx(12).toFloat()
        NowPlayingAmbientAnimationUtils.animateTranslationY(
            this,
            0f,
            null,
            null,
            NowPlayingAmbientAnimationUtils.defaultSpatialSpec,
        )
    }

    fun hideAmbient() {
        if (!isVisibleAmbient) return
        isVisibleAmbient = false
        if (isExpanded) performCollapse()
        NowPlayingAmbientAnimationUtils.animateAlpha(
            this,
            0f,
            null,
            Runnable { visibility = View.INVISIBLE },
            NowPlayingAmbientAnimationUtils.fastEffectsSpec,
        )
    }

    /**
     * Collapses the expanded album-art card if currently expanded. Public entry point for
     * tap-outside-to-collapse, driven by NowPlayingViewController observing keyguard root view
     * taps.
     */
    fun collapseIfExpanded() {
        if (isExpanded) performCollapse()
    }

    // endregion

    private fun onCollapsedContainerClick() {
        val falsing = falsingManager
        if (falsing != null && falsing.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }
        if (expandOnTap && !isExpanded && currentAlbumArt != null) {
            powerInteractor?.wakeUpIfDozing(
                "NOW_PLAYING_AMBIENT_CLICK",
                android.os.PowerManager.WAKE_REASON_GESTURE,
            )
            performExpand()
        }
    }

    private fun renderCurrentState() {
        if (!initialized) return

        val displayText: CharSequence = trackText
        val displayArtistOrLyric: CharSequence =
            if (lyricsModeEnabled && !lyricLine.isNullOrEmpty()) lyricLine!! else artistText

        val hasContent = displayText.isNotEmpty()
        val previousText = textView.text
        val textChanged = !TextUtils.equals(previousText, displayText)

        updateIconDrawable()

        if (textChanged && previousText.isNotEmpty() && hasContent && !isExpanded) {
            runSongChangeSlideAnimation(displayText, displayArtistOrLyric)
        } else {
            textView.text = displayText
            textViewExtended.text = displayArtistOrLyric
            textViewExtended.visibility =
                if (displayArtistOrLyric.isNotEmpty()) View.VISIBLE else View.GONE
        }

        textContainer.visibility = if (hasContent) View.VISIBLE else View.GONE
        wrapperContainer.visibility = if (hasContent) View.VISIBLE else View.GONE

        updateContentDescription(displayText, artistText, hasContent)

        if (!hasContent && isExpanded) {
            performCollapse()
        }
    }

    private fun updateContentDescription(
        track: CharSequence,
        artist: CharSequence,
        hasContent: Boolean,
    ) {
        if (!hasContent) {
            collapsedContainer.contentDescription = null
            collapsedContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            return
        }
        val description = if (artist.isNotEmpty()) {
            context.getString(R.string.now_playing_track_and_artist_description, track, artist)
        } else {
            track
        }
        collapsedContainer.contentDescription = description
        collapsedContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun updateIconDrawable() {
        if (isExpanded) return // icon is driven by bindArtworkAsync()/updateColorScheme() while expanded
        val drawable: Drawable? =
            when (iconStyle) {
                ICON_STYLE_APP -> getAppIconDrawable()
                ICON_STYLE_MUSIC -> getMusicNoteIcon()
                else -> null
            }
        val bounded = drawable?.let { wrapToIconSize(it) }
        iconView.setImageDrawable(bounded)
        iconView.visibility = if (bounded != null) View.VISIBLE else View.GONE
    }

    private fun wrapToIconSize(drawable: Drawable): Drawable {
        return object : DrawableWrapper(drawable) {
            override fun getIntrinsicWidth(): Int = iconSizePx
            override fun getIntrinsicHeight(): Int = iconSizePx
        }
    }

    private fun getMusicNoteIcon(): Drawable? {
        if (musicNoteIcon == null) {
            musicNoteIcon = context.getDrawable(R.drawable.ic_now_playing_lockscreen)?.mutate()
        }
        return musicNoteIcon
    }

    private fun getAppIconDrawable(): Drawable? {
        if (appPackageName.isEmpty()) return null
        appIconCache.get(appPackageName)?.let { return it }
        return try {
            var icon = context.packageManager.getApplicationIcon(appPackageName)
            if (icon is AdaptiveIconDrawable) {
                val monochrome = icon.monochrome ?: icon.foreground
                monochrome?.setBounds(0, 0, iconSizePx, iconSizePx)
                icon = monochrome as? Drawable ?: icon
            }
            appIconCache.put(appPackageName, icon)
            icon
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun updateColors() {
        if (!initialized) return
        textView.setTextColor(textColor)
        textViewExtended.setTextColor(
            ColorStateList.valueOf(textColor).withAlpha(179).defaultColor
        )
        tempTextView.setTextColor(textColor)
        tempTextViewExtended.setTextColor(textViewExtended.currentTextColor)
        if (!isExpanded) {
            iconView.imageTintList = ColorStateList.valueOf(textColor)
        }
    }

    private fun runSongChangeSlideAnimation(newTrack: CharSequence, newArtistOrLyric: CharSequence) {
        tempTextView.text = newTrack
        tempTextViewExtended.text = newArtistOrLyric
        tempTextViewExtended.visibility =
            if (newArtistOrLyric.isNotEmpty()) View.VISIBLE else View.GONE

        val translateY = dpToPx(22).toFloat()
        tempTextSet.translationY = translateY
        tempTextSet.alpha = 0f
        tempTextSet.visibility = View.VISIBLE

        val springForce = NowPlayingAmbientAnimationUtils.slowSpatialSpec

        NowPlayingAmbientAnimationUtils.animateTranslationY(
            realTextSet,
            -translateY,
            null,
            null,
            springForce,
        )
        NowPlayingAmbientAnimationUtils.animateAlpha(
            realTextSet,
            0f,
            null,
            null,
            NowPlayingAmbientAnimationUtils.fastEffectsSpec,
        )
        NowPlayingAmbientAnimationUtils.animateTranslationY(
            tempTextSet,
            0f,
            null,
            Runnable {
                textView.text = newTrack
                textViewExtended.text = newArtistOrLyric
                textViewExtended.visibility =
                    if (newArtistOrLyric.isNotEmpty()) View.VISIBLE else View.GONE
                realTextSet.translationY = 0f
                realTextSet.alpha = 1f
                tempTextSet.alpha = 0f
                tempTextSet.visibility = View.INVISIBLE
                tempTextView.text = null
                tempTextViewExtended.text = null
            },
            springForce,
        )
        NowPlayingAmbientAnimationUtils.animateAlpha(
            tempTextSet,
            1f,
            null,
            null,
            NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
        )
    }

    // region Expand / collapse with album art

    private fun performExpand() {
        if (isExpanded) return
        isExpanded = true
        Trace.beginAsyncSection("nowplaying_ambient_expand", 2)
        background.visibility = View.VISIBLE
        bindArtworkAsync()
    }

    private fun performCollapse() {
        if (!isExpanded) return
        Trace.beginAsyncSection("nowplaying_ambient_collapse", 4)
        isExpanded = false

        val bgImageView = background
        NowPlayingAmbientAnimationUtils.animateAlpha(
            bgImageView,
            0f,
            NowPlayingAmbientAnimationHelper.animateBackgroundArtworkInCollapseUpdateListener,
            NowPlayingAmbientAnimationHelper.animateBackgroundArtworkInCollapseEndAction(
                bgImageView,
                0,
            ),
            NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
        )

        val musicIcon = getMusicNoteIcon()
        val currentIconDrawable = iconView.drawable
        if (currentIconDrawable != null && musicIcon != null) {
            NowPlayingAmbientAnimationUtils.animateDrawableAlpha(
                currentIconDrawable,
                iconView,
                0,
                NowPlayingAmbientAnimationHelper
                    .animateIconTransitionExpandNoIconUpdateListener(iconView, musicIcon),
                NowPlayingAmbientAnimationHelper.animateIconTransitionEnd,
                NowPlayingAmbientAnimationUtils.fastEffectsSpec,
            )
        } else {
            updateIconDrawable()
        }

        updateColors()
        Trace.endAsyncSection("nowplaying_ambient_collapse", 4)
    }

    private fun bindArtworkAsync() {
        if (!isExpanded) return
        val bitmap = currentAlbumArt
        if (bitmap == null) {
            updateColorScheme(null, null)
            return
        }

        val requestKey = currentAlbumArtBitmapKey
        val width = extendedContainer.width.takeIf { it > 0 } ?: dpToPx(212)
        val height = extendedContainer.height.takeIf { it > 0 } ?: dpToPx(80)

        NowPlayingAmbientArtworkHelper.processFromBitmap(
            context,
            bitmap,
            width,
            height,
            mainHandler,
        ) { artwork, colorScheme ->
            if (requestKey != currentAlbumArtBitmapKey) return@processFromBitmap // stale
            updateColorScheme(artwork, colorScheme)
        }
    }

    private fun updateColorScheme(artwork: Drawable?, colorScheme: ColorScheme?) {
        val imageView = background
        val toBgColor =
            if (colorScheme != null) {
                colorScheme.materialScheme.secondaryPalette.tone(20)
            } else {
                context.getColor(android.R.color.black)
            }

        val bgAnimationEnd = NowPlayingAmbientAnimationHelper.bgAnimationEndTraceGuard(artwork)

        if (imageView.background == null) {
            NowPlayingAmbientAnimationHelper.animateBackgroundArtworkInExpand(
                imageView,
                toBgColor,
                bgAnimationEnd,
                artwork,
            ).run()
        } else {
            val colorDrawable = imageView.background as? ColorDrawable
            if (colorDrawable != null) {
                NowPlayingAmbientAnimationHelper.animateDrawableColor(
                    colorDrawable,
                    NowPlayingAmbientAnimationHelper.getBackgroundColor(imageView),
                    toBgColor,
                    bgAnimationEnd,
                    NowPlayingAmbientAnimationHelper::setColorDrawableColor,
                )
            }
            NowPlayingAmbientAnimationHelper.animateBackgroundArtworkInExpandStartToSrcAnimation(
                artwork,
                imageView,
            )
        }

        val textViews = listOf(textView, textViewExtended)
        val hasArtwork = artwork != null
        val textTargetColor = if (hasArtwork) 0xFFFFFFFF.toInt() else textColor
        val textFromColor = textViews.first().currentTextColor
        val textColorAnimator = ValueAnimator.ofArgb(textFromColor, textTargetColor)
        textColorAnimator.duration = 200L
        textColorAnimator.addUpdateListener(
            NowPlayingAmbientAnimationHelper.textColorsUpdateListener(textViews)
        )
        textColorAnimator.start()
    }

    // endregion
}
