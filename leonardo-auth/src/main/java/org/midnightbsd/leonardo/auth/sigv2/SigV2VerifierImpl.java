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
package org.midnightbsd.leonardo.auth.sigv2;

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
import java.util.Base64;
import java.util.Optional;

/**
 * Verifies AWS Signature Version 2 (SigV2) HMAC-SHA1 signatures.
 *
 * <p>Given a pre-built {@link SigV2Verifier.SigV2Request} (produced by
 * {@code RequestAuthenticatorImpl}), this class:
 * <ol>
 *   <li>Looks up the secret key via {@link ApiKeyStore}.</li>
 *   <li>Computes {@code Base64(HMAC-SHA1(secretKey, stringToSign))}.</li>
 *   <li>Compares the result with the provided signature using a constant-time
 *       equality check to prevent timing attacks.</li>
 * </ol>
 *
 * <p>Only active when {@code leonardo.auth.allow_sigv2=true} (the default).
 * Every successful SigV2 verification is logged so operators can identify
 * clients that still use the legacy scheme (see project plan §1 Decisions).
 */
@Singleton
public final class SigV2VerifierImpl implements SigV2Verifier {

    private static final Logger LOG = LoggerFactory.getLogger(SigV2VerifierImpl.class);
    private static final String HMAC_SHA1 = "HmacSHA1";

    private final ApiKeyStore keyStore;

    public SigV2VerifierImpl(final ApiKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public Optional<String> verify(final SigV2Request request) {
        final Optional<ApiKeyStore.ApiKey> maybeKey = keyStore.findByAccessKey(request.accessKey());
        if (maybeKey.isEmpty()) {
            LOG.debug("SigV2: unknown or disabled access key {}", request.accessKey());
            return Optional.empty();
        }
        final ApiKeyStore.ApiKey apiKey = maybeKey.get();

        try {
            final String computed = hmacSha1Base64(apiKey.secretKey(), request.stringToSign());
            if (!constantTimeEquals(computed, request.signature())) {
                LOG.debug("SigV2: signature mismatch for access key {}", request.accessKey());
                return Optional.empty();
            }
            return Optional.of(apiKey.identity());
        } catch (final NoSuchAlgorithmException | InvalidKeyException ex) {
            LOG.error("SigV2: HMAC computation failed", ex);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------

    public static String hmacSha1Base64(final String secretKey, final String data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        final Mac mac = Mac.getInstance(HMAC_SHA1);
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantTimeEquals(final String a, final String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
