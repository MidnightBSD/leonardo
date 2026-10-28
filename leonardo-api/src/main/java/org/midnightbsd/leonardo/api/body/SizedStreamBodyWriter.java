/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.midnightbsd.leonardo.api.body;

import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.body.stream.InputStreamByteBody;
import io.micronaut.http.codec.CodecException;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.OutputStream;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;

/**
 * Writes a {@link SizedStream} the same way Micronaut writes an {@code InputStream},
 * except that the known length is passed through so the response carries
 * {@code Content-Length} instead of {@code Transfer-Encoding: chunked}.
 */
@Singleton
public final class SizedStreamBodyWriter implements ResponseBodyWriter<SizedStream> {

    private final ExecutorService blockingExecutor;

    public SizedStreamBodyWriter(@Named(TaskExecutors.BLOCKING) final ExecutorService blockingExecutor) {
        this.blockingExecutor = blockingExecutor;
    }

    @Override
    public CloseableByteBody writePiece(
            final ByteBodyFactory bodyFactory,
            final HttpRequest<?> request,
            final HttpResponse<?> response,
            final Argument<SizedStream> type,
            final MediaType mediaType,
            final SizedStream object) throws CodecException {
        return InputStreamByteBody.create(
                object.stream(), OptionalLong.of(object.length()), blockingExecutor, bodyFactory);
    }

    @Override
    public void writeTo(
            final Argument<SizedStream> type,
            final MediaType mediaType,
            final SizedStream object,
            final MutableHeaders outgoingHeaders,
            final OutputStream outputStream) throws CodecException {
        outgoingHeaders.set(HttpHeaders.CONTENT_LENGTH, Long.toString(object.length()));
        try (var in = object.stream()) {
            in.transferTo(outputStream);
        } catch (final IOException ex) {
            throw new CodecException("Error writing streamed body: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean isBlocking() {
        return true;
    }
}
