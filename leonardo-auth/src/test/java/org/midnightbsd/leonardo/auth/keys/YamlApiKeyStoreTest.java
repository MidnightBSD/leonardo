/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.auth.keys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YamlApiKeyStoreTest {

    @Test
    void loadsEnabledKey(@TempDir final Path tmp) throws IOException {
        final Path keysFile = copyTestKeys(tmp);
        final YamlApiKeyStore store = new YamlApiKeyStore(keysFile.toString());

        final Optional<ApiKeyStore.ApiKey> key = store.findByAccessKey("AKIATESTKEY0000000001");
        assertThat(key).isPresent();
        assertThat(key.get().identity()).isEqualTo("test-alice");
        assertThat(key.get().secretKey()).isEqualTo("TestSecretKey0000000000000000000000000001");
    }

    @Test
    void hidesDisabledKey(@TempDir final Path tmp) throws IOException {
        final Path keysFile = copyTestKeys(tmp);
        final YamlApiKeyStore store = new YamlApiKeyStore(keysFile.toString());

        assertThat(store.findByAccessKey("AKIATESTKEY0000000002")).isEmpty();
    }

    @Test
    void returnsEmptyForMissingKey(@TempDir final Path tmp) throws IOException {
        final Path keysFile = copyTestKeys(tmp);
        final YamlApiKeyStore store = new YamlApiKeyStore(keysFile.toString());

        assertThat(store.findByAccessKey("NONEXISTENT")).isEmpty();
    }

    @Test
    void gracefullyHandlesMissingFile(@TempDir final Path tmp) {
        final YamlApiKeyStore store = new YamlApiKeyStore(tmp.resolve("missing.yaml").toString());
        assertThat(store.findByAccessKey("AKIATESTKEY0000000001")).isEmpty();
    }

    @Test
    void reloadRefreshesCache(@TempDir final Path tmp) throws IOException {
        final Path keysFile = copyTestKeys(tmp);
        final YamlApiKeyStore store = new YamlApiKeyStore(keysFile.toString());
        assertThat(store.findByAccessKey("AKIATESTKEY0000000001")).isPresent();

        // Overwrite with an empty key file
        Files.writeString(keysFile, "keys: []\n");
        store.reload();
        assertThat(store.findByAccessKey("AKIATESTKEY0000000001")).isEmpty();
    }

    private static Path copyTestKeys(final Path tmp) throws IOException {
        final Path dest = tmp.resolve("api-keys.yaml");
        try (InputStream in = YamlApiKeyStoreTest.class.getResourceAsStream("/test-api-keys.yaml")) {
            assert in != null : "test resource not found";
            Files.copy(in, dest);
        }
        return dest;
    }
}
