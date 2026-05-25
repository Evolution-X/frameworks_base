package com.android.systemui.statusbar.notification.interruption

import android.app.Notification.VISIBILITY_SECRET
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.provider.Settings
import android.security.Flags.secureLockDevice
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.ambient.statusbar.shared.flag.OngoingActivityChipsOnDream
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.asIndenting
import com.android.systemui.util.println
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.withIncreasedIndent
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.io.PrintWriter
import java.util.function.Consumer
import javax.inject.Inject

/** Determines if notifications should be visible based on the state of the keyguard. */
interface KeyguardNotificationVisibilityProvider {
    /**
     * Determines if the given notification should be hidden based on the current keyguard state. If
     * a [Consumer] registered via [addOnStateChangedListener] is invoked, the results of this
     * method may no longer be valid and should be re-queried.
     */
    fun shouldHideNotification(entry: NotificationEntry): Boolean

    /** Registers a listener to be notified when the internal keyguard state has been updated. */
    fun addOnStateChangedListener(listener: Consumer<String>)

    /** Unregisters a listener previously registered with [addOnStateChangedListener]. */
    fun removeOnStateChangedListener(listener: Consumer<String>)
}

/** Provides a [KeyguardNotificationVisibilityProvider] in [SysUISingleton] scope. */
@Module(includes = [KeyguardNotificationVisibilityProviderImplModule::class])
object KeyguardNotificationVisibilityProviderModule

@Module
interface KeyguardNotificationVisibilityProviderImplModule {
    @Binds
    fun bindImpl(
        impl: KeyguardNotificationVisibilityProviderImpl
    ): KeyguardNotificationVisibilityProvider

    @Binds
    @IntoMap
    @ClassKey(KeyguardNotificationVisibilityProvider::class)
    fun bindStartable(impl: KeyguardNotificationVisibilityProviderImpl): CoreStartable
}

