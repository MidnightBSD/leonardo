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
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.midnightbsd.leonardo.core.Ulid;
import org.reactivestreams.Publisher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Adds mandatory S3-protocol response headers to every outbound response:
 * <ul>
 *   <li>{@code x-amz-request-id} — unique request correlation ID (ULID-based)</li>
 *   <li>{@code x-amz-id-2} — extended request ID (Base64 of two ULIDs)</li>
 *   <li>{@code Server} — {@code Leonardo}</li>
 * </ul>
 *
 * <p>All S3 clients (aws-cli, rclone, s3cmd, mc, s3fs-fuse, Terraform) expect
 * {@code x-amz-request-id} in every response; without it they log warnings or
 * treat the server as non-S3-compatible.
 *
 * <p>Runs at order {@code 100} — after the auth filter ({@code -100}) so that
 * even auth-rejected 403 responses carry the header.
 */
@Filter("/**")
public final class S3ResponseHeaderFilter implements HttpServerFilter {

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            final HttpRequest<?> request,
            final ServerFilterChain chain) {
        return Publishers.map(chain.proceed(request), response -> {
            if (!response.getHeaders().contains("x-amz-request-id")) {
                final String rid = Ulid.generate().toLowerCase();
                response.header("x-amz-request-id", rid);
                response.header("x-amz-id-2", Base64.getEncoder().encodeToString(
                        (rid + Ulid.generate().toLowerCase()).getBytes(StandardCharsets.UTF_8)));
            }
            response.header("Server", "Leonardo");
            return response;
        });
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
