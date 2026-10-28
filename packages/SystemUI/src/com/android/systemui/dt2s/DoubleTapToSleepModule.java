package com.android.systemui.dt2s;

import com.android.systemui.CoreStartable;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

@Module
public abstract class DoubleTapToSleepModule {
    @Binds
    @IntoMap
    @ClassKey(DoubleTapToSleepController.class)
    public abstract CoreStartable bindDoubleTapToSleepController(
            DoubleTapToSleepController impl);
}
