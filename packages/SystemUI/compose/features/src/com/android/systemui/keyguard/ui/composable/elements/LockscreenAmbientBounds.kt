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
}
