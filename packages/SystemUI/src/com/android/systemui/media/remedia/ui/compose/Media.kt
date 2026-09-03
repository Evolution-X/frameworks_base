/*
 * Copyright (C) 2025 The Android Open Source Project
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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.media.remedia.ui.compose

import android.util.Log
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults.colors
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastRoundToInt
import com.android.compose.PlatformButton
import com.android.compose.PlatformIconButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffect
import com.android.compose.gesture.overscrollToDismiss
import com.android.compose.modifiers.height
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.byLayoutId
import com.android.systemui.common.ui.compose.load
import com.android.systemui.common.ui.compose.singleton
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaColorScheme
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardGutsViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaDeviceChipViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaNavigationViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaPlayPauseActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSecondaryActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSettingsButtonViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.verticalSquish
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * Renders a media controls UI element.
 *
 * This composable supports a multitude of presentation styles/layouts controlled by the
 * [presentationStyle] parameter. If the card carousel can be swiped away to dismiss by the user,
 * the [onDismissed] callback will be invoked when/if that happens.
 */
@Composable
fun Media(
    viewModelFactory: MediaViewModel.Factory,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    visible: () -> Boolean = { true },
    mediaSquishiness: () -> Float = { 1f },
    location: Media.Location,
    expansion: () -> Float = { 0F },
) {
    val context = LocalContext.current
    val viewModel: MediaViewModel =
        rememberViewModel("Media.viewModel") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = behavior.carouselVisibility,
            )
        }

    LaunchedEffect(visible) { viewModel.setVisibility(visible) }

    viewModel.mediaUiEventLogger.logCarouselLocation(location)

    CardCarousel(
        viewModel = viewModel,
        presentationStyle = presentationStyle,
        behavior = behavior,
        onDismissed = onDismissed,
        modifier = modifier,
        mediaSquishiness = mediaSquishiness,
        expansion = expansion,
    )
}

/**
 * Renders a media controls carousel of cards.
 *
 * This composable supports a multitude of presentation styles/layouts controlled by the
 * [presentationStyle] parameter. The behavior is controlled by [behavior]. If
 * [MediaUiBehavior.isCarouselDismissible] is `true`, the [onDismissed] callback will be invoked
 * when/if that happens.
 */
@Composable
private fun CardCarousel(
    viewModel: MediaViewModel,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float,
    expansion: () -> Float,
) {
    AnimatedVisibility(
        visible = viewModel.isCarouselVisible,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        CardCarouselContent(
            viewModel = viewModel,
            presentationStyle = presentationStyle,
            behavior = behavior,
            onDismissed = onDismissed,
            mediaSquishiness = mediaSquishiness,
            expansion = expansion,
        )
    }
}

@Composable
private fun CardCarouselContent(
    viewModel: MediaViewModel,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float,
    expansion: () -> Float,
) {
    val carouselState = rememberCarouselState {
        if (behavior.isCarouselScrollingEnabled) {
            viewModel.cards.size
        } else {
            1
        }
    }
    var userTappedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewModel.currentIndex) {
        if (viewModel.currentIndex != carouselState.currentItem) {
            userTappedIndex = viewModel.currentIndex
            carouselState.animateScrollToItem(viewModel.currentIndex)
        }
    }
    LaunchedEffect(carouselState.currentItem, viewModel.cards.size) {
        if (carouselState.currentItem < viewModel.cards.size) {
            // This effect is triggered from both a swipe between items in the carousel, and the
            // user tapping on a thumbnail, which could animate more than one index away.
            // In the second case, we need to ignore the intermediate events caused by
            // `animateScrollToItem`, so that scrolling can fully complete.
            if (userTappedIndex == null || carouselState.currentItem == userTappedIndex) {
                viewModel.onCardSelected(carouselState.currentItem)

                val areDeviceChipsVisible =
                    presentationStyle !in
                        listOf(MediaPresentationStyle.Thumbnail, MediaPresentationStyle.Compact)
                viewModel.onCardVisible(carouselState.currentItem, areDeviceChipsVisible)

                userTappedIndex = null
            }
        }
    }
    var isFalseTouchDetected: Boolean by
        remember(behavior.isCarouselScrollFalseTouch) { mutableStateOf(false) }
    val isSwipingEnabled = behavior.isCarouselScrollingEnabled && !isFalseTouchDetected

    val roundedCornerShape = remember { RoundedCornerShape(32.dp) }

    Box(
        modifier =
            modifier
                .pointerInput(behavior) {
                    if (behavior.isCarouselScrollFalseTouch != null) {
                        awaitEachGesture {
                            awaitFirstDown(false, PointerEventPass.Initial)
                            isFalseTouchDetected = behavior.isCarouselScrollFalseTouch.invoke()
                        }
                    }
                }
                .graphicsLayer {
                    shape = roundedCornerShape
                    clip = true
                }
    ) {
        @Composable
        fun PagerContent() {
            Box(modifier = Modifier.sysuiResTag(MediaRes.MEDIA_CAROUSEL_SCROLLER)) {
                HorizontalCenteredHeroCarousel(
                    state = carouselState,
                    modifier = Modifier.sysuiResTag(MediaRes.MEDIA_CAROUSEL),
                    userScrollEnabled = isSwipingEnabled,
                    itemSpacing = 8.dp,
                    minSmallItemWidth = 48.dp,
                    maxSmallItemWidth = 48.dp,
                    contentPadding = PaddingValues.Zero,
                ) { pageIndex: Int ->
                    if (behavior.isCarouselScrollingEnabled || pageIndex == 0) {
                        if (Media.DEBUG) {
                            Log.d(
                                Media.TAG,
                                "composing media card ${viewModel.cards[pageIndex].key}",
                            )
                        }
                        val presentation =
                            if (
                                pageIndex == viewModel.currentIndex ||
                                    !behavior.isCarouselScrollingEnabled
                            ) {
                                presentationStyle
                            } else {
                                MediaPresentationStyle.Thumbnail
                            }
                        Card(
                            mediaViewModel = viewModel,
                            cardIndex = pageIndex,
                            cardStyle = presentation,
                            carouselStyle = presentationStyle,
                            mediaSquishiness = mediaSquishiness,
                            expansion = expansion,
                            modifier =
                                Modifier.maskClip(roundedCornerShape)
                                    .fillMaxWidth()
                                    .sysuiResTag(MediaRes.MEDIA_CONTROLS),
                        )
                    }
                }
            }
        }

        if (behavior.isCarouselDismissible) {
            Box(
                modifier =
                    Modifier.overscrollToDismiss(
                        orientation = Orientation.Horizontal,
                        enabled = isSwipingEnabled,
                        onDismissed = onDismissed,
                    )
            ) {
                PagerContent()
            }
        } else {
            val overscrollEffect = rememberOffsetOverscrollEffect()
            SwipeToReveal(
                foregroundContent = { PagerContent() },
                foregroundContentEffect = overscrollEffect,
                revealedContent = { revealAmount ->
                    RevealedContent(
                        viewModel = viewModel.settingsButtonViewModel,
                        revealAmount = revealAmount,
                    )
                },
                isSwipingEnabled = isSwipingEnabled,
            )
        }
    }

    LaunchedEffect(viewModel.scrollToFirst) {
        if (viewModel.scrollToFirst && viewModel.cards.isNotEmpty()) {
            carouselState.animateScrollToItem(0)
            viewModel.onScrollToFirstCard()
        }
    }
}