@SysUISingleton
class KeyguardNotificationVisibilityProviderImpl
@Inject
constructor(
    @Main private val handler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val highPriorityProvider: HighPriorityProvider,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    private val secureLockDeviceInteractor: Lazy<SecureLockDeviceInteractor>,
) : CoreStartable, KeyguardNotificationVisibilityProvider {
    private val showSilentNotifsUri =
        secureSettings.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS)
    private val onStateChangedListeners = ListenerSet<Consumer<String>>()
    private var hideSilentNotificationsOnLockscreen: Boolean = false

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                readShowSilentNotificationSetting()
                if (isLockedOrLocking) {
                    // maybe public mode changed
                    notifyStateChanged("onUserSwitched")
                }
            }
        }

    // KeyguardUpdateMonitor stores callbacks as WeakRefs, so we need to keep a strong reference to
    // the callback in order to prevent it from being garbage collected.
    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onStrongAuthStateChanged(userId: Int) {
                notifyStateChanged("onStrongAuthStateChanged")
            }

            override fun onDreamingStateChanged(dreaming: Boolean) {
                if (OngoingActivityChipsOnDream.isEnabled && NotificationBundleUi.isEnabled) {
                    // Only notify if we're no longer dreaming, otherwise this will cause a
                    // flicker as the device transitions to dreaming
                    if (!dreaming) {
                        notifyStateChanged("onDreamingStateChanged(dreaming=false)")
                    }
                }
            }
        }

    override fun start() {
        readShowSilentNotificationSetting()
        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onUnlockedChanged() {
                    notifyStateChanged("onUnlockedChanged")
                }

                override fun onKeyguardShowingChanged() {
                    notifyStateChanged("onKeyguardShowingChanged")
                }
            }
        )

        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)

        // register lockscreen settings changed callbacks:
        val settingsObserver: ContentObserver =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri == showSilentNotifsUri) {
                        readShowSilentNotificationSetting()
                    }
                    if (isLockedOrLocking) {
                        notifyStateChanged("Settings $uri changed")
                    }
                }
            }

        secureSettings.registerContentObserverForUserSync(
            Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
            settingsObserver,
            UserHandle.USER_ALL,
        )

        secureSettings.registerContentObserverForUserSync(
            Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            true,
            settingsObserver,
            UserHandle.USER_ALL,
        )

        globalSettings.registerContentObserverSync(Settings.Global.ZEN_MODE, settingsObserver)

        secureSettings.registerContentObserverForUserSync(
            Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
            settingsObserver,
            UserHandle.USER_ALL,
        )

        // register (maybe) public mode changed callbacks:
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    notifyStateChanged("onStatusBarStateChanged")
                }

                override fun onUpcomingStateChanged(state: Int) {
                    notifyStateChanged("onStatusBarUpcomingStateChanged")
                }
            }
        )
        userTracker.addCallback(userTrackerCallback, HandlerExecutor(handler))
    }

    override fun addOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.addIfAbsent(listener)
    }

    override fun removeOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.remove(listener)
    }

    private fun notifyStateChanged(reason: String) {
        onStateChangedListeners.forEach { it.accept(reason) }
    }

    override fun shouldHideNotification(entry: NotificationEntry): VisState =
        when {
            // Show notifications if we're in a dream with overlay. The dream status bar needs
            // notifications to render ongoing call chip.
            OngoingActivityChipsOnDream.isEnabled && keyguardUpdateMonitor.isDreamingWithOverlay ->
                SHOW
            // Keyguard state doesn't matter if the keyguard is not showing.
            !isLockedOrLocking -> SHOW
            // Notifications not allowed on the lockscreen, always hide.
            !lockscreenUserManager.shouldShowLockscreenNotifications() -> HIDE
            // secure lock device mode is enabled always disallow
            secureLockDevice() &&
                secureLockDeviceInteractor.get().isSecureLockDeviceEnabled.value -> HIDE
            // User settings do not allow this notification on the lockscreen, so hide it.
            userSettingsDisallowNotification(entry) -> HIDE
            // Entry is explicitly marked SECRET, so hide it.
            entry.sbn.notification.visibility == VISIBILITY_SECRET -> HIDE
            // if entry is silent, apply custom logic to see if should hide
            shouldHideIfEntrySilent(entry) -> HIDE
            else -> SHOW
        }

    private fun shouldHideIfEntrySilent(entry: PipelineEntry): VisState =
        when {
            entry.section?.bucket?.let { it != BUCKET_SILENT } == true -> SHOW
            // Show if explicitly high priority (not hidden)
            highPriorityProvider.isExplicitlyHighPriority(entry) -> SHOW
            // Ambient notifications are hidden always from lock screen
            entry.asListEntry()?.representativeEntry?.isAmbient == true -> HIDE
            // [Now notification is silent]
            // Always hide if user wants silent notifs hidden
            hideSilentNotificationsOnLockscreen -> HIDE
            // Show when silent notifications are allowed on lockscreen
            else -> SHOW
        }

    private fun userSettingsDisallowNotification(entry: NotificationEntry): VisState {
        fun disallowForUser(user: Int): VisState =
            when {
                // user is in lockdown, always disallow
                keyguardUpdateMonitor.isUserInLockdown(user) -> HIDE
                // device isn't public, no need to check public-related settings, so allow
                !lockscreenUserManager.isLockscreenPublicMode(user) -> SHOW
                // entry is meant to be secret on the lockscreen, disallow
                isRankingVisibilitySecret(entry) -> HIDE
                // disallow if user disallows notifications in public
                lockscreenUserManager.userAllowsNotificationsInPublic(user) -> SHOW
                else -> HIDE
            }
        val currentUser = lockscreenUserManager.currentUserId
        val notifUser = entry.sbn.user.identifier
        return when {
            disallowForUser(currentUser) == HIDE -> HIDE
            notifUser == UserHandle.USER_ALL -> SHOW
            notifUser == currentUser -> SHOW
            else -> disallowForUser(notifUser)
        }
    }

    private fun isRankingVisibilitySecret(entry: NotificationEntry): Boolean {
        // ranking.lockscreenVisibilityOverride contains possibly out of date DPC and Setting
        // info, and NotificationLockscreenUserManagerImpl is already listening for updates
        // to those
        return entry.ranking.channel?.lockscreenVisibility == VISIBILITY_SECRET
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        pw.asIndenting().run {
            println("isLockedOrLocking", isLockedOrLocking)
            withIncreasedIndent {
                println("keyguardStateController.isShowing", keyguardStateController.isShowing)
                println(
                    "statusBarStateController.currentOrUpcomingState",
                    statusBarStateController.currentOrUpcomingState,
                )
            }
            println("hideSilentNotificationsOnLockscreen", hideSilentNotificationsOnLockscreen)
        }

    private val isLockedOrLocking
        get() =
            keyguardStateController.isShowing ||
                statusBarStateController.currentOrUpcomingState == StatusBarState.KEYGUARD

    private fun readShowSilentNotificationSetting() {
        val showSilentNotifs =
            secureSettings.getBoolForUser(
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
                false,
                UserHandle.USER_CURRENT,
            )
        hideSilentNotificationsOnLockscreen = !showSilentNotifs
    }
}

private typealias VisState = Boolean

private const val SHOW: VisState = false
private const val HIDE: VisState = true
