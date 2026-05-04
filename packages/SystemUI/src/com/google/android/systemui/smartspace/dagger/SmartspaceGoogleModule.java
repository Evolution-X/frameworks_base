package com.google.android.systemui.smartspace.dagger;

import com.android.systemui.dagger.SysUISingleton;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartspaceDataProvider;
import com.google.android.systemui.smartspace.WeatherSmartspaceDataProvider;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

@Module
public abstract class SmartspaceGoogleModule {
    @Binds
    @SysUISingleton
    abstract BcSmartspaceDataPlugin bindBcSmartspaceDataPlugin(
            BcSmartspaceDataProvider provider);

    @Provides
    @SysUISingleton
    static BcSmartspaceDataProvider provideDreamBcSmartspaceDataPlugin() {
        return new BcSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    static WeatherSmartspaceDataProvider provideDreamWeatherSmartspaceDataPlugin() {
        return new WeatherSmartspaceDataProvider();
    }
}
