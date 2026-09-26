/*
 * Copyright (C) 2019-2024 crDroid Android Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

public class CompassTile extends QSTileImpl<BooleanState> implements SensorEventListener {

    public static final String TILE_SPEC = "compass";

    private static final float ALPHA = 0.97f;
    private static final long UI_UPDATE_INTERVAL_MS = 100L;

    private final SensorManager mSensorManager;
    @Nullable
    private final Sensor mAccelerationSensor;
    @Nullable
    private final Sensor mGeomagneticFieldSensor;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_compass);

    private boolean mActive;
    private boolean mListeningSensors;
    private float[] mAcceleration;
    private float[] mGeomagnetic;
    private long mLastUiUpdateElapsed;

    @Inject
    public CompassTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mAccelerationSensor = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        mGeomagneticFieldSensor = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
    }

    @Override
    public BooleanState newTileState() {
        final BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleDestroy() {
        setListeningSensors(false);
        super.handleDestroy();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (!isAvailable()) {
            return;
        }

        mActive = !mActive;
        if (mActive) {
            mAcceleration = null;
            mGeomagnetic = null;
            mLastUiUpdateElapsed = 0L;
        }
        setListeningSensors(mActive);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    private void setListeningSensors(boolean listening) {
        if (listening == mListeningSensors || mSensorManager == null) {
            return;
        }

        mListeningSensors = listening;
        if (listening && mAccelerationSensor != null && mGeomagneticFieldSensor != null) {
            mSensorManager.registerListener(
                    this, mAccelerationSensor, SensorManager.SENSOR_DELAY_UI);
            mSensorManager.registerListener(
                    this, mGeomagneticFieldSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_compass_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final Float degrees = arg instanceof Float ? (Float) arg : null;

        state.value = mActive;
        state.icon = mIcon;
        state.label = getTileLabel();
        state.expandedAccessibilityClassName = Button.class.getName();

        if (mActive) {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = degrees != null
                    ? formatValueWithCardinalDirection(degrees)
                    : mContext.getString(R.string.quick_settings_compass_init);
        } else {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = mContext.getString(R.string.quick_settings_state_off);
        }

        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_PANEL;
    }

    @Override
    public boolean isAvailable() {
        return mSensorManager != null
                && mAccelerationSensor != null
                && mGeomagneticFieldSensor != null;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (!listening) {
            setListeningSensors(false);
            mActive = false;
        } else {
            refreshState();
        }
    }

    private String formatValueWithCardinalDirection(float degree) {
        final int cardinalDirectionIndex =
                (int) (Math.floor(((degree - 22.5f) % 360f) / 45f) + 1) % 8;
        final String[] cardinalDirections = mContext.getResources().getStringArray(
                R.array.cardinal_directions);

        return mContext.getString(
                R.string.quick_settings_compass_value,
                degree,
                cardinalDirections[cardinalDirectionIndex]);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final float[] values;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (mAcceleration == null) {
                mAcceleration = event.values.clone();
            }
            values = mAcceleration;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (mGeomagnetic == null) {
                mGeomagnetic = event.values.clone();
            }
            values = mGeomagnetic;
        } else {
            return;
        }

        for (int i = 0; i < 3; i++) {
            values[i] = ALPHA * values[i] + (1 - ALPHA) * event.values[i];
        }

        if (!mActive || !mListeningSensors || mAcceleration == null || mGeomagnetic == null) {
            return;
        }

        final float[] rotation = new float[9];
        final float[] inclination = new float[9];
        if (!SensorManager.getRotationMatrix(rotation, inclination, mAcceleration, mGeomagnetic)) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        if (now - mLastUiUpdateElapsed < UI_UPDATE_INTERVAL_MS) {
            return;
        }
        mLastUiUpdateElapsed = now;

        final float[] orientation = new float[3];
        SensorManager.getOrientation(rotation, orientation);
        float degree = (float) Math.toDegrees(orientation[0]);
        degree = (degree + 360f) % 360f;
        degree = (-degree + 360f) % 360f;

        refreshState(Float.valueOf(degree));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op.
    }
}
