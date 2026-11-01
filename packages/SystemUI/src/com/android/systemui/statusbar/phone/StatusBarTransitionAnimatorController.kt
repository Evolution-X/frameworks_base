package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.animation.TransitionAnimator.Companion.getProgress
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl.Companion.FADE_IN_DELAY
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl.Companion.FADE_IN_DURATION

/**
 * A [ActivityTransitionAnimator.Controller] that takes care of collapsing the status bar at the
 * right time.
 */
class StatusBarTransitionAnimatorController(
    private val delegate: ActivityTransitionAnimator.Controller,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val shadeController: ShadeController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val commandQueue: CommandQueue,
    @DisplayId private val displayId: Int,
    private val isLaunchForActivity: Boolean = true,
) : ActivityTransitionAnimator.Controller by delegate {
    // After cancellation, this controller won't receive any more animation-related calls. However,
    // [onIntentStarted()] will still be called if the cancellation happens before it. The behavior
    // needs to change accordingly.
    private var cancelled = false
    private var hideIconsDuringLaunchAnimation: Boolean = true

    // Always sync the opening window with the shade, given that we draw a hole punch in the shade
    // of the same size and position as the opening app to make it visible.
    override val openingWindowSyncView: View?
        get() = notificationShadeWindowController.windowRootView

    override fun onIntentStarted(willAnimate: Boolean) {
        delegate.onIntentStarted(willAnimate)
        if (willAnimate && !cancelled) {
            shadeAnimationInteractor.setIsLaunchingActivity(
                true,
                "StatusBarTransitionAnimatorController.onIntentStarted",
            )
        } else {
            shadeController.collapseOnMainThread()
        }
    }

    override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
        // We set this before calling the delegate to make sure that accessibility is disabled
        // for the whole duration of the transition, so that we don't have stray TalkBack events
        // once the animating view becomes invisible.
        shadeAnimationInteractor.setIsLaunchingActivity(
            true,
            "StatusBarTransitionAnimatorController.onTransitionAnimationStart",
        )
        delegate.onTransitionAnimationStart(isExpandingFullyAbove)

        if (!isExpandingFullyAbove) {
            shadeController.collapseWithDuration(
                ActivityTransitionAnimator.TIMINGS.totalDuration.toInt()
            )
        }
    }

    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
        shadeAnimationInteractor.setIsLaunchingActivity(
            false,
            "StatusBarTransitionAnimatorController.onTransitionAnimationEnd",
        )
        shadeController.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onTransitionAnimationProgress(
        state: TransitionAnimator.State,
        progress: Float,
        linearProgress: Float,
    ) {
        delegate.onTransitionAnimationProgress(state, progress, linearProgress)
        shadeAnimationInteractor.setLaunchProgress(linearProgress)
        val hideIcons =
            getProgress(
                ActivityTransitionAnimator.TIMINGS,
                linearProgress,
                ANIMATION_DELAY_ICON_FADE_IN,
                100,
            ) == 0.0f
        if (hideIcons != hideIconsDuringLaunchAnimation) {
            hideIconsDuringLaunchAnimation = hideIcons
            if (!hideIcons) {
                commandQueue.recomputeDisableFlags(displayId, true /* animate */)
            }
        }
    }

    override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
        delegate.onTransitionAnimationCancelled()
        cancelled = true
        shadeAnimationInteractor.setIsLaunchingActivity(
            false,
            "StatusBarTransitionAnimatorController.onTransitionAnimationCancelled",
        )
        shadeController.onLaunchAnimationCancelled(isLaunchForActivity)
    }

    companion object {
        val ANIMATION_DELAY_ICON_FADE_IN =
            (ActivityTransitionAnimator.TIMINGS.totalDuration -
                FADE_IN_DURATION -
                FADE_IN_DELAY -
                48)
    }
}
