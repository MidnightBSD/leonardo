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

/**
 * Verifies AWS Signature Version 4 (SigV4) signatures on inbound requests.
 *
 * <p>Real implementation lands in M1. The contract is intentionally simple:
 * given a parsed authorization header and the request context (method, host,
 * path, query, headers, body hash), recompute the signature and compare.
 *
 * <p>SigV4 specifics to handle in the implementation:
 * <ul>
 *   <li>UNSIGNED-PAYLOAD vs. signed payload (the payload hash header indicates which).</li>
 *   <li>STREAMING-AWS4-HMAC-SHA256-PAYLOAD for chunked uploads.</li>
 *   <li>Pre-signed URLs via {@code X-Amz-Signature} in the query string.</li>
 *   <li>Trailer headers (CRC32C, SHA-256) added by newer SDKs.</li>
 * </ul>
 */
public interface SigV4Verifier {

    /** Successful verification with the derived, request-scoped signing key. */
    record VerificationResult(String identity, byte[] signingKey) {}

    /**
     * Verifies the request signature.
     *
     * @return the caller's identity string on success, or empty if the access key
     *         is unknown, disabled, or the signature does not match.
     */
    java.util.Optional<String> verify(SigV4Request request);

    /**
     * Verifies a request and, where supported, returns its derived signing key
     * for the chained aws-chunked signatures.
     */
    default java.util.Optional<VerificationResult> verifyDetailed(final SigV4Request request) {
        return verify(request).map(identity -> new VerificationResult(identity, null));
    }

    record SigV4Request(
            String accessKey,
            String date,          // YYYYMMDD from the Credential scope
            String region,
            String service,
            String signedHeaders,
            String signature,
            String stringToSign) {}
}
