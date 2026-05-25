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

    /**
     * @return true iff the signature matches.
     */
    boolean verify(SigV4Request request);

    record SigV4Request(
            String accessKey,
            String region,
            String service,
            String signedHeaders,
            String signature,
            String stringToSign) {}
}
