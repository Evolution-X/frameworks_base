/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Helper utility class for managing custom App Lock credentials (PIN, Password, Pattern)
 * independent of system device lock screen settings.
 *
 * @hide
 */
public class AppLockCredentialUtils {

    public static final int CREDENTIAL_TYPE_DEVICE = 0;
    public static final int CREDENTIAL_TYPE_PIN = 1;
    public static final int CREDENTIAL_TYPE_PASSWORD = 2;
    public static final int CREDENTIAL_TYPE_PATTERN = 3;

    public static final String KEY_APP_LOCK_CREDENTIAL_TYPE = "app_lock_credential_type";
    public static final String KEY_APP_LOCK_CREDENTIAL_HASH = "app_lock_credential_hash";
    public static final String KEY_APP_LOCK_CREDENTIAL_SALT = "app_lock_credential_salt";
    public static final String KEY_APP_LOCK_BIOMETRICS_ALLOWED = "app_lock_biometrics_allowed";
    public static final String KEY_APP_LOCK_TIMEOUT = "app_lock_timeout";

    /**
     * Returns the currently active credential type for App Lock.
     */
    public static int getCredentialType(Context context, int userId) {
        return Settings.Secure.getIntForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_TYPE,
                CREDENTIAL_TYPE_DEVICE,
                userId
        );
    }

    /**
     * Checks if App Lock is secured either by device lock or custom App Lock credential.
     */
    public static boolean isAppLockSecure(Context context, int userId) {
        int type = getCredentialType(context, userId);
        if (type == CREDENTIAL_TYPE_DEVICE) {
            KeyguardManager km = context.getSystemService(KeyguardManager.class);
            return km != null && km.isDeviceSecure(userId);
        }
        String hash = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_HASH,
                userId
        );
        return !TextUtils.isEmpty(hash);
    }

    /**
     * Verifies the provided credential input against the stored salt + SHA-256 hash.
     */
    public static boolean verifyCredential(Context context, int userId, String input) {
        if (TextUtils.isEmpty(input)) {
            return false;
        }
        String storedHash = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_HASH,
                userId
        );
        String storedSalt = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_SALT,
                userId
        );
        if (TextUtils.isEmpty(storedHash) || TextUtils.isEmpty(storedSalt)) {
            return false;
        }
        String computedHash = hashCredential(storedSalt, input);
        return storedHash.equalsIgnoreCase(computedHash);
    }

    /**
     * Saves a new credential type and input. Generates a fresh random salt and stores salt + hash.
     */
    public static boolean saveCredential(Context context, int userId, int type, String input) {
        if (type == CREDENTIAL_TYPE_DEVICE) {
            clearCustomCredential(context, userId);
            return true;
        }
        if (TextUtils.isEmpty(input)) {
            return false;
        }
        String salt = generateSalt();
        String hash = hashCredential(salt, input);
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_SALT,
                salt,
                userId
        );
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_HASH,
                hash,
                userId
        );
        Settings.Secure.putIntForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_TYPE,
                type,
                userId
        );
        return true;
    }

    /**
     * Resets credential type to Device Lock and clears stored salt and hash.
     */
    public static void clearCustomCredential(Context context, int userId) {
        Settings.Secure.putIntForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_TYPE,
                CREDENTIAL_TYPE_DEVICE,
                userId
        );
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_HASH,
                "",
                userId
        );
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_CREDENTIAL_SALT,
                "",
                userId
        );
    }

    /**
     * Checks if biometrics are allowed to unlock custom App Lock credentials.
     */
    public static boolean isBiometricsAllowed(Context context, int userId) {
        return Settings.Secure.getIntForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_BIOMETRICS_ALLOWED,
                1,
                userId
        ) == 1;
    }

    /**
     * Checks if biometric hardware is available and enabled for App Lock.
     */
    public static boolean isBiometricEnabled(Context context, int userId) {
        if (!isBiometricsAllowed(context, userId)) {
            return false;
        }
        android.hardware.biometrics.BiometricManager bm = context.getSystemService(android.hardware.biometrics.BiometricManager.class);
        return bm != null && bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Sets whether biometrics are allowed to unlock custom App Lock credentials.
     */
    public static void setBiometricsAllowed(Context context, int userId, boolean allowed) {
        Settings.Secure.putIntForUser(
                context.getContentResolver(),
                KEY_APP_LOCK_BIOMETRICS_ALLOWED,
                allowed ? 1 : 0,
                userId
        );
    }

    /**
     * Computes SHA-256 hash of salt + input.
     */
    public static String hashCredential(String salt, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest((salt + ":" + input).getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Generates a 16-byte random hex salt.
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        StringBuilder sb = new StringBuilder();
        for (byte b : salt) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
