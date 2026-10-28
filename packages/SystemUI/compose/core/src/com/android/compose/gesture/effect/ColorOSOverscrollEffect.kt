/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.compose.gesture.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Returns a [remember]ed factory of [ColorOSOverscrollEffect]s. */
@Composable
fun rememberColorOSOverscrollEffectFactory(): OverscrollFactory {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope) { ColorOSOverscrollEffectFactory(animationScope) }
}

data class ColorOSOverscrollEffectFactory(private val animationScope: CoroutineScope) :
    OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        ColorOSOverscrollEffect(animationScope)
}

/**
 * The overscroll of the ColorOS control center: the content is stretched down from its top edge
 * while it is pulled past its end, and springs back on release.
 *
 * Only vertical scrolls are stretched. A horizontal scroll that shares the factory (the pages of
 * the tile grid, a swipe on the media card) goes through untouched.
 */
class ColorOSOverscrollEffect(animationScope: CoroutineScope) :
    BaseContentOverscrollEffect(animationScope, ReleaseSpring) {
    private val shown = Animatable(0f)

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        if (delta.y == 0f) return performScroll(delta)
        return super.applyToScroll(delta, source, performScroll)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        if (velocity.y == 0f) {
            performFling(velocity)
            return
        }
        super.applyToFling(velocity, performFling)
    }

    override val node: DelegatableNode =
        object : Modifier.Node(), LayoutModifierNode {
            override fun onAttach() {
                coroutineScope.launch {
                    snapshotFlow { overscrollDistance }
                        .collectLatest { target ->
                            if (target != 0f) {
                                shown.snapTo(target)
                            } else {
                                shown.animateTo(0f, ReleaseSpring)
                            }
                        }
                }
            }

            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints,
            ): MeasureResult {
                val placeable = measurable.measure(constraints)
                val maxDistancePx = MaxDistance.toPx()
                return layout(placeable.width, placeable.height) {
                    val distance = shown.value
                    if (distance == 0f) {
                        placeable.place(0, 0)
                    } else {
                        val progress = Stretch.convert(distance / maxDistancePx)
                        placeable.placeWithLayer(0, 0) {
                            scaleY = 1f + progress * StretchPerProgress
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                    }
                }
            }
        }

    private companion object {
        val MaxDistance = 500.dp
        const val StretchPerProgress = 0.45f
        val Stretch = ProgressConverter.tanh(maxProgress = 0.3f, tilt = 1f)
        val ReleaseSpring =
            spring(
                dampingRatio = 0.7f,
                stiffness = 322f,
                visibilityThreshold = Spring.DefaultDisplacementThreshold,
            )
    }
}
