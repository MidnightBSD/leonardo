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
package org.midnightbsd.leonardo.core.object;

import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.Ulid;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.midnightbsd.leonardo.core.bucket.BucketMetadataStore;
import org.midnightbsd.leonardo.storage.ObjectPayloadStore;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;
import org.midnightbsd.leonardo.storage.lock.StripedLockManager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Business logic for object operations: PutObject, GetObject, HeadObject,
 * DeleteObject, DeleteObjects, CopyObject, ListObjects, ListObjectsV2.
 *
 * <p>Write operations acquire the per-{@code bucket/key} stripe lock via
 * {@link StripedLockManager}. Locks are short-lived — held across metadata
 * writes only, never across payload streaming (project plan §5).
 */
@Singleton
public final class ObjectService {

    private final ObjectMetadataStore metaStore;
    private final BucketMetadataStore bucketStore;
    private final ObjectPayloadStore payloadStore;
    private final StripedLockManager locks = new StripedLockManager();

    public ObjectService(
            final ObjectMetadataStore metaStore,
            final BucketMetadataStore bucketStore,
            final ObjectPayloadStore payloadStore) {
        this.metaStore = metaStore;
        this.bucketStore = bucketStore;
        this.payloadStore = payloadStore;
    }

    // -------------------------------------------------------------------------
    // PutObject
    // -------------------------------------------------------------------------

    /**
     * Stores an object. Returns the ETag (MD5 hex, without quotes).
     *
     * @param callerObjectId   if non-empty, the caller-supplied on-disk ID (§6)
     * @param contentType      the object's Content-Type
     * @param userMetadata     x-amz-meta-* headers
     * @param checksums        optional integrity checksums already verified by the
     *                         controller (algorithm → Base64 value); stored in metadata
     * @param body             the object payload bytes (may be empty for 0-byte objects)
     */
    public String putObject(
            final String bucket,
            final String key,
            final String callerObjectId,
            final String contentType,
            final Map<String, String> userMetadata,
            final Map<String, String> checksums,
            final byte[] body) throws IOException {

        ensureBucketExists(bucket);

        final String objectId = resolveObjectId(bucket, callerObjectId);
        final byte[] payload = body != null ? body : new byte[0];

        // Write payload first; if metadata write fails, the orphaned data file
        // will be cleaned up by a future scrubber (M6). This ordering ensures
        // we never have metadata pointing at a missing data file.
        final String etag = payloadStore.write(bucket, objectId, payload);
        final Instant now = Instant.now();

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final var meta = new ObjectMetadata(
                    key, objectId, payload.length,
                    contentType != null && !contentType.isEmpty()
                            ? contentType : "application/octet-stream",
                    "\"" + etag + "\"",
                    now, now, "STANDARD",
                    null, userMetadata, null, "private", false, null,
                    checksums != null && !checksums.isEmpty() ? checksums : null, null);
            metaStore.write(bucket, meta);
        } finally {
            lock.writeLock().unlock();
        }

