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
package org.midnightbsd.leonardo.storage.layout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves paths within {@code base_dir} according to the on-disk layout from
 * §3 of the project plan. Pure path math — no I/O.
 *
 * <p>Centralizing this here means no other module hard-codes the layout;
 * if it ever changes, only this class moves.
 */
public final class StorageLayout {

    private final Path baseDir;

    public StorageLayout(final Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    public Path baseDir() { return baseDir; }
    public Path configDir() { return baseDir.resolve("config"); }
    public Path apiKeysFile() { return configDir().resolve("api-keys.yaml"); }
    public Path bucketsDir() { return baseDir.resolve("buckets"); }
    public Path tmpDir() { return baseDir.resolve("tmp"); }
    public Path multipartTmpDir() { return tmpDir().resolve("multipart"); }
    public Path logsDir() { return baseDir.resolve("logs"); }

    public Path bucketDir(final String bucket) {
        validateBucketName(bucket);
        return bucketsDir().resolve(bucket);
    }

    public Path bucketMetaFile(final String bucket) {
        return bucketDir(bucket).resolve("_meta").resolve("bucket.yaml");
    }

    public Path bucketObjectsMetaDir(final String bucket) {
        return bucketDir(bucket).resolve("_meta").resolve("objects");
    }

    public Path bucketUploadsMetaDir(final String bucket) {
        return bucketDir(bucket).resolve("_meta").resolve("uploads");
    }

    public Path bucketDataDir(final String bucket) {
        return bucketDir(bucket).resolve("data");
    }

    /**
     * Resolves the metadata file for an object by hashing its key — see §3
     * of the plan for why we don't mirror keys into the directory tree.
     */
    public Path objectMetaFile(final String bucket, final String key) {
        return bucketObjectsMetaDir(bucket).resolve(hashKey(key) + ".yaml");
    }

    public Path objectDataFile(final String bucket, final String objectId) {
        return bucketDataDir(bucket).resolve(objectId);
    }

    public Path multipartUploadMetaFile(final String bucket, final String uploadId) {
        return bucketUploadsMetaDir(bucket).resolve(uploadId + ".yaml");
    }

    public Path multipartUploadPartsDir(final String uploadId) {
        return multipartTmpDir().resolve(uploadId);
    }

    /**
     * SHA-256 hex of the key. 64 hex chars — well within any sane filesystem's
     * filename length limit, and collision-free for our purposes.
     */
    public static String hashKey(final String key) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JCE spec; absence is impossible.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Validates an S3 bucket name. Subset of the AWS rules: 3-63 chars,
     * lowercase letters, digits, hyphens, dots; no consecutive dots; no
     * leading/trailing hyphen; not an IP address.
     *
     * @throws IllegalArgumentException when the name is invalid
     */
    public static void validateBucketName(final String bucket) {
        if (bucket == null || bucket.length() < 3 || bucket.length() > 63) {
            throw new IllegalArgumentException("bucket name must be 3-63 chars: " + bucket);
        }
        if (!bucket.matches("^[a-z0-9][a-z0-9.\\-]*[a-z0-9]$")) {
            throw new IllegalArgumentException("invalid bucket name: " + bucket);
        }
        if (bucket.contains("..")) {
            throw new IllegalArgumentException("bucket name contains '..': " + bucket);
        }
        if (bucket.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            throw new IllegalArgumentException("bucket name must not be an IPv4 address: " + bucket);
        }
    }
}