/** Renders the UI of a single media card. */
@Composable
private fun Card(
    mediaViewModel: MediaViewModel,
    cardIndex: Int,
    cardStyle: MediaPresentationStyle,
    carouselStyle: MediaPresentationStyle,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float,
    expansion: () -> Float,
) {
    val viewModel = mediaViewModel.cards[cardIndex]
    val stlState =
        rememberMutableSceneTransitionLayoutState(
            initialScene = cardStyle.toScene(),
            transitions = Media.Transitions,
        )

    val colorScheme = rememberAnimatedColorScheme(viewModel.colorScheme)

    // Each time the presentation style changes, animate to the corresponding scene.
    LaunchedEffect(cardStyle) {
        stlState.setTargetScene(targetScene = cardStyle.toScene(), animationScope = this)
    }

    Box(modifier) {
        if (carouselStyle != MediaPresentationStyle.Compact) {
            CardBackground(
                image = viewModel.background,
                colorScheme = colorScheme,
                modifier =
                    Modifier.matchParentSize()
                        .sysuiResTag(MediaRes.BACKGROUND)
                        .clip(RoundedCornerShape(32.dp)),
            )
        }

        Expandable(
            controller =
                rememberExpandableController(color = Color.Transparent, shape = RectangleShape),
            useModifierBasedImplementation = true,
            modifier = Modifier.verticalSquish(mediaSquishiness),
        ) {
            key(stlState) {
                SceneTransitionLayout(state = stlState, debugName = "Media Card") {
                    scene(Media.Scenes.Default) {
                        CardForeground(
                            expandable = it,
                            mediaViewModel = mediaViewModel,
                            cardIndex = cardIndex,
                            colorScheme = colorScheme,
                            threeRows = true,
                            fillHeight = false,
                        )
                    }

                    scene(Media.Scenes.Large) {
                        CardForeground(
                            expandable = it,
                            mediaViewModel = mediaViewModel,
                            cardIndex = cardIndex,
                            colorScheme = colorScheme,
                            threeRows = true,
                            fillHeight = true,
                        )
                    }

                    scene(Media.Scenes.Compressed) {
                        CardForeground(
                            expandable = it,
                            mediaViewModel = mediaViewModel,
                            cardIndex = cardIndex,
                            colorScheme = colorScheme,
                            threeRows = false,
                            fillHeight = false,
                        )
                    }

                    scene(Media.Scenes.Compact) {
                        CompactCardForeground(expandable = it, viewModel = viewModel)
                    }

                    scene(Media.Scenes.Thumbnail) {
                        // Thumbnail is album art only, no foreground elements
                        var thumbnailModifier =
                            Modifier.clickable(onClick = { viewModel.onClick(it) }).height {
                                when (carouselStyle) {
                                    MediaPresentationStyle.Default -> Media.DEFAULT_HEIGHT
                                    // Large is only used for communal, which isn't scrollable
                                    MediaPresentationStyle.Large -> Dp.Unspecified
                                    MediaPresentationStyle.Compressed ->
                                        lerp(
                                            Media.COMPRESSED_HEIGHT,
                                            Media.DEFAULT_HEIGHT,
                                            expansion(),
                                        )
                                    MediaPresentationStyle.Compact -> Media.COMPACT_HEIGHT
                                    else ->
                                        throw IllegalArgumentException(
                                            "Invalid carousel presentation"
                                        )
                                }.roundToPx()
                            }
                        if (carouselStyle == MediaPresentationStyle.Compact) {
                            thumbnailModifier =
                                thumbnailModifier.then(
                                    Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                                )
                        }
                        Spacer(modifier = modifier.then(thumbnailModifier))
                    }
                }
            }
        }

        LaunchedEffect(cardStyle) {
            launch {
                if (
                    cardStyle != MediaPresentationStyle.Thumbnail &&
                        carouselStyle == MediaPresentationStyle.Compressed
                ) {
                    synchronizeMediaState(
                        stlState,
                        expansion,
                        Media.Scenes.Compressed,
                        Media.Scenes.Default,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberAnimatedColorScheme(colorScheme: MediaColorScheme?): AnimatedColorScheme {
    val primaryColor = colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed
    val onPrimaryColor = colorScheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimaryFixed
    val backgroundColor = colorScheme?.background ?: MaterialTheme.colorScheme.onSurface
    val animatedPrimary by animateColorAsState(targetValue = primaryColor)
    val animatedOnPrimary by animateColorAsState(targetValue = onPrimaryColor)
    val animatedBackground by animateColorAsState(targetValue = backgroundColor)

    return remember {
        object : AnimatedColorScheme {
            override val primary: Color
                get() = animatedPrimary

            override val onPrimary: Color
                get() = animatedOnPrimary

            override val background: Color
                get() = animatedBackground
        }
    }
}

/**
 * Renders the foreground of a card, including all UI content and the internal "guts".
 *
 * If [threeRows] is `true`, the layout will be organized as three horizontal rows; if `false`, two
 * rows will be used, resulting in a more compact layout.
 *
 * If [fillHeight] is `true`, the card will grow vertically to fill all available space in its
 * parent. If not, it'll only be as tall as needed to show its UI.
 */
@Composable
private fun ContentScope.CardForeground(
    expandable: Expandable,
    mediaViewModel: MediaViewModel,
    cardIndex: Int,
    colorScheme: AnimatedColorScheme,
    threeRows: Boolean,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val viewModel = mediaViewModel.cards[cardIndex]
    // Can't use a Crossfade composable because of the custom layout logic below. Animate the alpha
    // of the guts (and, indirectly, of the content) from here.
    val gutsAlphaAnimatable = remember { Animatable(0f) }
    val isGutsVisible = viewModel.guts.isVisible
    LaunchedEffect(isGutsVisible) { gutsAlphaAnimatable.animateTo(if (isGutsVisible) 1f else 0f) }

    // Use a custom layout to measure the content even if the content is being hidden because the
    // internal guts are showing. This is needed because only the content knows the size the of the
    // card and the guts are set to be the same size of the content.
    Layout(
        content = {
            CardForegroundContent(
                expandable = expandable,
                mediaViewModel = mediaViewModel,
                cardIndex = cardIndex,
                threeRows = threeRows,
                fillHeight = fillHeight,
                colorScheme = colorScheme,
                modifier =
                    Modifier.layoutId(Media.LayoutId.CardForeground).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - gutsAlphaAnimatable.value
                    },
            )

            CardGuts(
                viewModel = viewModel.guts,
                colorScheme = colorScheme,
                modifier =
                    Modifier.layoutId(Media.LayoutId.CardGuts).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = gutsAlphaAnimatable.value
                    },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val measurableByLayoutId = measurables.byLayoutId<Media.LayoutId>()
        val contentPlaceable =
            measurableByLayoutId[Media.LayoutId.CardForeground]!!.measure(constraints)
        // Guts should always have the exact dimensions as the content, even if we don't show the
        // content.
        val gutsPlaceable =
            measurableByLayoutId[Media.LayoutId.CardGuts]!!.measure(
                Constraints.fixed(contentPlaceable.width, contentPlaceable.height)
            )

        layout(contentPlaceable.measuredWidth, contentPlaceable.measuredHeight) {
            // Place content so its modifiers stay active and don't wait for previous pointer events
            // after the switch from guts to content.
            contentPlaceable.place(0, 0)
            if (viewModel.guts.isVisible || gutsAlphaAnimatable.isRunning) {
                gutsPlaceable.place(0, 0)
            }
        }
    }
}

@Composable
private fun ContentScope.CardForegroundContent(
    expandable: Expandable,
    mediaViewModel: MediaViewModel,
    cardIndex: Int,
    threeRows: Boolean,
    fillHeight: Boolean,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    val viewModel = mediaViewModel.cards[cardIndex]
    Column(
        modifier =
            modifier
                .combinedClickable(
                    onClick = { viewModel.onClick(expandable) },
                    onLongClick = viewModel.onLongClick,
                )
                .semantics { contentDescription = viewModel.contentDescription }
    ) {
        // Always add the first/top row, regardless of presentation style.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Icon.
            Element(key = Media.Elements.AppIcon, modifier = modifier) {
                Icon(
                    icon = viewModel.icon,
                    tint = colorScheme.primary,
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .padding(top = 16.dp, start = 16.dp)
                            .size(24.dp)
                            .clip(CircleShape),
                )
            }

            Element(
                key = Media.Elements.OutputSwitcherButton,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                val cardMaxWidth = mediaViewModel.cardMaxWidth
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            // Output switcher chip must be limited to at most 40% of the
                            // maximum width of the card.
                            //
                            // This saves the maximum possible width of the card so it can be
                            // referred to by child custom layout code below.
                            //
                            // The assumption is that the row can be as wide as the entire card.
                            .layout { measurable, constraints ->
                                mediaViewModel.cardMaxWidth =
                                    cardMaxWidth.coerceAtLeast(constraints.maxWidth)
                                val placeable = measurable.measure(constraints)

                                layout(placeable.measuredWidth, placeable.measuredHeight) {
                                    placeable.place(0, 0)
                                }
                            },
                ) {
                    AnimatedVisibility(
                        visible = viewModel.deviceSuggestionChip != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        rememberLastNonNull(viewModel.deviceSuggestionChip)?.let {
                            DeviceChip(
                                viewModel = it,
                                style =
                                    DeviceChipStyle(
                                        fillColor = Color.Transparent,
                                        contentColor = colorScheme.primary,
                                        borderColor = colorScheme.primary,
                                    ),
                                modifier =
                                    Modifier.fractionalMaxWidth(
                                            containerMaxWidth = cardMaxWidth,
                                            fraction = 0.5f,
                                        )
                                        .sysuiResTag(MediaRes.SUGGESTED_DEVICE_CHIP),
                            )
                        }
                    }

                    DeviceChip(
                        viewModel = viewModel.outputSwitcherChip,
                        style =
                            if (viewModel.outputSwitcherChip.text.isNullOrBlank()) {
                                DeviceChipStyle(
                                    fillColor = Color.Transparent,
                                    contentColor = colorScheme.primary,
                                    borderColor = colorScheme.primary,
                                )
                            } else {
                                DeviceChipStyle(
                                    fillColor = colorScheme.primary,
                                    contentColor = colorScheme.onPrimary,
                                )
                            },
                        modifier =
                            Modifier
                                // The chip must be limited to 40% of the width of the card at
                                // most.
                                //
                                // The underlying assumption is that there'll never be more than
                                // one chip with text and one more icon-only chip.
                                // Only the one with text can ever end up being too wide.
                                .fractionalMaxWidth(
                                    containerMaxWidth = cardMaxWidth,
                                    fraction = 0.4f,
                                )
                                .padding(end = 16.dp)
                                .sysuiResTag(MediaRes.OUTPUT_CHIP),
                    )
                }
            }
        }

        // If the card is taller than necessary to show all the rows, this adds spacing
        // between the top row and the rows below, anchoring the next rows to the bottom
        // of the card.
        if (fillHeight) {
            Spacer(Modifier.weight(1f))
        }

        if (threeRows) {
            // Three row presentation style.
            //
            // Second row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp).heightIn(min = 48.dp),
            ) {
                Metadata(
                    title = viewModel.title,
                    subtitle = viewModel.subtitle,
                    isExplicit = viewModel.isExplicit,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 8.dp).animateContentSize(),
                )

                if (viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause) {
                    AnimatedVisibility(visible = viewModel.playPauseAction != null) {
                        PlayPauseAction(
                            viewModel = viewModel.playPauseAction,
                            buttonColor = colorScheme.primary,
                            iconColor = colorScheme.onPrimary,
                            buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 48.dp },
                        )
                    }
                } else {
                    Spacer(Modifier.size(width = 0.dp, height = 48.dp))
                }
            }

            // Third row.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                ) {
                    val areActionsVisible =
                        viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause
                    Navigation(
                        viewModel = viewModel.navigation,
                        isSeekBarVisible = true,
                        isLeftActionVisible = areActionsVisible,
                        isRightActionVisible = areActionsVisible,
                        modifier = Modifier.weight(1f),
                    )

                    viewModel.additionalActions.fastForEachIndexed { index, action ->
                        SecondaryAction(
                            viewModel = action,
                            resId = "action$index",
                            element = Media.Elements.additionalActionButton(index),
                        )
                    }
                }
            }
        } else {
            // Two row presentation style.
            //
            // Bottom row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Metadata(
                    title = viewModel.title,
                    subtitle = viewModel.subtitle,
                    isExplicit = viewModel.isExplicit,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 8.dp).animateContentSize(),
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val areActionsVisible =
                        viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause
                    Navigation(
                        viewModel = viewModel.navigation,
                        isSeekBarVisible = false,
                        isLeftActionVisible = false,
                        isRightActionVisible = areActionsVisible,
                        modifier = Modifier.padding(end = 8.dp),
                    )

                    if (
                        viewModel.actionButtonLayout
                            is MediaCardActionButtonLayout.SecondaryActionsOnly
                    ) {
                        (viewModel.actionButtonLayout
                                as MediaCardActionButtonLayout.SecondaryActionsOnly)
                            .indicesForCompressed
                            .fastForEach { index ->
                                SecondaryAction(
                                    viewModel = viewModel.additionalActions[index],
                                    resId = "action$index",
                                    element = Media.Elements.additionalActionButton(index),
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                    }
                }

                if (viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause) {
                    AnimatedVisibility(visible = viewModel.playPauseAction != null) {
                        PlayPauseAction(
                            viewModel = viewModel.playPauseAction,
                            buttonColor = colorScheme.primary,
                            iconColor = colorScheme.onPrimary,
                            buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 48.dp },
                        )
                    }
                } else {
                    Spacer(Modifier.size(width = 0.dp, height = 48.dp))
                }
            }
        }
    }
}

