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
package org.midnightbsd.leonardo.core.object;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ObjectMetadataTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "01HXYZABCDEF1234567890",
            "pkg-name-1.2.3.tgz",
            "a",
            "build_2026.05.25_abc-def"
    })
    void acceptsValidCallerObjectIds(final String id) {
        assertThatNoException().isThrownBy(() -> ObjectMetadata.validateCallerObjectId(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../escape",
            "..",
            ".hidden",
            "with/slash",
            "with\\backslash",
            "with space",
            "trailing\n"
    })
    void rejectsMalformedCallerObjectIds(final String id) {
        assertThatThrownBy(() -> ObjectMetadata.validateCallerObjectId(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> ObjectMetadata.validateCallerObjectId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ObjectMetadata.validateCallerObjectId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLong() {
        final String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> ObjectMetadata.validateCallerObjectId(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAt128Chars() {
        final String maxLength = "a".repeat(128);
        assertThatNoException().isThrownBy(() -> ObjectMetadata.validateCallerObjectId(maxLength));
    }
}
