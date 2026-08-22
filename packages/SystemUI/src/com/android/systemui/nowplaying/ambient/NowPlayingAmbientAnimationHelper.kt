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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Trace
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestration helpers for NowPlayingAmbientContainer's expand/collapse and artwork
 * transitions. Adapted from Google's AmbientIndicationAnimationHelper, with the
 * action-button (like/play) animation functions removed since this fork has no
 * favoriting or default-music-player-intent data source to drive them.
 */
object NowPlayingAmbientAnimationHelper {

    fun animateBackgroundArtworkInExpand(
        view: ImageView,
        toBgColor: Int,
        bgAnimationEnd: Runnable,
        toSrc: Drawable?,
    ): Runnable = Runnable {
        val colorDrawable = ColorDrawable(0)
        view.background = colorDrawable
        animateDrawableColor(
            colorDrawable,
            0,
            toBgColor,
            bgAnimationEnd,
            ::setColorDrawableColor,
        )
        animateBackgroundArtworkInExpandStartToSrcAnimation(toSrc, view)
    }

    fun animateBackgroundArtworkInExpandStartToSrcAnimation(
        drawable: Drawable?,
        imageView: ImageView,
    ) {
        drawable ?: return

        drawable.alpha = 0
        imageView.setImageDrawable(drawable)

        val layerDrawable = drawable as? LayerDrawable ?: return
        val springForce = NowPlayingAmbientAnimationUtils.slowEffectsSpec

        NowPlayingAmbientAnimationUtils.animateLayeredDrawableAlpha(
            layerDrawable,
            imageView,
            255,
            null,
            Runnable { Trace.endAsyncSection("nowplaying_ambient_bind_artwork", 3) },
            springForce,
        )

        val spatialSpringForce = NowPlayingAmbientAnimationUtils.defaultSpatialSpec
        val scaleAnimation = SpringAnimation(imageView, DynamicAnimation.SCALE_X, 1.05f)
        scaleAnimation.spring =
            NowPlayingAmbientAnimationUtils.copySpringForce(spatialSpringForce, 1.05f)
        scaleAnimation.start()
    }

    val animateBackgroundArtworkInCollapseUpdateListener =
        DynamicAnimation.OnAnimationUpdateListener { animation, value, _ ->
            if (value <= 0.1f) {
                animation.cancel()
            }
        }

    fun animateBackgroundArtworkInCollapseEndAction(view: ImageView, variant: Int): Runnable =
        Runnable {
            when (variant) {
                0 -> {
                    view.background = null
                    view.setImageDrawable(null)
                    view.alpha = 1.0f
                    view.visibility = View.GONE
                }
                1 -> view.background = null
                2 -> view.background = null
                else -> view.imageTintList = null
            }
        }

    val animateIconAndThumbnailOnExpandNoAlbumArtEnd = Runnable {}

    fun animateIconAndThumbnailOnExpandWithAlbumArtUpdateListener(
        iconView: ImageView,
        toBgDrawable: Drawable,
        hasTriggeredBgDrawableAnim: AtomicBoolean,
    ) = DynamicAnimation.OnAnimationUpdateListener { _, value, _ ->
        if (!hasTriggeredBgDrawableAnim.get() && value <= 114.75f) {
            toBgDrawable.alpha = 0
            iconView.background = toBgDrawable
            NowPlayingAmbientAnimationUtils.animateDrawableAlpha(
                toBgDrawable,
                iconView,
                255,
                null,
                Runnable {},
                NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
            )
            hasTriggeredBgDrawableAnim.set(true)
        }
    }

    fun animateIconTransitionExpandNoIconUpdateListener(
        iconView: ImageView,
        toIconDrawable: Drawable,
    ) =
        object : DynamicAnimation.OnAnimationUpdateListener {
            override fun onAnimationUpdate(
                animation: DynamicAnimation<*>,
                value: Float,
                velocity: Float,
            ) {
                if (value <= 51.0f) {
                    animation.removeUpdateListener(this)
                    toIconDrawable.alpha = 0
                    iconView.setImageDrawable(toIconDrawable)
                    NowPlayingAmbientAnimationUtils.animateDrawableAlpha(
                        toIconDrawable,
                        iconView,
                        255,
                        null,
                        Runnable {},
                        NowPlayingAmbientAnimationUtils.defaultEffectsSpec,
                    )
                    animation.cancel()
                }
            }
        }

    val animateIconTransitionEnd = Runnable {}

    fun textColorsUpdateListener(textViews: List<TextView>) =
        ValueAnimator.AnimatorUpdateListener { animator ->
            val color = animator.animatedValue as Int
            for (tv in textViews) tv.setTextColor(color)
        }

    fun iconTintUpdateListener(iconView: ImageView) =
        ValueAnimator.AnimatorUpdateListener { animator ->
            iconView.imageTintList =
                android.content.res.ColorStateList.valueOf(animator.animatedValue as Int)
        }

    fun bgAnimationEndTraceGuard(drawable: Drawable?): Runnable = Runnable {
        if (drawable == null) {
            Trace.endAsyncSection("nowplaying_ambient_bind_artwork", 3)
        }
    }

    fun animateDrawableColor(
        drawable: Any,
        fromColor: Int,
        toColor: Int,
        onEnd: Runnable,
        setColor: (Any, Int) -> Unit,
    ) {
        if (fromColor == toColor) {
            setColor(drawable, toColor)
            return
        }
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        animator.duration = 200L
        animator.addUpdateListener { animation ->
            setColor(drawable, animation.animatedValue as Int)
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd.run()
                }
            }
        )
        animator.start()
    }

    fun setColorDrawableColor(drawable: Any, color: Int) {
        (drawable as ColorDrawable).color = color
    }

    fun getBackgroundColor(view: View): Int {
        return when (val background = view.background) {
            is GradientDrawable -> background.color?.defaultColor ?: 0
            is ColorDrawable -> background.color
            else -> 0
        }
    }
}
