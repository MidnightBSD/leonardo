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

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.midnightbsd.leonardo.api.filter.FilterTestSupport.first;

final class ErrorConnectionCloseFilterTest {

    private final ErrorConnectionCloseFilter filter = new ErrorConnectionCloseFilter();

    @Test
    void errorResponseToRequestWithBodyClosesConnection() {
        final HttpRequest<String> put = HttpRequest.PUT("/bucket/key?partNumber=1&uploadId=nope", "part")
                .header(HttpHeaders.CONTENT_LENGTH, "4");

        final HttpResponse<?> response = first(filter.doFilter(put, respondingWith(HttpStatus.NOT_FOUND)));
        assertThat(response.getHeaders().get(HttpHeaders.CONNECTION)).isEqualTo("close");
    }

    @Test
    void errorResponseToChunkedRequestClosesConnection() {
        final HttpRequest<String> put = HttpRequest.PUT("/bucket/key", "part")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked");

        final HttpResponse<?> response = first(filter.doFilter(put, respondingWith(HttpStatus.FORBIDDEN)));
        assertThat(response.getHeaders().get(HttpHeaders.CONNECTION)).isEqualTo("close");
    }

    @Test
    void successfulResponseKeepsConnectionAlive() {
        final HttpRequest<String> put = HttpRequest.PUT("/bucket/key", "data")
                .header(HttpHeaders.CONTENT_LENGTH, "4");

        final HttpResponse<?> response = first(filter.doFilter(put, respondingWith(HttpStatus.OK)));
        assertThat(response.getHeaders().get(HttpHeaders.CONNECTION)).isNull();
    }

    @Test
    void bodilessRequestIsLeftAlone() {
        final HttpResponse<?> response =
                first(filter.doFilter(HttpRequest.GET("/bucket/missing"), respondingWith(HttpStatus.NOT_FOUND)));
        assertThat(response.getHeaders().get(HttpHeaders.CONNECTION)).isNull();
    }

    private static ServerFilterChain respondingWith(final HttpStatus status) {
        return r -> Publishers.just(HttpResponse.status(status));
    }
}
