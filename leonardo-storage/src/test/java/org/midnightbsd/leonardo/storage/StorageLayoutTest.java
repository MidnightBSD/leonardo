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

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StorageLayoutTest {

    private final StorageLayout layout = new StorageLayout(Paths.get("/var/leonardo"));

    @Test
    void resolvesBucketMetaUnderUnderscoreMeta() {
        final Path expected = Paths.get("/var/leonardo/buckets/photos/_meta/bucket.yaml");
        assertThat(layout.bucketMetaFile("photos")).isEqualTo(expected);
    }

    @Test
    void objectMetaFileUsesSha256HashOfKey() {
        // SHA-256("holidays/2025/beach.jpg") is deterministic; recompute and compare.
        final String expected = StorageLayout.hashKey("holidays/2025/beach.jpg") + ".yaml";
        assertThat(layout.objectMetaFile("photos", "holidays/2025/beach.jpg").getFileName().toString())
                .isEqualTo(expected);
    }

    @Test
    void rejectsBucketNameTooShort() {
        assertThatThrownBy(() -> StorageLayout.validateBucketName("ab"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBucketNameAsIpAddress() {
        assertThatThrownBy(() -> StorageLayout.validateBucketName("192.168.1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPv4");
    }

    @Test
    void rejectsBucketNameWithDoubleDot() {
        assertThatThrownBy(() -> StorageLayout.validateBucketName("foo..bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValidBucketName() {
        StorageLayout.validateBucketName("photos-prod-2026");
        StorageLayout.validateBucketName("a.b.c");   // dots are allowed
    }

    @Test
    void hashKeyIsStableAndHex() {
        final String h = StorageLayout.hashKey("hello");
        assertThat(h).hasSize(64).matches("^[0-9a-f]+$");
        // Cross-checked against `echo -n hello | sha256sum`
        assertThat(h).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
