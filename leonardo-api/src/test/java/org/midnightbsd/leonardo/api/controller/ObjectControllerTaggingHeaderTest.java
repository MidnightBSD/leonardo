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
package org.midnightbsd.leonardo.api.controller;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.core.S3Exception;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ObjectControllerTaggingHeaderTest {

    @Test
    void parsesUrlEncodedPairsInOrder() {
        assertThat(ObjectController.parseTaggingHeader("env=test&team=stor%20age&empty="))
                .containsExactly(Map.entry("env", "test"), Map.entry("team", "stor age"), Map.entry("empty", ""));
    }

    @Test
    void absentOrBlankHeaderMeansNoTags() {
        assertThat(ObjectController.parseTaggingHeader(null)).isNull();
        assertThat(ObjectController.parseTaggingHeader("  ")).isNull();
        assertThat(ObjectController.parseTaggingHeader("&")).isNull();
    }

    @Test
    void rejectsEmptyKeysDuplicatesAndTooManyTags() {
        assertThatThrownBy(() -> ObjectController.parseTaggingHeader("=value"))
                .isInstanceOf(S3Exception.class).hasMessageContaining("TagKey");
        assertThatThrownBy(() -> ObjectController.parseTaggingHeader("a=1&a=2"))
                .isInstanceOf(S3Exception.class).hasMessageContaining("same key");
        final StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) eleven.append("k").append(i).append("=v&");
        assertThatThrownBy(() -> ObjectController.parseTaggingHeader(eleven.toString()))
                .isInstanceOf(S3Exception.class).hasMessageContaining("greater than 10");
    }
}
