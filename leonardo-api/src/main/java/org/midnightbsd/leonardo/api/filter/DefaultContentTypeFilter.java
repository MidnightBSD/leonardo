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
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

/**
 * Gives requests that carry a body but no {@code Content-Type} header a
 * default of {@code application/octet-stream}, which is how S3 treats them.
 *
 * <p>botocore (aws-cli, boto3) and several other clients send XML request
 * bodies such as CompleteMultipartUpload, DeleteObjects and CreateBucket
 * without a Content-Type. Micronaut resolves a route's body reader at startup
 * from the unwrapped parameter type and only re-selects a reader at request
 * time when a media type is present, so without this default an
 * {@code @Body Optional<byte[]>} parameter is handed a bare {@code byte[]},
 * the conversion fails and the client gets a 400 "Required Body [body] not
 * specified".
 */
@Filter("/**")
public final class DefaultContentTypeFilter implements HttpServerFilter {

    static final String DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM;

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {
        if (request.getContentType().isEmpty()
                && S3AuthenticationFilter.hasBody(request)
                && request.getHeaders() instanceof MutableHttpHeaders mutable) {
            mutable.set(HttpHeaders.CONTENT_TYPE, DEFAULT_CONTENT_TYPE);
        }
        return chain.proceed(request);
    }

    /** After authentication (-300), before virtual-host routing (-200). */
    @Override
    public int getOrder() {
        return -250;
    }
}
