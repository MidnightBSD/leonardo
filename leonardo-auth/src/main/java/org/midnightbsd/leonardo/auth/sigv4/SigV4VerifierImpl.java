/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth.sigv4;

import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.auth.keys.ApiKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Verifies AWS Signature Version 4 (SigV4) HMAC-SHA256 signatures.
 *
 * <p>Given a pre-built {@link SigV4Verifier.SigV4Request} (produced by
 * {@code RequestAuthenticatorImpl} from the raw HTTP request), this class:
 * <ol>
 *   <li>Looks up the secret key via {@link ApiKeyStore}.</li>
 *   <li>Derives the per-request signing key via the four-step HMAC chain:
 *       {@code kDate → kRegion → kService → kSigning}.</li>
 *   <li>Computes {@code HMAC-SHA256(signingKey, stringToSign)} and hex-encodes it.</li>
 *   <li>Compares the result with the provided signature using a constant-time
 *       equality check to prevent timing attacks.</li>
 * </ol>
 */
@Singleton
public final class SigV4VerifierImpl implements SigV4Verifier {

    private static final Logger LOG = LoggerFactory.getLogger(SigV4VerifierImpl.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ApiKeyStore keyStore;

    public SigV4VerifierImpl(final ApiKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public Optional<String> verify(final SigV4Request request) {
        return verifyDetailed(request).map(VerificationResult::identity);
    }

    @Override
    public Optional<VerificationResult> verifyDetailed(final SigV4Request request) {
        final Optional<ApiKeyStore.ApiKey> maybeKey = keyStore.findByAccessKey(request.accessKey());
        if (maybeKey.isEmpty()) {
            LOG.debug("SigV4: unknown or disabled access key {}", request.accessKey());
            return Optional.empty();
        }
        final ApiKeyStore.ApiKey apiKey = maybeKey.get();

        try {
            final byte[] signingKey = deriveSigningKey(
                    apiKey.secretKey(), request.date(), request.region(), request.service());
            final byte[] computed = hmacSha256(signingKey, request.stringToSign());
            final String computedHex = HexFormat.of().formatHex(computed);

            if (!constantTimeEquals(computedHex, request.signature())) {
                LOG.debug("SigV4: signature mismatch for access key {}", request.accessKey());
                return Optional.empty();
            }
            return Optional.of(new VerificationResult(apiKey.identity(), signingKey));
        } catch (final NoSuchAlgorithmException | InvalidKeyException ex) {
            LOG.error("SigV4: HMAC computation failed", ex);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------

    public static byte[] deriveSigningKey(
            final String secretKey, final String date, final String region, final String service)
            throws NoSuchAlgorithmException, InvalidKeyException {
        final byte[] kDate    = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        final byte[] kRegion  = hmacSha256(kDate, region);
        final byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    public static byte[] hmacSha256(final byte[] key, final String data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        final Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(key, HMAC_SHA256));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(final String a, final String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
