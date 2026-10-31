package android.security.trickystore;

import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1InputStream;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.*;

/**
 * @hide
 */
public final class AttestationUtils {
    private static final String TAG = "AttestationUtils";

    private static final File CONFIG_DIR = new File("/data/adb/tricky_store");
    private static final File BOOT_KEY_FILE = new File(CONFIG_DIR, "boot_key");
    private static final File HBK_FILE = new File(CONFIG_DIR, "hbk");

    private static volatile byte[] sBootKey;
    private static volatile byte[] sBootHash;
    private static volatile boolean sTeeBroken = false;
    // True while the cached boot key/hash was derived from the keybox, so it must be
    // re-derived when the keybox changes. Persisted random values are not affected.
    private static volatile boolean sBootKeyFallback;
    private static volatile boolean sBootHashFallback;

    private AttestationUtils() {}

    public static void setTeeBroken(boolean broken) {
        sTeeBroken = broken;
    }

    public static boolean isTeeBroken() {
        return sTeeBroken;
    }

    /**
     * Called after a keybox replacement succeeds, not after a failed fetch. Deliberately not
     * synchronized: the keybox is replaced from inside the service's own locks, and
     * getBootKey()/getBootHash() take this class's lock before calling into the service.
     */
    public static void invalidateFallbackBootState() {
        if (sBootKeyFallback) sBootKey = null;
        if (sBootHashFallback) sBootHash = null;
    }

    public static byte[] getBootKey() {
        byte[] key = sBootKey;
        if (key != null) return key;
        synchronized (AttestationUtils.class) {
            key = sBootKey;
            if (key == null) {
                key = getBootKeyFromProp();
                boolean fromKeybox = false;
                if (key == null) {
                    key = getBootKeyFromKeybox();
                    fromKeybox = key != null;
                }
                if (key == null) key = loadOrCreatePersisted(BOOT_KEY_FILE);
                sBootKeyFallback = fromKeybox;
                sBootKey = key;
            }
        }
        return key;
    }

