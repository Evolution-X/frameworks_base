/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.DisplayManager.EXTERNAL_DISPLAY_CONNECTION_PREFERENCE_DEFAULT;
import static android.hardware.display.DisplayManager.DEFAULT_HDR_PREFERENCE;
import static android.hardware.display.DisplayManager.EXTERNAL_DISPLAY_CONNECTION_PREFERENCE_ASK;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_NITS;

import android.annotation.Nullable;
import android.graphics.Point;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManager.ExternalDisplayConnection;
import android.hardware.display.DisplayManager.HdrPreference;
import android.hardware.display.WifiDisplay;
import android.os.Handler;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.Xml;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.display.persistence.PersistentDataStoreDelegate;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages persistent state recorded by the display manager service as an XML file.
 * Caller must acquire lock on the data store before accessing it.
 *
 * File format:
 * <code>
 * &lt;display-manager-state>
 *   &lt;remembered-wifi-displays>
 *     &lt;wifi-display deviceAddress="00:00:00:00:00:00" deviceName="XXXX" deviceAlias="YYYY" />
 *   &lt;remembered-wifi-displays>
 *   &lt;display-states>
 *      &lt;display unique-id="XXXXXXX">
 *          &lt;color-mode>0&lt;/color-mode>
 *          &lt;brightness-value>0&lt;/brightness-value>
 *          &lt;brightness-configurations>
 *              &lt;brightness-configuration user-serial="0" package-name="com.example"
 *              timestamp="1234">
 *                  &lt;brightness-curve description="some text">
 *                      &lt;brightness-point lux="0" nits="13.25"/>
 *                      &lt;brightness-point lux="20" nits="35.94"/>
 *                  &lt;/brightness-curve>
 *              &lt;/brightness-configuration>
 *          &lt;/brightness-configurations>
 *          &lt;display-mode>0&lt;
 *              &lt;resolution-width>1080&lt;/resolution-width>
 *              &lt;resolution-height>1920&lt;/resolution-height>
 *              &lt;refresh-rate>60&lt;/refresh-rate>
 *          &lt;/display-mode>
 *      &lt;/display>
 *  &lt;/display-states>
 *  &lt;stable-device-values>
 *      &lt;stable-display-height>1920&lt;/stable-display-height>
 *      &lt;stable-display-width>1080&lt;/stable-display-width>
 *  &lt;/stable-device-values>
 *  &lt;brightness-configurations>
 *      &lt;brightness-configuration user-serial="0" package-name="com.example" timestamp="1234">
 *          &lt;brightness-curve description="some text">
 *              &lt;brightness-point lux="0" nits="13.25"/>
 *              &lt;brightness-point lux="20" nits="35.94"/>
 *          &lt;/brightness-curve>
 *      &lt;/brightness-configuration>
 *  &lt;/brightness-configurations>
 *  &lt;brightness-nits-for-default-display>600&lt;/brightness-nits-for-default-display>
 * &lt;/display-manager-state>
 * </code>
 *
 * TODO: refactor this to extract common code shared with the input manager's data store
 */
public final class LegacyPersistentDataStore {
    private static final String TAG = "DisplayManager.PersistentDataStore";
    private static final String FILE_NAME = "/data/system/display-manager-state.xml";

    private static final String TAG_DISPLAY_MANAGER_STATE = "display-manager-state";

    private static final String TAG_REMEMBERED_WIFI_DISPLAYS = "remembered-wifi-displays";
    private static final String TAG_WIFI_DISPLAY = "wifi-display";
    private static final String ATTR_DEVICE_ADDRESS = "deviceAddress";
    private static final String ATTR_DEVICE_NAME = "deviceName";
    private static final String ATTR_DEVICE_ALIAS = "deviceAlias";

    private static final String TAG_DISPLAY_STATES = "display-states";
    private static final String TAG_DISPLAY = "display";
    private static final String TAG_COLOR_MODE = "color-mode";
    private static final String TAG_BRIGHTNESS_VALUE = "brightness-value";
    private static final String ATTR_UNIQUE_ID = "unique-id";

    private static final String TAG_STABLE_DEVICE_VALUES = "stable-device-values";
    private static final String TAG_STABLE_DISPLAY_HEIGHT = "stable-display-height";
    private static final String TAG_STABLE_DISPLAY_WIDTH = "stable-display-width";

    private static final String TAG_BRIGHTNESS_CONFIGURATIONS = "brightness-configurations";
    private static final String TAG_BRIGHTNESS_CONFIGURATION = "brightness-configuration";
    private static final String ATTR_USER_SERIAL = "user-serial";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_TIME_STAMP = "timestamp";

    private static final String TAG_RESOLUTION_WIDTH = "resolution-width";
    private static final String TAG_RESOLUTION_HEIGHT = "resolution-height";
    private static final String TAG_REFRESH_RATE = "refresh-rate";
    private static final String TAG_BRIGHTNESS_NITS_FOR_DEFAULT_DISPLAY =
            "brightness-nits-for-default-display";
    private static final String TAG_CONNECTION_PREFERENCE = "connection-preference";
    private static final String TAG_HDR_PREFERENCE = "hdr-preference";

