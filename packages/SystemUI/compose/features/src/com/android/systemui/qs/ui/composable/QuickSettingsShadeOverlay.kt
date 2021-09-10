/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.ui.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSliderDefaults
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.mechanics.TileRevealFlag
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.compose.modifiers.thenIf
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.notifications.ui.composable.SnoozeableHeadsUpNotificationSpace
import com.android.systemui.notifications.ui.composable.headsUpTopInset
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.TileDetails
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.panels.ui.compose.toolbar.Toolbar
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.qs.tiles.dialog.AudioDetailsViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsShade.systemGestureExclusionInShade
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayActionsViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeOverlayContentViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ui.composable.ChipHighlightModel
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.OverlayShadeHeader
import com.android.systemui.shade.ui.composable.QuickSettingsOverlayHeader
import com.android.systemui.shade.ui.composable.isFullWidthShade
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlider
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickSettingsShadeOverlay
@Inject
constructor(
    private val actionsViewModelFactory: QuickSettingsShadeOverlayActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsShadeOverlayContentViewModel.Factory,
    private val quickSettingsContainerViewModelFactory: QuickSettingsContainerViewModel.Factory,
    private val notificationStackScrollView: Lazy<NotificationScrollView>,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
) : Overlay {

    override val key = Overlays.QuickSettingsShade

    private val actionsViewModel: QuickSettingsShadeOverlayActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override val alwaysCompose: Boolean = false

    override suspend fun activate(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(modifier: Modifier) {
        val coroutineScope = rememberCoroutineScope()
        val contentViewModel =
            rememberViewModel("QuickSettingsShadeOverlayContent", key = coroutineScope) {
                contentViewModelFactory.create(coroutineScope)
            }
        val useBrightnessMirrorInOverlay = useBrightnessMirrorInOverlay()
        val quickSettingsContainerViewModel =
            rememberViewModel("QuickSettingsShadeOverlayContainer") {
                quickSettingsContainerViewModelFactory.create(
                    supportsBrightnessMirroring = useBrightnessMirrorInOverlay
                )
            }
        val hunPlaceholderViewModel =
            rememberViewModel("QuickSettingsShadeOverlayPlaceholder") {
                notificationsPlaceholderViewModelFactory.create()
            }

        val showBrightnessMirror =
            quickSettingsContainerViewModel.brightnessSliderViewModel.showMirror
        val contentAlphaFromBrightnessMirror by
            animateFloatAsState(if (showBrightnessMirror) 0f else 1f)
        val headsUpInset = with(LocalDensity.current) { headsUpTopInset().toPx() }

        val targetBlurRadiusPx: Float by
            remember(layoutState) {
                derivedStateOf {
                    contentViewModel.calculateTargetBlurRadius(layoutState.transitionState)
                }
            }
        val animatedBlurRadiusPx: Float by
            animateFloatAsState(targetValue = targetBlurRadiusPx, label = "NSOverlay-blurRadius")

        // Set the bounds to null when the QuickSettings overlay disappears.
        DisposableEffectWithLifecycle(Unit) {
            onDispose {
                contentViewModel.onPanelShapeInWindowChanged(null)
                contentViewModel.onShadeOverlayBoundsChanged(null)
            }
        }

        LaunchedEffectWithLifecycle(key1 = Unit) { contentViewModel.detectShadeModeChanges() }

        Box(
            modifier =
                modifier
                    .graphicsLayer { alpha = contentAlphaFromBrightnessMirror }
                    .blur(with(LocalDensity.current) { animatedBlurRadiusPx.toDp() })
                    .thenIf(showBrightnessMirror) { Modifier.gesturesDisabled() }
        ) {
            OverlayShade(
                panelElement = QuickSettingsShade.Elements.Panel,
                alignmentOnWideScreens = Alignment.End,
                enableTransparency = contentViewModel.isTransparencyEnabled,
                onScrimClicked = contentViewModel::onScrimClicked,
                onBackgroundPlaced = { bounds, topCornerRadius, bottomCornerRadius ->
                    contentViewModel.onShadeOverlayBoundsChanged(bounds)
                    contentViewModel.onPanelShapeInWindowChanged(
                        ShadeScrimShape(
                            bounds = ShadeScrimBounds(bounds),
                            topRadius = topCornerRadius.roundToInt(),
                            bottomRadius = bottomCornerRadius.roundToInt(),
                        )
                    )
                },
                header = {
                    if (contentViewModel.showHeader) {
                        val headerViewModel = quickSettingsContainerViewModel.shadeHeaderViewModel
                        OverlayShadeHeader(
                            viewModel = headerViewModel,
                            notificationsHighlight = headerViewModel.inactiveChipHighlight,
                            quickSettingsHighlight = ChipHighlightModel.Strong,
                            showClock = true,
                            modifier = Modifier.element(QuickSettingsShade.Elements.StatusBar),
                        )
                    }
                },
            ) {
                QuickSettingsContainer(
                    contentViewModel = contentViewModel,
                    containerViewModel = quickSettingsContainerViewModel,
                )
            }
            SnoozeableHeadsUpNotificationSpace(
                useDrawBounds = {
                    with(layoutState.transitionState) {
                        // When overlaid on top of the lock screen, drawBounds updates are already
                        // being sent.
                        isIdle(key) && !isIdle(Scenes.Lockscreen)
                    }
                },
                stackScrollView = notificationStackScrollView.get(),
                viewModel = hunPlaceholderViewModel,
                modifier =
                    Modifier.onGloballyPositioned { layoutCoordinates ->
                        val bounds = layoutCoordinates.boundsInWindow().toAndroidRectF()
                        if (bounds.height() > 0) {
                            // HUN gesture area must extend from the top of the screen for
                            // animations
                            bounds.top = 0f
                            bounds.bottom += headsUpInset
                            notificationStackScrollView.get().updateDrawBounds(bounds)
                        }
                    },
            )
        }
    }
}

/** The possible states of the `ShadeBody`. */
private sealed interface ShadeBodyState {
    data object Editing : ShadeBodyState

    data object TileDetails : ShadeBodyState

    data object Default : ShadeBodyState
}

@Composable
@ReadOnlyComposable
private fun useBrightnessMirrorInOverlay(): Boolean {
    // The `config_useBrightnessMirrorInOverlay` config is true by default. If false, the Quick
    // Settings shade overlay will remain visible during brightness adjustments.
    return LocalResources.current.getBoolean(R.bool.config_useBrightnessMirrorInOverlay)
}

@Composable
private fun ContentScope.QuickSettingsContainer(
    contentViewModel: QuickSettingsShadeOverlayContentViewModel,
    containerViewModel: QuickSettingsContainerViewModel,
    modifier: Modifier = Modifier,
) {
    val isEditing by containerViewModel.editModeViewModel.isEditing.collectAsStateWithLifecycle()
    val tileDetails =
        if (QsDetailedView.isEnabled) containerViewModel.detailsViewModel.activeTileDetails
        else null

    val focusRequester = remember { FocusRequester() }

    LaunchedEffectWithLifecycle(focusRequester) {
        // Request focus on the `QuickSettingsContainer` without user interaction so that the user
        // can press the tab key once to enter the Quick Settings area. Without this line, the user
        // has to tab through unrelated views of the higher view hierarchy level.
        focusRequester.requestFocus()
    }

    AnimatedContent(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .sysuiResTag("quick_settings_container"),
        targetState =
            when {
                isEditing -> ShadeBodyState.Editing
                tileDetails != null -> ShadeBodyState.TileDetails
                else -> ShadeBodyState.Default
            },
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
    ) { state ->
        when (state) {
            ShadeBodyState.Editing -> {
                EditMode(
                    viewModel = containerViewModel.editModeViewModel,
                    modifier =
                        modifier.fillMaxWidth().padding(QuickSettingsShade.Dimensions.Padding),
                )
            }

            ShadeBodyState.TileDetails -> {
                TileDetails(modifier = modifier, containerViewModel.detailsViewModel)
            }

            ShadeBodyState.Default -> {
                QuickSettingsLayout(
                    qsContainerViewModel = containerViewModel,
                    toolbarViewModelFactory = contentViewModel.toolbarViewModelFactory,
                    isTransparencyEnabled = contentViewModel.isTransparencyEnabled,
                    volumeSliderViewModel = contentViewModel.volumeSliderViewModel,
                    audioDetailsViewModelFactory = contentViewModel.audioDetailsViewModelFactory,
                    modifier = modifier.sysuiResTag("quick_settings_panel"),
                )
            }
        }
    }
}

/** Column containing Brightness and QS tiles. */
@Composable
private fun ContentScope.QuickSettingsLayout(
    qsContainerViewModel: QuickSettingsContainerViewModel,
    toolbarViewModelFactory: ToolbarViewModel.Factory,
    isTransparencyEnabled: Boolean,
    volumeSliderViewModel: AudioStreamSliderViewModel?,
    audioDetailsViewModelFactory: AudioDetailsViewModel.Factory,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier.padding(
                start = QuickSettingsShade.Dimensions.Padding,
                end = QuickSettingsShade.Dimensions.Padding,
            ),
    ) {
        if (isFullWidthShade()) {
            VerticalSeparator(QuickSettingsShade.Dimensions.ShortPadding)
            QuickSettingsOverlayHeader(
                viewModel = qsContainerViewModel.shadeHeaderViewModel,
                modifier = Modifier.element(QuickSettingsShade.Elements.Header),
            )

            VerticalSeparator(QuickSettingsShade.Dimensions.ShortPadding)
        } else {
            VerticalSeparator(QuickSettingsShade.Dimensions.Padding)
        }

        val toolbarViewModel =
            rememberViewModel("QuickSettingsLayout") { toolbarViewModelFactory.create() }

        Toolbar(
            modifier =
                Modifier.fillMaxWidth().requiredHeight(QuickSettingsShade.Dimensions.ToolbarHeight),
            viewModel = toolbarViewModel,
            isFullyVisible = { layoutState.isIdle(contentKey) },
        )

        VerticalSeparator(QuickSettingsShade.Dimensions.ShortPadding)

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Media(
                viewModelFactory = qsContainerViewModel.mediaViewModelFactory,
                presentationStyle = MediaPresentationStyle.Compact,
                behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                onDismissed = qsContainerViewModel::onMediaSwipeToDismiss,
                modifier = Modifier,
            )

            if (qsContainerViewModel.showMedia) {
                VerticalSeparator(QuickSettingsShade.Dimensions.Padding)
            }

            Box(
                Modifier.systemGestureExclusionInShade(
                    enabled = { layoutState.transitionState is TransitionState.Idle }
                )
            ) {
                BrightnessSliderContainer(
                    viewModel = qsContainerViewModel.brightnessSliderViewModel,
                    containerColors =
                        ContainerColors(
                            idleColor = Color.Transparent,
                            mirrorColor = OverlayShade.Colors.panelBackground(isTransparencyEnabled),
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (volumeSliderViewModel != null) {
                val volumeSliderState by volumeSliderViewModel.slider.collectAsStateWithLifecycle()

                VerticalSeparator(QuickSettingsShade.Dimensions.Padding)
                Box(
                    Modifier.systemGestureExclusionInShade(
                        enabled = { layoutState.transitionState is TransitionState.Idle }
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VolumeSlider(
                            modifier = Modifier.weight(1f),
                            showLabel = false,
                            state = volumeSliderState,
                            onValueChange = { newValue: Float ->
                                volumeSliderViewModel.onValueChanged(volumeSliderState, newValue)
                            },
                            onValueChangeFinished = {
                                volumeSliderViewModel.onValueChangeFinished()
                            },
                            onIconTapped = { volumeSliderViewModel.toggleMuted(volumeSliderState) },
                            sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
                            hapticsViewModelFactory =
                                volumeSliderViewModel.getSliderHapticsViewModelFactory(),
                        )
                        IconButton(
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            onClick = {
                                qsContainerViewModel.detailsViewModel.onVolumeSettingsButtonClicked(
                                    audioDetailsViewModelFactory.create()
                                )
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.horizontal_ellipsis),
                                // TODO(b/378513663): Update the placeholder content description
                                contentDescription = "Volume settings",
                            )
                        }
                    }
                }
            }

            VerticalSeparator(QuickSettingsShade.Dimensions.Padding)

            GridAnchor()
            TileGrid(
                viewModel = qsContainerViewModel.tileGridViewModel,
                modifier = Modifier.fillMaxWidth(),
                enableRevealEffect = TileRevealFlag.isEnabled,
            )

            VerticalSeparator(QuickSettingsShade.Dimensions.Padding)
        }
    }
}

@Composable
private fun VerticalSeparator(height: Dp) {
    Spacer(Modifier.height(height = height))
}

object QuickSettingsShade {
    object Elements {
        val StatusBar = ElementKey("QuickSettingsShadeOverlayStatusBar")
        val Panel = ElementKey("QuickSettingsShadeOverlayPanel")
        val Header = ElementKey("QuickSettingsShadeOverlayHeader")
    }

    object Dimensions {
        // This is used around the header and toolbar
        val ShortPadding = 8.dp
        val Padding = 16.dp
        val ToolbarHeight = 48.dp
    }

    /**
     * Applies system gesture exclusion to a component adding [Dimensions.Padding] to left and
     * right.
     */
    @Composable
    fun Modifier.systemGestureExclusionInShade(enabled: () -> Boolean): Modifier {
        val density = LocalDensity.current
        return thenIf(enabled()) {
            Modifier.systemGestureExclusion { layoutCoordinates ->
                val sidePadding = with(density) { Dimensions.Padding.toPx() }
                Rect(
                    offset = Offset(x = -sidePadding, y = 0f),
                    size =
                        Size(
                            width = layoutCoordinates.size.width.toFloat() + 2 * sidePadding,
                            height = layoutCoordinates.size.height.toFloat(),
                        ),
                )
            }
        }
    }
}
