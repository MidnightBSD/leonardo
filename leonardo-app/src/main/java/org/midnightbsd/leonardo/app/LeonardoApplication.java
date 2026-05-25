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
package org.midnightbsd.leonardo.app;

import io.micronaut.runtime.Micronaut;

/**
 * Leonardo daemon entry point.
 *
 * <p>Boots a Micronaut application context, which wires up the Netty HTTP server,
 * the S3 API controllers ({@code leonardo-api}), the admin endpoints
 * ({@code leonardo-admin}), and the storage backend ({@code leonardo-storage}).
 *
 * <p>Configuration is loaded from (in order of precedence): command-line
 * {@code --config=PATH}, the {@code LEONARDO_CONFIG} environment variable, then
 * {@code /etc/leonardo/leonardo.yaml}. See {@code docs/project-plan.md} §4 for
 * the configuration schema.
 */
public final class LeonardoApplication {

    private LeonardoApplication() {
        // utility class — not instantiated
    }

    public static void main(final String[] args) {
        Micronaut.build(args)
                .banner(false)
                .mainClass(LeonardoApplication.class)
                .start();
    }
}
