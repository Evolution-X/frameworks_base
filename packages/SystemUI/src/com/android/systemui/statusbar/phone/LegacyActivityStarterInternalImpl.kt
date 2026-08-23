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

package com.android.systemui.statusbar.phone

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.IActivityTaskManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.RemoteAnimationAdapter
import android.view.View
import android.view.WindowManager
import android.window.RemoteTransition
import com.android.app.displaylib.PerDisplayRepository
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DelegateTransitionAnimatorController
import com.android.systemui.assist.AssistManager
import com.android.systemui.camera.CameraIntents
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.desktop.DesktopFirstRepository
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.model.SysUiState
import com.android.systemui.plugins.ActivityStartOptions
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.kotlin.getOrNull
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Encapsulates the activity logic for activity starter. */
@SysUISingleton
class LegacyActivityStarterInternalImpl
@Inject
constructor(
    private val centralSurfacesOptLazy: Lazy<Optional<CentralSurfaces>>,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val assistManagerLazy: Lazy<AssistManager>,
    private val dozeServiceHostLazy: Lazy<DozeServiceHost>,
    private val biometricUnlockControllerLazy: Lazy<BiometricUnlockController>,
    private val keyguardViewMediatorLazy: Lazy<KeyguardViewMediator>,
    private val shadeControllerLazy: Lazy<ShadeController>,
    private val commandQueue: CommandQueue,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val statusBarKeyguardViewManagerLazy: Lazy<StatusBarKeyguardViewManager>,
    private val notifShadeWindowControllerLazy: Lazy<NotificationShadeWindowController>,
    private val activityTransitionAnimator: ActivityTransitionAnimator,
    private val contextInteractor: ShadeDialogContextInteractor,
    private val lockScreenUserManager: NotificationLockscreenUserManager,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val userTracker: UserTracker,
    private val activityIntentHelper: ActivityIntentHelper,
    private val activityTaskManager: IActivityTaskManager,
    @Main private val mainExecutor: DelayableExecutor,
    @Application private val applicationScope: CoroutineScope,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val perDisplaySysUiStateRepository: PerDisplayRepository<SysUiState>,
    private val desktopFirstRepository: DesktopFirstRepository,
) : ActivityStarterInternal {
    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    private val currentShadeContext: Context
        get() = contextInteractor.context

    private val currentShadeDisplayId: Int
        get() = currentShadeContext.displayId

    private val shadeSysUiState: Long
        get() {
            val sysUiState = perDisplaySysUiStateRepository[currentShadeDisplayId]
            if (sysUiState == null) {
                Log.w(TAG, "SysUiState is null for display $currentShadeDisplayId")
                return 0L
            }
            return sysUiState.flags
        }

    private val isInDesktopModeOnCurrentShadeDisplay: Boolean
        get() = (shadeSysUiState and SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE) != 0L

    override fun registerTransition(
        cookie: ActivityTransitionAnimator.TransitionCookie,
        controllerFactory: ActivityTransitionAnimator.ControllerFactory,
        scope: CoroutineScope,
    ) {
        val factory =
            object :
                ActivityTransitionAnimator.ControllerFactory(
                    controllerFactory.cookie,
                    controllerFactory.component,
                    controllerFactory.launchCujType,
                    controllerFactory.returnCujType,
                ) {
                override suspend fun createController(
                    forLaunch: Boolean
                ): ActivityTransitionAnimator.Controller {
                    val baseController = controllerFactory.createController(forLaunch)
                    val rootView = baseController.transitionContainer.rootView
                    val statusBarWindowController =
                        statusBarWindowControllerStore.forDisplay(rootView.context.displayId)
                    val controllerFromStatusBar: Optional<ActivityTransitionAnimator.Controller>? =
                        statusBarWindowController?.wrapAnimationControllerIfInStatusBar(
                            rootView,
                            baseController,
                        )
                    return if (controllerFromStatusBar?.isPresent == true) {
                        controllerFromStatusBar.get()
                    } else {
                        baseController
                    }
                }
            }

        activityTransitionAnimator.registerLongLivedTransitions(cookie, factory, scope)
    }

    override fun unregisterTransition(cookie: ActivityTransitionAnimator.TransitionCookie) {
        activityTransitionAnimator.unregisterLongLivedTransitions(cookie)
    }

    @Deprecated("Use startActivityDismissingKeyguard(options: ActivityStartOptions) instead")
    override fun startActivityDismissingKeyguard(
        intent: Intent,
        dismissShade: Boolean,
        onlyProvisioned: Boolean,
        callback: ActivityStarter.Callback?,
        flags: Int,
        animationController: ActivityTransitionAnimator.Controller?,
        customMessage: String?,
        disallowEnterPictureInPictureWhileLaunching: Boolean,
        userHandle: UserHandle?,
        activityOptions: ActivityOptions?,
    ) {
        startActivityDismissingKeyguard(
            ActivityStartOptions(
                intent = intent,
                dismissShade = dismissShade,
                onlyProvisioned = onlyProvisioned,
                callback = callback,
                flags = flags,
                animationController = animationController,
                customMessage = customMessage,
                disallowPipWhileLaunching = disallowEnterPictureInPictureWhileLaunching,
                userHandle = userHandle,
                activityOptions = activityOptions,
            )
        )
    }

    override fun startActivityDismissingKeyguard(options: ActivityStartOptions) {
        val intent = options.intent
        val userHandle: UserHandle = options.userHandle ?: getActivityUserHandle(intent)

        if (options.onlyProvisioned && !deviceProvisionedController.isDeviceProvisioned) return

        val willLaunchResolverActivity: Boolean =
            activityIntentHelper.wouldLaunchResolverActivity(
                intent,
                lockScreenUserManager.currentUserId,
            )

        val animate =
            options.animationController != null &&
                !willLaunchResolverActivity &&
                shouldAnimateLaunch(isActivityIntent = true)
        val animController =
            wrapAnimationControllerForShadeOrStatusBar(
                animationController = options.animationController,
                dismissShade = options.dismissShade,
                isLaunchForActivity = true,
            )

        // If we animate, we will dismiss the shade only once the animation is done. This is
        // taken care of by the StatusBarLaunchAnimationController.
        val dismissShadeDirectly = options.dismissShade && animController == null

        val runnable = Runnable {
            assistManagerLazy.get().hideAssist()
            intent.flags =
                if (intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0) {
                    Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            intent.addFlags(options.flags)
            val result = intArrayOf(ActivityManager.START_CANCELED)

            val startIntent = { optionsBundle: Bundle ->
                val activityOptions = ActivityOptions(optionsBundle)
                if (options.activityOptions != null) {
                    activityOptions.update(options.activityOptions)
                }

                // We know that the intent of the caller is to dismiss the keyguard and
                // this runnable is called right after the keyguard is solved, so we tell
                // WM that we should dismiss it to avoid flickers when opening an activity
                // that can also be shown over the keyguard.
                activityOptions.setDismissKeyguardIfInsecure()
                activityOptions.setDisallowEnterPictureInPictureWhileLaunching(
                    options.disallowPipWhileLaunching
                )
                if (CameraIntents.isInsecureCameraIntent(intent)) {
                    // Normally an activity will set it's requested rotation
                    // animation on its window. However when launching an activity
                    // causes the orientation to change this is too late. In these cases
                    // the default animation is used. This doesn't look good for
                    // the camera (as it rotates the camera contents out of sync
                    // with physical reality). So, we ask the WindowManager to
                    // force the cross fade animation if an orientation change
                    // happens to occur during the launch.
                    activityOptions.rotationAnimationHint =
                        WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
                }
                if (Settings.Panel.ACTION_VOLUME == intent.action) {
                    // Settings Panel is implemented as activity(not a dialog), so
                    // underlying app is paused and may enter picture-in-picture mode
                    // as a result.
                    // So we need to disable picture-in-picture mode here
                    // if it is volume panel.
                    activityOptions.setDisallowEnterPictureInPictureWhileLaunching(true)
                }
                intent.collectExtraIntentKeys()
                try {
                    result[0] =
                        activityTaskManager.startActivityAsUser(
                            null,
                            currentShadeContext.basePackageName,
                            currentShadeContext.attributionTag,
                            intent,
                            intent.resolveTypeIfNeeded(currentShadeContext.contentResolver),
                            null,
                            null,
                            0,
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                            null,
                            activityOptions.toBundle(),
                            userHandle.identifier,
                        )
                } catch (e: RemoteException) {
                    Log.w(TAG, "Unable to start activity", e)
                }
                result[0]
            }

            if (ActivityTransitionAnimator.shellMigrationEnabled()) {
                val controllerWithCookie = addCookieIfNeeded(animController)
                activityTransitionAnimator.startIntentWithAnimation(
                    controllerWithCookie,
                    applicationScope,
                    animate = animate,
                ) { transition: RemoteTransition? ->
                    startIntent(
                        createActivityOptions(
                            currentShadeDisplayId,
                            transition,
                            controllerWithCookie?.transitionCookie,
                        )
                    )
                }
            } else {
                activityTransitionAnimator.startIntentWithAnimation(
                    animController,
                    animate,
                    intent.getPackage(),
                ) { adapter: RemoteAnimationAdapter? ->
                    startIntent(createActivityOptions(currentShadeDisplayId, adapter))
                }
            }

            options.callback?.onActivityStarted(result[0])
        }
        val cancelRunnable = Runnable {
            options.callback?.onActivityStarted(ActivityManager.START_CANCELED)
        }
        // Do not deferKeyguard when occluded because, when keyguard is occluded, we do not launch
        // the activity until keyguard is done. The only exception is when we're on the Hub and want
        // to dismiss the shade immediately, which means that another animation will take care of
        // the transition.
        val occluded = (keyguardStateController.isShowing && keyguardStateController.isOccluded)
        val dismissOnCommunal =
            communalSettingsInteractor.isCommunalFlagEnabled() &&
                communalSceneInteractor.isCommunalVisible.value &&
                dismissShadeDirectly
        val deferred = !occluded || dismissOnCommunal
        executeRunnableDismissingKeyguard(
            runnable,
            cancelRunnable,
            dismissShadeDirectly,
            willLaunchResolverActivity,
            deferred,
            animate,
            options.customMessage,
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable?,
        associatedView: View?,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreen: Boolean,
        skipLockscreenChecks: Boolean,
        fillInIntent: Intent?,
        extraOptions: Bundle?,
        customMessage: String?,
    ) {
        val animationController =
            if (associatedView is ExpandableNotificationRow) {
                centralSurfaces?.getAnimatorControllerFromNotification(associatedView)
            } else animationController

        val willLaunchResolverActivity =
            (intent.isActivity &&
                activityIntentHelper.wouldPendingLaunchResolverActivity(
                    intent,
                    lockScreenUserManager.currentUserId,
                ))

        val actuallyShowOverLockscreen =
            showOverLockscreen &&
                intent.isActivity &&
                (skipLockscreenChecks ||
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        intent,
                        lockScreenUserManager.currentUserId,
                    ))

        val animate =
            !willLaunchResolverActivity &&
                animationController != null &&
                shouldAnimateLaunch(intent.isActivity, actuallyShowOverLockscreen)

        // We wrap animationCallback with a StatusBarLaunchAnimatorController so
        // that the shade is collapsed after the animation (or when it is cancelled,
        // aborted, etc).
        val statusBarController =
            wrapAnimationControllerForShadeOrStatusBar(
                animationController = animationController,
                dismissShade = dismissShade,
                isLaunchForActivity = intent.isActivity,
            )
        val controller =
            if (actuallyShowOverLockscreen) {
                wrapAnimationControllerForLockscreen(dismissShade, statusBarController)
            } else {
                statusBarController
            }

        val isCommunalDismissLaunch = isCommunalWidgetLaunch() && !actuallyShowOverLockscreen
        // If we animate, don't collapse the shade and defer the keyguard dismiss (in case we
        // run the animation on the keyguard). The animation will take care of (instantly)
        // collapsing the shade and hiding the keyguard once it is done.
        val collapse = (dismissShade || isCommunalDismissLaunch) && !animate
        val runnable = Runnable {
            val startIntent = { optionsBundle: Bundle ->
                extraOptions?.let { optionsBundle.putAll(it) }
                val options = ActivityOptions(optionsBundle)
                // TODO b/221255671: restrict this to only be set for notifications
                options.isEligibleForLegacyPermissionPrompt = true
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )

                intent.sendAndReturnResult(
                    currentShadeContext,
                    0,
                    fillInIntent,
                    null,
                    null,
                    null,
                    options.toBundle(),
                )
            }

            try {
                if (ActivityTransitionAnimator.shellMigrationEnabled()) {
                    val controllerWithCookie = addCookieIfNeeded(controller)
                    activityTransitionAnimator.startPendingIntentWithAnimation(
                        controller,
                        applicationScope,
                        animate = animate,
                        showOverLockscreen = actuallyShowOverLockscreen,
                        intentStarter =
                            object : ActivityTransitionAnimator.PendingIntentStarter {
                                override fun startPendingIntent(
                                    transition: RemoteTransition?
                                ): Int {
                                    return startIntent(
                                        createActivityOptions(
                                            currentShadeDisplayId,
                                            transition,
                                            controllerWithCookie?.transitionCookie,
                                        )
                                    )
                                }
                            },
                    )
                } else {
                    activityTransitionAnimator.startPendingIntentWithAnimation(
                        controller,
                        animate,
                        intent.creatorPackage,
                        actuallyShowOverLockscreen,
                        object : ActivityTransitionAnimator.LegacyPendingIntentStarter {
                            override fun startPendingIntent(
                                animationAdapter: RemoteAnimationAdapter?
                            ): Int {
                                return startIntent(
                                    createActivityOptions(currentShadeDisplayId, animationAdapter)
                                )
                            }
                        },
                    )
                }
            } catch (e: PendingIntent.CanceledException) {
                // the stack trace isn't very helpful here.
                // Just log the exception message.
                Log.w(TAG, "Sending intent failed: $e")
                if (!collapse) {
                    // executeRunnableDismissingKeyguard did not collapse for us already.
                    shadeControllerLazy.get().collapseOnMainThread()
                }
                // TODO: Dismiss Keyguard.
            }
            if (intent.isActivity) {
                assistManagerLazy.get().hideAssist()
            }
            intentSentUiThreadCallback?.let { postOnUiThread(runnable = it) }
        }

        if (!actuallyShowOverLockscreen) {
            postOnUiThread(delay = 0) {
                executeRunnableDismissingKeyguard(
                    runnable = runnable,
                    dismissShade = collapse,
                    afterKeyguardGone = willLaunchResolverActivity,
                    deferred = isCommunalDismissLaunch,
                    willAnimateOnKeyguard = animate,
                    customMessage = customMessage,
                )
            }
        } else {
            postOnUiThread(delay = 0, runnable)
        }
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle?,
    ) {
        val userHandle = userHandle ?: getActivityUserHandle(intent)
        // Make sure that we dismiss the keyguard if it is directly dismissible or when we don't
        // want to show the activity above it.
        if (keyguardStateController.isUnlocked || !showOverLockscreenWhenLocked) {
            startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = false,
                dismissShade = dismissShade,
                disallowEnterPictureInPictureWhileLaunching = false,
                callback = null,
                flags = 0,
                animationController = animationController,
                userHandle = userHandle,
            )
            return
        }

        val animate =
            animationController != null &&
                shouldAnimateLaunch(/* isActivityIntent= */ true, showOverLockscreenWhenLocked)

        var controller: ActivityTransitionAnimator.Controller? = null
        if (animate) {
            // Wrap the animation controller to dismiss the shade and set
            // mIsLaunchingActivityOverLockscreen during the animation.
            val delegate =
                wrapAnimationControllerForShadeOrStatusBar(
                    animationController = animationController,
                    dismissShade = dismissShade,
                    isLaunchForActivity = true,
                )
            controller = wrapAnimationControllerForLockscreen(dismissShade, delegate)
        } else if (dismissShade) {
            // The animation will take care of dismissing the shade at the end of the animation.
            // If we don't animate, collapse it directly.
            shadeControllerLazy.get().cancelExpansionAndCollapseShade()
        }

        // We should exit the dream to prevent the activity from starting below the
        // dream.
        if (keyguardUpdateMonitor.isDreaming) {
            centralSurfaces?.awakenDreams()
        }

        if (ActivityTransitionAnimator.shellMigrationEnabled()) {
            val controllerWithCookie = addCookieIfNeeded(controller)
            activityTransitionAnimator.startIntentWithAnimation(
                controllerWithCookie,
                applicationScope,
                animate = animate,
                showOverLockscreen = showOverLockscreenWhenLocked,
            ) { transition: RemoteTransition? ->
                TaskStackBuilder.create(currentShadeContext)
                    .addNextIntent(intent)
                    .startActivities(
                        createActivityOptions(
                            currentShadeDisplayId,
                            transition,
                            controllerWithCookie?.transitionCookie,
                        ),
                        userHandle,
                    )
            }
        } else {
            activityTransitionAnimator.startIntentWithAnimation(
                controller,
                animate,
                intent.getPackage(),
                showOverLockscreenWhenLocked,
            ) { adapter: RemoteAnimationAdapter? ->
                TaskStackBuilder.create(currentShadeContext)
                    .addNextIntent(intent)
                    .startActivities(
                        createActivityOptions(currentShadeDisplayId, adapter),
                        userHandle,
                    )
            }
        }
    }

    override fun dismissKeyguardThenExecute(
        action: ActivityStarter.OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String?,
    ) {
        Log.i(TAG, "Invoking dismissKeyguardThenExecute, afterKeyguardGone: $afterKeyguardGone")
        if (
            !action.willRunAnimationOnKeyguard() &&
                wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_ASLEEP &&
                keyguardStateController.canDismissLockScreen() &&
                !statusBarStateController.leaveOpenOnKeyguardHide() &&
                dozeServiceHostLazy.get().isPulsing
        ) {
            // Reuse the biometric wake-and-unlock transition if we dismiss keyguard from a
            // pulse.
            // TODO (b/338578036): Factor this transition out of BiometricUnlockController.
            biometricUnlockControllerLazy
                .get()
                .startWakeAndUnlock(BiometricUnlockController.MODE_WAKE_AND_DISMISS_PULSING, null)
        }
        if (keyguardStateController.isShowing) {
            statusBarKeyguardViewManagerLazy
                .get()
                .dismissWithAction(action, cancel, afterKeyguardGone, customMessage)
        } else {
            // If the keyguard isn't showing but the device is dreaming, we should exit the
            // dream.
            if (keyguardUpdateMonitor.isDreaming) {
                centralSurfaces?.awakenDreams()
            }
            action.onDismiss()
        }
    }

    override fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable?,
        dismissShade: Boolean,
        afterKeyguardGone: Boolean,
        deferred: Boolean,
        willAnimateOnKeyguard: Boolean,
        customMessage: String?,
    ) {
        val onDismissAction: ActivityStarter.OnDismissAction =
            object : ActivityStarter.OnDismissAction {
                override fun onDismiss(): Boolean {
                    if (runnable != null) {
                        // We don't wait for Keyguard to be gone if we're dismissing the shade
                        // immediately and we're on the Communal Hub. This is to make sure that the
                        // Hub -> Edit Mode transition is seamless.
                        val dismissOnCommunal =
                            communalSettingsInteractor.isCommunalFlagEnabled() &&
                                communalSceneInteractor.isCommunalVisible.value &&
                                dismissShade
                        if (
                            keyguardStateController.isShowing &&
                                keyguardStateController.isOccluded &&
                                !isCommunalWidgetLaunch() &&
                                !dismissOnCommunal
                        ) {
                            statusBarKeyguardViewManagerLazy
                                .get()
                                .addAfterKeyguardGoneRunnable(runnable)
                        } else {
                            mainExecutor.execute(runnable)
                        }
                    }
                    if (dismissShade) {
                        shadeControllerLazy.get().collapseShadeForActivityStart()
                    }
                    return deferred
                }

                override fun willRunAnimationOnKeyguard(): Boolean {
                    return willAnimateOnKeyguard
                }
            }
        dismissKeyguardThenExecute(onDismissAction, cancelAction, afterKeyguardGone, customMessage)
    }

    /**
     * Return a [ActivityTransitionAnimator.Controller] wrapping `animationController` so that:
     * - if it launches in the notification shade window and `dismissShade` is true, then the shade
     *   will be instantly dismissed at the end of the animation.
     * - if it launches in status bar window, it will make the status bar window match the device
     *   size during the animation (that way, the animation won't be clipped by the status bar
     *   size).
     *
     * @param animationController the controller that is wrapped and will drive the main animation.
     * @param dismissShade whether the notification shade will be dismissed at the end of the
     *   animation. This is ignored if `animationController` is not animating in the shade window.
     * @param isLaunchForActivity whether the launch is for an activity.
     */
    private fun wrapAnimationControllerForShadeOrStatusBar(
        animationController: ActivityTransitionAnimator.Controller?,
        dismissShade: Boolean,
        isLaunchForActivity: Boolean,
    ): ActivityTransitionAnimator.Controller? {
        if (animationController == null) {
            return null
        }
        val rootView = animationController.transitionContainer.rootView
        val statusBarWindowController =
            statusBarWindowControllerStore.forDisplay(rootView.context.displayId)
        val controllerFromStatusBar: Optional<ActivityTransitionAnimator.Controller>? =
            statusBarWindowController?.wrapAnimationControllerIfInStatusBar(
                rootView,
                animationController,
            )
        if (controllerFromStatusBar?.isPresent == true) {
            return controllerFromStatusBar.get()
        }

        centralSurfaces?.let {
            // If the view is not in the status bar, then we are animating a view in the shade.
            // We have to make sure that we collapse it when the animation ends or is cancelled.
            if (dismissShade) {
                return StatusBarTransitionAnimatorController(
                    animationController,
                    shadeAnimationInteractor,
                    shadeControllerLazy.get(),
                    notifShadeWindowControllerLazy.get(),
                    commandQueue,
                    currentShadeDisplayId,
                    isLaunchForActivity,
                )
            }
        }

        return animationController
    }

    /**
     * Wraps an animation controller so that if an activity would be launched on top of the
     * lockscreen, the correct flags are set for it to be occluded.
     */
    private fun wrapAnimationControllerForLockscreen(
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
    ): ActivityTransitionAnimator.Controller? {
        return animationController?.let {
            object : DelegateTransitionAnimatorController(it) {
                override fun onIntentStarted(willAnimate: Boolean) {
                    delegate.onIntentStarted(willAnimate)
                    if (willAnimate) {
                        centralSurfaces?.setIsLaunchingActivityOverLockscreen(true, dismissShade)
                    }
                }

                override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
                    super.onTransitionAnimationStart(isExpandingFullyAbove)
                    // Double check that the keyguard is still showing and not going
                    // away, but if so set the keyguard occluded. Typically, WM will let
                    // KeyguardViewMediator know directly, but we're overriding that to
                    // play the custom launch animation, so we need to take care of that
                    // here. The unocclude animation is not overridden, so WM will call
                    // KeyguardViewMediator's unocclude animation runner when the
                    // activity is exited.
                    if (
                        keyguardStateController.isShowing &&
                            !keyguardStateController.isKeyguardGoingAway
                    ) {
                        Log.d(TAG, "Setting occluded = true in #startActivity.")
                        keyguardViewMediatorLazy.get().setOccluded(true /* isOccluded */)
                    }
                }

                override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually
                    // finishing the animation so that we can assume that
                    // mIsLaunchingActivityOverLockscreen being true means that we will
                    // collapse the shade (or at least run the post collapse runnables)
                    // later on.
                    centralSurfaces?.setIsLaunchingActivityOverLockscreen(false, false)
                    delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                }

                override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
                    if (newKeyguardOccludedState != null) {
                        keyguardViewMediatorLazy.get().setOccluded(newKeyguardOccludedState)
                    }

                    // Set mIsLaunchingActivityOverLockscreen to false before actually
                    // finishing the animation so that we can assume that
                    // mIsLaunchingActivityOverLockscreen being true means that we will
                    // collapse the shade (or at least run the // post collapse
                    // runnables) later on.
                    centralSurfaces?.setIsLaunchingActivityOverLockscreen(false, false)
                    delegate.onTransitionAnimationCancelled(newKeyguardOccludedState)
                }
            }
        }
    }

    /** Retrieves the current user handle to start the Activity. */
    private fun getActivityUserHandle(intent: Intent?): UserHandle {
        if (intent == null) {
            Log.w(TAG, "getActivityUserHandle called with a null intent")
        }
        return if (intent != null &&
            currentShadeContext.packageName == intent.component?.packageName) {
            UserHandle(UserHandle.myUserId())
        } else {
            userTracker.userHandle
        }
    }

    /**
     * Whether we should animate an activity launch.
     *
     * Note: This method must be called *before* dismissing the keyguard.
     */
    private fun shouldAnimateLaunch(
        isActivityIntent: Boolean,
        showOverLockscreen: Boolean,
    ): Boolean {
        // TODO(b/294418322): always support launch animations when occluded.
        val ignoreOcclusion = showOverLockscreen || isCommunalWidgetLaunch()
        val isDesktopMode = isInDesktopModeOnCurrentShadeDisplay
        val isDesktopFirst = desktopFirstRepository.isDisplayDesktopFirst(currentShadeDisplayId)
        val keyguardShowing = keyguardStateController.isShowing

        Log.i(
            TAG,
            "shouldAnimateLaunch: " +
                "isActivityIntent=$isActivityIntent, " +
                "showOverLockscreen=$showOverLockscreen, " +
                "ignoreOcclusion=$ignoreOcclusion, " +
                "isInDesktopModeOnCurrentShadeDisplay=$isDesktopMode, " +
                "isDisplayDesktopFirst=$isDesktopFirst, " +
                "isKeyguardShowing=$keyguardShowing",
        )
        if (keyguardStateController.isOccluded && !ignoreOcclusion) {
            return false
        }

        if (
            isInDesktopModeOnCurrentShadeDisplay ||
                desktopFirstRepository.isDisplayDesktopFirst(currentShadeDisplayId)
        ) {
            return false
        }

        // Always animate if we are not showing the keyguard or if we animate over the lockscreen
        // (without unlocking it).
        if (showOverLockscreen || !keyguardShowing) {
            return true
        }

        // We don't animate non-activity launches as they can break the animation.
        // TODO(b/184121838): Support non activity launches on the lockscreen.
        return isActivityIntent
    }

    override fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean {
        return shouldAnimateLaunch(isActivityIntent, false)
    }

    private fun isCommunalWidgetLaunch(): Boolean {
        return communalSettingsInteractor.isCommunalFlagEnabled() &&
            communalSceneInteractor.isCommunalVisible.value &&
            communalSceneInteractor.isLaunchingWidget.value
    }

    private fun postOnUiThread(delay: Int = 0, runnable: Runnable) {
        mainExecutor.executeDelayed(runnable, delay.toLong())
    }

    companion object {
        private const val TAG = "LegacyActivityStarterInternalImpl"
    }
}
