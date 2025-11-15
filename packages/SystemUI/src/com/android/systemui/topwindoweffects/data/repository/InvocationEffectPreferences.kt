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

package com.android.systemui.topwindoweffects.data.repository

import android.annotation.SuppressLint
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface InvocationEffectPreferences {

    val isInvocationEffectEnabledByAssistant: StateFlow<Boolean>

    fun isInvocationEffectEnabledInPreferences(): Boolean

    fun getInwardAnimationPaddingDurationMillis(): Long

    fun getOutwardAnimationDurationMillis(): Long

    fun isCurrentUserAndAssistantPersisted(): Boolean

    fun setInvocationEffectConfig(config: Config, saveActiveUserAndAssistant: Boolean)

    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener)

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener)

    fun dump(pw: PrintWriter, args: Array<out String>)

    data class Config(
        val isEnabled: Boolean,
        val inwardsEffectDurationPadding: Long,
        val outwardsEffectDuration: Long,
    )
}

@SysUISingleton
class InvocationEffectPreferencesImpl
@Inject
constructor(
    @Application context: Context,
    @Background private val bgScope: CoroutineScope,
    private val userRepository: UserRepository,
    roleManager: RoleManager,
    @Background executor: Executor,
    @Background private val coroutineContext: CoroutineContext,
) : InvocationEffectPreferences {

    private val sharedPreferences by lazy {
        context.getSharedPreferences(SHARED_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    }

    private var activeUser: Int = userRepository.selectedUserHandle.identifier
    private var activeAssistant: String =
        roleManager.getCurrentAssistantFor(userRepository.selectedUserHandle)

    private val activeUserOrAssistantChanged: Flow<Boolean> =
        conflatedCallbackFlow {
                val listener = OnRoleHoldersChangedListener { roleName, _ ->
                    if (roleName == RoleManager.ROLE_ASSISTANT) {
                        trySendWithFailureLogging(
                            roleManager.getCurrentAssistantFor(userRepository.selectedUserHandle),
                            TAG,
                            "updated currentlyActiveAssistantName due to role change",
                        )
                    }
                }

                roleManager.addOnRoleHoldersChangedListenerAsUser(
                    executor,
                    listener,
                    UserHandle.ALL,
                )

                awaitClose {
                    roleManager.removeOnRoleHoldersChangedListenerAsUser(listener, UserHandle.ALL)
                }
            }
            .flowOn(coroutineContext)
            .combine(userRepository.selectedUser) { assistant, user ->
                val userId = user.userInfo.userHandle.identifier
                val changed = activeUser != userId || activeAssistant != assistant
                if (changed) {
                    activeUser = userId
                    activeAssistant =
                        roleManager.getCurrentAssistantFor(userRepository.selectedUserHandle)
                }
                changed
            }
            .filter { it /* only emit if assistant or user changed */ }

    override val isInvocationEffectEnabledByAssistant: StateFlow<Boolean> =
        merge(
                conflatedCallbackFlow {
                        val listener =
                            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                                if (key == IS_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT) {
                                    trySendWithFailureLogging(
                                        isInvocationEffectEnabledByAssistant(),
                                        TAG,
                                        "updated isInvocationEffectEnabledByAssistantFlow due to enabled status change",
                                    )
                                }
                            }
                        registerOnChangeListener(listener)
                        awaitClose { unregisterOnChangeListener(listener) }
                    }
                    .flowOn(coroutineContext),
                activeUserOrAssistantChanged,
            )
            .mapLatestConflated { isInvocationEffectEnabledByAssistant() }
            .stateIn(
                scope = bgScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isInvocationEffectEnabledByAssistant(),
            )

    private fun getSavedAssistant(): String =
        getOrDefault<String>(
            key = PERSISTED_FOR_ASSISTANT_PREFERENCE,
            default = "",
            checkUserAndAssistant = false,
        )

    private fun getSavedUserId(): Int =
        getOrDefault<Int>(
            key = PERSISTED_FOR_USER_PREFERENCE,
            default = Integer.MIN_VALUE,
            checkUserAndAssistant = false,
        )

    private fun isInvocationEffectEnabledByAssistant(): Boolean =
        isInvocationEffectEnabledInPreferences() && activeAssistant.isNotEmpty()

    override fun getInwardAnimationPaddingDurationMillis(): Long =
        getOrDefault<Long>(
            key = INVOCATION_EFFECT_ANIMATION_IN_DURATION_PADDING_MS,
            default = DEFAULT_INWARD_EFFECT_PADDING_DURATION_MS,
            checkUserAndAssistant = true,
        )

    override fun getOutwardAnimationDurationMillis(): Long =
        getOrDefault<Long>(
            key = INVOCATION_EFFECT_ANIMATION_OUT_DURATION_MS,
            default = DEFAULT_OUTWARD_EFFECT_DURATION_MS,
            checkUserAndAssistant = true,
        )

    override fun isCurrentUserAndAssistantPersisted(): Boolean =
        activeUser == getSavedUserId() && activeAssistant == getSavedAssistant()

    override fun isInvocationEffectEnabledInPreferences(): Boolean =
        getOrDefault<Boolean>(
            key = IS_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT,
            default = DEFAULT_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT_PREFERENCE,
            checkUserAndAssistant = true,
        )

    override fun setInvocationEffectConfig(
        config: InvocationEffectPreferences.Config,
        saveActiveUserAndAssistant: Boolean,
    ) {
        bgScope.launch(context = coroutineContext) {
            sharedPreferences.edit {
                if (saveActiveUserAndAssistant) {
                    putString(PERSISTED_FOR_ASSISTANT_PREFERENCE, activeAssistant)
                    putInt(PERSISTED_FOR_USER_PREFERENCE, activeUser)
                }

                if (config.isEnabled != isInvocationEffectEnabledInPreferences()) {
                    putBoolean(IS_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT, config.isEnabled)
                }

                if (
                    config.inwardsEffectDurationPadding != getInwardAnimationPaddingDurationMillis()
                ) {
                    putLong(
                        INVOCATION_EFFECT_ANIMATION_IN_DURATION_PADDING_MS,
                        if (config.inwardsEffectDurationPadding in 0..1000) {
                            config.inwardsEffectDurationPadding
                        } else {
                            DEFAULT_INWARD_EFFECT_PADDING_DURATION_MS
                        },
                    )
                }

                if (config.outwardsEffectDuration != getOutwardAnimationDurationMillis()) {
                    putLong(
                        INVOCATION_EFFECT_ANIMATION_OUT_DURATION_MS,
                        if (config.outwardsEffectDuration in 100..1000) {
                            config.outwardsEffectDuration
                        } else {
                            DEFAULT_OUTWARD_EFFECT_DURATION_MS
                        },
                    )
                }
            }
        }
    }

    private inline fun <reified T> getOrDefault(
        key: String,
        default: T,
        checkUserAndAssistant: Boolean,
    ): T {
        val value: Any? =
            try {
                when (T::class) {
                    Int::class -> sharedPreferences.getInt(key, default as Int)
                    Long::class -> sharedPreferences.getLong(key, default as Long)
                    Boolean::class -> sharedPreferences.getBoolean(key, default as Boolean)
                    String::class -> sharedPreferences.getString(key, default as String)
                    else -> /* type not supported */ null
                }
            } catch (e: ClassCastException /* ignore */) {
                null
            }

        val result = value ?: default

        return if (checkUserAndAssistant) {
            if (isCurrentUserAndAssistantPersisted()) {
                result as T
            } else {
                default
            }
        } else {
            result as T
        }
    }

    override fun registerOnChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun unregisterOnChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("$TAG:")
        pw.println("  activeUser=$activeUser")
        pw.println("  activeAssistant=$activeAssistant")
        pw.println("  savedUser=${getSavedUserId()}")
        pw.println("  savedAssistant=${getSavedAssistant()}")
        pw.println("  isCurrentUserAndAssistantPersisted=${isCurrentUserAndAssistantPersisted()}")
        pw.println(
            "  isInvocationEffectEnabledByAssistant=${isInvocationEffectEnabledByAssistant.value}"
        )
        pw.println(
            "  inwardAnimationPaddingDurationMillis=${getInwardAnimationPaddingDurationMillis()}"
        )
        pw.println("  outwardAnimationDurationMillis=${getOutwardAnimationDurationMillis()}")
    }

    companion object {
        private const val TAG = "InvocationEffectPreferences"
        private const val SHARED_PREFERENCES_FILE_NAME = "assistant_invocation_effect_preferences"
        @VisibleForTesting const val PERSISTED_FOR_ASSISTANT_PREFERENCE = "persisted_for_assistant"
        @VisibleForTesting const val PERSISTED_FOR_USER_PREFERENCE = "persisted_for_user"
        const val IS_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT = "is_invocation_effect_enabled"
        const val INVOCATION_EFFECT_ANIMATION_IN_DURATION_PADDING_MS =
            "invocation_effect_animation_in_duration_padding_ms"
        const val INVOCATION_EFFECT_ANIMATION_OUT_DURATION_MS =
            "invocation_effect_animation_out_duration_ms"
        const val DEFAULT_INWARD_EFFECT_PADDING_DURATION_MS = 450L
        const val DEFAULT_OUTWARD_EFFECT_DURATION_MS = 400L
        const val DEFAULT_INVOCATION_EFFECT_ENABLED_BY_ASSISTANT_PREFERENCE = true
    }
}

private val UserRepository.selectedUserHandle
    get() = selectedUser.value.userInfo.userHandle

@SuppressLint("MissingPermission")
private fun RoleManager.getCurrentAssistantFor(userHandle: UserHandle) =
    getRoleHoldersAsUser(RoleManager.ROLE_ASSISTANT, userHandle).firstOrNull() ?: ""
