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

package com.android.server.security.advancedprotection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.security.advancedprotection.AdvancedProtectionManager;
import android.util.ArraySet;

// config parser and classes are automatically generated based on advanced-protection-config.xsd
import com.android.internal.pm.pkg.component.AconfigFlags;
import com.android.server.security.advancedprotection.config.AdvancedProtectionConfig;
import com.android.server.security.advancedprotection.config.Protections;
import com.android.server.security.advancedprotection.config.Protections.Protection;
import com.android.server.security.advancedprotection.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Loads {@link AdvancedProtectionConfig} from an XML file located in {@link
 * #getSystemConfigFile()}.
 *
 * <p>Use {@link #isFeatureIdAvailable(int)} for Advanced Protection feature availability.
 *
 * @hide
 */
public class AdvancedProtectionConfigLoader {
    private static final String ADVANCED_PROTECTION_CONFIG_DIR = "advanced-protection-config";
    private static final String ETC_DIR = "etc";
    private static final String ADVANCED_PROTECTION_CONFIG_FILE_NAME =
            "advanced-protection-config.xml";

    @NonNull
    private static File getSystemConfigFile() {
        return Environment.buildPath(
                Environment.getRootDirectory(),
                ETC_DIR,
                ADVANCED_PROTECTION_CONFIG_DIR,
                ADVANCED_PROTECTION_CONFIG_FILE_NAME);
    }

    private final AdvancedProtectionConfig mSystemConfig;
    private final ArraySet<Integer> mAvailableProtections = new ArraySet<>();

    /**
     * Constructs a new AdvancedProtectionConfigLoader.
     *
     * <p>This constructor loads and parses the Advanced Protection system config.
     *
     * @throws IllegalStateException if the system configuration file is not present or cannot be
     *     parsed.
     */
    public AdvancedProtectionConfigLoader(Injector injector) {
        try (InputStream in = injector.readSystemConfig()) {
            if (in == null) {
                throw new IllegalStateException("System config input stream is null");
            }
            mSystemConfig = XmlParser.read(in);
            if (mSystemConfig == null) {
                throw new IllegalStateException("System config doesn't exist");
            }
            computeFeatureAvailability(mSystemConfig);
        } catch (DatatypeConfigurationException | IOException | XmlPullParserException e) {
            throw new IllegalStateException("Failed to read advanced protection system config", e);
        }
    }

    /**
     * Checks if a specific Advanced Protection feature is available.
     *
     * <p>A feature is considered available if it is listed as available in the system config and
     * its feature flag, if exists, is enabled.
     *
     * @param featureId The ID of the feature to check, as defined in {@link
     *     AdvancedProtectionManager.FeatureId}.
     * @return {@code true} if the feature is available, {@code false} otherwise.
     */
    public boolean isFeatureIdAvailable(int featureId) {
        return mAvailableProtections.contains(featureId);
    }

    private void computeFeatureAvailability(AdvancedProtectionConfig config)
            throws IllegalArgumentException {
        Protections availableProtections = config.getAvailableProtections();
        if (availableProtections == null) {
            return;
        }
        List<Protection> protections = availableProtections.getProtection();
        for (int i = 0; i < protections.size(); i++) {
            Protection protection = protections.get(i);
            String featureFlag = protection.getFeatureFlag();
            if (featureFlag == null || isFeatureFlagEnabled(featureFlag)) {
                String protectionIdString = protection.getId().getRawName();
                mAvailableProtections.add(
                        AdvancedProtectionManager.featureStringToId(protectionIdString));
            }
        }
    }

    private static boolean isFeatureFlagEnabled(@NonNull String featureFlag)
            throws IllegalArgumentException {
        boolean negated = false;
        if (featureFlag.startsWith("!")) {
            negated = true;
            featureFlag = featureFlag.substring(1).strip();
        }
        Boolean featureFlagValue = AconfigFlags.getInstance().getFlagValue(featureFlag);
        if (featureFlagValue == null) {
            return false;
        }
        return negated ? !featureFlagValue : featureFlagValue;
    }

    /**
     * Dumps the current state of the AdvancedProtectionConfigLoader to the provided PrintWriter.
     */
    public void dump(PrintWriter writer) {
        writer.println("AdvancedProtectionConfigLoader:");
        writer.println("  System Config:");

        // Protections defined in the config.
        writer.print("    All defined protections: [");
        writer.print(
                mSystemConfig.getAvailableProtections().getProtection().stream()
                        .map(protection -> protection.getId().getRawName())
                        .sorted()
                        .collect(Collectors.joining(", ")));
        writer.println("]");

        // Computed protections based on availability.
        writer.print("    Available protections: [");
        writer.print(
                mAvailableProtections.stream()
                        .map(AdvancedProtectionManager::featureIdToString)
                        .sorted()
                        .collect(Collectors.joining(", ")));
        writer.println("]");
    }

    public static class Injector {
        Injector() {}

        /**
         * Reads system config.
         *
         * @return input stream with system-defined config, or null if not configured.
         */
        @Nullable
        public InputStream readSystemConfig() throws IOException {
            File file = getSystemConfigFile();
            return file.exists() ? new FileInputStream(file) : null;
        }
    }
}