    /**
     * Derives a fallback verified-boot hash from the active keybox's issuer certificate, used
     * ahead of the random fallback so that, when the vbmeta prop is missing, nothing persisted
     * exists and the TEE is broken, the RootOfTrust hash is still deterministic for a given
     * keybox instead of changing on every boot. Domain-separated from the boot KEY digest
     * (a plain SHA-256 of the same cert) so the two fields don't collide.
     * Returns null if no keybox is loaded.
     */
    private static byte[] deriveBootHashFromKeybox() {
        try {
            KeyBoxManager keyBoxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            if (keyBoxManager == null || !keyBoxManager.hasKeyboxes()) {
                return null;
            }
            KeyBoxManager.KeyBox keybox = keyBoxManager.getKeybox("EC");
            if (keybox == null) {
                keybox = keyBoxManager.getKeybox("RSA");
            }
            if (keybox == null || keybox.certificates == null || keybox.certificates.isEmpty()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("boothash".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return digest.digest(keybox.certificates.get(0).getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "Failed to derive boot hash from keybox", e);
            return null;
        }
    }

    /**
     * Derives a fallback verified-boot key from the active keybox's issuer certificate,
     * so that when the real vbmeta property is unavailable the attested key is at least
     * deterministic and tied to whatever keybox is actually signing the chain, rather than
     * an arbitrary persisted random value with no relation to anything else we attest.
     * Returns null if no keybox is currently loaded.
     */
    private static byte[] getBootKeyFromKeybox() {
        try {
            KeyBoxManager keyBoxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            if (keyBoxManager == null || !keyBoxManager.hasKeyboxes()) {
                return null;
            }
            KeyBoxManager.KeyBox keybox = keyBoxManager.getKeybox("EC");
            if (keybox == null) {
                keybox = keyBoxManager.getKeybox("RSA");
            }
            if (keybox == null || keybox.certificates == null || keybox.certificates.isEmpty()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(keybox.certificates.get(0).getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "Failed to derive boot key from keybox issuer", e);
            return null;
        }
    }

    /**
     * Reads the real verified-boot public key digest from the bootloader property, so the
     * RootOfTrust key we attest matches the device's actual verified-boot chain instead of a
     * value with no relation to it. Falls back to the persisted random value only when the
     * property is unavailable.
     */
    private static byte[] getBootKeyFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.public_key_digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.public_key_digest", e);
            return null;
        }
    }

    public static byte[] getBootHash() {
        byte[] hash = sBootHash;
        if (hash != null) return hash;
        synchronized (AttestationUtils.class) {
            hash = sBootHash;
            if (hash == null) {
                hash = getBootHashFromProp();
                if (hash == null) hash = readPersisted(HBK_FILE);
                if (hash == null && !sTeeBroken) {
                    hash = extractBootHashFromTee();
                    if (hash != null) writePersisted(HBK_FILE, hash);
                }
                boolean fromKeybox = false;
                if (hash == null) {
                    hash = deriveBootHashFromKeybox();
                    fromKeybox = hash != null;
                }
                if (hash == null) {
                    Log.w(TAG, "Failed to get boot hash from prop, disk, TEE, and keybox, using random bytes");
                    hash = generateRandomBytes(32);
                    writePersisted(HBK_FILE, hash);
                }
                sBootHashFallback = fromKeybox;
                sBootHash = hash;
            }
        }
        return hash;
    }

    public static void initBootHash() {
        Log.i(TAG, "initBootHash: Starting boot hash initialization");
        byte[] hash = getBootHashFromProp();
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: Boot hash already set from prop: " + bytesToHex(hash));
            return;
        }
        hash = readPersisted(HBK_FILE);
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: Boot hash loaded from disk: " + bytesToHex(hash));
            return;
        }
        Log.i(TAG, "initBootHash: No prop or disk state, attempting TEE extraction");
        hash = extractBootHashFromTee();
        if (hash != null) {
            sBootHashFallback = false;
            sBootHash = hash;
            writePersisted(HBK_FILE, hash);
            Log.i(TAG, "initBootHash: TEE extraction successful, setting prop");
            setVbmetaDigestProp(bytesToHex(hash));
        } else {
            hash = deriveBootHashFromKeybox();
            if (hash != null) {
                sBootHashFallback = true;
                sBootHash = hash;
                Log.w(TAG, "initBootHash: TEE extraction failed, using keybox-derived boot hash");
            } else {
                Log.e(TAG, "initBootHash: Failed to extract boot hash from TEE");
            }
        }
    }

    private static void setVbmetaDigestProp(String digest) {
        try {
            SystemProperties.set("ro.boot.vbmeta.digest", digest);
            Log.i(TAG, "Set ro.boot.vbmeta.digest property to TEE boot hash");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set vbmeta.digest property", e);
        }
    }

    private static byte[] extractBootHashFromTee() {
        final String alias = "trickystore_attestation_key";
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            // A stale probe must never influence the next probe's result.
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[32])
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();

            java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
            if (chain == null || chain.length == 0
                    || !(chain[0] instanceof X509Certificate)) {
                Log.w(TAG, "TEE probe did not return an X.509 leaf");
                return null;
            }
            byte[] extValue = ((X509Certificate) chain[0]).getExtensionValue(
                    "1.3.6.1.4.1.11129.2.1.17");
            if (extValue == null) {
                Log.w(TAG, "TEE probe leaf has no attestation extension");
                return null;
            }

            try (ASN1InputStream extStream = new ASN1InputStream(extValue)) {
                ASN1OctetString octet = ASN1OctetString.getInstance(extStream.readObject());
                try (ASN1InputStream descriptionStream =
                        new ASN1InputStream(octet.getOctets())) {
                    ASN1Sequence description = ASN1Sequence.getInstance(
                            descriptionStream.readObject());
                    if (description.size() < 8) return null;
                    ASN1Sequence tee = ASN1Sequence.getInstance(description.getObjectAt(7));
                    for (int i = 0; i < tee.size(); i++) {
                        ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(tee.getObjectAt(i));
                        if (tagged.getTagNo() != 704) continue;
                        ASN1Sequence rootOfTrust = ASN1Sequence.getInstance(
                                tagged.getBaseObject());
                        // The fourth RootOfTrust element is optional on older Keymaster.
                        if (rootOfTrust.size() < 4) {
                            Log.i(TAG, "Older Keymaster attestation has no verifiedBootHash");
                            return null;
                        }
                        byte[] hash = ASN1OctetString.getInstance(
                                rootOfTrust.getObjectAt(3)).getOctets();
                        if (hash.length != 32) {
                            Log.w(TAG, "Unexpected verifiedBootHash length: " + hash.length);
                            return null;
                        }
                        return hash;
                    }
                }
            }
            Log.w(TAG, "TEE attestation did not expose a RootOfTrust");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "TEE boot hash probe failed", e);
            return null;
        } finally {
            if (ks != null) {
                try {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove temporary TEE probe key", e);
                }
            }
        }
    }

    public static byte[] getBootHashFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.digest", e);
            return null;
        }
    }

    public static int getOsVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q: return 100000;
            case Build.VERSION_CODES.R: return 110000;
            case Build.VERSION_CODES.S: return 120000;
            case Build.VERSION_CODES.S_V2: return 120100;
            case Build.VERSION_CODES.TIRAMISU: return 130000;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE: return 140000;
            case Build.VERSION_CODES.VANILLA_ICE_CREAM: return 150000;
            default: return (Build.VERSION.SDK_INT - 20) * 10000;
        }
    }

    public static int getAttestVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q:
            case Build.VERSION_CODES.R:
                return 4;
            case Build.VERSION_CODES.S:
            case Build.VERSION_CODES.S_V2:
                return 100;
            case Build.VERSION_CODES.TIRAMISU:
                return 200;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE:
            case Build.VERSION_CODES.VANILLA_ICE_CREAM:
                return 300;
            default:
                return 400;
        }
    }

    public static int getKeymasterVersion() {
        int attestVersion = getAttestVersion();
        return attestVersion == 4 ? 41 : attestVersion;
    }

    /**
     * Resolve the three patch levels from a single configuration snapshot.
     * The result is [system (YYYYMM), vendor (YYYYMMDD), boot (YYYYMMDD)].
     */
    public static int[] getPatchLevels(String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
                TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        int system = getPatchLevelFromConfig(customLevel, false);
        int vendor = getVendorPatchLevelFromConfig(customLevel, true);
        int boot = getBootPatchLevelFromConfig(customLevel, true);
        return new int[] {system, vendor, boot};
    }

    private static int getPatchLevelFromConfig(
            TrickyStoreService.CustomPatchLevel level, boolean isLong) {
        if (level != null && level.system != null) {
            Integer parsed = parsePatchLevel(level.system, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    private static int getVendorPatchLevelFromConfig(
            TrickyStoreService.CustomPatchLevel level, boolean isLong) {
        if (level != null && level.vendor != null) {
            Integer parsed = parsePatchLevel(level.vendor, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    private static int getBootPatchLevelFromConfig(
            TrickyStoreService.CustomPatchLevel level, boolean isLong) {
        if (level != null && level.boot != null) {
            Integer parsed = parsePatchLevel(level.boot, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getPatchLevel(boolean isLong) {
        return getPatchLevel(isLong, null);
    }

    public static int getPatchLevel(boolean isLong, String[] packages) {
        return getPatchLevelFromConfig(
                TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages), isLong);
    }

    public static int getVendorPatchLevel(boolean isLong) {
        return getVendorPatchLevel(isLong, null);
    }

    public static int getVendorPatchLevel(boolean isLong, String[] packages) {
        return getVendorPatchLevelFromConfig(
                TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages), isLong);
    }

    public static int getBootPatchLevel(boolean isLong) {
        return getBootPatchLevel(isLong, null);
    }

    public static int getBootPatchLevel(boolean isLong, String[] packages) {
        return getBootPatchLevelFromConfig(
                TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages), isLong);
    }

    private static Integer parsePatchLevel(String value, boolean isLong) {
        if (value == null || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("prop")) {
            return null;
        }

        String normalized = value.trim().replace("-", "");
        try {
            if (normalized.length() == 8 || normalized.length() == 6) {
                int year = Integer.parseInt(normalized.substring(0, 4));
                int month = Integer.parseInt(normalized.substring(4, 6));
                // A month-only value has no day; KeyMint's long form needs a real one.
                int day = normalized.length() == 8
                        ? Integer.parseInt(normalized.substring(6, 8)) : 1;
                if (!isValidPatchDate(year, month, day)) {
                    Log.e(TAG, "Invalid patch level date: " + value);
                    return null;
                }
                return isLong ? year * 10000 + month * 100 + day : year * 100 + month;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse patch level: " + value, e);
        }
        return null;
    }

    private static boolean isValidPatchDate(int year, int month, int day) {
        return year >= 1 && month >= 1 && month <= 12 && day >= 1
                && day <= java.time.YearMonth.of(year, month).lengthOfMonth();
    }

    public static int convertPatchLevel(String patchString, boolean isLong) {
        try {
            String[] parts = patchString.split("-");
            if (parts.length >= 2) {
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int day = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
                if (isValidPatchDate(year, month, day)) {
                    return isLong ? year * 10000 + month * 100 + day : year * 100 + month;
                }
            }
        } catch (Exception e) {
            // Falls through to the current date below.
        }
        Log.e(TAG, "Invalid patch level format: " + patchString);
        java.util.Calendar now = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        int year = now.get(java.util.Calendar.YEAR);
        int month = now.get(java.util.Calendar.MONTH) + 1;
        return isLong ? year * 10000 + month * 100 + 1 : year * 100 + month;
    }

    public static byte[] computeModuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(new byte[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute module hash", e);
            return new byte[32];
        }
    }

    private static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Loads a persisted value from {@code file}, generating and persisting
     * a fresh 32-byte random value on first run so it stays stable across
     * daemon restarts and reboots instead of being re-randomized every time.
     */
    private static byte[] loadOrCreatePersisted(File file) {
        byte[] existing = readPersisted(file);
        if (existing != null) {
            return existing;
        }
        byte[] fresh = generateRandomBytes(32);
        writePersisted(file, fresh);
        return fresh;
    }

    private static byte[] readPersisted(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length == 32) {
                return data;
            }
            Log.w(TAG, "Persisted value at " + file + " has unexpected length "
                    + data.length + ", ignoring");
        } catch (IOException e) {
            Log.w(TAG, "Failed to read persisted value from " + file, e);
        }
        return null;
    }

    private static void writePersisted(File file, byte[] data) {
        try {
            if (!CONFIG_DIR.isDirectory() && !CONFIG_DIR.mkdirs()) {
                Log.w(TAG, "Failed to create " + CONFIG_DIR);
                return;
            }
            File tmp = new File(CONFIG_DIR, file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Failed to rename " + tmp + " to " + file);
            } else {
                file.setReadable(true, true);
                file.setWritable(true, true);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist value to " + file, e);
        }
    }

    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) throw new IllegalArgumentException("Odd hexadecimal length");
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal character");
            }
            data[i / 2] = (byte) ((hi << 4) | lo);
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
