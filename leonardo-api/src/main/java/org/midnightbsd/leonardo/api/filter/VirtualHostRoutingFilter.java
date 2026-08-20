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

import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

import java.net.URI;

/**
 * Rewrites virtual-host-style S3 requests to path-style before routing.
 *
 * <p>A request to {@code bucket.s3.local/some/key} is rewritten to
 * {@code s3.local/bucket/some/key} so that the path-style controllers
 * handle it transparently.
 *
 * <p>Runs at order {@code -200}, after authentication. This preserves the
 * client-visible URI for SigV4 verification while still presenting a
 * path-style URI to the controllers.
 */
@Filter("/**")
public final class VirtualHostRoutingFilter implements HttpServerFilter {

    private final String domain;

    public VirtualHostRoutingFilter(
            @Value("${leonardo.server.domain:s3.local}") final String domain) {
        this.domain = domain.toLowerCase();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {

        final String host = request.getHeaders().get("host");
        if (host == null) return chain.proceed(request);

        // Strip port from host header value
        final String hostName = host.contains(":")
                ? host.substring(0, host.indexOf(':')) : host;
        final String lower = hostName.toLowerCase();

        // Pass through if the host is exactly the domain (already path-style)
        // or if it doesn't end with ".<domain>" (unrelated hostname)
        final String suffix = "." + domain;
        if (lower.equals(domain) || !lower.endsWith(suffix)) {
            return chain.proceed(request);
        }

        // Extract bucket from subdomain
        final String bucket = lower.substring(0, lower.length() - suffix.length());
        if (bucket.isEmpty()) return chain.proceed(request);

        // Rewrite: /key → /bucket/key
        final String path = request.getPath();
        final String rawQuery = request.getUri().getRawQuery();
        final String newPath = "/" + bucket + (path.startsWith("/") ? path : "/" + path);
        final String newUri = rawQuery != null && !rawQuery.isEmpty()
                ? newPath + "?" + rawQuery : newPath;

        return chain.proceed(request.mutate().uri(URI.create(newUri)));
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
