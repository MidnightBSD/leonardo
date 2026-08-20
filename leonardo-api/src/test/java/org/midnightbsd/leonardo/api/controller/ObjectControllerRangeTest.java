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
import org.midnightbsd.leonardo.core.object.ObjectMetadata;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.core.multipart.MultipartService;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ObjectControllerRangeTest {

    @Test
    void parsesStartEnd() {
        final long[] r = ObjectController.parseRange("bytes=0-1023", 5000);
        assertThat(r).containsExactly(0, 1023);
    }

    @Test
    void parsesOpenEnd() {
        final long[] r = ObjectController.parseRange("bytes=500-", 5000);
        assertThat(r).containsExactly(500, 4999);
    }

    @Test
    void parsesSuffix() {
        final long[] r = ObjectController.parseRange("bytes=-500", 5000);
        assertThat(r).containsExactly(4500, 4999);
    }

    @Test
    void clampsBeyondEnd() {
        final long[] r = ObjectController.parseRange("bytes=0-9999", 5000);
        assertThat(r).containsExactly(0, 4999);
    }

    @Test
    void returnsNullForInvalidSyntax() {
        assertThat(ObjectController.parseRange("invalid", 100)).isNull();
        assertThat(ObjectController.parseRange("bytes=abc-def", 100)).isNull();
    }

    @Test
    void returnsNullForUnsatisfiable() {
        assertThat(ObjectController.parseRange("bytes=5000-6000", 5000)).isNull();
        assertThat(ObjectController.parseRange("bytes=100-50", 5000)).isNull();
    }

    @Test
    void returnsNullForNull() {
        assertThat(ObjectController.parseRange(null, 100)).isNull();
        assertThat(ObjectController.parseRange("", 100)).isNull();
    }

    @Test
    void retainsObjectEncodingsButRemovesAwsChunkedTransportEncoding() {
        assertThat(ObjectController.storageContentEncoding("aws-chunked, gzip")).isEqualTo("gzip");
        assertThat(ObjectController.storageContentEncoding("aws-chunked")).isNull();
        assertThat(ObjectController.storageContentEncoding("br")).isEqualTo("br");
    }

    @Test
    void successfulIfMatchTakesPrecedenceOverIfUnmodifiedSince() {
        final ObjectMetadata meta = new ObjectMetadata("key", "id", 3, "text/plain", "\"etag\"",
                Instant.EPOCH, Instant.parse("2026-08-20T00:00:00Z"), "STANDARD",
                null, null, null, "private", false, null, null, null, null);

        assertThat(ObjectController.checkConditionals(meta, "\"etag\"", "", "",
                "Wed, 01 Jan 2020 00:00:00 GMT")).isNull();
    }

    @Test
    void headObjectAppliesConditionalHeaders() {
        final ObjectMetadata meta = new ObjectMetadata("key", "id", 3, "text/plain", "\"etag\"",
                Instant.EPOCH, Instant.parse("2026-08-20T00:00:00Z"), "STANDARD",
                null, null, null, "private", false, null, null, null, null);
        final ObjectService service = mock(ObjectService.class);
        when(service.headObject("bucket", "key")).thenReturn(meta);
        final ObjectController controller = new ObjectController(service, mock(MultipartService.class));

        assertThat(controller.headObject("bucket", "key", "", "\"etag\"", "", "", "")
                .getStatus().getCode()).isEqualTo(304);
    }
}
