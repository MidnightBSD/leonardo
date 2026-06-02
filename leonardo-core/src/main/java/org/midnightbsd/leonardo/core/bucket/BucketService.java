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
package org.midnightbsd.leonardo.core.bucket;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;
import org.midnightbsd.leonardo.storage.lock.StripedLockManager;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Business logic for bucket lifecycle operations (CreateBucket, DeleteBucket,
 * HeadBucket, ListBuckets, GetBucketLocation).
 *
 * <p>Write operations acquire the per-bucket stripe lock to prevent races.
 */
@Singleton
public final class BucketService {

    private final BucketMetadataStore store;
    private final StorageLayout layout;
    private final StripedLockManager locks = new StripedLockManager();
    private final String defaultRegion;

    public BucketService(
            final BucketMetadataStore store,
            final StorageLayout layout,
            @Value("${leonardo.server.region:us-east-1}") final String defaultRegion) {
        this.store = store;
        this.layout = layout;
        this.defaultRegion = defaultRegion;
    }

    /**
     * Creates a new bucket. Validates the name, rejects if it already exists,
     * initializes the directory structure, and writes {@code bucket.yaml}.
     *
     * @param name               the bucket name (validated by {@link StorageLayout#validateBucketName})
     * @param owner              the authenticated identity creating the bucket
     * @param locationConstraint if non-null, must match the server's configured region
     * @param objectLockEnabled  if true, enables object lock and forces versioning on
     * @throws S3Exception on validation failure or duplicate bucket
     * @throws IOException on filesystem errors
     */
    public void createBucket(
            final String name,
            final String owner,
            final String locationConstraint,
            final boolean objectLockEnabled) throws IOException {

        try {
            StorageLayout.validateBucketName(name);
        } catch (final IllegalArgumentException ex) {
            throw S3Exception.invalidBucketName(name);
        }

        if (locationConstraint != null && !locationConstraint.isEmpty()
                && !locationConstraint.equals(defaultRegion)) {
            throw new S3Exception(S3Exception.INVALID_LOCATION,
                    "The specified location constraint is not valid: " + locationConstraint, 400);
        }

        final var lock = locks.lockFor(name);
        lock.writeLock().lock();
        try {
            if (store.exists(name)) {
                throw S3Exception.bucketAlreadyExists(name);
            }

            // Create directory structure: _meta/objects, _meta/uploads, data
            Files.createDirectories(layout.bucketObjectsMetaDir(name));
            Files.createDirectories(layout.bucketUploadsMetaDir(name));
            Files.createDirectories(layout.bucketDataDir(name));

            // Object lock requires versioning; enable both together
            final BucketMetadata.VersioningConfig versioning = objectLockEnabled
                    ? new BucketMetadata.VersioningConfig(
                            BucketMetadata.VersioningStatus.ENABLED, false)
                    : null;
            final BucketMetadata.ObjectLockConfig olc = objectLockEnabled
                    ? new BucketMetadata.ObjectLockConfig(true, null, null, null)
                    : null;

            final BucketMetadata meta = new BucketMetadata(
                    name, Instant.now(), owner, defaultRegion,
                    versioning, null, null, null, null, null, null, olc, null,
                    BucketMetadata.CallerObjectIdsMode.ALLOWED,
                    BucketMetadata.FsyncMode.INHERIT,
                    null, false, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null,
                    null);
            store.write(meta);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Deletes a bucket. Fails if the bucket does not exist or is not empty.
     *
     * @throws S3Exception  on missing or non-empty bucket
     * @throws IOException  on filesystem errors
     */
    public void deleteBucket(final String name) throws IOException {
        final var lock = locks.lockFor(name);
        lock.writeLock().lock();
        try {
            if (!store.exists(name)) {
                throw S3Exception.noSuchBucket(name);
            }
            if (!store.isEmpty(name)) {
                throw S3Exception.bucketNotEmpty(name);
            }
            // Delete entire bucket directory tree
            deleteTree(layout.bucketDir(name));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns {@code true} if the bucket exists.
     *
     * @throws S3Exception with 404 if the bucket doesn't exist
     */
    public BucketMetadata headBucket(final String name) {
        return store.read(name).orElseThrow(() -> S3Exception.noSuchBucket(name));
    }

    /** Returns the bucket's region. */
    public String getBucketLocation(final String name) {
        final BucketMetadata meta =
                store.read(name).orElseThrow(() -> S3Exception.noSuchBucket(name));
        final String region = meta.region();
        return region != null ? region : defaultRegion;
    }

    /**
     * Lists all buckets visible to the caller. In v1, all buckets are returned
     * (ownership filtering deferred to M4 when per-bucket ACLs land).
     */
    public List<BucketMetadata> listBuckets() throws IOException {
        return store.listAll();
    }

    /**
     * Returns the full metadata for a bucket.
     *
     * @throws S3Exception with 404 if the bucket doesn't exist
     */
    public BucketMetadata getBucketMetadata(final String name) {
        return store.read(name).orElseThrow(() -> S3Exception.noSuchBucket(name));
    }

    /**
     * Applies {@code updater} to a bucket's current metadata and writes the
     * result atomically under the per-bucket write lock.
     *
     * @param name    the bucket name
     * @param updater receives the current metadata, returns the updated form
     * @return the updated metadata after successful write
     * @throws S3Exception if the bucket doesn't exist or the invariant check fails
     * @throws IOException on filesystem errors
     */
    public BucketMetadata updateBucket(
            final String name,
            final UnaryOperator<BucketMetadata> updater) throws IOException {
        final var lock = locks.lockFor(name);
        lock.writeLock().lock();
        try {
            final BucketMetadata current = store.read(name)
                    .orElseThrow(() -> S3Exception.noSuchBucket(name));
            final BucketMetadata updated = updater.apply(current);
            try {
                updated.validateInvariants();
            } catch (final IllegalArgumentException ex) {
                throw new S3Exception("InvalidBucketState", ex.getMessage(), 409);
            }
            store.write(updated);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    final Path dir, final IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
