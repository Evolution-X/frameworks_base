/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Covers the diagnostic logic of the Duck RKP subsystem: the jitter on the retry schedule, the
 * retryable classification and the reason codes reported when a certificate response is
 * rejected. None of it changes whether provisioning succeeds or fails.
 */
public class DuckRkpCapabilityTest {
    // Mirrors RETRY_MS and MAX_RETRY_MS in DuckRkpManager; update both places together.
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long MAX_RETRY_MS = TimeUnit.HOURS.toMillis(6);

    private static long baseDelay(int failures) {
        return Math.min(MAX_RETRY_MS, RETRY_MS * (1L << Math.min(4, Math.max(0, failures - 1))));
    }

    @Test
    public void retryDelayStaysWithinTenPercentOfTheSchedule() {
        for (int failures = 0; failures <= 8; failures++) {
            long base = baseDelay(failures);
            for (int sample = 0; sample < 64; sample++) {
                long delay = DuckRkpManager.retryDelay(failures);
                assertTrue("retried earlier than the schedule: " + delay + " < " + base,
                        delay >= base - base / 10);
                assertTrue("retried later than the schedule: " + delay + " > " + base,
                        delay <= base + base / 10 + 1);
            }
        }
    }

    @Test
    public void retryDelaySaturatesAtTheMaximum() {
        long ceiling = MAX_RETRY_MS + MAX_RETRY_MS / 10 + 1;
        for (int failures = 5; failures <= 9; failures++) {
            long delay = DuckRkpManager.retryDelay(failures);
            assertTrue("retry delay is not capped: " + delay, delay <= ceiling);
        }
    }

    @Test
    public void retryableClassification() {
        assertTrue(DuckRkpManager.isRetryable("network_error", 0));
        assertTrue(DuckRkpManager.isRetryable("hal_unavailable", 0));
        assertTrue(DuckRkpManager.isRetryable("http_error", 408));
        assertTrue(DuckRkpManager.isRetryable("http_error", 429));
        assertTrue(DuckRkpManager.isRetryable("http_error", 503));
        assertFalse(DuckRkpManager.isRetryable("http_error", 400));
        assertFalse(DuckRkpManager.isRetryable("http_error", 404));
        assertFalse(DuckRkpManager.isRetryable("chain_invalid", 0));
        assertFalse(DuckRkpManager.isRetryable("cert_expired", 0));
    }

    @Test
    public void rejectedCertificateReasons() {
        assertEquals("untrusted_root", DuckRkpCertificates.classify(
                new IllegalArgumentException("Untrusted attestation root")));
        assertEquals("no_key_match", DuckRkpCertificates.classify(
                new IllegalArgumentException("No certificate matches the key")));
        assertEquals("chain_invalid", DuckRkpCertificates.classify(
                new IllegalArgumentException("Certificate cycle")));
        assertEquals("chain_invalid", DuckRkpCertificates.classify(
                new IllegalArgumentException("Missing issuer")));
        assertEquals("cert_expired", DuckRkpCertificates.classify(
                new CertificateExpiredException()));
        assertEquals("cert_expired", DuckRkpCertificates.classify(
                new CertificateNotYetValidException()));
    }
}
