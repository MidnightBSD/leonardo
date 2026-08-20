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
package org.midnightbsd.leonardo.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Computes and verifies S3-compatible object integrity checksums.
 *
 * <p>Supported algorithms (matching {@code x-amz-checksum-*} header names):
 * {@code crc32c}, {@code crc32}, {@code sha256}, {@code sha1}.
 * Values are Base64-encoded, matching the AWS SDK wire format.
 *
 * <p>Content-MD5 verification (Base64-encoded MD5) is handled separately
 * via {@link #verifyContentMd5}.
 */
public final class IntegrityChecker {

    public static final String CRC32C_ALG  = "crc32c";
    public static final String CRC32_ALG   = "crc32";
    public static final String SHA256_ALG  = "sha256";
    public static final String SHA1_ALG    = "sha1";

    private IntegrityChecker() {}

    /**
     * Computes the Base64-encoded checksum of {@code data} using {@code algorithm}.
     *
     * @throws S3Exception for unknown algorithms
     */
    public static String compute(final byte[] data, final String algorithm) {
        return switch (algorithm.toLowerCase()) {
            case CRC32C_ALG -> {
                final var crc = new CRC32C();
                crc.update(data);
                yield Base64.getEncoder().encodeToString(toBeInt((int) crc.getValue()));
            }
            case CRC32_ALG -> {
                final var crc = new java.util.zip.CRC32();
                crc.update(data);
                yield Base64.getEncoder().encodeToString(toBeInt((int) crc.getValue()));
            }
            case SHA256_ALG -> Base64.getEncoder().encodeToString(digest("SHA-256", data));
            case SHA1_ALG   -> Base64.getEncoder().encodeToString(digest("SHA-1", data));
            default -> throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Unsupported checksum algorithm: " + algorithm, 400);
        };
    }

    /**
     * Verifies the checksum of {@code data} against the Base64-encoded {@code expected}.
     *
     * @throws S3Exception with code {@code BadDigest} on mismatch
     */
    public static void verify(final byte[] data, final String algorithm, final String expected) {
        final String actual = compute(data, algorithm);
        if (!actual.equals(expected)) {
            throw new S3Exception("BadDigest",
                    "The " + algorithm.toUpperCase() + " checksum you specified did not match "
                    + "the data received.", 400);
        }
    }

    /**
     * Verifies all entries in {@code expected} (algorithm → Base64 value) against {@code data}.
     *
     * @throws S3Exception on the first mismatch
     */
    public static void verifyAll(final byte[] data, final Map<String, String> expected) {
        if (expected == null || expected.isEmpty()) return;
        for (final var entry : expected.entrySet()) {
            verify(data, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Verifies the {@code Content-MD5} header value (Base64-encoded raw MD5 bytes).
     *
     * @throws S3Exception with code {@code BadDigest} on mismatch; {@code InvalidArgument}
     *                     if the value is not valid Base64
     */
    public static void verifyContentMd5(final byte[] data, final String base64Md5) {
        if (base64Md5 == null || base64Md5.isEmpty()) return;
        try {
            final byte[] expected = Base64.getDecoder().decode(base64Md5);
            final byte[] actual   = MessageDigest.getInstance("MD5").digest(data);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new S3Exception("BadDigest",
                        "The Content-MD5 you specified did not match what we received.", 400);
            }
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        } catch (final IllegalArgumentException ex) {
            throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Invalid Content-MD5 value: " + base64Md5, 400);
        }
    }

    /** Computes a checksum from a stream without retaining the object in memory. */
    public static String compute(final InputStream data, final String algorithm) throws IOException {
        final byte[] buffer = new byte[8192];
        return switch (algorithm.toLowerCase()) {
            case CRC32C_ALG -> {
                final var crc = new CRC32C();
                int read;
                while ((read = data.read(buffer)) != -1) crc.update(buffer, 0, read);
                yield Base64.getEncoder().encodeToString(toBeInt((int) crc.getValue()));
            }
            case CRC32_ALG -> {
                final var crc = new java.util.zip.CRC32();
                int read;
                while ((read = data.read(buffer)) != -1) crc.update(buffer, 0, read);
                yield Base64.getEncoder().encodeToString(toBeInt((int) crc.getValue()));
            }
            case SHA256_ALG -> Base64.getEncoder().encodeToString(digest(data, "SHA-256", buffer));
            case SHA1_ALG -> Base64.getEncoder().encodeToString(digest(data, "SHA-1", buffer));
            default -> throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Unsupported checksum algorithm: " + algorithm, 400);
        };
    }


    private static byte[] toBeInt(final int value) {
        return new byte[]{
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    private static byte[] digest(final String algorithm, final byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " unavailable", ex);
        }
    }

    private static byte[] digest(final InputStream data, final String algorithm, final byte[] buffer)
            throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance(algorithm);
            int read;
            while ((read = data.read(buffer)) != -1) digest.update(buffer, 0, read);
            return digest.digest();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " unavailable", ex);
        }
    }
}
