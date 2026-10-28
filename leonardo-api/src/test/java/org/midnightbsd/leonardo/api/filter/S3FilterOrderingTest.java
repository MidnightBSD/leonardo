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

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.auth.RequestAuthenticator;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.midnightbsd.leonardo.api.filter.FilterTestSupport.first;

final class S3FilterOrderingTest {

    private static final RequestAuthenticator DENY_ALL = (method, uri, headers) -> Optional.empty();
    private static final ServerFilterChain NEVER_REACHED = request -> {
        throw new AssertionError("chain must not proceed for a rejected request");
    };

    @Test
    void authenticatesBeforeVirtualHostRoutingChangesTheSignedUri() {
        final S3AuthenticationFilter authentication = new S3AuthenticationFilter(DENY_ALL);
        final VirtualHostRoutingFilter routing = new VirtualHostRoutingFilter("s3.local");

        assertThat(authentication.getOrder()).isLessThan(routing.getOrder());
    }

    @Test
    void rejectedRequestWithUnreadBodyKeepsTheConnection() {
        // The filter answers before the body is read. The unread bytes are
        // drained server-side (RequestBodies.discard) rather than answered
        // with Connection: close, which would reset the socket on the BSDs.
        // StreamingUploadTest covers the live-connection behaviour.
        final S3AuthenticationFilter filter = new S3AuthenticationFilter(DENY_ALL);
        final HttpRequest<String> put = HttpRequest.PUT("/bucket/key", "xyz")
                .header(HttpHeaders.CONTENT_LENGTH, "3");

        final HttpResponse<?> denied = first(filter.doFilter(put, NEVER_REACHED));

        assertThat(denied.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(denied.getHeaders().contains(HttpHeaders.CONNECTION)).isFalse();
    }

    @Test
    void rejectedRequestAwaitingContinueClosesTheConnection() {
        // The client has not sent the body and will not after a final status,
        // so there is nothing to drain and the server must not wait for it.
        final S3AuthenticationFilter filter = new S3AuthenticationFilter(DENY_ALL);
        final HttpRequest<String> put = HttpRequest.PUT("/bucket/key", "xyz")
                .header(HttpHeaders.CONTENT_LENGTH, "3")
                .header(HttpHeaders.EXPECT, "100-continue");

        final HttpResponse<?> denied = first(filter.doFilter(put, NEVER_REACHED));

        assertThat(denied.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(denied.getHeaders().get(HttpHeaders.CONNECTION)).isEqualTo("close");
    }

    @Test
    void rejectedRequestWithoutBodyKeepsTheConnection() {
        final S3AuthenticationFilter filter = new S3AuthenticationFilter(DENY_ALL);

        final HttpResponse<?> denied = first(filter.doFilter(HttpRequest.GET("/bucket/key"), NEVER_REACHED));

        assertThat(denied.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(denied.getHeaders().contains(HttpHeaders.CONNECTION)).isFalse();
    }
}