    public static final int DEFAULT_USER_ID = -1;

    // Remembered Wi-Fi display devices.
    private final ArrayList<WifiDisplay> mRememberedWifiDisplays = new ArrayList<>();

    // Display state by unique id.
    private final HashMap<String, DisplayState> mDisplayStates = new HashMap<>();

    private float mBrightnessNitsForDefaultDisplay = INVALID_NITS;

    // Display values which should be stable across the device's lifetime.
    private final StableDeviceValues mStableDeviceValues = new StableDeviceValues();

    // Brightness configuration by user
    private final BrightnessConfigurations mGlobalBrightnessConfigurations =
            new BrightnessConfigurations();

    // True if the data has been loaded.
    private boolean mLoaded;

    // True if there are changes to be saved.
    private boolean mDirty;

    // The interface for methods which should be replaced by the test harness.
    private final PersistentDataStoreDelegate.Injector mInjector;

    private final Handler mHandler;
    private final Object mFileAccessLock = new Object();

    /**
     * Creates a new instance of LegacyPersistentDataStore.
     */
    public LegacyPersistentDataStore() {
        this(new PersistentDataStoreDelegate.Injector(FILE_NAME));
    }

    /**
     * Creates a new instance of LegacyPersistentDataStore with a specific injector.
     * @param injector The injector to use for file operations.
     */
    @VisibleForTesting
    public LegacyPersistentDataStore(PersistentDataStoreDelegate.Injector injector) {
        this(injector, new Handler(BackgroundThread.getHandler().getLooper()));
    }

    /**
     * Creates a new instance of LegacyPersistentDataStore with a specific injector and handler.
     * @param injector The injector to use for file operations.
     * @param handler The handler to use for asynchronous operations.
     */
    @VisibleForTesting
    LegacyPersistentDataStore(PersistentDataStoreDelegate.Injector injector,
            Handler handler) {
        mInjector = injector;
        mHandler = handler;
    }

    /**
     * Saves the data to the file if there are any pending changes.
     */
    public void saveIfNeeded() {
        if (mDirty) {
            save();
            mDirty = false;
        }
    }

