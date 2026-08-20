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
package org.midnightbsd.leonardo.auth;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates an inbound S3 HTTP request against the configured API key store.
 *
 * <p>The caller supplies the raw request components; this interface handles
 * Authorization-header SigV4, Authorization-header SigV2, and (in a future
 * milestone) pre-signed URL verification.
 */
public interface RequestAuthenticator {

    /** Authentication result, including SigV4 streaming context when applicable. */
    record AuthenticationResult(String identity, SigV4StreamingContext streamingContext) {}

    /**
     * Authenticates the request.
     *
     * @param method  HTTP method in upper case (GET, PUT, …)
     * @param uri     full request URI including path and raw query string
     * @param headers all request headers; keys must be lower-cased
     * @return the caller's identity string on success, or empty on failure
     *         (unknown key, bad signature, missing auth when required, etc.)
     */
    Optional<String> authenticate(String method, URI uri, Map<String, List<String>> headers);

    /**
     * Authenticates a request and returns the context needed by a streaming
     * SigV4 payload verifier. Implementations that do not support streaming
     * can retain the identity-only behavior.
     */
    default Optional<AuthenticationResult> authenticateDetailed(
            final String method, final URI uri, final Map<String, List<String>> headers) {
        return authenticate(method, uri, headers).map(identity -> new AuthenticationResult(identity, null));
    }
}
