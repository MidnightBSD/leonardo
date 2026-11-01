/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.RequestAuthenticator.AuthOutcome;
import org.midnightbsd.leonardo.auth.keys.ApiKeyStore;
import org.midnightbsd.leonardo.auth.sigv2.SigV2VerifierImpl;
import org.midnightbsd.leonardo.auth.sigv4.SigV4VerifierImpl;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failure classification ({@code InvalidAccessKeyId} vs
 * {@code SignatureDoesNotMatch} vs {@code AccessDenied}) and SigV2
 * query-string presigned URLs.
 */
final class RequestAuthenticatorFailureReasonTest {

    private static final String ACCESS_KEY = "AKIATESTLEONARDO00001";
    private static final String SECRET_KEY = "LeonardoTestSecret0000000000000000000001";
    private static final String IDENTITY   = "test-user";

    private ApiKeyStore keyStore;
    private RequestAuthenticatorImpl authenticator;

    @BeforeEach
    void setUp() {
        keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));
        when(keyStore.findByAccessKey("AKIADISABLED000000001"))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey("AKIADISABLED000000001", SECRET_KEY, "off", false)));
        when(keyStore.findByAccessKey("AKIAUNKNOWN0000000001")).thenReturn(Optional.empty());
        authenticator = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore), new SigV2VerifierImpl(keyStore), keyStore, false, true);
    }

    // -------------------------------------------------------------------------
    // Failure reasons
    // -------------------------------------------------------------------------

    @Test
    void anonymousRequestIsAccessDenied() {
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", URI.create("http://s3.local/bucket/key"), new HashMap<>());
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.failure().code()).isEqualTo("AccessDenied");
        assertThat(outcome.failure().httpStatus()).isEqualTo(403);
    }

    @Test
    void unknownSigV4AccessKeyIsInvalidAccessKeyId() {
        final Map<String, List<String>> headers = sigV4Headers("AKIAUNKNOWN0000000001");
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", URI.create("http://s3.local/bucket"), headers);
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.failure().code()).isEqualTo("InvalidAccessKeyId");
    }

    @Test
    void disabledAccessKeyIsInvalidAccessKeyId() {
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", URI.create("http://s3.local/bucket"), sigV4Headers("AKIADISABLED000000001"));
        assertThat(outcome.failure().code()).isEqualTo("InvalidAccessKeyId");
    }

    @Test
    void knownKeyWithBadSignatureIsSignatureDoesNotMatch() {
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", URI.create("http://s3.local/bucket"), sigV4Headers(ACCESS_KEY));
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.failure().code()).isEqualTo("SignatureDoesNotMatch");
    }

    @Test
    void unknownSigV2AccessKeyIsInvalidAccessKeyId() {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("date", List.of("Mon, 25 May 2026 12:00:00 GMT"));
        headers.put("authorization", List.of("AWS AKIAUNKNOWN0000000001:c2lnbmF0dXJl"));
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", URI.create("http://s3.local/bucket/key"), headers);
        assertThat(outcome.failure().code()).isEqualTo("InvalidAccessKeyId");
    }

    @Test
    void expiredSigV4PresignedUrlIsReportedAsExpired() {
        final URI uri = URI.create("http://s3.local/bucket/key"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + ACCESS_KEY + "%2F20200101%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20200101T000000Z&X-Amz-Expires=60"
                + "&X-Amz-SignedHeaders=host&X-Amz-Signature=deadbeef");
        final AuthOutcome outcome = authenticator.authenticateOrExplain("GET", uri, new HashMap<>());
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.failure().code()).isEqualTo("AccessDenied");
        assertThat(outcome.failure().message()).contains("expired");
    }

    @Test
    void successfulRequestCarriesTheIdentity() {
        final URI uri = URI.create("http://s3.local/bucket/key");
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final AuthOutcome outcome = authenticator.authenticateOrExplain(
                "GET", sigV2Presigned(uri, ACCESS_KEY, SECRET_KEY, expires), new HashMap<>());
        assertThat(outcome.authenticated()).isTrue();
        assertThat(outcome.result().identity()).isEqualTo(IDENTITY);
        assertThat(outcome.failure()).isNull();
    }

    // -------------------------------------------------------------------------
    // SigV2 presigned URLs
    // -------------------------------------------------------------------------

    @Test
    void sigV2PresignedUrlAuthenticates() {
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final URI signed = sigV2Presigned(URI.create("http://s3.local/bucket/my%20key"),
                ACCESS_KEY, SECRET_KEY, expires);
        assertThat(authenticator.authenticate("GET", signed, new HashMap<>())).contains(IDENTITY);
    }

    @Test
    void sigV2SignatureCoversResponseHeaderOverrides() throws Exception {
        // response-content-disposition is part of the CanonicalizedResource, so a
        // link signed without it must not accept one added afterwards, and a link
        // signed with it must verify.
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final URI plain = sigV2Presigned(URI.create("http://s3.local/bucket/key"), ACCESS_KEY, SECRET_KEY, expires);
        final URI tampered = URI.create(plain + "&response-content-disposition=attachment");
        assertThat(authenticator.authenticate("GET", tampered, new HashMap<>())).isEmpty();

        final String resource = "/bucket/key?response-content-disposition=attachment";
        final String sig = SigV2VerifierImpl.hmacSha1Base64(SECRET_KEY, "GET\n\n\n" + expires + "\n" + resource);
        final URI signedWithOverride = URI.create("http://s3.local/bucket/key?response-content-disposition=attachment"
                + "&AWSAccessKeyId=" + ACCESS_KEY + "&Expires=" + expires
                + "&Signature=" + URLEncoder.encode(sig, StandardCharsets.UTF_8));
        assertThat(authenticator.authenticate("GET", signedWithOverride, new HashMap<>())).contains(IDENTITY);
    }

    @Test
    void sigV2RejectsEncodedOrRepeatedSignedParameters() throws Exception {
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final URI plain = sigV2Presigned(URI.create("http://s3.local/bucket/key"), ACCESS_KEY, SECRET_KEY, expires);
        // a percent-encoded key decodes to a signed parameter on the server side
        final URI encodedKey = URI.create(plain + "&response%2Dcontent-type=text/html");
        assertThat(RequestAuthenticatorImpl.buildCanonicalizedResource(encodedKey))
                .isEqualTo("/bucket/key?response-content-type=text/html");
        assertThat(authenticator.authenticate("GET", encodedKey, new HashMap<>())).isEmpty();

        // a repeated signed parameter is ambiguous and never verifies, however it is signed
        final String resource = "/bucket/key?response-content-type=text/plain";
        final String sig = SigV2VerifierImpl.hmacSha1Base64(SECRET_KEY, "GET\n\n\n" + expires + "\n" + resource);
        final URI repeated = URI.create("http://s3.local/bucket/key?response-content-type=text/html"
                + "&response-content-type=text/plain&AWSAccessKeyId=" + ACCESS_KEY + "&Expires=" + expires
                + "&Signature=" + URLEncoder.encode(sig, StandardCharsets.UTF_8));
        assertThat(authenticator.authenticate("GET", repeated, new HashMap<>())).isEmpty();
        assertThat(RequestAuthenticatorImpl.buildCanonicalizedResource(repeated)).isNull();

        // decoded values are what gets signed, as botocore does it
        assertThat(RequestAuthenticatorImpl.buildCanonicalizedResource(
                URI.create("http://s3.local/b/k?response-content-type=text%2Fplain&versionId=v1&x=1")))
                .isEqualTo("/b/k?response-content-type=text/plain&versionId=v1");
    }

    @Test
    void expiredSigV2PresignedUrlIsRejected() {
        final URI signed = sigV2Presigned(URI.create("http://s3.local/bucket/key"),
                ACCESS_KEY, SECRET_KEY, 1_000_000L);
        assertThat(authenticator.authenticate("GET", signed, new HashMap<>())).isEmpty();
        assertThat(authenticator.authenticateOrExplain("GET", signed, new HashMap<>())
                .failure().message()).contains("expired");
    }

    @Test
    void sigV2PresignedUrlWithWrongSecretIsSignatureDoesNotMatch() {
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final URI signed = sigV2Presigned(URI.create("http://s3.local/bucket/key"),
                ACCESS_KEY, "not-the-secret", expires);
        final AuthOutcome outcome = authenticator.authenticateOrExplain("GET", signed, new HashMap<>());
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.failure().code()).isEqualTo("SignatureDoesNotMatch");
    }

    @Test
    void sigV2PresignedUrlRejectedWhenSigV2Disabled() {
        final RequestAuthenticatorImpl noV2 = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore), new SigV2VerifierImpl(keyStore), keyStore, false, false);
        final long expires = System.currentTimeMillis() / 1000 + 300;
        final URI signed = sigV2Presigned(URI.create("http://s3.local/bucket/key"),
                ACCESS_KEY, SECRET_KEY, expires);
        assertThat(noV2.authenticate("GET", signed, new HashMap<>())).isEmpty();
        assertThat(noV2.authenticateOrExplain("GET", signed, new HashMap<>()).failure().code())
                .isEqualTo("AccessDenied");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, List<String>> sigV4Headers(final String accessKey) {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("host", List.of("s3.local"));
        headers.put("x-amz-date", List.of("20260525T120000Z"));
        headers.put("x-amz-content-sha256", List.of("UNSIGNED-PAYLOAD"));
        headers.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=" + accessKey
                + "/20260525/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=0000000000000000000000000000000000000000000000000000000000000000"));
        return headers;
    }

    /** Signs a URL the way the AWS SDK v1 / boto2 did for SigV2 query authentication. */
    private static URI sigV2Presigned(final URI uri, final String accessKey, final String secret,
                                      final long expires) {
        final String stringToSign = "GET\n\n\n" + expires + "\n" + uri.getRawPath();
        final String sig;
        try {
            sig = SigV2VerifierImpl.hmacSha1Base64(secret, stringToSign);
        } catch (final java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
        return URI.create(uri + "?AWSAccessKeyId=" + accessKey + "&Expires=" + expires
                + "&Signature=" + URLEncoder.encode(sig, StandardCharsets.UTF_8));
    }
}
