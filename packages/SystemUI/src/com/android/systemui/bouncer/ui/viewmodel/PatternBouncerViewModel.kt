/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import android.util.TypedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Holds UI state and handles user input for the pattern bouncer UI. */
class PatternBouncerViewModel
@AssistedInject
constructor(
    private val applicationContext: Context,
    interactor: BouncerInteractor,
    @Assisted bouncerHapticPlayer: BouncerHapticPlayer,
    @Assisted isInputEnabled: StateFlow<Boolean>,
    @Assisted private val onIntentionalUserInput: () -> Unit,
    a11yInteractor: AccessibilityInteractor,
) :
    AuthMethodBouncerViewModel(
        interactor = interactor,
        isInputEnabled = isInputEnabled,
        traceName = "PatternBouncerViewModel",
        bouncerHapticPlayer = bouncerHapticPlayer,
    ) {

    /** The current pattern size. */
    val patternSize: StateFlow<Byte> = interactor.patternSize

    /** The number of columns in the dot grid. */
    val columnCount: Byte
        get() = interactor.patternSize.value

    /** The number of rows in the dot grid. */
    val rowCount: Byte
        get() = interactor.patternSize.value

    private val selectedDotSet = MutableStateFlow<LinkedHashSet<PatternDotViewModel>>(linkedSetOf())
    private val selectedDotList = MutableStateFlow(selectedDotSet.value.toList())
    /** The dots that were selected by the user, in the order of selection. */
    val selectedDots: StateFlow<List<PatternDotViewModel>> = selectedDotList.asStateFlow()

    private val _currentDot = MutableStateFlow<PatternDotViewModel?>(null)

    /** The most-recently selected dot that the user selected. */
    val currentDot: StateFlow<PatternDotViewModel?> = _currentDot.asStateFlow()

    private val _dots = MutableStateFlow(defaultDots())

    val isTouchExplorationEnabled by a11yInteractor.isTouchExplorationEnabled.hydratedStateOf(false)

    var inputPosition by mutableStateOf<Offset?>(null)
        private set

    var patternAreaContentDescription by mutableStateOf("")
        private set

    /** All dots on the grid. */
    val dots: StateFlow<List<PatternDotViewModel>> = _dots.asStateFlow()

    /** Whether the pattern itself should be rendered visibly. */
    val isPatternVisible: StateFlow<Boolean> = interactor.isPatternVisible

    override val _readyToTryAuthenticate = MutableStateFlow(false)

    override val authenticationMethod = AuthenticationMethodModel.Pattern

    override val lockoutMessageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { super.onActivated() }
            launch {
                selectedDotSet
                    .map { it.toList() }
                    .collect {
                        // Single-dot patterns are treated as errors.
                        _readyToTryAuthenticate.value = (it.size > 1)
                        selectedDotList.value = it.toList()
                    }
            }
            launch {
                interactor.patternSize.collect {
                    clearInput()
                }
            }
            awaitCancellation()
        }
    }

    /** Notifies that the user has started a drag gesture across the dot grid. */
    fun onDragStart(startOffset: Offset) {
        onDown()
        onIntentionalUserInput()
        inputPosition = startOffset
        patternAreaContentDescription =
            applicationContext
                .getText(com.android.internal.R.string.lockscreen_access_pattern_area)
                .toString()
    }

    /**
     * Notifies that the user is dragging across the dot grid.
     *
     * @param xPx The horizontal coordinate of the position of the user's pointer, in pixels.
     * @param yPx The vertical coordinate of the position of the user's pointer, in pixels.
     * @param containerSizePx The size of the container of the dot grid, in pixels. It's assumed
     *   that the dot grid is perfectly square such that width and height are equal.
     * @param horizontalOffset The horizontal offset of the dot grid within the container, in pixels.
     * @param verticalOffset The vertical offset of the dot grid within the container, in pixels.
     */
    fun onDrag(
        xPx: Float,
        yPx: Float,
        containerSizePx: Int,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f,
    ) {
        inputPosition = Offset(xPx, yPx)
        val localX = xPx - horizontalOffset
        val localY = yPx - verticalOffset
        val cellWidthPx = containerSizePx.toFloat() / columnCount
        val cellHeightPx = containerSizePx.toFloat() / rowCount

        if (localX < 0 || localY < 0) {
            return
        }

        val dotColumn = (localX / cellWidthPx).toInt()
        val dotRow = (localY / cellHeightPx).toInt()
        if (dotColumn > columnCount - 1 || dotRow > rowCount - 1) {
            return
        }

        val dotPixelX = dotColumn * cellWidthPx + cellWidthPx / 2f
        val dotPixelY = dotRow * cellHeightPx + cellHeightPx / 2f

        val distance = sqrt((localX - dotPixelX).pow(2) + (localY - dotPixelY).pow(2))
        val hitRadius = hitFactor * min(cellWidthPx, cellHeightPx) / 2f
        if (distance > hitRadius) {
            return
        }

        val hitDot = dots.value.firstOrNull { dot -> dot.x == dotColumn && dot.y == dotRow }
        if (hitDot != null && !selectedDotSet.value.contains(hitDot)) {
            val skippedOverDots =
                currentDot.value?.let { previousDot ->
                    val dRow = hitDot.y - previousDot.y
                    val dColumn = hitDot.x - previousDot.x
                    if (dRow == 0 || dColumn == 0 || abs(dRow) == abs(dColumn)) {
                        buildList {
                            var fillInRow = previousDot.y
                            var fillInColumn = previousDot.x
                            val stepRow = if (dRow > 0) 1 else if (dRow < 0) -1 else 0
                            val stepCol = if (dColumn > 0) 1 else if (dColumn < 0) -1 else 0
                            while (true) {
                                fillInRow += stepRow
                                fillInColumn += stepCol
                                if (fillInRow == hitDot.y && fillInColumn == hitDot.x) break
                                val gapDot = PatternDotViewModel(x = fillInColumn, y = fillInRow)
                                if (!selectedDotSet.value.contains(gapDot)) {
                                    add(gapDot)
                                }
                            }
                        }
                    } else {
                        emptyList()
                    }
                } ?: emptyList()

            val newSet =
                linkedSetOf<PatternDotViewModel>().apply {
                    addAll(selectedDotSet.value)
                    addAll(skippedOverDots)
                    add(hitDot)
                }
            selectedDotSet.value = newSet
            selectedDotList.value = newSet.toList()
            _readyToTryAuthenticate.value = (newSet.size > 1)
            patternAreaContentDescription =
                applicationContext.resources.getString(
                    com.android.internal.R.string.lockscreen_access_pattern_cell_added_verbose,
                    dotRow * columnCount + (dotColumn + 1),
                )
            _currentDot.value = hitDot
        }
    }

    /** Notifies that the user has ended the drag gesture across the dot grid. */
    fun onDragEnd() {
        inputPosition = null
        val pattern = getInput()
        if (pattern.size == 1) {
            // Single dot patterns are treated as erroneous/false taps:
            interactor.onFalseUserInput()
        }

        clearInput()
        tryAuthenticate(input = pattern)
    }

    override fun clearInput() {
        _dots.value = defaultDots()
        _currentDot.value = null
        selectedDotSet.value = linkedSetOf()
        selectedDotList.value = emptyList()
        _readyToTryAuthenticate.value = false
    }

    override fun getInput(): List<Any> {
        return selectedDotSet.value.map(PatternDotViewModel::toCoordinate)
    }

    private fun defaultDots(): List<PatternDotViewModel> {
        return buildList {
            (0 until columnCount).forEach { x ->
                (0 until rowCount).forEach { y -> add(PatternDotViewModel(x = x, y = y)) }
            }
        }
    }

    private val hitFactor: Float by lazy {
        val outValue = TypedValue()
        applicationContext.resources.getValue(
            com.android.internal.R.dimen.lock_pattern_dot_hit_factor,
            outValue,
            true,
        )
        max(min(outValue.float, 1f), MIN_DOT_HIT_FACTOR)
    }

    fun performDotFeedback() = bouncerHapticPlayer.playPatternDotFeedback()

    @AssistedFactory
    interface Factory {
        fun create(
            bouncerHapticPlayer: BouncerHapticPlayer,
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
        ): PatternBouncerViewModel
    }

    companion object {
        private const val MIN_DOT_HIT_FACTOR = 0.2f
    }
}

data class PatternDotViewModel(val x: Int, val y: Int) {
    fun toCoordinate(): AuthenticationPatternCoordinate {
        return AuthenticationPatternCoordinate(x = x, y = y)
    }
}
