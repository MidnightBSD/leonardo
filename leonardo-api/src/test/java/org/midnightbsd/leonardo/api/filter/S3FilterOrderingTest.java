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
package org.midnightbsd.leonardo.api.filter;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.RequestAuthenticator;

import static org.assertj.core.api.Assertions.assertThat;

final class S3FilterOrderingTest {

    @Test
    void authenticatesBeforeVirtualHostRoutingChangesTheSignedUri() {
        final RequestAuthenticator noOpAuthenticator = (method, uri, headers) -> java.util.Optional.empty();
        final S3AuthenticationFilter authentication = new S3AuthenticationFilter(noOpAuthenticator);
        final VirtualHostRoutingFilter routing = new VirtualHostRoutingFilter("s3.local");

        assertThat(authentication.getOrder()).isLessThan(routing.getOrder());
    }
}
