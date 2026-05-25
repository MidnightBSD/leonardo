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
package org.midnightbsd.leonardo.auth.keys;

import java.util.Optional;

/**
 * Resolves access-key IDs to credentials and identity.
 *
 * <p>v1 implementation reads from {@code api-keys.yaml} (see project plan §4).
 * Future implementations may pull from LDAP, OIDC, or an external secret
 * manager. The interface stays narrow so swapping backends is a one-line
 * dependency-injection change.
 */
public interface ApiKeyStore {

    /**
     * Look up an access key. Returns empty if unknown or disabled.
     */
    Optional<ApiKey> findByAccessKey(String accessKey);

    /**
     * Trigger a reload from the backing source. Called by the admin endpoint
     * so operators can rotate keys without restarting the daemon.
     */
    void reload();

    record ApiKey(String accessKey, String secretKey, String identity, boolean enabled) {}
}