    /**
     * Gets a remembered Wi-Fi display by its device address.
     * @param deviceAddress The address of the device to retrieve.
     * @return The remembered Wi-Fi display, or null if not found.
     */
    @Nullable
    public WifiDisplay getRememberedWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            return mRememberedWifiDisplays.get(index);
        }
        return null;
    }

    /**
     * Gets all remembered Wi-Fi displays.
     * @return An array of remembered Wi-Fi displays.
     */
    public WifiDisplay[] getRememberedWifiDisplays() {
        loadIfNeeded();
        return mRememberedWifiDisplays.toArray(WifiDisplay.EMPTY_ARRAY);
    }

    /**
     * Applies the saved alias to a given Wi-Fi display if it matches a remembered display.
     * @param display The display to apply the alias to.
     * @return A new WifiDisplay with the alias applied, or the same display if no alias is found.
     */
    public WifiDisplay applyWifiDisplayAlias(WifiDisplay display) {
        if (display != null) {
            loadIfNeeded();

            String alias = null;
            int index = findRememberedWifiDisplay(display.getDeviceAddress());
            if (index >= 0) {
                alias = mRememberedWifiDisplays.get(index).getDeviceAlias();
            }
            if (!Objects.equals(display.getDeviceAlias(), alias)) {
                return new WifiDisplay(display.getDeviceAddress(), display.getDeviceName(),
                        alias, display.isAvailable(), display.canConnect(), display.isRemembered());
            }
        }
        return display;
    }

    /**
     * Remembers a Wi-Fi display, updating its information if it already exists.
     * @param display The Wi-Fi display to remember.
     * @return true if the remembered list was changed.
     */
    public boolean rememberWifiDisplay(WifiDisplay display) {
        loadIfNeeded();

        int index = findRememberedWifiDisplay(display.getDeviceAddress());
        if (index >= 0) {
            WifiDisplay other = mRememberedWifiDisplays.get(index);
            if (other.equals(display)) {
                return false; // already remembered without change
            }
            mRememberedWifiDisplays.set(index, display);
        } else {
            mRememberedWifiDisplays.add(display);
        }
        setDirty();
        return true;
    }

    /**
     * Forgets a remembered Wi-Fi display by its device address.
     * @param deviceAddress The address of the device to forget.
     * @return true if the device was forgotten.
     */
    public boolean forgetWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            mRememberedWifiDisplays.remove(index);
            setDirty();
            return true;
        }
        return false;
    }

    private int findRememberedWifiDisplay(String deviceAddress) {
        int count = mRememberedWifiDisplays.size();
        for (int i = 0; i < count; i++) {
            if (mRememberedWifiDisplays.get(i).getDeviceAddress().equals(deviceAddress)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets the color mode for a display device.
     * @param device The display device.
     * @return The color mode, or Display.COLOR_MODE_INVALID if not found.
     */
    public int getColorMode(DisplayDevice device) {
        if (!device.hasStableUniqueId()) {
            return Display.COLOR_MODE_INVALID;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Display.COLOR_MODE_INVALID;
        }
        return state.getColorMode();
    }

    /**
     * Sets the color mode for a display device.
     * @param device The display device.
     * @param colorMode The color mode to set.
     * @return true if the color mode was changed.
     */
    public boolean setColorMode(DisplayDevice device, int colorMode) {
        if (!device.hasStableUniqueId()) {
            return false;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), true);
        if (state.setColorMode(colorMode)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets the brightness level for a display device and user.
     * @param device The display device.
     * @param userSerial The serial number of the user.
     * @return The brightness level, or Float.NaN if not found.
     */
    public float getBrightness(DisplayDevice device, int userSerial) {
        if (device == null || !device.hasStableUniqueId()) {
            return Float.NaN;
        }
        final DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Float.NaN;
        }
        return state.getBrightness(userSerial);
    }

    /**
     * Sets the brightness level for a display device and user.
     * @param displayDevice The display device.
     * @param brightness The brightness level to set.
     * @param userSerial The serial number of the user.
     * @return true if the brightness level was changed.
     */
    public boolean setBrightness(DisplayDevice displayDevice, float brightness, int userSerial) {
        if (displayDevice == null || !displayDevice.hasStableUniqueId()) {
            return false;
        }
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (displayDeviceUniqueId == null) {
            return false;
        }
        final DisplayState state = getDisplayState(displayDeviceUniqueId, true);
        if (state.setBrightness(brightness, userSerial)) {
            setDirty();
            saveIfNeeded();
            return true;
        }
        return false;
    }

    /**
     * Gets the brightness in nits for the default display.
     * @return The brightness in nits.
     */
    public float getBrightnessNitsForDefaultDisplay() {
        return mBrightnessNitsForDefaultDisplay;
    }

    /**
     * Sets the brightness in nits for the default display.
     * @param nits The brightness in nits to set.
     * @return true if the brightness was changed.
     */
    public boolean setBrightnessNitsForDefaultDisplay(float nits) {
        if (nits != mBrightnessNitsForDefaultDisplay) {
            mBrightnessNitsForDefaultDisplay = nits;
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Sets the user preferred refresh rate for a display device.
     * @param displayDevice The display device.
     * @param refreshRate The user preferred refresh rate to set.
     * @return true if the refresh rate was changed.
     */
    public boolean setUserPreferredRefreshRate(DisplayDevice displayDevice, float refreshRate) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        DisplayState state = getDisplayState(displayDevice.getUniqueId(), true);
        if (state.setRefreshRate(refreshRate)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets the user preferred refresh rate for a display device.
     *
     * @param device The display device.
     * @return The user preferred refresh rate, or {@link Display.INVALID_DISPLAY_REFRESH_RATE} if
     * not found.
     */
    public float getUserPreferredRefreshRate(DisplayDevice device) {
        if (device == null || !device.hasStableUniqueId()) {
            return Display.INVALID_DISPLAY_REFRESH_RATE;
        }
        final DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Display.INVALID_DISPLAY_REFRESH_RATE;
        }
        return state.getRefreshRate();
    }

    /**
     * Sets the user preferred resolution for a display device.
     * @param displayDevice The display device.
     * @param width The user preferred resolution width to set.
     * @param height The user preferred resolution height to set.
     * @return true if the resolution was changed.
     */
    public boolean setUserPreferredResolution(DisplayDevice displayDevice, int width, int height) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        DisplayState state = getDisplayState(displayDevice.getUniqueId(), true);
        if (state.setResolution(width, height)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets the user preferred resolution for a display device.
     * @param displayDevice The display device.
     * @return The user preferred resolution as a Point(width, height), or null if not found.
     */
    @Nullable
    public Point getUserPreferredResolution(DisplayDevice displayDevice) {
        if (displayDevice == null || !displayDevice.hasStableUniqueId()) {
            return null;
        }
        final DisplayState state = getDisplayState(displayDevice.getUniqueId(), false);
        if (state == null) {
            return null;
        }
        return state.getResolution();
    }

    /**
     * Gets the stable display size.
     * @return The stable display size as a Point(width, height).
     */
    public Point getStableDisplaySize() {
        loadIfNeeded();
        return mStableDeviceValues.getDisplaySize();
    }

    /**
     * Sets the stable display size.
     * @param size The stable display size as a Point(width, height) to set.
     */
    public void setStableDisplaySize(Point size) {
        loadIfNeeded();
        if (mStableDeviceValues.setDisplaySize(size)) {
            setDirty();
        }
    }

    /**
     * Sets the brightness configuration for a specific user.
     * @param c The brightness configuration.
     * @param userSerial The serial number of the user.
     * @param packageName The package name of the app setting the configuration.
     */
    // Used for testing & reset
    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userSerial,
            @Nullable String packageName) {
        loadIfNeeded();
        if (mGlobalBrightnessConfigurations.setBrightnessConfigurationForUser(c, userSerial,
                packageName)) {

            setDirty();
        }
    }

    /**
     * Sets the brightness configuration for a specific display device and user.
     * @param configuration The brightness configuration.
     * @param device The display device.
     * @param userSerial The serial number of the user.
     * @param packageName The package name of the app setting the configuration.
     * @return true if the configuration was changed.
     */
    public boolean setBrightnessConfigurationForDisplayLocked(BrightnessConfiguration configuration,
            DisplayDevice device, int userSerial, String packageName) {
        if (device == null || !device.hasStableUniqueId()) {
            return false;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), /*createIfAbsent*/ true);
        if (state.setBrightnessConfiguration(configuration, userSerial, packageName)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets the brightness configuration for a specific display device and user.
     * @param uniqueDisplayId The unique ID of the display.
     * @param userSerial The serial number of the user.
     * @return The brightness configuration, or null if not found.
     */
    @Nullable
    public BrightnessConfiguration getBrightnessConfigurationForDisplayLocked(
            String uniqueDisplayId, int userSerial) {
        loadIfNeeded();
        DisplayState state = mDisplayStates.get(uniqueDisplayId);
        if (state != null) {
            return state.getBrightnessConfiguration(userSerial);
        }
        return null;
    }

    /**
     * Gets the brightness configuration for a specific user.
     * @param userSerial The serial number of the user.
     * @return The brightness configuration, or null if not found.
     */
    @Nullable
    public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
        loadIfNeeded();
        return mGlobalBrightnessConfigurations.getBrightnessConfiguration(userSerial);
    }

    /**
     * Sets and persists the connection preference for a given display device.
     * <p>
     * The preference is only stored if the device has a stable unique ID. This method marks the
     * data store as dirty if the preference changes, triggering a subsequent save operation.
     *
     * @param device The display device for which to set the preference.
     * @param preference The connection preference to set, as defined by the
     * {@code @ExternalDisplayConnection} values in {@link android.hardware.display.DisplayManager}.
     * @return {@code true} if the preference was successfully updated, {@code false} if the value
     * was unchanged or if the device does not have a stable unique ID.
     */
    public boolean setConnectionPreference(
            DisplayDevice device,
            @ExternalDisplayConnection int preference
    ) {
        if (!device.hasStableUniqueId()) return false;
        DisplayState state = getDisplayState(device.getUniqueId(), /* createIfAbsent= */ true);

        if (state.setConnectionPreference(preference)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets the saved connection preference for a given display device.
     *
     * @param device The display device for which to retrieve the preference.
     * @return The stored {@code @ExternalDisplayConnection} preference for the device, or a default
     * value if no preference has been saved or if the device lacks a stable unique ID.
     */
    public @ExternalDisplayConnection int getConnectionPreference(DisplayDevice device) {
        if (!device.hasStableUniqueId()) return EXTERNAL_DISPLAY_CONNECTION_PREFERENCE_DEFAULT;
        DisplayState state = getDisplayState(device.getUniqueId(), /* createIfAbsent= */ false);
        if (state == null) {
            return EXTERNAL_DISPLAY_CONNECTION_PREFERENCE_DEFAULT;
        }
        return state.getConnectionPreference();
    }

    /**
     * Sets and persists user HDR preferred mode for a given display device.
     *
     * <p>The preference is only stored if the device has a stable unique ID. This method marks the
     * data store as dirty if the preference changes, triggering a subsequent save operation.
     *
     * @param displayDevice The display device for which to set the preference.
     * @param preference The HDR preference to set, as defined by the
     *     {@code @HdrPreference} values in {@link
     *     android.hardware.display.DisplayManager}.
     * @return {@code true} if the preference was successfully updated, {@code false} if the value
     *     was unchanged or if the device does not have a stable unique ID.
     */
    public boolean setUserPreferredHdrMode(
            DisplayDevice displayDevice, @HdrPreference int preference) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        DisplayState state =
                getDisplayState(displayDeviceUniqueId, /* createIfAbsent= */ true);
        if (state.setHdrPreference(preference)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Gets user HDR preferred mode for a given display device.
     *
     * @param device The display device for which to retrieve the preference.
     * @return The stored {@code @HdrPreference} preference for the device, or a
     *     default value if no preference has been saved or if the device lacks a stable unique ID.
     */
    public @HdrPreference int getUserPreferredHdrMode(DisplayDevice device) {
        if (!device.hasStableUniqueId()) {
            return DEFAULT_HDR_PREFERENCE;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), /* createIfAbsent= */ false);
        if (state == null) {
            return DEFAULT_HDR_PREFERENCE;
        }
        return state.getHdrPreference();
    }

    private DisplayState getDisplayState(String uniqueId, boolean createIfAbsent) {
        loadIfNeeded();
        DisplayState state = mDisplayStates.get(uniqueId);
        if (state == null && createIfAbsent) {
            state = new DisplayState();
            mDisplayStates.put(uniqueId, state);
            setDirty();
        }
        return state;
    }

    /**
     * Loads the data from the file if it has not been loaded yet.
     */
    public void loadIfNeeded() {
        if (!mLoaded) {
            load();
            mLoaded = true;
        }
    }

    /**
     * Removes all persistent data associated with a specific deleted user id.
     * This should be called when a device user is removed from the system.
     * @param userSerial The id number of the user to remove.
     */
    public void removeUserData(int userSerial) {
        loadIfNeeded();
        mGlobalBrightnessConfigurations.removeUser(userSerial);

        // Remove from each DisplayState's per-user brightness and configurations
        for (DisplayState state : mDisplayStates.values()) {
            state.removeUser(userSerial);
        }

        setDirty();
    }

    private void setDirty() {
        mDirty = true;
    }

    private void clearState() {
        mRememberedWifiDisplays.clear();
    }

    private void load() {
        synchronized (mFileAccessLock) {
            clearState();

            final InputStream is;
            try {
                is = mInjector.openRead();
            } catch (FileNotFoundException ex) {
                Slog.e(TAG, "The file does not exist.", ex);
                return;
            }

            TypedXmlPullParser parser;
            try {
                parser = Xml.resolvePullParser(is);
                loadFromXml(parser);
            } catch (IOException | XmlPullParserException ex) {
                Slog.e(TAG, "Failed to load display manager persistent store data.", ex);
                clearState();
            } finally {
                IoUtils.closeQuietly(is);
            }
        }
    }

    private void save() {
        final ByteArrayOutputStream os;
        try {
            os = new ByteArrayOutputStream();

            TypedXmlSerializer serializer = Xml.resolveSerializer(os);
            saveToXml(serializer);
            serializer.flush();

            mHandler.removeCallbacksAndMessages(/* token */ null);
            mHandler.post(() -> {
                synchronized (mFileAccessLock) {
                    OutputStream fileOutput = null;
                    try {
                        fileOutput = mInjector.startWrite();
                        os.writeTo(fileOutput);
                        fileOutput.flush();
                    } catch (IOException ex) {
                        Slog.e(TAG, "Failed to save display manager persistent store data.", ex);
                    } finally {
                        if (fileOutput != null) {
                            mInjector.finishWrite(fileOutput, true);
                        }
                    }
                }
            });
        } catch (IOException ex) {
            Slog.e(TAG, "Failed to process the XML serializer.", ex);
        }
    }

    private void loadFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, TAG_DISPLAY_MANAGER_STATE);
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_REMEMBERED_WIFI_DISPLAYS)) {
                loadRememberedWifiDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_DISPLAY_STATES)) {
                loadDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_STABLE_DEVICE_VALUES)) {
                mStableDeviceValues.loadFromXml(parser);
            }
            if (parser.getName().equals(TAG_BRIGHTNESS_CONFIGURATIONS)) {
                mGlobalBrightnessConfigurations.loadFromXml(parser);
            }
            if (parser.getName().equals(TAG_BRIGHTNESS_NITS_FOR_DEFAULT_DISPLAY)) {
                String value = parser.nextText();
                mBrightnessNitsForDefaultDisplay = Float.parseFloat(value);
            }
        }
    }

    private void loadRememberedWifiDisplaysFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_WIFI_DISPLAY)) {
                String deviceAddress = parser.getAttributeValue(null, ATTR_DEVICE_ADDRESS);
                String deviceName = parser.getAttributeValue(null, ATTR_DEVICE_NAME);
                String deviceAlias = parser.getAttributeValue(null, ATTR_DEVICE_ALIAS);
                if (deviceAddress == null || deviceName == null) {
                    throw new XmlPullParserException(
                            "Missing deviceAddress or deviceName attribute on wifi-display.");
                }
                if (findRememberedWifiDisplay(deviceAddress) >= 0) {
                    throw new XmlPullParserException(
                            "Found duplicate wifi display device address.");
                }

                mRememberedWifiDisplays.add(
                        new WifiDisplay(deviceAddress, deviceName, deviceAlias,
                                false, false, false));
            }
        }
    }

    private void loadDisplaysFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_DISPLAY)) {
                String uniqueId = parser.getAttributeValue(null, ATTR_UNIQUE_ID);
                if (uniqueId == null) {
                    throw new XmlPullParserException(
                            "Missing unique-id attribute on display.");
                }
                if (mDisplayStates.containsKey(uniqueId)) {
                    throw new XmlPullParserException("Found duplicate display.");
                }

                DisplayState state = new DisplayState();
                state.loadFromXml(parser);
                mDisplayStates.put(uniqueId, state);
            }
        }
    }

    private void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.startTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        for (WifiDisplay display : mRememberedWifiDisplays) {
            serializer.startTag(null, TAG_WIFI_DISPLAY);
            serializer.attribute(null, ATTR_DEVICE_ADDRESS, display.getDeviceAddress());
            serializer.attribute(null, ATTR_DEVICE_NAME, display.getDeviceName());
            if (display.getDeviceAlias() != null) {
                serializer.attribute(null, ATTR_DEVICE_ALIAS, display.getDeviceAlias());
            }
            serializer.endTag(null, TAG_WIFI_DISPLAY);
        }
        serializer.endTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        serializer.startTag(null, TAG_DISPLAY_STATES);
        for (Map.Entry<String, DisplayState> entry : mDisplayStates.entrySet()) {
            final String uniqueId = entry.getKey();
            final DisplayState state = entry.getValue();
            serializer.startTag(null, TAG_DISPLAY);
            serializer.attribute(null, ATTR_UNIQUE_ID, uniqueId);
            state.saveToXml(serializer);
            serializer.endTag(null, TAG_DISPLAY);
        }

        serializer.endTag(null, TAG_DISPLAY_STATES);
        serializer.startTag(null, TAG_STABLE_DEVICE_VALUES);
        mStableDeviceValues.saveToXml(serializer);
        serializer.endTag(null, TAG_STABLE_DEVICE_VALUES);
        serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        mGlobalBrightnessConfigurations.saveToXml(serializer);
        serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        serializer.startTag(null, TAG_BRIGHTNESS_NITS_FOR_DEFAULT_DISPLAY);
        serializer.text(Float.toString(mBrightnessNitsForDefaultDisplay));
        serializer.endTag(null, TAG_BRIGHTNESS_NITS_FOR_DEFAULT_DISPLAY);
        serializer.endTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.endDocument();
    }

    /**
     * Dumps the state of the persistent data store.
     * @param pw The print writer to dump to.
     */
    public void dump(PrintWriter pw) {
        pw.println("LegacyPersistentDataStore:");
        pw.println("--------------------");

        pw.println("  mLoaded=" + mLoaded);
        pw.println("  mDirty=" + mDirty);
        pw.println("  RememberedWifiDisplays:");
        int i = 0;
        for (WifiDisplay display : mRememberedWifiDisplays) {
            pw.println("    " + i++ + ": " + display);
        }
        pw.println("  DisplayStates:");
        i = 0;
        for (Map.Entry<String, DisplayState> entry : mDisplayStates.entrySet()) {
            pw.println("    " + i++ + ": " + entry.getKey());
            entry.getValue().dump(pw, "      ");
        }
        pw.println("  StableDeviceValues:");
        mStableDeviceValues.dump(pw, "      ");
        pw.println("  GlobalBrightnessConfigurations:");
        mGlobalBrightnessConfigurations.dump(pw, "      ");
        pw.println("  mBrightnessNitsForDefaultDisplay=" + mBrightnessNitsForDefaultDisplay);
    }

    private static final class DisplayState {
        private int mColorMode;

        private final SparseArray<Float> mPerUserBrightness = new SparseArray<>();
        private int mWidth;
        private int mHeight;
        private float mRefreshRate;
        private int mHdrPreference = DEFAULT_HDR_PREFERENCE;

        // Brightness configuration by user
        private final BrightnessConfigurations mDisplayBrightnessConfigurations =
                new BrightnessConfigurations();

        private int mConnectionPreference = EXTERNAL_DISPLAY_CONNECTION_PREFERENCE_ASK;

        public boolean setColorMode(int colorMode) {
            if (colorMode == mColorMode) {
                return false;
            }
            mColorMode = colorMode;
            return true;
        }

        public int getColorMode() {
            return mColorMode;
        }

        public boolean setBrightness(float brightness, int userSerial) {
            // Remove the default user brightness, before setting a new user-specific value.
            // This is a one-time operation, required to restructure the config after user-specific
            // brightness was introduced.
            mPerUserBrightness.remove(DEFAULT_USER_ID);

            if (getBrightness(userSerial) == brightness) {
                return false;
            }
            mPerUserBrightness.set(userSerial, brightness);
            return true;
        }

        public float getBrightness(int userSerial) {
            float brightness = mPerUserBrightness.get(userSerial, Float.NaN);
            if (Float.isNaN(brightness)) {
                brightness = mPerUserBrightness.get(DEFAULT_USER_ID, Float.NaN);
            }
            return brightness;
        }

        public boolean setBrightnessConfiguration(BrightnessConfiguration configuration,
                int userSerial, String packageName) {
            mDisplayBrightnessConfigurations.setBrightnessConfigurationForUser(
                    configuration, userSerial, packageName);
            return true;
        }

        @Nullable
        public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
            return mDisplayBrightnessConfigurations.mConfigurations.get(userSerial);
        }

        public boolean setResolution(int width, int height) {
            if (width == mWidth && height == mHeight) {
                return false;
            }
            mWidth = width;
            mHeight = height;
            return true;
        }

        public Point getResolution() {
            return new Point(mWidth, mHeight);
        }

        public boolean setRefreshRate(float refreshRate) {
            if (refreshRate == mRefreshRate) {
                return false;
            }
            mRefreshRate = refreshRate;
            return true;
        }

        public float getRefreshRate() {
            return mRefreshRate;
        }

        public int getConnectionPreference() {
            return mConnectionPreference;
        }

        public boolean setConnectionPreference(int preference) {
            if (preference == mConnectionPreference) {
                return false;
            }
            mConnectionPreference = preference;
            return true;
        }

        private void removeUser(int userId) {
            mPerUserBrightness.remove(userId);
            mDisplayBrightnessConfigurations.removeUser(userId);
        }

        private boolean setHdrPreference(int preference) {
            if (preference == mHdrPreference) {
                return false;
            }
            mHdrPreference = preference;
            return true;
        }

        private int getHdrPreference() {
            return mHdrPreference;
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();

            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                switch (parser.getName()) {
                    case TAG_COLOR_MODE:
                        String value = parser.nextText();
                        mColorMode = Integer.parseInt(value);
                        break;
                    case TAG_BRIGHTNESS_VALUE:
                        loadBrightnessFromXml(parser);
                        break;
                    case TAG_BRIGHTNESS_CONFIGURATIONS:
                        mDisplayBrightnessConfigurations.loadFromXml(parser);
                        break;
                    case TAG_RESOLUTION_WIDTH:
                        String width = parser.nextText();
                        mWidth = Integer.parseInt(width);
                        break;
                    case TAG_RESOLUTION_HEIGHT:
                        String height = parser.nextText();
                        mHeight = Integer.parseInt(height);
                        break;
                    case TAG_REFRESH_RATE:
                        String refreshRate = parser.nextText();
                        mRefreshRate = Float.parseFloat(refreshRate);
                        break;
                    case TAG_CONNECTION_PREFERENCE:
                        String connectionPreference = parser.nextText();
                        mConnectionPreference = Integer.parseInt(connectionPreference);
                        break;
                    case TAG_HDR_PREFERENCE:
                        String hdrPreference = parser.nextText();
                        mHdrPreference = Integer.parseInt(hdrPreference);
                        break;
                }
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_COLOR_MODE);
            serializer.text(Integer.toString(mColorMode));
            serializer.endTag(null, TAG_COLOR_MODE);

            for (int i = 0; i < mPerUserBrightness.size(); i++) {
                serializer.startTag(null, TAG_BRIGHTNESS_VALUE);
                serializer.attributeInt(null, ATTR_USER_SERIAL, mPerUserBrightness.keyAt(i));
                serializer.text(Float.toString(mPerUserBrightness.valueAt(i)));
                serializer.endTag(null, TAG_BRIGHTNESS_VALUE);
            }

            serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
            mDisplayBrightnessConfigurations.saveToXml(serializer);
            serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);

            serializer.startTag(null, TAG_CONNECTION_PREFERENCE);
            serializer.text(Integer.toString(mConnectionPreference));
            serializer.endTag(null, TAG_CONNECTION_PREFERENCE);

            serializer.startTag(null, TAG_HDR_PREFERENCE);
            serializer.text(Integer.toString(mHdrPreference));
            serializer.endTag(null, TAG_HDR_PREFERENCE);

            serializer.startTag(null, TAG_RESOLUTION_WIDTH);
            serializer.text(Integer.toString(mWidth));
            serializer.endTag(null, TAG_RESOLUTION_WIDTH);

            serializer.startTag(null, TAG_RESOLUTION_HEIGHT);
            serializer.text(Integer.toString(mHeight));
            serializer.endTag(null, TAG_RESOLUTION_HEIGHT);

            serializer.startTag(null, TAG_REFRESH_RATE);
            serializer.text(Float.toString(mRefreshRate));
            serializer.endTag(null, TAG_REFRESH_RATE);
        }

        public void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "ColorMode=" + mColorMode);
            pw.println(prefix + "BrightnessValues: ");
            for (int i = 0; i < mPerUserBrightness.size(); i++) {
                pw.println("User: " + mPerUserBrightness.keyAt(i)
                        + " Value: " + mPerUserBrightness.valueAt(i));
            }
            pw.println(prefix + "DisplayBrightnessConfigurations: ");
            mDisplayBrightnessConfigurations.dump(pw, prefix);
            pw.println(prefix + "ConnectionPreference=" + mConnectionPreference);
            pw.println(prefix + "Resolution=" + mWidth + " " + mHeight);
            pw.println(prefix + "RefreshRate=" + mRefreshRate);
            pw.println(prefix + "HdrPreference=" + mHdrPreference);
        }

        private void loadBrightnessFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            int userSerial;
            try {
                userSerial = parser.getAttributeInt(null, ATTR_USER_SERIAL);
            } catch (NumberFormatException | XmlPullParserException e) {
                userSerial = DEFAULT_USER_ID;
                Slog.e(TAG, "Failed to read user serial", e);
            }
            String brightness = parser.nextText();
            try {
                mPerUserBrightness.set(userSerial, Float.parseFloat(brightness));
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, "Failed to read brightness", nfe);
            }
        }
    }

    private static final class StableDeviceValues {
        private int mWidth;
        private int mHeight;

        private Point getDisplaySize() {
            return new Point(mWidth, mHeight);
        }

        public boolean setDisplaySize(Point r) {
            if (mWidth != r.x || mHeight != r.y) {
                mWidth = r.x;
                mHeight = r.y;
                return true;
            }
            return false;
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                switch (parser.getName()) {
                    case TAG_STABLE_DISPLAY_WIDTH:
                        mWidth = loadIntValue(parser);
                        break;
                    case TAG_STABLE_DISPLAY_HEIGHT:
                        mHeight = loadIntValue(parser);
                        break;
                }
            }
        }

        private static int loadIntValue(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            try {
                String value = parser.nextText();
                return Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            if (mWidth > 0 && mHeight > 0) {
                serializer.startTag(null, TAG_STABLE_DISPLAY_WIDTH);
                serializer.text(Integer.toString(mWidth));
                serializer.endTag(null, TAG_STABLE_DISPLAY_WIDTH);
                serializer.startTag(null, TAG_STABLE_DISPLAY_HEIGHT);
                serializer.text(Integer.toString(mHeight));
                serializer.endTag(null, TAG_STABLE_DISPLAY_HEIGHT);
            }
        }

        public void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "StableDisplayWidth=" + mWidth);
            pw.println(prefix + "StableDisplayHeight=" + mHeight);
        }
    }

    private static final class BrightnessConfigurations {
        // Maps from a user ID to the users' given brightness configuration
        private final SparseArray<BrightnessConfiguration> mConfigurations;
        // Timestamp of time the configuration was set.
        private final SparseLongArray mTimeStamps;
        // Package that set the configuration.
        private final SparseArray<String> mPackageNames;

        BrightnessConfigurations() {
            mConfigurations = new SparseArray<>();
            mTimeStamps = new SparseLongArray();
            mPackageNames = new SparseArray<>();
        }

        private boolean setBrightnessConfigurationForUser(BrightnessConfiguration c,
                int userSerial, String packageName) {
            BrightnessConfiguration currentConfig = mConfigurations.get(userSerial);
            if (!Objects.equals(currentConfig, c)) {
                if (c != null) {
                    if (packageName == null) {
                        mPackageNames.remove(userSerial);
                    } else {
                        mPackageNames.put(userSerial, packageName);
                    }
                    mTimeStamps.put(userSerial, System.currentTimeMillis());
                    mConfigurations.put(userSerial, c);
                } else {
                    mPackageNames.remove(userSerial);
                    mTimeStamps.delete(userSerial);
                    mConfigurations.remove(userSerial);
                }
                return true;
            }
            return false;
        }

        @Nullable
        public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
            return mConfigurations.get(userSerial);
        }

        private void removeUser(int userId) {
            mConfigurations.remove(userId);
            mTimeStamps.delete(userId);
            mPackageNames.remove(userId);
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (TAG_BRIGHTNESS_CONFIGURATION.equals(parser.getName())) {
                    int userSerial;
                    try {
                        userSerial = parser.getAttributeInt(null, ATTR_USER_SERIAL);
                    } catch (NumberFormatException nfe) {
                        userSerial = -1;
                        Slog.e(TAG, "Failed to read in brightness configuration", nfe);
                    }

                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    long timeStamp = parser.getAttributeLong(null, ATTR_TIME_STAMP, -1);

                    try {
                        BrightnessConfiguration config =
                                BrightnessConfiguration.loadFromXml(parser);
                        if (userSerial >= 0) {
                            mConfigurations.put(userSerial, config);
                            if (timeStamp != -1) {
                                mTimeStamps.put(userSerial, timeStamp);
                            }
                            if (packageName != null) {
                                mPackageNames.put(userSerial, packageName);
                            }
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.e(TAG, "Failed to load brightness configuration!", iae);
                    }
                }
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            for (int i = 0; i < mConfigurations.size(); i++) {
                final int userSerial = mConfigurations.keyAt(i);
                final BrightnessConfiguration config = mConfigurations.valueAt(i);

                serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATION);
                serializer.attributeInt(null, ATTR_USER_SERIAL, userSerial);
                String packageName = mPackageNames.get(userSerial);
                if (packageName != null) {
                    serializer.attribute(null, ATTR_PACKAGE_NAME, packageName);
                }
                long timestamp = mTimeStamps.get(userSerial, -1);
                if (timestamp != -1) {
                    serializer.attributeLong(null, ATTR_TIME_STAMP, timestamp);
                }
                config.saveToXml(serializer);
                serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATION);
            }
        }

        public void dump(final PrintWriter pw, final String prefix) {
            for (int i = 0; i < mConfigurations.size(); i++) {
                final int userSerial = mConfigurations.keyAt(i);
                long time = mTimeStamps.get(userSerial, -1);
                String packageName = mPackageNames.get(userSerial);
                pw.println(prefix + "User " + userSerial + ":");
                if (time != -1) {
                    pw.println(prefix + "  set at: " + TimeUtils.formatForLogging(time));
                }
                if (packageName != null) {
                    pw.println(prefix + "  set by: " + packageName);
                }
                pw.println(prefix + "  " + mConfigurations.valueAt(i));
            }
        }
    }
}
