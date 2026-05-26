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
package org.midnightbsd.leonardo.auth.keys;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.yaml.YamlMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loads {@link ApiKeyStore.ApiKey} records from {@code api-keys.yaml} and caches
 * them in a {@code volatile} map for lock-free concurrent reads.
 *
 * <p>The file is read once at startup and again whenever {@link #reload()} is
 * called (e.g. by the admin key-reload endpoint). Reload failures are logged
 * but leave the existing cache intact so the server keeps running.
 */
@Singleton
public final class YamlApiKeyStore implements ApiKeyStore {

    private static final Logger LOG = LoggerFactory.getLogger(YamlApiKeyStore.class);
    private static final ObjectMapper MAPPER = YamlMappers.get();

    private final Path keysFile;
    private volatile Map<String, ApiKey> cache;

    public YamlApiKeyStore(
            @Value("${leonardo.auth.api_keys_file:/var/leonardo/config/api-keys.yaml}")
            final String keysFilePath) {
        this.keysFile = Path.of(keysFilePath);
        this.cache = loadKeys();
    }

    @Override
    public Optional<ApiKey> findByAccessKey(final String accessKey) {
        final ApiKey key = cache.get(accessKey);
        return (key != null && key.enabled()) ? Optional.of(key) : Optional.empty();
    }

    @Override
    public void reload() {
        final Map<String, ApiKey> fresh = loadKeys();
        this.cache = fresh;
        LOG.info("API key store reloaded: {} key(s) active", fresh.size());
    }

    private Map<String, ApiKey> loadKeys() {
        if (!Files.exists(keysFile)) {
            LOG.warn("API keys file {} not found; no keys loaded.", keysFile);
            return Collections.emptyMap();
        }
        try {
            final KeysFile parsed = MAPPER.readValue(keysFile.toFile(), KeysFile.class);
            final Map<String, ApiKey> result = parsed.keys.stream()
                    .map(e -> new ApiKey(e.accessKey, e.secretKey, e.identity, e.enabled))
                    .collect(Collectors.toUnmodifiableMap(ApiKey::accessKey, k -> k));
            LOG.info("Loaded {} API key(s) from {}", result.size(), keysFile);
            return result;
        } catch (final IOException ex) {
            LOG.error("Failed to load API keys from {}; cache unchanged.", keysFile, ex);
            return cache != null ? cache : Collections.emptyMap();
        }
    }

    /** YAML file shape: {@code { keys: [ { access_key, secret_key, identity, enabled } ] }} */
    private static final class KeysFile {
        public List<KeyEntry> keys = List.of();
    }

    private static final class KeyEntry {
        @JsonProperty("access_key") public String accessKey;
        @JsonProperty("secret_key") public String secretKey;
        public String identity;
        public boolean enabled = true;
    }
}
