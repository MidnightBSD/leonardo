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
package org.midnightbsd.leonardo.yaml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.EnumFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Factory for the shared, pre-configured Jackson YAML {@link ObjectMapper}.
 *
 * <p>All Leonardo YAML files use snake_case field names; the mapper is configured
 * with {@link PropertyNamingStrategies#SNAKE_CASE} so that Java camelCase record
 * components and bean properties round-trip transparently.
 *
 * <p>The shared instance is safe to use from multiple threads without additional
 * synchronisation — {@link ObjectMapper} is thread-safe once configured.
 */
public final class YamlMappers {

    private static final ObjectMapper INSTANCE = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(EnumFeature.READ_ENUM_KEYS_USING_INDEX, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private YamlMappers() {}

    /** Returns the shared, thread-safe YAML {@link ObjectMapper}. */
    public static ObjectMapper get() {
        return INSTANCE;
    }
}
