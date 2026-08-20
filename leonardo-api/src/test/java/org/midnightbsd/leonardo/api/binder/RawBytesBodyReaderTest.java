/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.api.binder;

import io.micronaut.core.type.Headers;
import io.micronaut.http.codec.CodecException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RawBytesBodyReaderTest {

    @Test
    void rejectsChunkedBodyThatExceedsInMemoryLimit() {
        final RawBytesBodyReader reader = new RawBytesBodyReader(3);
        final Headers headers = mock(Headers.class);
        when(headers.get("Content-Length", Long.class)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(null, null, headers,
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("maximum allowed in-memory size");
    }

    @Test
    void acceptsBodyAtInMemoryLimit() {
        final RawBytesBodyReader reader = new RawBytesBodyReader(3);
        final Headers headers = mock(Headers.class);
        when(headers.get("Content-Length", Long.class)).thenReturn(Optional.empty());

        final Optional<byte[]> body = reader.read(null, null, headers,
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertThat(body).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
    }
}
