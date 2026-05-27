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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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

    /** Result of a PutObject: ETag plus optional version ID (non-null when versioning is enabled). */
    public record PutResult(String etag, String versionId) {}

    /**
     * Stores an object. Returns a {@link PutResult} containing the ETag and, when
     * bucket versioning is enabled, the new version ID.
     *
     * <p>Enforces:
     * <ul>
     *   <li>Bucket immutability ({@code immutable=true}) — rejects overwrites.
     *   <li>COMPLIANCE retention on the existing object — rejects overwrites.
     * </ul>
     *
     * @param callerObjectId   if non-empty, the caller-supplied on-disk ID (§6)
     * @param contentType      the object's Content-Type
     * @param userMetadata     x-amz-meta-* headers
     * @param checksums        optional integrity checksums already verified by the
     *                         controller (algorithm → Base64 value); stored in metadata
     * @param body             the object payload bytes (may be empty for 0-byte objects)
     */
    public PutResult putObject(
            final String bucket,
            final String key,
            final String callerObjectId,
            final String contentType,
            final Map<String, String> userMetadata,
            final Map<String, String> checksums,
            final byte[] body) throws IOException {

        ensureBucketExists(bucket);
        final BucketMetadata bucketMeta = bucketStore.read(bucket)
                .orElseThrow(() -> S3Exception.noSuchBucket(bucket));

        final String objectId = resolveObjectId(bucket, callerObjectId);
        final byte[] payload = body != null ? body : new byte[0];

        // Write payload first; if metadata write fails, the orphaned data file
        // will be cleaned up by a future scrubber. This ordering ensures we
        // never have metadata pointing at a missing data file.
        final String etag = payloadStore.write(bucket, objectId, payload);
        final Instant now = Instant.now();

        final boolean versioningEnabled = bucketMeta.versioning() != null
                && bucketMeta.versioning().status() == BucketMetadata.VersioningStatus.ENABLED;

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final var existing = metaStore.read(bucket, key);

            // Enforce bucket immutability and WORM before overwrite
            if (existing.isPresent()) {
                if (bucketMeta.immutable()) {
                    throw new S3Exception("BucketImmutable",
                            "The bucket is configured as immutable; existing objects cannot be overwritten.", 403);
                }
                checkWormWrite(existing.get());
            }

            // Build versioned history
            String newVersionId = null;
            List<ObjectMetadata.ObjectVersion> newVersions = null;
            if (versioningEnabled) {
                newVersionId = Ulid.generate();
                if (existing.isPresent()) {
                    final ObjectMetadata prev = existing.get();
                    final var histEntry = new ObjectMetadata.ObjectVersion(
                            prev.versionId() != null ? prev.versionId() : "null",
                            prev.objectId(), prev.size(), prev.etag(),
                            false, prev.lastModified());
                    final var combined = new ArrayList<ObjectMetadata.ObjectVersion>();
                    combined.add(histEntry);
                    if (prev.versions() != null) combined.addAll(prev.versions());
                    newVersions = Collections.unmodifiableList(combined);
                }
            }

            final var meta = new ObjectMetadata(
                    key, objectId, payload.length,
                    contentType != null && !contentType.isEmpty()
                            ? contentType : "application/octet-stream",
                    "\"" + etag + "\"",
                    now, now, "STANDARD",
                    newVersions, userMetadata, null, "private", false, null,
                    newVersionId,
                    checksums != null && !checksums.isEmpty() ? checksums : null, null);
            metaStore.write(bucket, meta);
            return new PutResult(etag, newVersionId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // GetObject
    // -------------------------------------------------------------------------

    public record GetResult(ObjectMetadata meta, byte[] data) {}

    /** Returns the object metadata + payload bytes for the current version. */
    public GetResult getObject(final String bucket, final String key) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        final byte[] data = payloadStore.read(bucket, meta.objectId());
        return new GetResult(meta, data);
    }

    /** Returns the object metadata + payload bytes for a specific version. */
    public GetResult getObjectVersion(
            final String bucket, final String key, final String versionId) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.readVersion(bucket, key, versionId)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        if (meta.legalHold() && meta.versionId() == null) {
            // delete marker — treat as 404
            throw S3Exception.noSuchKey(key);
        }
        final byte[] data = payloadStore.read(bucket, meta.objectId());
        return new GetResult(meta, data);
    }

    // -------------------------------------------------------------------------
    // HeadObject
    // -------------------------------------------------------------------------

    /** Returns the object metadata without the payload for the current version. */
    public ObjectMetadata headObject(final String bucket, final String key) {
        ensureBucketExists(bucket);
        return metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
    }

    /** Returns the object metadata without the payload for a specific version. */
    public ObjectMetadata headObjectVersion(
            final String bucket, final String key, final String versionId) {
        ensureBucketExists(bucket);
        return metaStore.readVersion(bucket, key, versionId)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
    }

    // -------------------------------------------------------------------------
    // DeleteObject
    // -------------------------------------------------------------------------

    /**
     * Result of a delete operation. When versioning is enabled, {@code deleteMarkerVersionId}
     * is the ULID of the newly created delete marker; when versioning is disabled, it is null.
     */
    public record DeleteObjectResult(String deleteMarkerVersionId) {
        public boolean isDeleteMarker() { return deleteMarkerVersionId != null; }
    }

    /**
     * Deletes an object (current version). Silently succeeds if the key doesn't exist.
     *
     * <p>When bucket versioning is <em>Enabled</em>, creates a delete marker instead
     * of physically removing the data, and returns the delete-marker version ID.
     *
     * <p>When versioning is disabled or suspended, checks WORM constraints before
     * deleting: legal hold and active retention both block the delete (GOVERNANCE can
     * be bypassed with {@code bypassGovernance=true}).
     */
    public DeleteObjectResult deleteObject(
            final String bucket, final String key,
            final boolean bypassGovernance) throws IOException {
        ensureBucketExists(bucket);
        final BucketMetadata bucketMeta = bucketStore.read(bucket)
                .orElseThrow(() -> S3Exception.noSuchBucket(bucket));
        final boolean versioningEnabled = bucketMeta.versioning() != null
                && bucketMeta.versioning().status() == BucketMetadata.VersioningStatus.ENABLED;

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final var maybeOld = metaStore.read(bucket, key);

            if (versioningEnabled) {
                // Create a delete marker instead of physically deleting
                final String markerVersionId = Ulid.generate();
                final Instant now = Instant.now();
                if (maybeOld.isPresent()) {
                    final ObjectMetadata prev = maybeOld.get();
                    final var histEntry = new ObjectMetadata.ObjectVersion(
                            prev.versionId() != null ? prev.versionId() : "null",
                            prev.objectId(), prev.size(), prev.etag(),
                            false, prev.lastModified());
                    final var combined = new ArrayList<ObjectMetadata.ObjectVersion>();
                    combined.add(histEntry);
                    if (prev.versions() != null) combined.addAll(prev.versions());

                    // Write delete marker as the current "version"
                    final ObjectMetadata marker = new ObjectMetadata(
                            key, "", 0, "", "",
                            now, now, "STANDARD",
                            Collections.unmodifiableList(combined),
                            null, null, "private", false, null,
                            markerVersionId, null, null);
                    metaStore.write(bucket, marker);
                } else {
                    // Key doesn't exist; S3 still returns a delete marker on new keys
                    final ObjectMetadata marker = new ObjectMetadata(
                            key, "", 0, "", "",
                            now, now, "STANDARD",
                            null, null, null, "private", false, null,
                            markerVersionId, null, null);
                    metaStore.write(bucket, marker);
                }
                return new DeleteObjectResult(markerVersionId);
            }

            // Versioning disabled — enforce WORM, then physically delete
            if (maybeOld.isPresent()) {
                checkWormDelete(maybeOld.get(), bypassGovernance);
                metaStore.delete(bucket, key);
                payloadStore.delete(bucket, maybeOld.get().objectId());
            }
            return new DeleteObjectResult(null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Permanently deletes a specific version (by version ID) from the version history.
     * This is the {@code ?versionId=} variant of DELETE. WORM constraints apply.
     */
    public void deleteObjectVersion(
            final String bucket, final String key,
            final String versionId, final boolean bypassGovernance) throws IOException {
        ensureBucketExists(bucket);

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final ObjectMetadata meta = metaStore.read(bucket, key)
                    .orElseThrow(() -> S3Exception.noSuchKey(key));

            // Deleting the current version
            if (versionId.equals(meta.versionId())) {
                checkWormDelete(meta, bypassGovernance);
                if (meta.versions() != null && !meta.versions().isEmpty()) {
                    // Promote the newest historical version to current
                    final ObjectMetadata.ObjectVersion newest = meta.versions().get(0);
                    final List<ObjectMetadata.ObjectVersion> remaining =
                            meta.versions().size() > 1
                                    ? Collections.unmodifiableList(meta.versions().subList(1, meta.versions().size()))
                                    : null;
                    final ObjectMetadata promoted = new ObjectMetadata(
                            meta.key(), newest.objectId(), newest.size(), meta.contentType(),
                            newest.etag(), newest.createdAt(), newest.createdAt(),
                            meta.storageClass(), remaining, meta.userMetadata(),
                            meta.tags(), meta.aclCanned(), meta.legalHold(), meta.retention(),
                            newest.versionId(), meta.checksums(), meta.partsCount());
                    metaStore.write(bucket, promoted);
                } else {
                    metaStore.delete(bucket, key);
                }
                payloadStore.delete(bucket, meta.objectId());
                return;
            }

            // Deleting a historical version
            if (meta.versions() == null) throw S3Exception.noSuchKey(key);
            final List<ObjectMetadata.ObjectVersion> remaining = new ArrayList<>();
            boolean found = false;
            String deletedObjectId = null;
            for (final ObjectMetadata.ObjectVersion v : meta.versions()) {
                if (!found && versionId.equals(v.versionId())) {
                    found = true;
                    deletedObjectId = v.objectId();
                } else {
                    remaining.add(v);
                }
            }
            if (!found) throw S3Exception.noSuchKey(key);

            final ObjectMetadata updated = new ObjectMetadata(
                    meta.key(), meta.objectId(), meta.size(), meta.contentType(),
                    meta.etag(), meta.createdAt(), meta.lastModified(),
                    meta.storageClass(),
                    remaining.isEmpty() ? null : Collections.unmodifiableList(remaining),
                    meta.userMetadata(), meta.tags(), meta.aclCanned(),
                    meta.legalHold(), meta.retention(), meta.versionId(),
                    meta.checksums(), meta.partsCount());
            metaStore.write(bucket, updated);
            if (deletedObjectId != null && !deletedObjectId.isEmpty()) {
                payloadStore.delete(bucket, deletedObjectId);
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
                deleteObject(bucket, key, false);
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
                    null, srcMeta.userMetadata(), null, "private", false, null, null, null, null);
            metaStore.write(dstBucket, dstMeta);
        } finally {
            lock.writeLock().unlock();
        }

        return etag;
    }

    // -------------------------------------------------------------------------
    // UpdateObject (generic metadata update)
    // -------------------------------------------------------------------------

    /**
     * Applies {@code updater} to the object's current metadata and writes the
     * result atomically under the per-key write lock.
     *
     * @throws S3Exception if the bucket or key does not exist
     */
    public ObjectMetadata updateObject(
            final String bucket,
            final String key,
            final UnaryOperator<ObjectMetadata> updater) throws IOException {
        ensureBucketExists(bucket);
        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final ObjectMetadata current = metaStore.read(bucket, key)
                    .orElseThrow(() -> S3Exception.noSuchKey(key));
            final ObjectMetadata updated = updater.apply(current);
            metaStore.write(bucket, updated);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // RenameObject
    // -------------------------------------------------------------------------

    /**
     * Renames {@code srcKey} to {@code dstKey} within the same bucket by
     * rewriting the metadata file — the payload file is shared and not moved.
     *
     * @throws S3Exception if the source key doesn't exist or the destination already exists
     */
    public void renameObject(
            final String bucket,
            final String srcKey,
            final String dstKey) throws IOException {
        ensureBucketExists(bucket);
        // Acquire both locks in canonical order to prevent deadlock
        final String lockKeyA = srcKey.compareTo(dstKey) <= 0
                ? bucket + "/" + srcKey : bucket + "/" + dstKey;
        final String lockKeyB = srcKey.compareTo(dstKey) <= 0
                ? bucket + "/" + dstKey : bucket + "/" + srcKey;
        final var la = locks.lockFor(lockKeyA);
        final var lb = locks.lockFor(lockKeyB);
        la.writeLock().lock();
        try {
            lb.writeLock().lock();
            try {
                final ObjectMetadata srcMeta = metaStore.read(bucket, srcKey)
                        .orElseThrow(() -> S3Exception.noSuchKey(srcKey));
                if (metaStore.exists(bucket, dstKey)) {
                    throw new S3Exception("KeyAlreadyExists",
                            "The destination key already exists: " + dstKey, 409);
                }
                final Instant now = Instant.now();
                final ObjectMetadata dstMeta = new ObjectMetadata(
                        dstKey, srcMeta.objectId(), srcMeta.size(),
                        srcMeta.contentType(), srcMeta.etag(),
                        srcMeta.createdAt(), now, srcMeta.storageClass(),
                        srcMeta.versions(), srcMeta.userMetadata(), srcMeta.tags(),
                        srcMeta.aclCanned(), srcMeta.legalHold(), srcMeta.retention(),
                        srcMeta.versionId(), srcMeta.checksums(), srcMeta.partsCount());
                metaStore.write(bucket, dstMeta);
                metaStore.delete(bucket, srcKey);
            } finally {
                lb.writeLock().unlock();
            }
        } finally {
            la.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // ListObjectVersions
    // -------------------------------------------------------------------------

    /** Delegates to the metadata store's version listing. */
    public ObjectMetadataStore.ListVersionsPage listObjectVersions(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String keyMarker,
            final String versionIdMarker) throws IOException {
        ensureBucketExists(bucket);
        return metaStore.listVersions(bucket, prefix, delimiter, maxKeys,
                keyMarker, versionIdMarker);
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
    // WORM enforcement helpers
    // -------------------------------------------------------------------------

    /** Blocks writes (overwrites) when COMPLIANCE retention is active. */
    private static void checkWormWrite(final ObjectMetadata meta) {
        if (meta.retention() != null
                && "COMPLIANCE".equals(meta.retention().mode())
                && meta.retention().retainUntilDate() != null
                && meta.retention().retainUntilDate().isAfter(Instant.now())) {
            throw new S3Exception("AccessDenied",
                    "Object is under COMPLIANCE retention and cannot be overwritten until "
                            + meta.retention().retainUntilDate(), 403);
        }
    }

    /** Blocks deletes when legal hold or active retention prevents it. */
    private static void checkWormDelete(
            final ObjectMetadata meta, final boolean bypassGovernance) {
        if (meta.legalHold()) {
            throw new S3Exception("AccessDenied",
                    "Object has a legal hold active and cannot be deleted.", 403);
        }
        if (meta.retention() != null
                && meta.retention().retainUntilDate() != null
                && meta.retention().retainUntilDate().isAfter(Instant.now())) {
            final String mode = meta.retention().mode();
            if ("COMPLIANCE".equals(mode)) {
                throw new S3Exception("AccessDenied",
                        "Object is under COMPLIANCE retention and cannot be deleted until "
                                + meta.retention().retainUntilDate(), 403);
            }
            if ("GOVERNANCE".equals(mode) && !bypassGovernance) {
                throw new S3Exception("AccessDenied",
                        "Object is under GOVERNANCE retention. "
                                + "Use x-amz-bypass-governance-retention: true to override.", 403);
            }
        }
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
