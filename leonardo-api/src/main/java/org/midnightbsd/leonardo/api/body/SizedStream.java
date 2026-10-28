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

import java.io.InputStream;

/**
 * A response body streamed from an {@link InputStream} whose length is known up front.
 *
 * <p>Micronaut's built-in {@code InputStream} writer always streams with an unknown
 * length, which makes the server switch to chunked transfer encoding and drop any
 * {@code Content-Length} header the controller set. S3 clients rely on
 * {@code Content-Length} for GetObject and range responses, so object downloads use
 * this type instead; {@link SizedStreamBodyWriter} hands the length to the server.
 *
 * @param stream the bytes to send; closed by the server once written
 * @param length the exact number of bytes {@code stream} will produce
 */
public record SizedStream(InputStream stream, long length) {

    public SizedStream {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
    }
}
