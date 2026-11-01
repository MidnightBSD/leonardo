/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core.multipart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class MultipartServiceEtagTest {

    @Test
    void comparesEtagsIgnoringQuotesAndCase() {
        assertThat(MultipartService.etagMatches("\"ABCDEF\"", "abcdef")).isTrue();
        assertThat(MultipartService.etagMatches("abcdef", "\"abcdef\"")).isTrue();
        assertThat(MultipartService.etagMatches(" \"abcdef\" ", "abcdef")).isTrue();
    }

    @Test
    void rejectsMissingEmptyOrDifferentEtags() {
        assertThat(MultipartService.etagMatches(null, "abcdef")).isFalse();
        assertThat(MultipartService.etagMatches("abcdef", null)).isFalse();
        assertThat(MultipartService.etagMatches("\"\"", "")).isFalse();
        assertThat(MultipartService.etagMatches("abcdef", "abcdee")).isFalse();
    }
}
