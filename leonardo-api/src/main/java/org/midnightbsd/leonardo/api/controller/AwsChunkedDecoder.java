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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the {@code aws-chunked} application-level framing used by AWS SDK v2
 * and AWS CLI v2 for {@code PutObject} and {@code UploadPart} requests.
 *
 * <p>aws-chunked format (each chunk):
 * <pre>
 * &lt;hex-size&gt;[;chunk-signature=&lt;sig&gt;]\r\n
 * &lt;data bytes&gt;\r\n
 * </pre>
 * Terminated by a zero-size chunk: {@code 0;...\r\n\r\n}.
 *
 * <p>We strip the framing and return the raw payload bytes. We intentionally do
 * NOT verify per-chunk signatures here — the outer SigV4 request signature
 * already authenticates the request.
 */
final class AwsChunkedDecoder {

    private AwsChunkedDecoder() {}

    /**
     * Returns {@code true} if the request body is aws-chunked encoded.
     *
     * @param contentEncoding value of the {@code Content-Encoding} request header
     * @param payloadHash     value of the {@code x-amz-content-sha256} header
     */
    static boolean isChunked(final String contentEncoding, final String payloadHash) {
        if (contentEncoding != null
                && contentEncoding.toLowerCase().contains("aws-chunked")) {
            return true;
        }
        if (payloadHash != null
                && (payloadHash.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD")
                    || payloadHash.startsWith("STREAMING-UNSIGNED-PAYLOAD"))) {
            return true;
        }
        return false;
    }

    /**
     * Strips aws-chunked framing and returns the raw payload.
     *
     * @param chunked the raw aws-chunked encoded body bytes
     * @return decoded payload bytes
     * @throws IllegalArgumentException on malformed chunk framing
     */
    static byte[] decode(final byte[] chunked) {
        if (chunked == null || chunked.length == 0) {
            return new byte[0];
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(chunked.length);
        int pos = 0;
        while (pos < chunked.length) {
            // Find end of chunk header line (\r\n)
            final int lineEnd = indexOfCrLf(chunked, pos);
            if (lineEnd < 0) break;

            final String headerLine =
                    new String(chunked, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            pos = lineEnd + 2; // skip \r\n

            if (headerLine.isEmpty()) continue;

            // Parse hex chunk size (everything before optional ';')
            final int semi = headerLine.indexOf(';');
            final String hexSize = semi < 0 ? headerLine : headerLine.substring(0, semi);
            final int chunkSize;
            try {
                chunkSize = Integer.parseUnsignedInt(hexSize.trim(), 16);
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "aws-chunked: invalid chunk size '" + hexSize + "'");
            }

            if (chunkSize == 0) {
                break; // terminal chunk
            }

            if (pos + chunkSize > chunked.length) {
                throw new IllegalArgumentException(
                        "aws-chunked: chunk extends past end of payload");
            }

            out.write(chunked, pos, chunkSize);
            pos += chunkSize;

            // Skip trailing \r\n after chunk data
            if (pos + 1 < chunked.length
                    && chunked[pos] == '\r' && chunked[pos + 1] == '\n') {
                pos += 2;
            }
        }
        return out.toByteArray();
    }

    /** Returns a stream that strips aws-chunked framing as bytes are consumed. */
    static InputStream decodeStream(final InputStream chunked) {
        return new InputStream() {
            private int remaining;
            private boolean finished;

            @Override
            public int read() throws IOException {
                final byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                if (length == 0) return 0;
                while (remaining == 0 && !finished) nextChunk();
                if (finished) return -1;
                final int count = chunked.read(buffer, offset, Math.min(length, remaining));
                if (count == -1) throw new IOException("aws-chunked: truncated chunk data");
                remaining -= count;
                if (remaining == 0) requireCrLf();
                return count;
            }

            private void nextChunk() throws IOException {
                final String line = readLine();
                final int semi = line.indexOf(';');
                try {
                    remaining = Integer.parseUnsignedInt(
                            (semi < 0 ? line : line.substring(0, semi)).trim(), 16);
                } catch (final NumberFormatException ex) {
                    throw new IOException("aws-chunked: invalid chunk size", ex);
                }
                if (remaining == 0) finished = true;
            }

            private String readLine() throws IOException {
                final ByteArrayOutputStream line = new ByteArrayOutputStream();
                int previous = -1;
                while (line.size() < 16 * 1024) {
                    final int current = chunked.read();
                    if (current == -1) throw new IOException("aws-chunked: truncated chunk header");
                    if (previous == '\r' && current == '\n') {
                        final byte[] bytes = line.toByteArray();
                        return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
                    }
                    line.write(current);
                    previous = current;
                }
                throw new IOException("aws-chunked: chunk header is too large");
            }

            private void requireCrLf() throws IOException {
                if (chunked.read() != '\r' || chunked.read() != '\n') {
                    throw new IOException("aws-chunked: missing chunk terminator");
                }
            }
        };
    }

    /** Returns the index of the first {@code \r\n} at or after {@code from}, or {@code -1}. */
    private static int indexOfCrLf(final byte[] data, final int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
