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
package org.midnightbsd.leonardo.api.body;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.ServerHttpRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * A request body that is claimed from the server only when first read.
 *
 * <p>Binding {@code @Body InputStream} claims the body before the handler
 * runs, and claiming a streamed body is what makes Micronaut send
 * {@code 100 Continue}. A handler that then rejects the request without
 * reading (NoSuchUpload, NoSuchBucket, ...) has told the client to send a
 * body it will never look at, and has also sent an interim and a final
 * response back to back, which {@code HttpURLConnection} is known to lose
 * (it discards whatever follows the {@code 100 Continue} in the same read, so
 * the AWS SDK's URL-connection client then waits for a response that has
 * already gone by). Claiming lazily means a rejected request is answered with
 * a final status only, exactly as {@code Expect: 100-continue} intends.
 */
public final class LazyRequestBody extends InputStream {

    private final ServerHttpRequest<?> source;
    private InputStream delegate;

    private LazyRequestBody(final ServerHttpRequest<?> source) {
        this.source = source;
    }

    /** Wraps the body of {@code request}; requests without a body read as empty. */
    public static LazyRequestBody of(final HttpRequest<?> request) {
        return new LazyRequestBody(request instanceof ServerHttpRequest<?> server ? server : null);
    }

    /** Whether the body has been claimed from the server (and so may be in flight). */
    public boolean claimed() {
        return delegate != null;
    }

    private InputStream open() {
        if (delegate == null) {
            if (source == null || source.byteBody().expectedLength().orElse(-1) == 0) {
                delegate = InputStream.nullInputStream();
            } else {
                delegate = source.byteBody().toInputStream();
            }
        }
        return delegate;
    }

    @Override
    public int read() throws IOException {
        return open().read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        return open().read(b, off, len);
    }

    @Override
    public long skip(final long n) throws IOException {
        return open().skip(n);
    }

    @Override
    public int available() throws IOException {
        return open().available();
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
        }
    }
}
