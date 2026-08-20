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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.midnightbsd.leonardo.auth.RequestAuthenticator;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates every inbound S3 request using {@link RequestAuthenticator}.
 *
 * <p>Supports Authorization-header SigV4 ({@code AWS4-HMAC-SHA256}) and
 * SigV2 ({@code AWS}) as implemented in {@code leonardo-auth}. Pre-signed
 * URLs are deferred to M2.
 *
 * <p>Admin paths ({@code /admin/**}) are skipped — they are handled by
 * {@code AdminPortFilter} and the admin port does not require S3 auth.
 *
 * <p>Runs at order {@code -300}, before virtual-host routing. SigV4 covers the
 * original request URI, so authentication must happen before routing rewrites
 * a virtual-host-style path into the internal path-style form.
 */
@Filter("/**")
public final class S3AuthenticationFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(S3AuthenticationFilter.class);

    private static final String ACCESS_DENIED_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Error><Code>AccessDenied</Code>"
            + "<Message>Access Denied</Message></Error>";

    private final RequestAuthenticator authenticator;

    public S3AuthenticationFilter(final RequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {

        final String path = request.getPath();

        // Admin endpoints are port-restricted by AdminPortFilter; no S3 auth needed.
        if (path.equals("/admin") || path.startsWith("/admin/")) {
            return chain.proceed(request);
        }

        final Map<String, List<String>> headers = lowercasedHeaders(request);
        final Optional<RequestAuthenticator.AuthenticationResult> authenticated = authenticator.authenticateDetailed(
                request.getMethodName(), request.getUri(), headers);

        if (authenticated.isEmpty()) {
            LOG.debug("Auth rejected: {} {}", request.getMethodName(), path);
            return Publishers.just(
                    HttpResponse.status(HttpStatus.FORBIDDEN)
                            .contentType(MediaType.APPLICATION_XML)
                            .body(ACCESS_DENIED_XML));
        }

        final RequestAuthenticator.AuthenticationResult result = authenticated.get();
        LOG.debug("Auth passed: identity={} {} {}", result.identity(), request.getMethodName(), path);
        request.setAttribute("s3.identity", result.identity());
        if (result.streamingContext() != null) {
            request.setAttribute("s3.sigv4Streaming", result.streamingContext());
        }
        return chain.proceed(request);
    }

    @Override
    public int getOrder() {
        return -300;
    }

    private static Map<String, List<String>> lowercasedHeaders(final HttpRequest<?> request) {
        final Map<String, List<String>> result = new HashMap<>();
        request.getHeaders().forEachValue((name, value) ->
                result.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value));
        return result;
    }
}
