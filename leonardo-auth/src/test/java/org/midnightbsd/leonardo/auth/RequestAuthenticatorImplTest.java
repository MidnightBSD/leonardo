/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.keys.ApiKeyStore;
import org.midnightbsd.leonardo.auth.keys.YamlApiKeyStore;
import org.midnightbsd.leonardo.auth.sigv4.SigV4VerifierImpl;
import org.midnightbsd.leonardo.auth.sigv2.SigV2VerifierImpl;

import java.net.URI;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end unit tests for {@link RequestAuthenticatorImpl} that build
 * real canonical requests and verify against computed signatures — this is
 * the M1 request-signing test harness.
 *
 * <p>Tests use known credentials and compute expected signatures using the
 * same HMAC primitives as the verifiers, so any divergence between the
 * canonical-request builder and the verifier will be caught here.
 */
class RequestAuthenticatorImplTest {

    private static final String ACCESS_KEY = "AKIATESTLEONARDO00001";
    private static final String SECRET_KEY = "LeonardoTestSecret0000000000000000000001";
    private static final String IDENTITY   = "test-user";
    private static final String REGION     = "us-east-1";
    private static final String SERVICE    = "s3";
    private static final String DATE       = "20260525";
    private static final String TIMESTAMP  = "20260525T120000Z";

    private ApiKeyStore keyStore;
    private RequestAuthenticatorImpl authenticator;

