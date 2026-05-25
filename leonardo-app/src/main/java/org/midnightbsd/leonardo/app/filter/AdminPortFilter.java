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
package org.midnightbsd.leonardo.app.filter;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.midnightbsd.leonardo.app.config.LeonardoConfig;
import org.reactivestreams.Publisher;

/**
 * Enforces port-based routing between the S3 API server (port 9000) and the
 * admin server (port 9001, configurable via {@code leonardo.server.admin_port}).
 *
 * <ul>
 *   <li>Requests to {@code /admin/**} on the main port are rejected with 404.</li>
 *   <li>Requests to any non-admin path on the admin port are rejected with 404.</li>
 * </ul>
 *
 * <p>Runs at order {@code -1000}, before {@code S3AuthenticationFilter} ({@code -100}),
 * so unauthenticated requests are never forwarded to the wrong server.
 */
@Filter("/**")
public final class AdminPortFilter implements HttpServerFilter {

    private final int adminPort;

    public AdminPortFilter(final LeonardoConfig config) {
        this.adminPort = config.getServer().getAdminPort();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {

        final int port = request.getServerAddress().getPort();
        final String path = request.getPath();
        final boolean isAdminPath = path.equals("/admin") || path.startsWith("/admin/");

        if (isAdminPath && port != adminPort) {
            return Publishers.just(HttpResponse.notFound());
        }
        if (!isAdminPath && port == adminPort) {
            return Publishers.just(HttpResponse.notFound());
        }

        return chain.proceed(request);
    }

    @Override
    public int getOrder() {
        return -1000;
    }
}
