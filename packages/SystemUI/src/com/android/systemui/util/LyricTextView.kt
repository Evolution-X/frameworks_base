/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *               2022 Project Kaleidoscope
 *               2025-2026 RisingOS (revived) Android Project
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
import android.graphics.Canvas
import android.graphics.Paint
import android.icu.text.Bidi
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class LyricTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : TextView(context, attrs, defStyleAttr, defStyleRes) {

    private var stopped = true
    private var textWidth = 0
    private var scrollSpeed = 4
    private var offset = 0
    private var text: String? = null
    private var textRtl = false

    private val startScrollRunnable = Runnable { startScroll() }
    private val invalidateRunnable = Runnable { invalidate() }

    override fun onDetachedFromWindow() {
        removeCallbacks(startScrollRunnable)
        super.onDetachedFromWindow()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        stopScroll()
        if (text != null) {
            this.text = text.toString()
            textRtl = Bidi.getBaseDirection(this.text) == Bidi.RTL
            if (textRtl) {
                paint.textAlign = Paint.Align.RIGHT
                offset = -1
            } else {
                paint.textAlign = Paint.Align.LEFT
                offset = if (View.LAYOUT_DIRECTION_RTL == layoutDirection) -1 else 0
            }
            textWidth = paint.measureText(this.text).toInt()
            postInvalidate()
            postDelayed(startScrollRunnable, START_SCROLL_DELAY.toLong())
        } else {
            this.text = null
        }
    }

    override fun setTextColor(color: Int) {
        paint.color = color
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val viewRtl = View.LAYOUT_DIRECTION_RTL == layoutDirection
        if (textRtl && offset == -1) {
            offset = width
        } else if (viewRtl && offset == -1) {
            offset = max(0, width - textWidth)
        }
        if (text != null) {
            val y = height / 2.0f + abs(paint.ascent() + paint.descent()) / 2
            canvas.drawText(text!!, offset.toFloat(), y, paint)
        }
        if (!stopped) {
            if (!textRtl) {
                if (width - offset + scrollSpeed >= textWidth) {
                    offset = if (width > textWidth && !viewRtl) 0 else width - textWidth
                    stopScroll()
                } else {
                    offset -= scrollSpeed
                }
            } else {
                if (offset + scrollSpeed >= textWidth) {
                    offset = max(width, textWidth)
                    stopScroll()
                } else {
                    offset += scrollSpeed
                }
            }
            invalidateAfter(INVALIDATE_DELAY.toLong())
        }
    }

    private fun invalidateAfter(delay: Long) {
        removeCallbacks(invalidateRunnable)
        postDelayed(invalidateRunnable, delay)
    }

    fun startScroll() {
        stopped = false
        postInvalidate()
    }

    fun stopScroll() {
        stopped = true
        removeCallbacks(startScrollRunnable)
        postInvalidate()
    }

    fun setScrollSpeed(scrollSpeed: Int) {
        this.scrollSpeed = scrollSpeed
    }

    companion object {
        private const val START_SCROLL_DELAY = 500
        private const val INVALIDATE_DELAY = 10
    }
}
