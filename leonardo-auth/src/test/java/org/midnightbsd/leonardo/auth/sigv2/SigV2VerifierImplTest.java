/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth.sigv2;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.keys.ApiKeyStore;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SigV2VerifierImpl}.
 *
 * <p>The expected HMAC-SHA1 values were generated offline using the standard
 * Python {@code hmac} library against the same inputs to provide an independent
 * reference.
 */
class SigV2VerifierImplTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String IDENTITY   = "test-alice";

    /**
     * Verifies a known-good SigV2 signature.
     *
     * <p>String-to-sign matches the example from:
     * https://docs.aws.amazon.com/AmazonS3/latest/userguide/RESTAuthentication.html
     * <pre>
     * GET\n\n\nTue, 27 Mar 2007 19:36:42 +0000\n/johnsmith/photos/puppy.jpg
     * </pre>
     * Expected signature: bWq2s1WEIj+Ydj0vQ697zp+IXMU=
     */
    @Test
    void verifyKnownGoodSignature() throws NoSuchAlgorithmException, InvalidKeyException {
        final String stringToSign =
                "GET\n\n\nTue, 27 Mar 2007 19:36:42 +0000\n/johnsmith/photos/puppy.jpg";

        final String expectedSig = SigV2VerifierImpl.hmacSha1Base64(SECRET_KEY, stringToSign);
        // Cross-check with known value from AWS docs
        assertThat(expectedSig).isEqualTo("bWq2s1WEIj+Ydj0vQ697zp+IXMU=");

        final SigV2Verifier.SigV2Request request =
                new SigV2Verifier.SigV2Request(ACCESS_KEY, expectedSig, stringToSign);

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));

        assertThat(new SigV2VerifierImpl(keyStore).verify(request)).contains(IDENTITY);
    }

    @Test
    void rejectsWrongSignature() {
        final String stringToSign = "GET\n\n\nTue, 27 Mar 2007 19:36:42 +0000\n/bucket/key";
        final SigV2Verifier.SigV2Request request =
                new SigV2Verifier.SigV2Request(ACCESS_KEY, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA=", stringToSign);

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));

        assertThat(new SigV2VerifierImpl(keyStore).verify(request)).isEmpty();
    }

    @Test
    void rejectsUnknownAccessKey() {
        final SigV2Verifier.SigV2Request request =
                new SigV2Verifier.SigV2Request("UNKNOWN", "sig", "sts");

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey("UNKNOWN")).thenReturn(Optional.empty());

        assertThat(new SigV2VerifierImpl(keyStore).verify(request)).isEmpty();
    }
}
