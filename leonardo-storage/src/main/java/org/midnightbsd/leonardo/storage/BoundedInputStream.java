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
package org.midnightbsd.leonardo.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Exposes at most {@code limit} bytes of the wrapped stream, then reports end
 * of stream. Used to serve byte ranges of a stored payload without reading the
 * whole file into memory.
 */
public final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    public BoundedInputStream(final InputStream delegate, final long limit) {
        super(delegate);
        this.remaining = Math.max(0, limit);
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0) return -1;
        final int value = in.read();
        if (value != -1) remaining--;
        return value;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        if (remaining == 0) return -1;
        final int read = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (read > 0) remaining -= read;
        return read;
    }

    @Override
    public long skip(final long n) throws IOException {
        final long skipped = in.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }
}
