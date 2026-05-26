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

/**
 * Verifies legacy AWS Signature Version 2 (SigV2) signatures on inbound requests.
 *
 * <p>Only loaded when {@code auth.allow_sigv2: true} (default). SigV2 is
 * supported for compatibility with old {@code s3cmd}, {@code boto 2.x}, and
 * embedded clients — see project plan §1 (Decisions) and §13 (Risks). Every
 * SigV2 request is logged to the audit log with the access key and User-Agent
 * so operators can identify and migrate stragglers.
 *
 * <p>SigV2 specifics to handle:
 * <ul>
 *   <li>Sub-resource canonicalization: only certain query params (acl, location,
 *       versioning, etc.) are signed, and they must be alphabetically ordered.</li>
 *   <li>{@code x-amz-*} headers are signed but in lowercase + sorted order.</li>
 *   <li>No payload hashing — V2 trusts TLS for in-transit integrity.</li>
 * </ul>
 */
public interface SigV2Verifier {

    /**
     * Verifies the request signature.
     *
     * @return the caller's identity string on success, or empty if the access key
     *         is unknown, disabled, or the signature does not match.
     */
    java.util.Optional<String> verify(SigV2Request request);

    record SigV2Request(
            String accessKey,
            String signature,
            String stringToSign) {}
}
