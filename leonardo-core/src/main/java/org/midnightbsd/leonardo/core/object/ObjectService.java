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
import org.midnightbsd.leonardo.core.IntegrityChecker;
import org.midnightbsd.leonardo.core.Ulid;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.midnightbsd.leonardo.core.bucket.BucketMetadataStore;
import org.midnightbsd.leonardo.storage.ObjectPayloadStore;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;
import org.midnightbsd.leonardo.storage.lock.StripedLockManager;

import java.io.IOException;
import java.io.InputStream;
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
    private final ObjectPublisher publisher;
    private final StripedLockManager locks;

    public ObjectService(
            final ObjectMetadataStore metaStore,
            final BucketMetadataStore bucketStore,
            final ObjectPayloadStore payloadStore,
            final ObjectPublisher publisher) {
        this.metaStore = metaStore;
        this.bucketStore = bucketStore;
        this.payloadStore = payloadStore;
        this.publisher = publisher;
        this.locks = publisher.locks();
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

        return putObject(bucket, key, callerObjectId, contentType, userMetadata, checksums,
                new java.io.ByteArrayInputStream(body != null ? body : new byte[0]));
    }

    /** Stores an object by streaming its payload directly to durable storage. */
    public PutResult putObject(
            final String bucket,
            final String key,
            final String callerObjectId,
            final String contentType,
            final Map<String, String> userMetadata,
            final Map<String, String> checksums,
            final InputStream body) throws IOException {

        return putObject(bucket, key, callerObjectId, contentType, userMetadata, checksums, null, body);
    }

    /** Stores a streamed object and verifies supplied integrity values before publishing metadata. */
    public PutResult putObject(
            final String bucket, final String key, final String callerObjectId,
            final String contentType, final Map<String, String> userMetadata,
            final Map<String, String> checksums, final String contentMd5, final InputStream body)
            throws IOException {
        return putObject(bucket, key, callerObjectId, contentType, userMetadata, null, null,
                checksums, contentMd5, body);
    }

    /**
     * Stores a streamed object with the full set of client-supplied attributes.
     *
     * @param tags            object tags from {@code x-amz-tagging}, or null
     * @param contentHeaders  standard entity headers to echo on GET/HEAD, or null
     */
    public PutResult putObject(
            final String bucket, final String key, final String callerObjectId,
            final String contentType, final Map<String, String> userMetadata,
            final Map<String, String> tags, final ObjectMetadata.ContentHeaders contentHeaders,
            final Map<String, String> checksums, final String contentMd5, final InputStream body)
            throws IOException {

        ensureBucketExists(bucket);

        final String objectId = resolveObjectId(bucket, callerObjectId);

        // Write payload first; if metadata write fails, the orphaned data file
        // will be cleaned up by a future scrubber. This ordering ensures we
        // never have metadata pointing at a missing data file.
        final ObjectPayloadStore.WriteResult stored = payloadStore.write(bucket, objectId, body);
        final String etag = stored.etag();
        try (InputStream storedData = payloadStore.open(bucket, objectId)) {
            IntegrityChecker.verifyContentMd5(storedData, contentMd5);
        } catch (final RuntimeException | IOException ex) {
            payloadStore.delete(bucket, objectId);
            throw ex;
        }
        if (checksums != null) {
            try {
                for (final var checksum : checksums.entrySet()) {
                    try (InputStream storedData = payloadStore.open(bucket, objectId)) {
                        IntegrityChecker.verify(storedData, checksum.getKey(), checksum.getValue());
                    }
                }
            } catch (final RuntimeException | IOException ex) {
                payloadStore.delete(bucket, objectId);
                throw ex;
            }
        }
        final Instant now = Instant.now();

        final var draft = new ObjectMetadata(
                key, objectId, stored.size(),
                contentType != null && !contentType.isEmpty()
                        ? contentType : "application/octet-stream",
                "\"" + etag + "\"",
                now, now, "STANDARD",
                null, userMetadata,
                tags != null && !tags.isEmpty() ? tags : null,
                "private", false, null,
                null,
                checksums != null && !checksums.isEmpty() ? checksums : null, null,
                contentHeaders);
        final String newVersionId = publisher.publish(bucket, draft);
        return new PutResult(etag, newVersionId);
    }

    // -------------------------------------------------------------------------
    // GetObject
    // -------------------------------------------------------------------------

    public record GetResult(ObjectMetadata meta, byte[] data) {}
    public record GetStreamResult(ObjectMetadata meta, InputStream data) {}

    /** Opens the current object payload as a stream. The caller owns the stream. */
    public GetStreamResult openObject(final String bucket, final String key) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        if (isDeleteMarker(meta)) throw S3Exception.noSuchKey(key);
        return new GetStreamResult(meta, payloadStore.open(bucket, meta.objectId()));
    }

    /** Opens a versioned object payload as a stream. The caller owns the stream. */
    public GetStreamResult openObjectVersion(
            final String bucket, final String key, final String versionId) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.readVersion(bucket, key, versionId)
                .orElseThrow(() -> versionNotFound(bucket, key, versionId));
        if (isDeleteMarker(meta)) throw deleteMarkerNotAllowed();
        return new GetStreamResult(meta, payloadStore.open(bucket, meta.objectId()));
    }

    /** Returns the object metadata + payload bytes for the current version. */
    public GetResult getObject(final String bucket, final String key) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        if (isDeleteMarker(meta)) {
            throw S3Exception.noSuchKey(key);
        }
        final byte[] data = payloadStore.read(bucket, meta.objectId());
        return new GetResult(meta, data);
    }

    /** Returns the object metadata + payload bytes for a specific version. */
    public GetResult getObjectVersion(
            final String bucket, final String key, final String versionId) throws IOException {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.readVersion(bucket, key, versionId)
                .orElseThrow(() -> versionNotFound(bucket, key, versionId));
        if (isDeleteMarker(meta)) throw deleteMarkerNotAllowed();
        final byte[] data = payloadStore.read(bucket, meta.objectId());
        return new GetResult(meta, data);
    }

    // -------------------------------------------------------------------------
    // HeadObject
    // -------------------------------------------------------------------------

    /** Returns the object metadata without the payload for the current version. */
    public ObjectMetadata headObject(final String bucket, final String key) {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.read(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(key));
        if (isDeleteMarker(meta)) {
            throw S3Exception.noSuchKey(key);
        }
        return meta;
    }

    /** Returns the object metadata without the payload for a specific version. */
    public ObjectMetadata headObjectVersion(
            final String bucket, final String key, final String versionId) {
        ensureBucketExists(bucket);
        final ObjectMetadata meta = metaStore.readVersion(bucket, key, versionId)
                .orElseThrow(() -> versionNotFound(bucket, key, versionId));
        if (isDeleteMarker(meta)) throw deleteMarkerNotAllowed();
        return meta;
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
        final BucketMetadata.VersioningStatus status = ObjectPublisher.versioningStatus(bucketMeta);

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final var maybeOld = metaStore.read(bucket, key);

            if (status == BucketMetadata.VersioningStatus.SUSPENDED) {
                // A suspended bucket gets a null delete marker that replaces the
                // null version (if any); real versions stay reachable by id.
                if (maybeOld.isPresent() && maybeOld.get().versionId() == null) {
                    checkWormDelete(maybeOld.get(), bypassGovernance);
                }
                final List<String> replaced = new ArrayList<>();
                final List<ObjectMetadata.ObjectVersion> history = maybeOld
                        .map(prev -> ObjectPublisher.historyForNullVersion(prev, replaced))
                        .orElse(null);
                final Instant now = Instant.now();
                metaStore.write(bucket, new ObjectMetadata(
                        key, "", 0, "", "", now, now, "STANDARD", history,
                        null, null, "private", false, null, null, null, null, null));
                publisher.deletePayloads(bucket, replaced, null);
                return new DeleteObjectResult("null");
            }

            if (status == BucketMetadata.VersioningStatus.ENABLED) {
                // Create a delete marker instead of physically deleting
                final String markerVersionId = Ulid.generate();
                final Instant now = Instant.now();
                final List<ObjectMetadata.ObjectVersion> combined = maybeOld
                        .map(ObjectPublisher::historyWith)
                        .orElse(List.of());

                // S3 requires a delete marker even when no live version exists.  The
                // marker-only metadata is observable via ListObjectVersions and prevents
                // a later write from making an earlier delete appear to have been ignored.
                final ObjectMetadata marker = new ObjectMetadata(
                        key, "", 0, "", "",
                        now, now, "STANDARD",
                        combined.isEmpty() ? null : Collections.unmodifiableList(combined),
                        null, null, "private", false, null,
                        markerVersionId, null, null, null);
                metaStore.write(bucket, marker);
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
     * The id {@code "null"} names the version written while versioning was off.
     *
     * @return {@code true} when the removed version was a delete marker
     */
    public boolean deleteObjectVersion(
            final String bucket, final String key,
            final String versionId, final boolean bypassGovernance) throws IOException {
        ensureBucketExists(bucket);

        final var lock = locks.lockFor(bucket + "/" + key);
        lock.writeLock().lock();
        try {
            final ObjectMetadata meta = metaStore.read(bucket, key)
                    .orElseThrow(() -> S3Exception.noSuchKey(key));

            // Deleting the current version
            if (ObjectMetadataStore.sameVersionId(versionId, meta.versionId())) {
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
                            newest.versionId(), meta.checksums(), meta.partsCount(),
                            meta.contentHeaders());
                    metaStore.write(bucket, promoted);
                } else {
                    metaStore.delete(bucket, key);
                }
                if (!isDeleteMarker(meta)) {
                    payloadStore.delete(bucket, meta.objectId());
                }
                return isDeleteMarker(meta);
            }

            // Deleting a historical version
            if (meta.versions() == null) throw S3Exception.noSuchVersion(key, versionId);
            final List<ObjectMetadata.ObjectVersion> remaining = new ArrayList<>();
            boolean found = false;
            boolean removedMarker = false;
            String deletedObjectId = null;
            for (final ObjectMetadata.ObjectVersion v : meta.versions()) {
                if (!found && ObjectMetadataStore.sameVersionId(versionId, v.versionId())) {
                    found = true;
                    removedMarker = v.deleteMarker();
                    deletedObjectId = v.objectId();
                } else {
                    remaining.add(v);
                }
            }
            if (!found) throw S3Exception.noSuchVersion(key, versionId);

            final ObjectMetadata updated = new ObjectMetadata(
                    meta.key(), meta.objectId(), meta.size(), meta.contentType(),
                    meta.etag(), meta.createdAt(), meta.lastModified(),
                    meta.storageClass(),
                    remaining.isEmpty() ? null : Collections.unmodifiableList(remaining),
                    meta.userMetadata(), meta.tags(), meta.aclCanned(),
                    meta.legalHold(), meta.retention(), meta.versionId(),
                    meta.checksums(), meta.partsCount(), meta.contentHeaders());
            metaStore.write(bucket, updated);
            if (deletedObjectId != null && !deletedObjectId.isEmpty()) {
                payloadStore.delete(bucket, deletedObjectId);
            }
            return removedMarker;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // DeleteObjects (batch)
    // -------------------------------------------------------------------------

    /** One entry of a DeleteObjects request; {@code versionId} null means "the current version". */
    public record DeleteRef(String key, String versionId) {}

    /**
     * One successfully processed DeleteObjects entry.
     *
     * @param versionId             the version removed when the request named one
     * @param deleteMarker          a delete marker was created (no version id given) or removed
     * @param deleteMarkerVersionId the id of a marker that was created
     */
    public record Deleted(String key, String versionId, boolean deleteMarker, String deleteMarkerVersionId) {}

    public record DeleteResult(List<Deleted> deleted, List<DeleteError> errors) {}

    public record DeleteError(String key, String versionId, String code, String message) {}

    /** Deletes the current version of each key; see {@link #deleteObjects(String, List, boolean)}. */
    public DeleteResult deleteObjects(final String bucket, final List<String> keys) throws IOException {
        return deleteObjects(bucket, keys.stream().map(k -> new DeleteRef(k, null)).toList(), false);
    }

    /**
     * DeleteObjects: each entry without a version id behaves like DeleteObject
     * (a delete marker in a versioned bucket); each entry with one removes that
     * version permanently, exactly like {@code DELETE ?versionId=}. Failures are
     * reported per entry, never for the batch as a whole.
     */
    public DeleteResult deleteObjects(
            final String bucket, final List<DeleteRef> refs, final boolean bypassGovernance)
            throws IOException {
        ensureBucketExists(bucket);
        final var deleted = new java.util.ArrayList<Deleted>();
        final var errors = new java.util.ArrayList<DeleteError>();
        for (final DeleteRef ref : refs) {
            try {
                if (ref.versionId() == null || ref.versionId().isEmpty()) {
                    final DeleteObjectResult r = deleteObject(bucket, ref.key(), bypassGovernance);
                    deleted.add(new Deleted(ref.key(), null, r.isDeleteMarker(), r.deleteMarkerVersionId()));
                } else {
                    final boolean wasMarker = deleteObjectVersion(bucket, ref.key(), ref.versionId(), bypassGovernance);
                    deleted.add(new Deleted(ref.key(), ref.versionId(), wasMarker, null));
                }
            } catch (final S3Exception ex) {
                errors.add(new DeleteError(ref.key(), ref.versionId(), ex.getCode(), ex.getMessage()));
            } catch (final IOException ex) {
                errors.add(new DeleteError(ref.key(), ref.versionId(), "InternalError", ex.getMessage()));
            }
        }
        return new DeleteResult(deleted, errors);
    }

    // -------------------------------------------------------------------------
    // CopyObject
    // -------------------------------------------------------------------------

    /**
     * Client-controlled attributes of a CopyObject request. With
     * {@code replaceMetadata} false ({@code x-amz-metadata-directive: COPY}, the
     * default) the content type, user metadata and entity headers travel with
     * the bytes; with it true the values given here are used instead. Tags work
     * the same way under {@code replaceTagging}.
     */
    public record CopyOptions(
            boolean replaceMetadata,
            String contentType,
            Map<String, String> userMetadata,
            ObjectMetadata.ContentHeaders contentHeaders,
            boolean replaceTagging,
            Map<String, String> tags) {

        /** The default directives: copy everything from the source. */
        public static CopyOptions copyAll() {
            return new CopyOptions(false, null, null, null, false, null);
        }
    }

    /** Result of CopyObject; {@code versionId} is null unless destination versioning is enabled. */
    public record CopyResult(
            String etag, long size, Instant lastModified, String versionId, String sourceVersionId) {}

    /**
     * Copies the current version of {@code srcBucket/srcKey} to
     * {@code dstBucket/dstKey}, carrying metadata and tags along. Returns the
     * ETag of the new object.
     */
    public String copyObject(
            final String srcBucket,
            final String srcKey,
            final String dstBucket,
            final String dstKey) throws IOException {
        return copyObject(srcBucket, srcKey, null, dstBucket, dstKey, CopyOptions.copyAll()).etag();
    }

    /**
     * Copies an object (optionally a specific version of it) to
     * {@code dstBucket/dstKey}. The payload is streamed from one file to the
     * other, so objects of any size can be copied, and the destination is
     * published through the same path as PutObject so bucket versioning,
     * immutability and retention apply.
     *
     * @param srcVersionId version to copy, or null/empty for the current one
     * @throws S3Exception NoSuchKey when the source is missing or its current
     *         version is a delete marker; InvalidRequest when a delete marker
     *         is named by version id, or when an object is copied onto itself
     *         without changing metadata or tags
     */
    public CopyResult copyObject(
            final String srcBucket,
            final String srcKey,
            final String srcVersionId,
            final String dstBucket,
            final String dstKey,
            final CopyOptions options) throws IOException {

        ensureBucketExists(srcBucket);
        ensureBucketExists(dstBucket);

        final boolean specificVersion = srcVersionId != null && !srcVersionId.isEmpty();
        final ObjectMetadata srcMeta = (specificVersion
                ? metaStore.readVersion(srcBucket, srcKey, srcVersionId)
                : metaStore.read(srcBucket, srcKey))
                .orElseThrow(() -> specificVersion
                        ? versionNotFound(srcBucket, srcKey, srcVersionId)
                        : S3Exception.noSuchKey(srcKey));
        if (isDeleteMarker(srcMeta)) {
            if (specificVersion) {
                throw new S3Exception("InvalidRequest",
                        "The source of a copy request may not specifically refer to a delete marker by version id.",
                        400);
            }
            throw S3Exception.noSuchKey(srcKey);
        }

        final boolean sameObject = srcBucket.equals(dstBucket) && srcKey.equals(dstKey)
                && (!specificVersion || ObjectMetadataStore.sameVersionId(srcVersionId, currentVersionId(srcBucket, srcKey)));
        if (sameObject && !options.replaceMetadata() && !options.replaceTagging()) {
            throw new S3Exception("InvalidRequest",
                    "This copy request is illegal because it is trying to copy an object to itself "
                    + "without changing the object's metadata, storage class, website redirect location "
                    + "or encryption attributes.", 400);
        }

        // Stream the payload into a new object id; the destination record is
        // published afterwards, and the new file is removed if that fails.
        final String dstObjectId = Ulid.generate();
        final ObjectPayloadStore.WriteResult stored;
        try (InputStream in = payloadStore.open(srcBucket, srcMeta.objectId())) {
            stored = payloadStore.write(dstBucket, dstObjectId, in);
        }
        final Instant now = Instant.now();

        final String contentType = options.replaceMetadata()
                && options.contentType() != null && !options.contentType().isEmpty()
                ? options.contentType() : srcMeta.contentType();
        final Map<String, String> userMetadata = options.replaceMetadata()
                ? options.userMetadata() : srcMeta.userMetadata();
        final ObjectMetadata.ContentHeaders contentHeaders = options.replaceMetadata()
                ? options.contentHeaders() : srcMeta.contentHeaders();
        final Map<String, String> tags = options.replaceTagging() ? options.tags() : srcMeta.tags();

        final var draft = new ObjectMetadata(
                dstKey, dstObjectId, stored.size(),
                contentType != null ? contentType : "application/octet-stream",
                "\"" + stored.etag() + "\"",
                now, now, "STANDARD",
                null,
                userMetadata != null && !userMetadata.isEmpty() ? userMetadata : null,
                tags != null && !tags.isEmpty() ? tags : null,
                "private", false, null,
                null, options.replaceMetadata() ? null : srcMeta.checksums(), null,
                contentHeaders);
        final String versionId;
        try {
            versionId = publisher.publish(dstBucket, draft);
        } catch (final RuntimeException | IOException ex) {
            try {
                payloadStore.delete(dstBucket, dstObjectId);
            } catch (final IOException ignored) {
                // best effort: the record was never published
            }
            throw ex;
        }
        return new CopyResult(stored.etag(), stored.size(), now, versionId,
                srcMeta.versionId() != null ? srcMeta.versionId() : (specificVersion ? "null" : null));
    }

    /**
     * NoSuchVersion when the key exists but the version does not; NoSuchKey
     * when the key itself is unknown. Package-visible through the public
     * overload so MultipartService reports UploadPartCopy sources the same way.
     */
    public static S3Exception versionNotFound(final ObjectMetadataStore metaStore, final String bucket,
                                              final String key, final String versionId) {
        if (versionId != null && !versionId.isEmpty() && metaStore.read(bucket, key).isPresent()) {
            return S3Exception.noSuchVersion(key, versionId);
        }
        return S3Exception.noSuchKey(key);
    }

    private S3Exception versionNotFound(final String bucket, final String key, final String versionId) {
        return versionNotFound(metaStore, bucket, key, versionId);
    }

    private String currentVersionId(final String bucket, final String key) {
        return metaStore.read(bucket, key).map(ObjectMetadata::versionId).orElse(null);
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
                        srcMeta.versionId(), srcMeta.checksums(), srcMeta.partsCount(),
                        srcMeta.contentHeaders());
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

    private static boolean isDeleteMarker(final ObjectMetadata meta) {
        return meta.isDeleteMarker();
    }

    /** S3 answers GET/HEAD on a delete marker's version id with 405. */
    private static S3Exception deleteMarkerNotAllowed() {
        return new S3Exception("MethodNotAllowed",
                "The specified method is not allowed against this resource.", 405);
    }

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
