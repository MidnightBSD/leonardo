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

import java.util.Arrays;

/**
 * Per-request material needed to authenticate an aws-chunked SigV4 payload.
 *
 * <p>The derived signing key is scoped to one credential date, region, and
 * service. It is kept only as an in-memory request attribute and is copied at
 * the API boundary so that neither the long-lived API secret nor a mutable key
 * array escapes the authentication layer.
 */
public final class SigV4StreamingContext {
    private final byte[] signingKey;
    private final String timestamp;
    private final String credentialScope;
    private final String seedSignature;

    public SigV4StreamingContext(
            final byte[] signingKey, final String timestamp,
            final String credentialScope, final String seedSignature) {
        this.signingKey = Arrays.copyOf(signingKey, signingKey.length);
        this.timestamp = timestamp;
        this.credentialScope = credentialScope;
        this.seedSignature = seedSignature;
    }

    public byte[] signingKey() { return Arrays.copyOf(signingKey, signingKey.length); }
    public String timestamp() { return timestamp; }
    public String credentialScope() { return credentialScope; }
    public String seedSignature() { return seedSignature; }
}
