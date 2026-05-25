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
package org.midnightbsd.leonardo.admin;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.Map;

/**
 * Admin endpoints — health, metrics scrape target, key-store reload trigger.
 *
 * <p>Should bind to a separate port ({@code leonardo.server.admin_port}, default
 * 9001) so operators can firewall it off from S3 clients. The actual
 * port-split wiring lands in M0/M1 alongside Micronaut's
 * {@code @ConfigurationProperties} server config.
 */
@Controller("/admin")
public final class AdminController {

    @Get("/health")
    public HttpResponse<Map<String, String>> health() {
        return HttpResponse.ok(Map.of(
                "status", "UP",
                "service", "leonardo",
                "version", "0.1.0-SNAPSHOT"
        ));
    }
}
