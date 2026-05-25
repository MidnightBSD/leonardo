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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inspects every inbound S3 request, extracts the {@code Authorization} header,
 * dispatches to the SigV4 or SigV2 verifier, and rejects mismatched signatures.
 *
 * <p>Pre-signed URLs are recognized by the presence of {@code X-Amz-Signature}
 * (SigV4) or {@code Signature} (SigV2) in the query string and verified via
 * the same code paths.
 *
 * <p>Currently a pass-through skeleton — the real verification lands in M1.
 */
@Filter("/**")
public final class S3AuthenticationFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(S3AuthenticationFilter.class);

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {

        final String auth = request.getHeaders().get("Authorization");
        if (auth == null || auth.isBlank()) {
            // M1: distinguish "no auth header" from "anonymous read allowed",
            // and reject the former with HTTP 403 AccessDenied. For now, pass
            // through so the skeleton stays runnable.
            LOG.debug("Request {} {} has no Authorization header; passing through (M0 stub)",
                    request.getMethod(), request.getPath());
        } else if (auth.startsWith("AWS4-HMAC-SHA256")) {
            LOG.debug("SigV4 request — verification stub");
        } else if (auth.startsWith("AWS ")) {
            LOG.debug("SigV2 request — verification stub");
        } else {
            LOG.debug("Unknown Authorization scheme: {}", auth.split(" ")[0]);
        }

        return chain.proceed(request);
    }

    @Override
    public int getOrder() {
        // Run before everything else so unauthenticated requests never touch
        // a controller. Lower numbers run earlier.
        return -100;
    }
}