    @BeforeEach
    void setUp() {
        keyStore = mock(ApiKeyStore.class);
        when(keyStore.findByAccessKey(ACCESS_KEY))
                .thenReturn(Optional.of(new ApiKeyStore.ApiKey(ACCESS_KEY, SECRET_KEY, IDENTITY, true)));
        authenticator = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore),
                new SigV2VerifierImpl(keyStore),
                false, true);
    }

    // -------------------------------------------------------------------------
    // SigV4
    // -------------------------------------------------------------------------

    @Test
    void sigV4GetBucketAuthenticates() throws Exception {
        final URI uri = URI.create("http://s3.local:9000/mybucket?list-type=2");
        final String payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // SHA-256("")

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("host",                  List.of("s3.local:9000"));
        headers.put("x-amz-date",            List.of(TIMESTAMP));
        headers.put("x-amz-content-sha256",  List.of(payloadHash));

        final String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        final String authHeader = buildSigV4Auth("GET", uri, headers, signedHeaders, payloadHash);
        headers.put("authorization", List.of(authHeader));

        assertThat(authenticator.authenticate("GET", uri, headers)).contains(IDENTITY);
    }

    @Test
    void sigV4UppercaseSignedHeadersAuthenticates() throws Exception {
        final URI uri = URI.create("http://s3.local:9000/mybucket");
        final String payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("host",                 List.of("s3.local:9000"));
        headers.put("x-amz-date",           List.of(TIMESTAMP));
        headers.put("x-amz-content-sha256", List.of(payloadHash));

        final String signedHeaders = "Host;X-Amz-Content-Sha256;X-Amz-Date";
        final String authHeader = buildSigV4Auth("GET", uri, headers, signedHeaders, payloadHash);
        headers.put("authorization", List.of(authHeader));

        assertThat(authenticator.authenticate("GET", uri, headers)).contains(IDENTITY);
    }

    @Test
    void sigV4RejectsTamperedRequest() throws Exception {
        final URI uri = URI.create("http://s3.local:9000/mybucket");
        final String payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("host",                 List.of("s3.local:9000"));
        headers.put("x-amz-date",           List.of(TIMESTAMP));
        headers.put("x-amz-content-sha256", List.of(payloadHash));

        final String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        final String authHeader = buildSigV4Auth("GET", uri, headers, signedHeaders, payloadHash);
        headers.put("authorization", List.of(authHeader));

        // Tamper: change URI after signing
        final URI tamperedUri = URI.create("http://s3.local:9000/otherbucket");
        assertThat(authenticator.authenticate("GET", tamperedUri, headers)).isEmpty();
    }

    @Test
    void sigV4RejectsMissingAuthHeader() {
        assertThat(authenticator.authenticate("GET",
                URI.create("http://s3.local/bucket"), Map.of())).isEmpty();
    }

    @Test
    void anonymousReadAllowedWhenConfigured() {
        final RequestAuthenticatorImpl anonOk = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore), new SigV2VerifierImpl(keyStore), true, true);
        assertThat(anonOk.authenticate("GET", URI.create("http://s3.local/bucket"), Map.of()))
                .contains("anonymous");
    }

    @Test
    void anonymousWriteRejectedEvenWhenAnonReadEnabled() {
        final RequestAuthenticatorImpl anonOk = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore), new SigV2VerifierImpl(keyStore), true, true);
        assertThat(anonOk.authenticate("PUT", URI.create("http://s3.local/bucket/key"), Map.of()))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // SigV2
    // -------------------------------------------------------------------------

    @Test
    void sigV2GetObjectAuthenticates() throws Exception {
        final URI uri = URI.create("http://s3.local:9000/mybucket/mykey");
        final String date = "Mon, 25 May 2026 12:00:00 GMT";

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("host", List.of("s3.local:9000"));
        headers.put("date", List.of(date));

        final String stringToSign = "GET\n\n\n" + date + "\n/mybucket/mykey";
        final String sig = org.midnightbsd.leonardo.auth.sigv2.SigV2VerifierImpl
                .hmacSha1Base64(SECRET_KEY, stringToSign);

        headers.put("authorization", List.of("AWS " + ACCESS_KEY + ":" + sig));

        assertThat(authenticator.authenticate("GET", uri, headers)).contains(IDENTITY);
    }

    @Test
    void sigV2RejectedWhenDisabled() throws Exception {
        final RequestAuthenticatorImpl noV2 = new RequestAuthenticatorImpl(
                new SigV4VerifierImpl(keyStore), new SigV2VerifierImpl(keyStore), false, false);

        final URI uri = URI.create("http://s3.local/bucket/key");
        final String date = "Mon, 25 May 2026 12:00:00 GMT";
        final String sts = "GET\n\n\n" + date + "\n/bucket/key";
        final String sig = SigV2VerifierImpl.hmacSha1Base64(SECRET_KEY, sts);

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("date",          List.of(date));
        headers.put("authorization", List.of("AWS " + ACCESS_KEY + ":" + sig));

        assertThat(noV2.authenticate("GET", uri, headers)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a real SigV4 Authorization header by computing the signature locally. */
    private String buildSigV4Auth(
            final String method, final URI uri,
            final Map<String, List<String>> headers,
            final String signedHeaders, final String payloadHash)
            throws NoSuchAlgorithmException, InvalidKeyException {

        final String credentialScope = DATE + "/" + REGION + "/" + SERVICE + "/aws4_request";
        final String canonicalRequest = buildCanonicalRequest(method, uri, headers, signedHeaders, payloadHash);
        final String hashedCR = RequestAuthenticatorImpl.sha256Hex(canonicalRequest);
        final String stringToSign = "AWS4-HMAC-SHA256\n" + TIMESTAMP + "\n" + credentialScope + "\n" + hashedCR;

        final byte[] signingKey = SigV4VerifierImpl.deriveSigningKey(SECRET_KEY, DATE, REGION, SERVICE);
        final byte[] sig = SigV4VerifierImpl.hmacSha256(signingKey, stringToSign);
        final String hexSig = java.util.HexFormat.of().formatHex(sig);

        return "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + hexSig;
    }

    private String buildCanonicalRequest(
            final String method, final URI uri,
            final Map<String, List<String>> headers,
            final String signedHeaders, final String payloadHash) {

        final String canonicalUri = canonicalizeUri(uri.getRawPath());
        final String canonicalQuery = canonicalizeQueryString(uri.getRawQuery());
        final StringBuilder hdrs = new StringBuilder();
        for (final String h : signedHeaders.split(";")) {
            final String lowerH = h.trim().toLowerCase(Locale.ROOT);
            final List<String> vals = headers.getOrDefault(lowerH, List.of());
            hdrs.append(lowerH).append(':').append(String.join(",", vals).trim()).append('\n');
        }
        return method.toUpperCase() + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + hdrs + "\n"
                + signedHeaders + "\n"
                + payloadHash;
    }

    private static String canonicalizeUri(final String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        final String[] segs = rawPath.split("/", -1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(RequestAuthenticatorImpl.uriEncode(segs[i]));
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static String canonicalizeQueryString(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        final Map<String, List<String>> params = new TreeMap<>();
        for (final String pair : rawQuery.split("&", -1)) {
            final int eq = pair.indexOf('=');
            final String k = RequestAuthenticatorImpl.uriEncode(eq < 0 ? pair : pair.substring(0, eq));
            final String v = RequestAuthenticatorImpl.uriEncode(eq < 0 ? "" : pair.substring(eq + 1));
            params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        final StringJoiner sj = new StringJoiner("&");
        params.forEach((k, vs) -> { Collections.sort(vs); vs.forEach(v -> sj.add(k + "=" + v)); });
        return sj.toString();
    }
}
