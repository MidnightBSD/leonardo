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
package org.midnightbsd.leonardo.api.binder;

import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Reads {@code @Body Optional<byte[]>} parameters as raw bytes, bypassing any
 * character-encoding conversion that Micronaut's default conversion service would apply.
 *
 * <p>Without this reader, Micronaut routes binary bodies (e.g. UploadPart payloads)
 * through a UTF-8 decode/encode cycle, corrupting non-UTF-8 bytes.
 */
@Singleton
@Order(-100)
public final class RawBytesBodyReader implements MessageBodyReader<Optional<byte[]>> {

    @Override
    public boolean isReadable(final Argument<Optional<byte[]>> type, final MediaType mediaType) {
        final Class<?> rawType = type.getType();
        if (rawType != Optional.class) {
            return false;
        }
        final Argument<?>[] params = type.getTypeParameters();
        return params.length == 1 && params[0].getType() == byte[].class;
    }

    @Override
    public Optional<byte[]> read(
            final Argument<Optional<byte[]>> type,
            final MediaType mediaType,
            final Headers httpHeaders,
            final ByteBuffer<?> byteBuffer) throws CodecException {
        final byte[] bytes = byteBuffer.toByteArray();
        return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
    }

    @Override
    public Optional<byte[]> read(
            final Argument<Optional<byte[]>> type,
            final MediaType mediaType,
            final Headers httpHeaders,
            final InputStream inputStream) throws CodecException {
        try {
            final byte[] bytes = inputStream.readAllBytes();
            return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        } catch (final IOException ex) {
            throw new CodecException("Error reading body: " + ex.getMessage(), ex);
        }
    }
}