        return etag;
    }

    // -------------------------------------------------------------------------
    // GetObject
    // -------------------------------------------------------------------------

    /** Returns the object metadata + payload bytes. */
    public GetResult getObject(final String bucket, final String key) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        final byte[] data = payloadStore.read(bucket, meta.objectId());
        return new GetResult(meta, data);
    }

    public record GetResult(ObjectMetadata meta, byte[] data) {}

    // -------------------------------------------------------------------------
    // HeadObject
    // -------------------------------------------------------------------------

    /** Returns the object metadata without the payload. */
    public ObjectMetadata headObject(final String bucket, final String key) {
        ensureBucketExists(bucket);
        return metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
    }

    // -------------------------------------------------------------------------
    // DeleteObject
    // -------------------------------------------------------------------------

    /**
     * Deletes an object. Silently succeeds if the key doesn't exist (matching
     * S3's idempotent delete behaviour).
     */
    public void deleteObject(final String bucket, final String key) throws IOException {
        ensureBucketExists(bucket);

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final var maybeOld = metaStore.read(bucket, key);
            metaStore.delete(bucket, key);
            if (maybeOld.isPresent()) {
                payloadStore.delete(bucket, maybeOld.get().objectId());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // DeleteObjects (batch)
    // -------------------------------------------------------------------------

    public record DeleteResult(List<String> deleted, List<DeleteError> errors) {}

    public record DeleteError(String key, String code, String message) {}

    /** Deletes multiple objects; returns per-key success/error info. */
    public DeleteResult deleteObjects(
            final String bucket, final List<String> keys) throws IOException {
        ensureBucketExists(bucket);
        final var deleted = new java.util.ArrayList<String>();
        final var errors = new java.util.ArrayList<DeleteError>();
        for (final String key : keys) {
            try {
                deleteObject(bucket, key);
                deleted.add(key);
            } catch (final S3Exception ex) {
                errors.add(new DeleteError(key, ex.getCode(), ex.getMessage()));
            } catch (final IOException ex) {
                errors.add(new DeleteError(key, "InternalError", ex.getMessage()));
            }
        }
        return new DeleteResult(deleted, errors);
    }

    // -------------------------------------------------------------------------
    // CopyObject
    // -------------------------------------------------------------------------

    /**
     * Copies an object from {@code srcBucket/srcKey} to {@code dstBucket/dstKey}.
     * Returns the ETag of the new object.
     */
    public String copyObject(
            final String srcBucket,
            final String srcKey,
            final String dstBucket,
            final String dstKey) throws IOException {

        ensureBucketExists(srcBucket);
        ensureBucketExists(dstBucket);

        final ObjectMetadata srcMeta = metaStore.read(srcBucket, srcKey)
                .orElseThrow(() -> S3Exception.noSuchKey(srcKey));
        final byte[] data = payloadStore.read(srcBucket, srcMeta.objectId());

        // Generate a new object ID for the destination
        final String dstObjectId = Ulid.generate();
        final String etag = payloadStore.write(dstBucket, dstObjectId, data);
        final Instant now = Instant.now();

        final var lock = locks.lockFor(dstBucket + "/" + dstKey);
        lock.writeLock().lock();
        try {
            final var dstMeta = new ObjectMetadata(
                    dstKey, dstObjectId, srcMeta.size(),
                    srcMeta.contentType(), "\"" + etag + "\"",
                    now, now, "STANDARD",
                    null, srcMeta.userMetadata(), null, "private", false, null, null, null);
            metaStore.write(dstBucket, dstMeta);
        } finally {
            lock.writeLock().unlock();
        }

        return etag;
    }

    // -------------------------------------------------------------------------
    // ListObjectsV2 + ListObjects
    // -------------------------------------------------------------------------

    /** Delegates to the metadata store's paged listing. */
    public ObjectMetadataStore.ListPage listObjects(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String startAfterKey) throws IOException {
        ensureBucketExists(bucket);
        return metaStore.list(bucket, prefix, delimiter, maxKeys, startAfterKey);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void ensureBucketExists(final String bucket) {
        if (!bucketStore.exists(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }
    }

    private String resolveObjectId(final String bucket, final String callerObjectId)
            throws IOException {
        if (callerObjectId == null || callerObjectId.isEmpty()) {
            return Ulid.generate();
        }
        try {
            ObjectMetadata.validateCallerObjectId(callerObjectId);
        } catch (final IllegalArgumentException ex) {
            throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Invalid x-leonardo-object-id: " + ex.getMessage(), 400);
        }
        // Check for collision with an existing data file
        if (payloadStore.exists(bucket, callerObjectId)) {
            throw new S3Exception(S3Exception.OBJECT_ID_CONFLICT,
                    "The object-id '" + callerObjectId + "' is already in use in bucket '"
                            + bucket + "'", 409);
        }
        return callerObjectId;
    }
}
