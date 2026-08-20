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

import org.midnightbsd.leonardo.auth.SigV4StreamingContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

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
 * <p>For signed SigV4 streaming requests, every chunk is authenticated against
 * the previous chunk signature. The seed signature authenticates the headers;
 * the chained signatures authenticate the bytes that follow.
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
        return new DecodedInputStream(chunked, null, Set.of(), new HashMap<>());
    }

    /**
     * Returns a stream that verifies signed chunks and declared checksum trailers
     * as it removes aws-chunked framing. Trailer checksums are added to
     * {@code requestChecksums} only after they verify successfully.
     */
    static InputStream decodeStream(
            final InputStream chunked,
            final SigV4StreamingContext signing,
            final String trailerHeaderNames,
            final Map<String, String> requestChecksums) {
        return new DecodedInputStream(chunked, signing, parseTrailerNames(trailerHeaderNames), requestChecksums);
    }

    private static Set<String> parseTrailerNames(final String trailerHeaderNames) {
        if (trailerHeaderNames == null || trailerHeaderNames.isBlank()) return Set.of();
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final String name : trailerHeaderNames.split(",")) {
            final String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("x-amz-checksum-(crc32|crc32c|sha1|sha256)")) {
                throw new IllegalArgumentException("aws-chunked: unsupported trailer '" + name + "'");
            }
            names.add(normalized);
        }
        return Set.copyOf(names);
    }

    private static final class DecodedInputStream extends InputStream {
            private long remaining;
            private boolean finished;
            private String previousSignature;
            private String currentSignature;
            private MessageDigest currentChunkDigest;
            private final InputStream chunked;
            private final SigV4StreamingContext signing;
            private final Set<String> trailerNames;
            private final Map<String, String> requestChecksums;
            private final Map<String, ChecksumAccumulator> trailers;

            private DecodedInputStream(
                    final InputStream chunked, final SigV4StreamingContext signing,
                    final Set<String> trailerNames, final Map<String, String> requestChecksums) {
                this.chunked = chunked;
                this.signing = signing;
                this.trailerNames = trailerNames;
                this.requestChecksums = requestChecksums;
                this.previousSignature = signing == null ? null : signing.seedSignature();
                this.trailers = new HashMap<>();
                for (final String trailer : trailerNames) {
                    final String algorithm = trailer.substring("x-amz-checksum-".length());
                    this.trailers.put(algorithm, ChecksumAccumulator.forAlgorithm(algorithm));
                }
            }

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
                final int count = chunked.read(buffer, offset, (int) Math.min(length, remaining));
                if (count == -1) throw new IOException("aws-chunked: truncated chunk data");
                remaining -= count;
                if (currentChunkDigest != null) currentChunkDigest.update(buffer, offset, count);
                trailers.values().forEach(accumulator -> accumulator.update(buffer, offset, count));
                if (remaining == 0) {
                    requireCrLf();
                    verifyChunk(currentChunkDigest.digest());
                }
                return count;
            }

            private void nextChunk() throws IOException {
                final String line = readLine();
                final int semi = line.indexOf(';');
                try {
                    remaining = Long.parseUnsignedLong(
                            (semi < 0 ? line : line.substring(0, semi)).trim(), 16);
                } catch (final NumberFormatException ex) {
                    throw new IOException("aws-chunked: invalid chunk size", ex);
                }
                currentSignature = chunkSignature(line);
                currentChunkDigest = sha256();
                if (remaining == 0) {
                    verifyChunk(currentChunkDigest.digest());
                    readTrailers();
                    finished = true;
                }
            }

            private String chunkSignature(final String line) throws IOException {
                if (signing == null) return null;
                final String prefix = ";chunk-signature=";
                final int index = line.indexOf(prefix);
                if (index < 0 || index + prefix.length() + 64 != line.length()) {
                    throw new IOException("aws-chunked: missing or invalid chunk signature");
                }
                final String signature = line.substring(index + prefix.length());
                if (!signature.matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("aws-chunked: invalid chunk signature");
                }
                return signature;
            }

            private void verifyChunk(final byte[] dataHash) throws IOException {
                if (signing == null) return;
                final String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n"
                        + signing.timestamp() + "\n" + signing.credentialScope() + "\n"
                        + previousSignature + "\n" + hex(sha256().digest()) + "\n" + hex(dataHash);
                final byte[] expected;
                try {
                    expected = hmac(signing.signingKey(), stringToSign);
                } catch (final Exception ex) {
                    throw new IOException("aws-chunked: unable to verify signature", ex);
                }
                final byte[] provided = java.util.HexFormat.of().parseHex(currentSignature);
                if (!MessageDigest.isEqual(expected, provided)) {
                    throw new IOException("aws-chunked: chunk signature mismatch");
                }
                previousSignature = currentSignature;
            }

            private void readTrailers() throws IOException {
                final Map<String, String> values = new HashMap<>();
                while (true) {
                    final String line = readLine();
                    if (line.isEmpty()) break;
                    final int colon = line.indexOf(':');
                    if (colon <= 0) throw new IOException("aws-chunked: malformed trailer");
                    values.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                for (final String trailer : trailerNames) {
                    final String value = values.get(trailer);
                    if (value == null) throw new IOException("aws-chunked: missing declared trailer " + trailer);
                    final String algorithm = trailer.substring("x-amz-checksum-".length());
                    final String expected = trailers.get(algorithm).base64();
                    if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                            value.getBytes(StandardCharsets.US_ASCII))) {
                        throw new IOException("aws-chunked: trailer checksum mismatch");
                    }
                    requestChecksums.put(algorithm, value);
                }
                if (signing != null && !trailerNames.isEmpty()) {
                    final String canonical = trailerNames.stream().sorted()
                            .map(name -> name + ":" + values.get(name) + "\n")
                            .collect(java.util.stream.Collectors.joining());
                    final String stringToSign = "AWS4-HMAC-SHA256-TRAILER\n" + signing.timestamp() + "\n"
                            + signing.credentialScope() + "\n" + previousSignature + "\n" + hex(sha256().digest(canonical.getBytes(StandardCharsets.US_ASCII)));
                    final byte[] expected;
                    try { expected = hmac(signing.signingKey(), stringToSign); }
                    catch (final Exception ex) { throw new IOException("aws-chunked: unable to verify trailer", ex); }
                    final String provided = values.get("x-amz-trailer-signature");
                    if (provided == null || !provided.matches("[0-9a-fA-F]{64}")
                            || !MessageDigest.isEqual(expected, java.util.HexFormat.of().parseHex(provided))) {
                        throw new IOException("aws-chunked: trailer signature mismatch");
                    }
                }
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
    }

    private interface ChecksumAccumulator {
        void update(byte[] bytes, int offset, int length);
        String base64();

        static ChecksumAccumulator forAlgorithm(final String algorithm) {
            return switch (algorithm) {
                case "crc32" -> checksum(new CRC32());
                case "crc32c" -> checksum(new CRC32C());
                case "sha1" -> digest("SHA-1");
                case "sha256" -> digest("SHA-256");
                default -> throw new IllegalArgumentException("Unsupported checksum " + algorithm);
            };
        }
        private static ChecksumAccumulator checksum(final java.util.zip.Checksum checksum) {
            return new ChecksumAccumulator() {
                public void update(byte[] bytes, int offset, int length) { checksum.update(bytes, offset, length); }
                public String base64() { final long value = checksum.getValue(); final byte[] bytes = {
                        (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
                    return Base64.getEncoder().encodeToString(bytes); }
            };
        }
        private static ChecksumAccumulator digest(final String algorithm) {
            final MessageDigest digest = messageDigest(algorithm);
            return new ChecksumAccumulator() {
                public void update(byte[] bytes, int offset, int length) { digest.update(bytes, offset, length); }
                public String base64() { return Base64.getEncoder().encodeToString(digest.digest()); }
            };
        }
    }

    private static MessageDigest sha256() { return messageDigest("SHA-256"); }
    private static MessageDigest messageDigest(final String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (final NoSuchAlgorithmException ex) { throw new IllegalStateException(algorithm + " unavailable", ex); }
    }
    private static byte[] hmac(final byte[] key, final String value) throws Exception {
        final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }
    private static String hex(final byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }

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
