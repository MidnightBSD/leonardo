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
package org.midnightbsd.leonardo.core.multipart;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.core.Ulid;

import static org.assertj.core.api.Assertions.assertThat;

final class MultipartServiceUploadIdTest {

    @Test
    void acceptsEveryIdTheServerMints() {
        for (int i = 0; i < 1_000; i++) {
            final String id = Ulid.generate();
            assertThat(MultipartService.isWellFormedUploadId(id)).as(id).isTrue();
        }
    }

    @Test
    void rejectsAnythingThatIsNotAUlid() {
        for (final String bad : new String[] {
                null, "", "..", "../../etc/passwd", "/etc/passwd",
                "01JABCDEF0123456789ABCDEF",      // 25 chars
                "01JABCDEF0123456789ABCDEFGH",    // 27 chars
                "01jabcdef0123456789abcdefg",     // lowercase
                "01JABCDEF0123456789ABCDEFI",     // I is not Crockford
                "01JABCDEF0123456789ABCDEF/",
                "01JABCDEF0123456789ABCDEF\u0000"}) {
            assertThat(MultipartService.isWellFormedUploadId(bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}