/**
 * Renders a simplified version of [CardForeground] that puts everything on a single row and doesn't
 * support the guts.
 */
@Composable
private fun ContentScope.CompactCardForeground(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable(onClick = { viewModel.onClick(expandable) })
                .semantics { contentDescription = viewModel.contentDescription }
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
    ) {
        Icon(
            icon = viewModel.icon,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )

        Metadata(
            title = viewModel.title,
            subtitle = viewModel.subtitle,
            isExplicit = viewModel.isExplicit,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        SecondaryAction(
            viewModel = viewModel.outputSwitcherChipButton,
            resId = MediaRes.OUTPUT_CHIP,
            element = Media.Elements.OutputSwitcherButton,
            iconColor = MaterialTheme.colorScheme.onSurface,
        )

        val rightAction = viewModel.navigation.right
        if (rightAction !is MediaSecondaryActionViewModel.None) {
            SecondaryAction(
                viewModel = rightAction,
                resId = MediaRes.NEXT_BTN,
                element = Media.Elements.NextButton,
                iconColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        AnimatedVisibility(visible = viewModel.playPauseAction != null) {
            PlayPauseAction(
                viewModel = viewModel.playPauseAction,
                buttonColor = MaterialTheme.colorScheme.primaryContainer,
                iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 24.dp },
            )
        }
    }
}

/** Renders the background of a card, loading the artwork and showing an overlay on top of it. */
@Composable
private fun CardBackground(
    image: Icon?,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    Crossfade(targetState = image, modifier = modifier) { imageOrNull ->
        val backgroundImage =
            remember(imageOrNull) { imageOrNull?.let { (it as Icon.Loaded).asImageBitmap() } }
        if (backgroundImage != null) {
            // Loaded art.
            val gradientBaseColor = colorScheme.background
            Image(
                bitmap = backgroundImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize().drawWithContent {
                        // Draw the content (loaded art).
                        drawContent()

                        if (image != null) {
                            // Then draw the overlay.
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        0f to gradientBaseColor.copy(alpha = 0.65f),
                                        1f to gradientBaseColor.copy(alpha = 0.75f),
                                        center = size.center,
                                        radius = max(size.width, size.height) / 2,
                                    )
                            )
                        }
                    },
            )
        } else {
            // Placeholder.
            Box(Modifier.background(colorScheme.background).fillMaxSize())
        }
    }
}

