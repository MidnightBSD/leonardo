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
import io.micronaut.http.MediaType;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.midnightbsd.leonardo.api.filter.FilterTestSupport.first;

final class DefaultContentTypeFilterTest {

    private final DefaultContentTypeFilter filter = new DefaultContentTypeFilter();

    @Test
    void bodyWithoutContentTypeDefaultsToOctetStream() {
        final HttpRequest<String> post = HttpRequest.POST("/bucket?delete", "<Delete/>")
                .header(HttpHeaders.CONTENT_LENGTH, "9");

        assertThat(seenByChain(post)).contains(MediaType.APPLICATION_OCTET_STREAM_TYPE);
    }

    @Test
    void chunkedBodyWithoutContentTypeDefaultsToOctetStream() {
        final HttpRequest<String> post = HttpRequest.POST("/bucket/key?uploadId=1", "<CompleteMultipartUpload/>")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked");

        assertThat(seenByChain(post)).contains(MediaType.APPLICATION_OCTET_STREAM_TYPE);
    }

    @Test
    void explicitContentTypeIsKept() {
        final HttpRequest<String> post = HttpRequest.POST("/bucket?delete", "<Delete/>")
                .header(HttpHeaders.CONTENT_LENGTH, "9")
                .contentType(MediaType.APPLICATION_XML_TYPE);

        assertThat(seenByChain(post)).contains(MediaType.APPLICATION_XML_TYPE);
    }

    @Test
    void bodilessRequestIsLeftAlone() {
        assertThat(seenByChain(HttpRequest.GET("/bucket/key"))).isEmpty();
        assertThat(seenByChain(HttpRequest.PUT("/bucket/empty", "").header(HttpHeaders.CONTENT_LENGTH, "0")))
                .isEmpty();
    }

    private Optional<MediaType> seenByChain(final HttpRequest<?> request) {
        final AtomicReference<Optional<MediaType>> seen = new AtomicReference<>();
        final ServerFilterChain chain = r -> {
            seen.set(r.getContentType());
            return Publishers.just(HttpResponse.ok());
        };
        assertThat(first(filter.doFilter(request, chain)).status().getCode()).isEqualTo(200);
        return seen.get();
    }
}
