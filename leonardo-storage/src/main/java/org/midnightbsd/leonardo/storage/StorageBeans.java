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
package org.midnightbsd.leonardo.storage;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.nio.file.Path;

/**
 * Micronaut factory that exposes {@link StorageLayout} and
 * {@link AtomicFileWriter} as injectable singletons, reading their
 * configuration from {@code application.yml}.
 */
@Factory
public final class StorageBeans {

    @Singleton
    public StorageLayout storageLayout(
            @Value("${leonardo.storage.base_dir:/var/leonardo}") final String baseDir) {
        return new StorageLayout(Path.of(baseDir));
    }

    @Singleton
    public AtomicFileWriter atomicFileWriter(
            @Value("${leonardo.storage.fsync_on_write:true}") final boolean fsyncOnWrite) {
        return new AtomicFileWriter(fsyncOnWrite);
    }
}
