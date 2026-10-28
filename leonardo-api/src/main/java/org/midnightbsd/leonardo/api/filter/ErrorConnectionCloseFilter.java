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
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

/**
 * Closes the connection after an error response to a request that carried a
 * body.
 *
 * <p>Handlers such as UploadPart and PutObject stream the request body and
 * can fail (NoSuchUpload, NoSuchBucket, AccessDenied, ...) before they have
 * read any of it. If the connection were kept alive, the unread body bytes
 * would be parsed as the start of the next request on that connection and
 * the client's next call would hang until its read timeout. Telling the
 * client to open a fresh connection costs one TCP handshake and avoids
 * draining a possibly multi-gigabyte payload just to reject it.
 */
@Filter("/**")
public final class ErrorConnectionCloseFilter implements HttpServerFilter {

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {
        if (!S3AuthenticationFilter.hasBody(request)) {
            return chain.proceed(request);
        }
        return Publishers.map(chain.proceed(request), response -> {
            if (response.code() >= 400) {
                response.header(HttpHeaders.CONNECTION, "close");
            }
            return response;
        });
    }

    /** After authentication (-300), which handles its own rejections. */
    @Override
    public int getOrder() {
        return -275;
    }
}