/**
 * Renders the navigation UI (seek bar and/or previous/next buttons).
 *
 * If [isSeekBarVisible] is `false`, the seek bar will not be included in the layout, even if it
 * would otherwise be showing based on the view-model alone. This is meant for callers to decide
 * whether they'd like to show the seek bar in addition to the prev/next buttons or just show the
 * buttons.
 *
 * If [areActionsVisible] is `false`, the left/right buttons to the left and right of the seek bar
 * will not be included in the layout.
 */
@Composable
private fun ContentScope.Navigation(
    viewModel: MediaNavigationViewModel,
    isSeekBarVisible: Boolean,
    isLeftActionVisible: Boolean,
    isRightActionVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    when (viewModel) {
        is MediaNavigationViewModel.Showing -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
            ) {
                if (viewModel.isScrubbing) {
                    TimestampText(viewModel.progressText)
                } else if (
                    !isLeftActionVisible || viewModel.left is MediaSecondaryActionViewModel.None
                ) {
                    Spacer(Modifier.size(width = 16.dp, height = 48.dp))
                } else {
                    SecondaryAction(
                        viewModel = viewModel.left,
                        modifier = Modifier.sysuiResTag(MediaRes.PREV_BTN),
                        element = Media.Elements.PrevButton,
                    )
                }

                val interactionSource = remember { MutableInteractionSource() }
                val colors =
                    colors(
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        thumbColor = Color.White,
                    )
                if (isSeekBarVisible) {
                    // To allow the seek bar slider to fade in and out, it's tagged as an element.
                    Element(key = Media.Elements.SeekBarSlider, modifier = Modifier.weight(1f)) {
                        val sliderDragDelta = remember {
                            // Not a mutableStateOf - this is never accessed in composition and
                            // using an anonymous object avoids generics boxing of inline Offset.
                            object {
                                var value = Offset.Zero
                            }
                        }
                        val isEnabled = viewModel.onScrubChange != null
                        val velocityTracker = remember { VelocityTracker() }
                        val context = LocalContext.current
                        val flingVelocity =
                            remember(context) {
                                ViewConfiguration.get(context).scaledMinimumFlingVelocity * 10
                            }
                        var isDrag by remember { mutableStateOf(false) }
                        var isDragStartedOnThumb by remember { mutableStateOf(false) }
                        val currentProgress by rememberUpdatedState(viewModel.progress)
                        Slider(
                            interactionSource = interactionSource,
                            value = viewModel.progress,
                            enabled = isEnabled,
                            onValueChange = { progress ->
                                if (!isDrag || isDragStartedOnThumb) {
                                    viewModel.onScrubChange?.invoke(progress)
                                }
                            },
                            onValueChangeFinished = {
                                val velocity = velocityTracker.calculateVelocity().x
                                if (!isDrag || isDragStartedOnThumb) {
                                    viewModel.onScrubFinished?.invoke(
                                        sliderDragDelta.value,
                                        abs(velocity) < abs(flingVelocity),
                                    )
                                }

                                isDragStartedOnThumb = false
                                isDrag = false
                            },
                            colors = colors,
                            thumb = {
                                if (isEnabled) {
                                    SeekBarThumb(
                                        interactionSource = interactionSource,
                                        colors = colors,
                                    )
                                }
                            },
                            track = { sliderState ->
                                SeekBarTrack(
                                    sliderState = sliderState,
                                    isSquiggly = viewModel.isSquiggly,
                                    colors = colors,
                                    isThumbEnabled = isEnabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .semantics { stateDescription = viewModel.contentDescription }
                                    .pointerInput(Unit) {
                                        // Track and report the drag delta to the view-model so it
                                        // can decide whether to accept the next
                                        // onValueChangeFinished or reject it if the drag was overly
                                        // vertical.
                                        awaitPointerEventScope {
                                            var down: PointerInputChange? = null
                                            while (true) {
                                                val event =
                                                    awaitPointerEvent(PointerEventPass.Initial)

                                                event.changes.forEach { change ->
                                                    // Feed the velocity tracker to detect flings
                                                    velocityTracker.addPosition(
                                                        change.uptimeMillis,
                                                        change.position,
                                                    )
                                                }
                                                when (event.type) {
                                                    PointerEventType.Press -> {
                                                        // A new gesture has begun.
                                                        // Record the initial down input change.
                                                        down = event.changes.last()
                                                        isDrag = false
                                                    }

                                                    PointerEventType.Move -> {
                                                        // The pointer has moved. If it's the same
                                                        // pointer as the latest down, calculate and
                                                        // report the drag delta.
                                                        val change = event.changes.last()

                                                        if (change.id == down?.id) {
                                                            if (!isDrag) {
                                                                val thumbX =
                                                                    size.width * currentProgress
                                                                // Add some forgiveness to hit
                                                                // target by 20dp radius
                                                                isDragStartedOnThumb =
                                                                    abs(down.position.x - thumbX) <
                                                                        20.dp.toPx()
                                                            }
                                                            isDrag = true
                                                            if (isDragStartedOnThumb) {
                                                                sliderDragDelta.value =
                                                                    change.position - down.position
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                        )
                    }
                }

                if (viewModel.isScrubbing) {
                    TimestampText(viewModel.durationText)
                } else if (
                    !isRightActionVisible || viewModel.right is MediaSecondaryActionViewModel.None
                ) {
                    Spacer(Modifier.size(width = 16.dp, height = 48.dp))
                } else {
                    SecondaryAction(
                        viewModel = viewModel.right,
                        modifier = Modifier.sysuiResTag(MediaRes.NEXT_BTN),
                        element = Media.Elements.NextButton,
                    )
                }
            }
        }

        is MediaNavigationViewModel.Hidden -> {
            if (!isLeftActionVisible || viewModel.left is MediaSecondaryActionViewModel.None) {
                Spacer(Modifier.size(width = 16.dp, height = 48.dp))
            } else {
                SecondaryAction(
                    viewModel = viewModel.left,
                    modifier = Modifier.sysuiResTag(MediaRes.PREV_BTN),
                    element = Media.Elements.PrevButton,
                )
            }
            if (!isRightActionVisible || viewModel.right is MediaSecondaryActionViewModel.None) {
                Spacer(Modifier.size(width = 16.dp, height = 48.dp))
            } else {
                SecondaryAction(
                    viewModel = viewModel.right,
                    modifier = Modifier.sysuiResTag(MediaRes.NEXT_BTN),
                    element = Media.Elements.NextButton,
                )
            }
        }
    }
}

/** Renders the thumb of the seek bar. */
@Composable
private fun SeekBarThumb(
    interactionSource: MutableInteractionSource,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { mutableStateListOf<Interaction>() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> interactions.add(interaction)
                is PressInteraction.Release -> interactions.remove(interaction.press)
                is PressInteraction.Cancel -> interactions.remove(interaction.press)
                is DragInteraction.Start -> interactions.add(interaction)
                is DragInteraction.Stop -> interactions.remove(interaction.start)
                is DragInteraction.Cancel -> interactions.remove(interaction.start)
            }
        }
    }

    Spacer(
        modifier
            .size(width = 4.dp, height = 16.dp)
            .hoverable(interactionSource = interactionSource)
            .background(color = colors.thumbColor, shape = RoundedCornerShape(16.dp))
    )
}

/**
 * Renders the track of the seek bar.
 *
 * If [isSquiggly] is `true`, the part to the left of the thumb will animate a squiggly line that
 * oscillates up and down. The [waveLength] and [amplitude] control the geometry of the squiggle and
 * the [waveSpeedDpPerSec] controls the speed by which it seems to "move" horizontally.
 */
@Composable
private fun SeekBarTrack(
    sliderState: SliderState,
    isSquiggly: Boolean,
    colors: SliderColors,
    isThumbEnabled: Boolean,
    modifier: Modifier = Modifier,
    waveLength: Dp = 20.dp,
    amplitude: Dp = 3.dp,
    waveSpeedDpPerSec: Dp = 8.dp,
) {
    // Animating the amplitude allows the squiggle to gradually grow to its full height or shrink
    // back to a flat line as needed.
    val animatedAmplitude by
        animateDpAsState(
            targetValue = if (isSquiggly) amplitude else 0.dp,
            label = "SeekBarTrack.amplitude",
        )

    // This animates the horizontal movement of the squiggle.
    val animatedWaveOffset = remember { Animatable(0f) }

    LaunchedEffect(isSquiggly) {
        if (isSquiggly) {
            animatedWaveOffset.snapTo(0f)
            animatedWaveOffset.animateTo(
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            tween(
                                durationMillis = (1000 * (waveLength / waveSpeedDpPerSec)).toInt(),
                                easing = LinearEasing,
                            ),
                        repeatMode = RepeatMode.Restart,
                    ),
            )
        }
    }

    val path = remember { Path() }

    // Render the track.
    Canvas(modifier = modifier) {
        val thumbPositionPx = size.width * sliderState.value
        val amplitudePx = amplitude.toPx()
        val animatedAmplitudePx = animatedAmplitude.toPx()
        val waveLengthPx = waveLength.toPx()
        val halfWaveLengthPx = waveLengthPx / 2

        val offsetPx = waveLengthPx * animatedWaveOffset.value
        val totalWidth = size.width + waveLengthPx
        val halfWaveCount = (totalWidth / halfWaveLengthPx).toInt()

        var currentX = 0f
        path.reset()
        path.apply {
            repeat(halfWaveCount + 3) { index ->
                // the position of either the peak or the trough.
                val centerX = currentX + (halfWaveLengthPx / 2)

                // We subtract offsetPx because we are shifting the whole path to the left by
                // offsetPx, in order to calculate the new point.
                val posDiff = centerX - (offsetPx + thumbPositionPx)
                val transitionRatio =
                    when {
                        posDiff <= 0 -> 1f // Active part
                        posDiff < waveLengthPx ->
                            1f - (posDiff / waveLengthPx) // Active -> Inactive
                        else -> 0f // Inactive part (flat)
                    }
                val calculatedAmplitude = animatedAmplitudePx * transitionRatio

                // Draw a half wave (either a hill or a valley shape starting and ending on
                // the horizontal center).
                relativeQuadraticTo(
                    // The control point for the bezier curve is on top of the peak of the
                    // hill or the very center bottom of the valley shape.
                    dx1 = halfWaveLengthPx / 2,
                    dy1 = if (index % 2 == 0) -calculatedAmplitude else calculatedAmplitude,
                    // Advance horizontally, half a wave length at a time.
                    dx2 = halfWaveLengthPx,
                    dy2 = 0f,
                )

                currentX += halfWaveLengthPx
            }
        }

        // To handle the change in colors, we use the same path twice.
        // Paths are shifted by offsetPx due to the translation to the left.
        translate(left = -offsetPx, top = 0f) {
            // Draw inactive path first.
            if (isThumbEnabled) {
                // The flat line after the thumb, if thumb is enabled.
                drawLine(
                    color = colors.inactiveTrackColor,
                    start = Offset(thumbPositionPx + offsetPx, 0f),
                    end = Offset(size.width + offsetPx, 0f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                // Draw the full path (wave -> transition -> flat).
                clipRect(
                    left = offsetPx,
                    top = -amplitudePx * 2,
                    right = size.width + offsetPx,
                    bottom = amplitudePx * 2,
                ) {
                    drawPath(
                        path = path,
                        color = colors.inactiveTrackColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Draw the same path with active Color clipped to the thumb.
            clipRect(
                left = offsetPx,
                top = -amplitudePx * 2,
                right = thumbPositionPx + offsetPx,
                bottom = amplitudePx * 2,
            ) {
                drawPath(
                    path = path,
                    color = colors.activeTrackColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Renders the internal "guts" of a card. */
@Composable
private fun CardGuts(
    viewModel: MediaCardGutsViewModel,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.pointerInput(Unit) {
                detectTapGestures(onTap = {}, onLongPress = { viewModel.onLongClick() })
            }
    ) {
        // Settings button.
        Icon(
            icon = checkNotNull(viewModel.settingsButton.icon),
            modifier =
                Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp).clickable {
                    viewModel.settingsButton.onClick()
                },
            tint = Color.White,
        )

        //  Content.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 32.dp, top = 16.dp),
        ) {
            Text(
                text = viewModel.text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformButton(
                    onClick = viewModel.primaryAction.onClick,
                    modifier = Modifier.sysuiResTag(MediaRes.HIDE_BTN),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                ) {
                    Text(
                        text = checkNotNull(viewModel.primaryAction.text),
                        color = colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 14.sp,
                    )
                }

                viewModel.secondaryAction?.let { button ->
                    PlatformOutlinedButton(
                        onClick = button.onClick,
                        border = BorderStroke(width = 1.dp, color = colorScheme.primary),
                    ) {
                        Text(
                            text = checkNotNull(button.text),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Renders the metadata labels of a track. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentScope.Metadata(
    title: String,
    subtitle: String,
    isExplicit: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // This element can be animated when switching between scenes inside a media card.
    Element(key = Media.Elements.Metadata, modifier = modifier) {
        // When the title and/or subtitle change, crossfade between the old and the new.
        Crossfade(targetState = title to subtitle, label = "Labels.crossfade") { (title, subtitle)
            ->
            Column {
                Text(
                    text = title,
                    modifier = Modifier.sysuiResTag(MediaRes.TITLE),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isExplicit) {
                        Icon(
                            icon =
                                Icon.Resource(
                                    resId = R.drawable.ic_media_explicit_indicator,
                                    contentDescription = null,
                                ),
                            modifier =
                                Modifier.sysuiResTag(MediaRes.EXPLICIT_INDICATOR)
                                    .padding(end = 8.dp)
                                    .size(13.dp),
                            tint = color,
                        )
                    }

                    Text(
                        text = subtitle,
                        modifier = Modifier.sysuiResTag(MediaRes.ARTIST),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceChip(
    viewModel: MediaDeviceChipViewModel,
    style: DeviceChipStyle,
    modifier: Modifier = Modifier,
) {
    // For accessibility reasons, the touch area for the chip needs to be at least 48dp in height.
    // At the same time, the rounded corner chip should only be as tall as it needs to be to contain
    // its contents and look like a nice design; also, the ripple effect should only be shown within
    // the bounds of the chip.
    //
    // This is achieved by sharing this InteractionSource between the outer and inner composables.
    //
    // The outer composable hosts that clickable that writes user events into the InteractionSource.
    // The inner composable consumes the user events from the InteractionSource and feeds them into
    // its indication.
    val clickInteractionSource = remember { MutableInteractionSource() }
    val expandable = remember { Expandable() }
    Box(
        modifier =
            modifier
                .padding(top = 16.dp, bottom = 0.dp)
                .heightIn(min = 48.dp)
                .clickable(interactionSource = clickInteractionSource, indication = null) {
                    viewModel.onClick(expandable)
                }
                .clearAndSetSemantics {
                    contentDescription = viewModel.text?.toString() ?: viewModel.defaultDescription
                }
    ) {
        Expandable(
            expandable = expandable,
            controller =
                rememberExpandableController(
                    color = style.fillColor,
                    shape = RoundedCornerShape(12.dp),
                ),
            useModifierBasedImplementation = false,
            defaultMinSize = false,
            modifier = Modifier.wrapContentSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(style.fillColor)
                        .thenIf(style.borderColor != null) {
                            Modifier.border(
                                width = 1.dp,
                                color = style.borderColor!!,
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                        .indication(clickInteractionSource, ripple())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (viewModel.isConnecting) {
                    CircularProgressIndicator(
                        color = style.contentColor,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.dp,
                    )
                } else {
                    Icon(
                        icon = viewModel.icon,
                        tint = style.contentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                AnimatedVisibility(visible = viewModel.text != null) {
                    rememberLastNonNull(viewModel.text)?.let {
                        Text(
                            text = it.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = style.contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Renders the primary action of media controls: the play/pause button. */
@Composable
private fun ContentScope.PlayPauseAction(
    viewModel: MediaPlayPauseActionViewModel?,
    buttonColor: Color,
    iconColor: Color,
    buttonCornerRadius: (isPlaying: Boolean) -> Dp,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null) return

    val buttonSize = DpSize(width = 72.dp, height = 48.dp)
    val cornerRadius: Dp by
        animateDpAsState(
            targetValue = buttonCornerRadius(viewModel.state != MediaSessionState.Paused),
            label = "PlayPauseAction.cornerRadius",
        )

    val scaleAnimatable = remember { Animatable(1.0f) }
    val scope = rememberCoroutineScope()

    // This element can be animated when switching between scenes inside a media card.
    Element(key = Media.Elements.PlayPauseButton, modifier = modifier) {
        PlatformButton(
            onClick = {
                viewModel.onClick?.let {
                    it()
                    scope.launch {
                        scaleAnimatable.animateTo(
                            targetValue = 1.125f,
                            animationSpec = tween(durationMillis = 250),
                        )
                        scaleAnimatable.animateTo(
                            targetValue = 1.0f,
                            animationSpec = tween(durationMillis = 250),
                        )
                    }
                }
            },
            enabled = viewModel.onClick != null,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.size(buttonSize).scale(scaleAnimatable.value),
        ) {
            when (viewModel.state) {
                is MediaSessionState.Playing,
                is MediaSessionState.Paused -> {
                    val painterOrNull =
                        when (viewModel.icon) {
                            is Icon.Resource ->
                                rememberAnimatedVectorPainter(
                                    animatedImageVector =
                                        AnimatedImageVector.animatedVectorResource(
                                            id = viewModel.icon.resId
                                        ),
                                    atEnd = viewModel.state == MediaSessionState.Playing,
                                )
                            is Icon.Loaded -> rememberDrawablePainter(viewModel.icon.drawable)
                            null -> null
                        }
                    painterOrNull?.let { painter ->
                        Icon(
                            painter = painter,
                            contentDescription = viewModel.icon?.contentDescription?.load(),
                            tint = iconColor,
                            modifier = Modifier.size(24.dp).sysuiResTag(MediaRes.PLAY_PAUSE_BTN),
                        )
                    }
                }
                is MediaSessionState.Buffering -> {
                    CircularProgressIndicator(color = iconColor, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Renders an icon button for an action that's not the play/pause action.
 *
 * If [element] is provided, the secondary action element will be able to animate when switching
 * between scenes inside a media card.
 */
@Composable
private fun ContentScope.SecondaryAction(
    viewModel: MediaSecondaryActionViewModel,
    modifier: Modifier = Modifier,
    resId: String = MediaRes.EMPTY_STRING,
    element: ElementKey? = null,
    iconColor: Color = Color.White,
) {
    if (viewModel !is MediaSecondaryActionViewModel.None && element != null) {
        Element(key = element, modifier = modifier) {
            SecondaryActionContent(viewModel = viewModel, iconColor = iconColor, resId = resId)
        }
    } else {
        SecondaryActionContent(
            viewModel = viewModel,
            iconColor = iconColor,
            resId = resId,
            modifier = modifier,
        )
    }
}

/** The content of a [SecondaryAction]. */
@Composable
private fun SecondaryActionContent(
    viewModel: MediaSecondaryActionViewModel,
    iconColor: Color,
    resId: String,
    modifier: Modifier = Modifier,
) {
    val sharedModifier = modifier.size(48.dp).padding(13.dp)
    when (viewModel) {
        is MediaSecondaryActionViewModel.Action ->
            when (viewModel.icon) {
                is Icon.Resource ->
                    PlatformIconButton(
                        onClick = viewModel.onClick ?: {},
                        modifier = sharedModifier.sysuiResTag(resId),
                        enabled = viewModel.onClick != null,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = iconColor),
                        iconResource = viewModel.icon.resId,
                        contentDescription = viewModel.icon.contentDescription?.load(),
                    )

                is Icon.Loaded ->
                    PlatformIconButton(
                        onClick = viewModel.onClick ?: {},
                        modifier = sharedModifier.sysuiResTag(resId),
                        enabled = viewModel.onClick != null,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = iconColor),
                        iconDrawable = viewModel.icon.drawable,
                        contentDescription = viewModel.icon.contentDescription?.load(),
                    )
            }

        is MediaSecondaryActionViewModel.ReserveSpace -> Spacer(modifier = sharedModifier)

        is MediaSecondaryActionViewModel.None -> Unit
    }
}

@Composable
private fun TimestampText(text: String) {
    Text(
        text = text,
        modifier = Modifier.widthIn(min = 48.dp).padding(4.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * Renders the revealed content on the sides of the horizontal pager.
 *
 * @param revealAmount A callback that can return the amount of revealing done. This value will be
 *   in a range slightly larger than `-1` to `+1` where `1` is fully revealed on the left-hand side,
 *   `-1` is fully revealed on the right-hand side, and `0` is not revealed at all. Numbers lower
 *   than `-1` or greater than `1` are possible when the overscroll effect adds additional pixels of
 *   offset.
 */
@Composable
private fun RevealedContent(
    viewModel: MediaSettingsButtonViewModel,
    revealAmount: () -> Float,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = 18.dp

    // This custom layout's purpose is only to place the icon in the center of the revealed content,
    // taking into account the amount of reveal.
    Layout(
        content = {
            val revealAlpha =
                revealAmount().let { if (it.isNaN()) 0f else abs(it).fastCoerceIn(0f, 1f) }
            Icon(
                icon = viewModel.icon,
                modifier =
                    Modifier.background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = revealAlpha),
                            shape = CircleShape,
                        )
                        .layoutId(Media.LayoutId.CardRevealedContent)
                        .size(48.dp)
                        .padding(12.dp)
                        .graphicsLayer {
                            alpha = revealAlpha
                            rotationZ = revealAmount() * 90
                        }
                        .clickable { viewModel.onClick() },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable =
            measurables.singleton(Media.LayoutId.CardRevealedContent).measure(constraints)

        val totalWidth =
            min(horizontalPadding.roundToPx() * 2 + placeable.measuredWidth, constraints.maxWidth)

        layout(totalWidth, constraints.maxHeight) {
            coordinates?.size?.let { size ->
                val reveal = revealAmount()
                val x =
                    if (reveal >= 0f) {
                        ((size.width * abs(reveal)) - placeable.measuredWidth) / 2
                    } else {
                        size.width * (1 - abs(reveal) / 2) - placeable.measuredWidth / 2
                    }

                placeable.place(
                    x = x.fastRoundToInt(),
                    y = (size.height - placeable.measuredHeight) / 2,
                )
            }
        }
    }
}

/** Enumerates all supported media presentation styles. */
enum class MediaPresentationStyle {
    /** The "normal" 3-row carousel look. */
    Default,
    /** Similar to [Default] but with full height. Used in communal hub. */
    Large,
    /** Similar to [Default] but not as tall (2-row carousel look). */
    Compressed,
    /** A special single-row treatment that fits nicely in quick settings. */
    Compact,
    /** A minimal, non-interactive layout. Used for secondary cards in carousel view */
    Thumbnail,
}

data class MediaUiBehavior(
    val isCarouselDismissible: Boolean = true,
    /** If false, carousel will not be scrollable and will only show the first item */
    val isCarouselScrollingEnabled: Boolean = true,
    val carouselVisibility: MediaCarouselVisibility = MediaCarouselVisibility.WhenNotEmpty,
    /**
     * If provided, this callback will be consulted at the beginning of each carousel scroll gesture
     * to see if the falsing system thinks that it's a false touch. If it then returns `true`, the
     * scroll will be canceled.
     */
    val isCarouselScrollFalseTouch: (() -> Boolean)? = null,
)

@Stable
private interface AnimatedColorScheme {
    val primary: Color
    val onPrimary: Color
    val background: Color
}

private object MediaRes {
    const val EMPTY_STRING = ""
    const val BACKGROUND = "album_art"
    const val PLAY_PAUSE_BTN = "actionPlayPause"
    const val NEXT_BTN = "actionNext"
    const val PREV_BTN = "actionPrev"
    const val MEDIA_CAROUSEL_SCROLLER = "media_carousel_scroller"
    const val MEDIA_CAROUSEL = "media_carousel"
    const val MEDIA_CONTROLS = "qs_media_controls"
    const val OUTPUT_CHIP = "media_seamless"
    const val SUGGESTED_DEVICE_CHIP = "device_suggestion_button"
    const val TITLE = "header_title"
    const val ARTIST = "header_artist"
    const val EXPLICIT_INDICATOR = "media_explicit_indicator"
    const val HIDE_BTN = "dismiss"
}

object Media {

    /**
     * Scenes.
     *
     * The implementation uses a [SceneTransitionLayout] to smoothly animate transitions between
     * different card layouts. Each card layout is identified as its own "scene" and the STL
     * framework takes care of animating the layouts and their elements as the card morphs between
     * scenes.
     */
    object Scenes {
        /** The "normal" 3-row carousel look. */
        val Default = SceneKey("default")
        /** Similar to [Default] but with full height. Used in communal hub. */
        val Large = SceneKey("large")
        /** Similar to [Default] but not as tall (2-row carousel look). */
        val Compressed = SceneKey("compressed")
        /** A special single-row treatment that fits nicely in quick settings. */
        val Compact = SceneKey("compact")
        /** A minimal, non-interactive layout. Used for secondary cards in carousel view */
        val Thumbnail = SceneKey("thumbnail")
    }

    private fun TransitionBuilder.fadeAll() {
        fade(Elements.AppIcon)
        fade(Elements.PlayPauseButton)
        fade(Elements.Metadata)
        fade(Elements.PrevButton)
        fade(Elements.NextButton)
        fade(Elements.SeekBarSlider)
        fade(Elements.OutputSwitcherButton)
        for (i in 0..5) {
            fade(Elements.additionalActionButton(i))
        }
    }

    /** Definitions of how scene changes are transition-animated. */
    val Transitions = transitions {
        from(Scenes.Default, to = Scenes.Compact) {}
        from(Scenes.Default, to = Scenes.Large) {}
        from(Scenes.Default, to = Scenes.Compressed) { fade(Elements.SeekBarSlider) }
        from(Scenes.Compressed, to = Scenes.Default) {
            fractionRange(start = 0.35f) {
                fade(Elements.SeekBarSlider)
                fade(Elements.PrevButton)
                for (i in 0..5) {
                    fade(Elements.additionalActionButton(i))
                }
            }
        }
        from(Scenes.Compact, to = Scenes.Compressed) { fade(Elements.SeekBarSlider) }
        from(Scenes.Thumbnail, to = Scenes.Default) { fadeAll() }
        from(Scenes.Thumbnail, to = Scenes.Compact) { fadeAll() }
        from(Scenes.Thumbnail, to = Scenes.Large) { fadeAll() }
        from(Scenes.Thumbnail, to = Scenes.Compressed) { fadeAll() }
    }

    /**
     * Element keys.
     *
     * Composables that are wrapped in [ContentScope.Element] with one of these as their `key`
     * parameter will automatically be picked up by the STL transition animation framework and will
     * be animated from their bounds in the original scene to their bounds in the destination scene.
     *
     * In addition, tagging such elements with a key allows developers to customize the transition
     * animations even further.
     */
    object Elements {
        val AppIcon = ElementKey("app_icon")
        val PlayPauseButton = ElementKey("play_pause")
        val Metadata = ElementKey("metadata")
        val PrevButton = ElementKey("prev")
        val NextButton = ElementKey("next")
        val SeekBarSlider = ElementKey("seek_bar_slider")
        val OutputSwitcherButton = ElementKey("output_switcher")
        val MediaCarousel = ElementKey("media_carousel")

        fun additionalActionButton(index: Int): ElementKey {
            val name = "additional_action_$index"
            return ElementKey(debugName = name, identity = name)
        }
    }

    enum class LayoutId {
        CardForeground,
        CardGuts,
        CardRevealedContent,
    }

    enum class Location {
        QS,
        SHADE,
        LOCKSCREEN,
        COMMUNAL_HUB,
        STATUS_BAR_POPUP,
    }

    const val TAG = "Media"
    val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    val DEFAULT_HEIGHT = 176.dp
    val COMPRESSED_HEIGHT = 128.dp
    val COMPACT_HEIGHT = 80.dp
}

private fun MediaPresentationStyle.toScene(): SceneKey {
    return when (this) {
        MediaPresentationStyle.Default -> Media.Scenes.Default
        MediaPresentationStyle.Large -> Media.Scenes.Large
        MediaPresentationStyle.Compressed -> Media.Scenes.Compressed
        MediaPresentationStyle.Compact -> Media.Scenes.Compact
        MediaPresentationStyle.Thumbnail -> Media.Scenes.Thumbnail
    }
}

/** Allows to set the maxWidth constraint as a fractional value. */
private fun Modifier.fractionalMaxWidth(containerMaxWidth: Int, fraction: Float): Modifier {
    return layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                constraints.copy(
                    maxWidth =
                        min((containerMaxWidth * fraction).fastRoundToInt(), constraints.maxWidth)
                )
            )

        layout(placeable.measuredWidth, placeable.measuredHeight) { placeable.place(0, 0) }
    }
}

@Composable
fun <T> rememberLastNonNull(value: T?): T? {
    val ref = remember { Ref<T?>() }
    ref.value = value ?: ref.value
    return ref.value
}

private data class DeviceChipStyle(
    val fillColor: Color,
    val contentColor: Color,
    val borderColor: Color? = null,
)
