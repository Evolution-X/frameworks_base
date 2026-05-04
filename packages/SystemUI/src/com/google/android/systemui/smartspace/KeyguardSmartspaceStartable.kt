package com.google.android.systemui.smartspace

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.smartspace.config.BcSmartspaceConfigProvider
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.InitializationChecker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.launch

class KeyguardSmartspaceStartable
@Inject
constructor(
    private val zenController: KeyguardZenAlarmViewController,
    private val mediaController: KeyguardMediaViewController,
    private val initializationChecker: InitializationChecker,
    private val clockRegistry: ClockRegistry,
    private val bcSmartspaceConfigProvider: BcSmartspaceConfigProvider,
    private val smartspaceDataPlugin: BcSmartspaceDataPlugin,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    private val clockChangeListener =
        object : ClockRegistry.ClockChangeListener {
            override fun onCurrentClockChanged() {
                updateClockId()
            }
        }

    private fun updateClockId() {
        val clockId = clockRegistry.currentClockId
        if (bcSmartspaceConfigProvider.currentClockId == clockId) return
        bcSmartspaceConfigProvider.currentClockId = clockId

        smartspaceDataPlugin.registerConfigProvider(bcSmartspaceConfigProvider)
    }

    override fun start() {
        if (initializationChecker.initializeComponents()) {
            updateClockId()
            mainExecutor.execute {
                clockRegistry.registerClockChangeListener(clockChangeListener)
            }

            zenController.datePlugin.addOnAttachStateChangeListener(
                zenController.attachStateChangeListener
            )

            zenController.applicationScope.launch { zenController.updateNextAlarm() }

            mediaController.plugin.addOnAttachStateChangeListener(
                mediaController.attachStateChangeListener
            )
        }
    }
}
