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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AwsChunkedDecoderTest {

    @Test
    void decodesSimpleChunk() {
        // "Hello" (5 bytes) as a single aws-chunked chunk
        final String chunked = "5\r\nHello\r\n0\r\n\r\n";
        final byte[] result = AwsChunkedDecoder.decode(
                chunked.getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(result, StandardCharsets.US_ASCII)).isEqualTo("Hello");
    }

    @Test
    void decodesMultipleChunks() {
        final String chunked = "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n";
        final byte[] result = AwsChunkedDecoder.decode(
                chunked.getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(result, StandardCharsets.US_ASCII)).isEqualTo("Hello World");
    }

    @Test
    void decodesChunkWithSignatureExtension() {
        // aws-chunked chunks carry a ;chunk-signature=... extension
        final String chunked = "5;chunk-signature=deadbeef\r\nHello\r\n"
                + "0;chunk-signature=cafebabe\r\n\r\n";
        final byte[] result = AwsChunkedDecoder.decode(
                chunked.getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(result, StandardCharsets.US_ASCII)).isEqualTo("Hello");
    }

    @Test
    void decodesEmptyPayload() {
        assertThat(AwsChunkedDecoder.decode(new byte[0])).isEmpty();
        assertThat(AwsChunkedDecoder.decode((byte[]) null)).isEmpty();
    }

    @Test
    void decodesStreamingBody() throws Exception {
        final byte[] encoded = "3;chunk-signature=x\r\nabc\r\n0;chunk-signature=x\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (var decoded = AwsChunkedDecoder.decodeStream(new java.io.ByteArrayInputStream(encoded))) {
            assertThat(decoded.readAllBytes()).containsExactly('a', 'b', 'c');
        }
    }

    @Test
    void decodesTerminatingChunkOnly() {
        final byte[] result = AwsChunkedDecoder.decode("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsTruncatedChunk() {
        final String chunked = "a\r\nHello\r\n";  // claims 10 bytes, only 5 present
        assertThatThrownBy(() ->
                AwsChunkedDecoder.decode(chunked.getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk");
    }

    @Test
    void isChunkedDetectsContentEncoding() {
        assertThat(AwsChunkedDecoder.isChunked("aws-chunked", null)).isTrue();
        assertThat(AwsChunkedDecoder.isChunked("AWS-CHUNKED", null)).isTrue();
        assertThat(AwsChunkedDecoder.isChunked("gzip, aws-chunked", null)).isTrue();
        assertThat(AwsChunkedDecoder.isChunked("gzip", null)).isFalse();
        assertThat(AwsChunkedDecoder.isChunked(null, null)).isFalse();
    }

    @Test
    void isChunkedDetectsStreamingPayloadHash() {
        assertThat(AwsChunkedDecoder.isChunked(null,
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD")).isTrue();
        assertThat(AwsChunkedDecoder.isChunked(null,
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER")).isTrue();
        assertThat(AwsChunkedDecoder.isChunked(null,
                "STREAMING-UNSIGNED-PAYLOAD-TRAILER")).isTrue();
        assertThat(AwsChunkedDecoder.isChunked(null,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")).isFalse();
        assertThat(AwsChunkedDecoder.isChunked(null, "UNSIGNED-PAYLOAD")).isFalse();
    }
}
