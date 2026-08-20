/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

final class IntegrityCheckerStreamingTest {

    @Test
    void computesSameSha256ForStreamAsByteArray() throws Exception {
        final byte[] data = "streamed integrity data".getBytes(StandardCharsets.UTF_8);

        assertThat(IntegrityChecker.compute(new ByteArrayInputStream(data), "sha256"))
                .isEqualTo(IntegrityChecker.compute(data, "sha256"));
    }

    @Test
    void computesSameCrc32cForStreamAsByteArray() throws Exception {
        final byte[] data = "streamed integrity data".getBytes(StandardCharsets.UTF_8);

        assertThat(IntegrityChecker.compute(new ByteArrayInputStream(data), "crc32c"))
                .isEqualTo(IntegrityChecker.compute(data, "crc32c"));
    }
}
