package com.android.systemui.keyguard.ui.composable.elements

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Window-bottom edges of the lockscreen content that sits above the ambient slot. A value of -1
 * means that element is not currently placed.
 */
@SysUISingleton
class LockscreenAmbientBounds @Inject constructor() {
    val largeClockBottom = MutableStateFlow(-1f)
    val largeDateWeatherBottom = MutableStateFlow(-1f)
    val smallClockBottom = MutableStateFlow(-1f)
    val smallDateWeatherBottom = MutableStateFlow(-1f)
    val smartspaceBottom = MutableStateFlow(-1f)
    val mediaBottom = MutableStateFlow(-1f)

    /**
     * Window-top edge of the keyguard indication area (charging info, owner info, transient
     * messages), or -1 when it is not placed. Unlike the edges above this is content that sits
     * below the ambient slot. Its height varies (e.g. multi-line battery info), so it can reach
     * past the lower region and has to be tracked on its own.
     */
    val indicationAreaTop = MutableStateFlow(-1f)
}
