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
import org.midnightbsd.leonardo.auth.SigV4StreamingContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

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
        final byte[] encoded = ("3;chunk-signature=x\r\nabc\r\n0;chunk-signature=x\r\n"
                + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (var decoded = AwsChunkedDecoder.decodeStream(new java.io.ByteArrayInputStream(encoded))) {
            assertThat(decoded.readAllBytes()).containsExactly('a', 'b', 'c');
        }
    }

    @Test
    void verifiesSignedStreamingChunks() throws Exception {
        final byte[] signingKey = new byte[32];
        java.util.Arrays.fill(signingKey, (byte) 7);
        final SigV4StreamingContext context = new SigV4StreamingContext(
                signingKey, "20260820T000000Z", "20260820/us-east-1/s3/aws4_request", "0".repeat(64));
        final String first = chunkSignature(context, context.seedSignature(), "abc".getBytes(StandardCharsets.US_ASCII));
        final String terminal = chunkSignature(context, first, new byte[0]);
        final byte[] encoded = ("3;chunk-signature=" + first + "\r\nabc\r\n0;chunk-signature="
                + terminal + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        try (var decoded = AwsChunkedDecoder.decodeStream(
                new ByteArrayInputStream(encoded), context, null, new HashMap<>())) {
            assertThat(decoded.readAllBytes()).containsExactly('a', 'b', 'c');
        }
    }

    @Test
    void rejectsTamperedSignedStreamingChunk() throws Exception {
        final byte[] signingKey = new byte[32];
        final SigV4StreamingContext context = new SigV4StreamingContext(
                signingKey, "20260820T000000Z", "20260820/us-east-1/s3/aws4_request", "0".repeat(64));
        final String signature = chunkSignature(context, context.seedSignature(), "abc".getBytes(StandardCharsets.US_ASCII));
        final byte[] encoded = ("3;chunk-signature=" + signature + "\r\nxyz\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        try (var decoded = AwsChunkedDecoder.decodeStream(
                new ByteArrayInputStream(encoded), context, null, new HashMap<>())) {
            assertThatThrownBy(decoded::readAllBytes)
                    .hasMessageContaining("chunk signature mismatch");
        }
    }

    @Test
    void validatesAndExposesChecksumTrailers() throws Exception {
        final byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        final String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        final byte[] encoded = ("3\r\nabc\r\n0\r\n"
                + "x-amz-checksum-sha256:" + checksum + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        final Map<String, String> checksums = new HashMap<>();

        try (var decoded = AwsChunkedDecoder.decodeStream(
                new ByteArrayInputStream(encoded), null, "x-amz-checksum-sha256", checksums)) {
            assertThat(decoded.readAllBytes()).isEqualTo(data);
        }
        assertThat(checksums).containsEntry("sha256", checksum);
    }

    @Test
    void verifiesSignedChecksumTrailer() throws Exception {
        final byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 9);
        final SigV4StreamingContext context = new SigV4StreamingContext(
                key, "20260820T000000Z", "20260820/us-east-1/s3/aws4_request", "0".repeat(64));
        final byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        final String first = chunkSignature(context, context.seedSignature(), data);
        final String terminal = chunkSignature(context, first, new byte[0]);
        final String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        final String canonical = "x-amz-checksum-sha256:" + checksum + "\n";
        final String trailerSignature = trailerSignature(context, terminal, canonical);
        final byte[] encoded = ("3;chunk-signature=" + first + "\r\nabc\r\n0;chunk-signature=" + terminal
                + "\r\nx-amz-checksum-sha256:" + checksum + "\r\n"
                + "x-amz-trailer-signature:" + trailerSignature + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final Map<String, String> checksums = new HashMap<>();

        try (var decoded = AwsChunkedDecoder.decodeStream(
                new ByteArrayInputStream(encoded), context, "x-amz-checksum-sha256", checksums)) {
            assertThat(decoded.readAllBytes()).isEqualTo(data);
        }
        assertThat(checksums).containsEntry("sha256", checksum);
    }

    private static String chunkSignature(
            final SigV4StreamingContext context, final String previous, final byte[] payload) throws Exception {
        final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        final String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + context.timestamp() + "\n"
                + context.credentialScope() + "\n" + previous + "\n"
                + HexFormat.of().formatHex(sha256.digest()) + "\n"
                + HexFormat.of().formatHex(sha256.digest(payload));
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(context.signingKey(), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String trailerSignature(
            final SigV4StreamingContext context, final String previous, final String canonical) throws Exception {
        final String stringToSign = "AWS4-HMAC-SHA256-TRAILER\n" + context.timestamp() + "\n"
                + context.credentialScope() + "\n" + previous + "\n"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(context.signingKey(), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.US_ASCII)));
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
