/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth.sigv4;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.keys.ApiKeyStore;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SigV4VerifierImpl} using official AWS SigV4 test vectors.
 *
 * <p>Test vectors are taken from the AWS Signature Version 4 Test Suite at
 * https://docs.aws.amazon.com/general/latest/gr/sigv4-test-suite.html
 * (get-vanilla example).
 */
class SigV4VerifierImplTest {

    // Official AWS test vector credentials
    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String IDENTITY   = "test-user";

    private static final String DATE    = "20150830";
    private static final String REGION  = "us-east-1";
    private static final String SERVICE = "service";  // "service" in the official test vector

    /**
     * Official AWS SigV4 test vector: GET request to a hypothetical service.
     *
     * <p>Canonical request:
     * <pre>
     * GET
     * /
     *
     * host:example.amazonaws.com
     * x-amz-date:20150830T123600Z
     *
     * host;x-amz-date
     * e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
     * </pre>
     *
     * <p>Expected signature from the AWS test suite.
     */
    @Test
    void verifyKnownGoodSignature() {
        final String stringToSign =
                "AWS4-HMAC-SHA256\n"
                + "20150830T123600Z\n"
                + "20150830/us-east-1/service/aws4_request\n"
                + "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63";

        final String expectedSignature =
                "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31";

        final SigV4Verifier.SigV4Request request = new SigV4Verifier.SigV4Request(
                ACCESS_KEY, DATE, REGION, SERVICE,
                "host;x-amz-date", expectedSignature, stringToSign);

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));

        final SigV4VerifierImpl verifier = new SigV4VerifierImpl(keyStore);
        assertThat(verifier.verify(request)).contains(IDENTITY);
    }

    @Test
    void rejectsWrongSignature() {
        final String stringToSign =
                "AWS4-HMAC-SHA256\n"
                + "20150830T123600Z\n"
                + "20150830/us-east-1/service/aws4_request\n"
                + "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63";

        final SigV4Verifier.SigV4Request request = new SigV4Verifier.SigV4Request(
                ACCESS_KEY, DATE, REGION, SERVICE, "host;x-amz-date",
                "0000000000000000000000000000000000000000000000000000000000000000",
                stringToSign);

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));

        assertThat(new SigV4VerifierImpl(keyStore).verify(request)).isEmpty();
    }

    @Test
    void rejectsUnknownAccessKey() {
        final SigV4Verifier.SigV4Request request = new SigV4Verifier.SigV4Request(
                "UNKNOWN", DATE, REGION, SERVICE, "host", "sig", "sts");

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey("UNKNOWN")).thenReturn(Optional.empty());

        assertThat(new SigV4VerifierImpl(keyStore).verify(request)).isEmpty();
    }

    @Test
    void rejectsDisabledKey() {
        final SigV4Verifier.SigV4Request request = new SigV4Verifier.SigV4Request(
                ACCESS_KEY, DATE, REGION, SERVICE, "host", "sig", "sts");

        final ApiKeyStore keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, false)));
        // findByAccessKey in YamlApiKeyStore returns empty for disabled keys; mock the interface contract
        when(keyStore.findByAccessKey(ACCESS_KEY)).thenReturn(Optional.empty());

        assertThat(new SigV4VerifierImpl(keyStore).verify(request)).isEmpty();
    }

    /** Verify the signing key derivation matches the published AWS test vector intermediate values. */
    @Test
    void signingKeyDerivation() throws NoSuchAlgorithmException, InvalidKeyException {
        // From the AWS SigV4 signing test page:
        // kDate    = HMAC-SHA256("AWS4wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830")
        // kRegion  = HMAC-SHA256(kDate, "us-east-1")
        // kService = HMAC-SHA256(kRegion, "iam")
        // kSigning = HMAC-SHA256(kService, "aws4_request")
        // Expected kSigning (hex): c4afb1cc5771d871763a393d9b7d4b465832ec1c98e3226b81ed243d821a3e3c
        final byte[] signingKey = SigV4VerifierImpl.deriveSigningKey(
                SECRET_KEY, "20150830", "us-east-1", "iam");
        assertThat(HexFormat.of().formatHex(signingKey))
                .isEqualTo("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9");
    }
}
