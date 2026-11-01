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
import org.midnightbsd.leonardo.storage.lock.StripedLockManager;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single place where a finished object becomes the current version of its
 * key. Both PutObject and CompleteMultipartUpload end here, so bucket
 * immutability, COMPLIANCE retention and version history are enforced the
 * same way for each, and both take the same per-key lock.
 */
@Singleton
public final class ObjectPublisher {

    private final ObjectMetadataStore metaStore;
    private final BucketMetadataStore bucketStore;
    private final ObjectPayloadStore payloadStore;
    private final StripedLockManager locks = new StripedLockManager();

    public ObjectPublisher(final ObjectMetadataStore metaStore, final BucketMetadataStore bucketStore,
                           final ObjectPayloadStore payloadStore) {
        this.metaStore = metaStore;
        this.bucketStore = bucketStore;
        this.payloadStore = payloadStore;
    }

    /** Per-{@code bucket/key} locks shared by every service that rewrites object metadata. */
    public StripedLockManager locks() {
        return locks;
    }

    /**
     * Writes {@code draft} as the current version of its key. The draft's
     * {@code versions} and {@code versionId} are ignored:
     * <ul>
     *   <li>versioning enabled: a fresh version id is minted and the previous
     *       current record (object or delete marker) is pushed onto the history;</li>
     *   <li>versioning suspended: the draft becomes the key's single null
     *       version. Real versions stay in the history; an earlier null version,
     *       current or historical, is replaced and its payload removed;</li>
     *   <li>versioning never enabled: the record is written unversioned and the
     *       payload it replaces is removed.</li>
     * </ul>
     *
     * @return the new version id, or {@code null} when versioning is not enabled
     * @throws S3Exception when the bucket is missing, immutable, or the existing
     *                     object is under COMPLIANCE retention
     */
    public String publish(final String bucket, final ObjectMetadata draft) throws IOException {
        final BucketMetadata bucketMeta = bucketStore.read(bucket)
                .orElseThrow(() -> S3Exception.noSuchBucket(bucket));
        final BucketMetadata.VersioningStatus status = versioningStatus(bucketMeta);

        final var lock = locks.lockFor(bucket + "/" + draft.key());
        lock.writeLock().lock();
        try {
            final var existing = metaStore.read(bucket, draft.key());
            if (existing.isPresent()) {
                if (bucketMeta.immutable()) {
                    throw new S3Exception("BucketImmutable",
                            "The bucket is configured as immutable; existing objects cannot be overwritten.", 403);
                }
                checkWormWrite(existing.get());
            }

            String newVersionId = null;
            List<ObjectMetadata.ObjectVersion> history = null;
            final List<String> replacedPayloads = new ArrayList<>();
            if (status == BucketMetadata.VersioningStatus.ENABLED) {
                newVersionId = Ulid.generate();
                if (existing.isPresent()) {
                    history = historyWith(existing.get());
                }
            } else if (existing.isPresent()) {
                history = status == BucketMetadata.VersioningStatus.SUSPENDED
                        ? historyForNullVersion(existing.get(), replacedPayloads)
                        : replaceUnversioned(existing.get(), replacedPayloads);
            }

            final var meta = new ObjectMetadata(
                    draft.key(), draft.objectId(), draft.size(), draft.contentType(), draft.etag(),
                    draft.createdAt(), draft.lastModified(), draft.storageClass(),
                    history, draft.userMetadata(), draft.tags(), draft.aclCanned(),
                    draft.legalHold(), draft.retention(), newVersionId,
                    draft.checksums(), draft.partsCount(), draft.contentHeaders());
            metaStore.write(bucket, meta);
            deletePayloads(bucket, replacedPayloads, draft.objectId());
            return newVersionId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The bucket's versioning state, {@code DISABLED} when it was never configured. */
    public static BucketMetadata.VersioningStatus versioningStatus(final BucketMetadata bucketMeta) {
        return bucketMeta.versioning() == null || bucketMeta.versioning().status() == null
                ? BucketMetadata.VersioningStatus.DISABLED
                : bucketMeta.versioning().status();
    }

    /**
     * History for a new null version written while versioning is suspended:
     * {@code prev} is kept when it is a real version, and every null version
     * (current or historical) is dropped, its payload queued for deletion.
     */
    public static List<ObjectMetadata.ObjectVersion> historyForNullVersion(
            final ObjectMetadata prev, final List<String> replacedPayloads) {
        final List<ObjectMetadata.ObjectVersion> candidates =
                prev.versionId() != null ? historyWith(prev)
                        : prev.versions() != null ? prev.versions() : List.of();
        if (prev.versionId() == null && !prev.isDeleteMarker()) {
            replacedPayloads.add(prev.objectId());
        }
        final var kept = new ArrayList<ObjectMetadata.ObjectVersion>();
        for (final ObjectMetadata.ObjectVersion v : candidates) {
            if ("null".equals(v.versionId())) {
                if (!v.deleteMarker() && v.objectId() != null && !v.objectId().isEmpty()) {
                    replacedPayloads.add(v.objectId());
                }
            } else {
                kept.add(v);
            }
        }
        return kept.isEmpty() ? null : Collections.unmodifiableList(kept);
    }

    /** An unversioned overwrite keeps no history; the old payload is queued for deletion. */
    private static List<ObjectMetadata.ObjectVersion> replaceUnversioned(
            final ObjectMetadata prev, final List<String> replacedPayloads) {
        if (!prev.isDeleteMarker()) {
            replacedPayloads.add(prev.objectId());
        }
        return null;
    }

    /** Removes payloads that no metadata record references any more. */
    public void deletePayloads(final String bucket, final List<String> objectIds, final String keep)
            throws IOException {
        for (final String id : objectIds) {
            if (id != null && !id.isEmpty() && !id.equals(keep)) {
                payloadStore.delete(bucket, id);
            }
        }
    }

    /**
     * Returns {@code prev}'s history with {@code prev} itself pushed on the
     * front. A delete marker stays a delete marker in the history; otherwise
     * ListObjectVersions would report it as a zero-byte version.
     */
    public static List<ObjectMetadata.ObjectVersion> historyWith(final ObjectMetadata prev) {
        final var entry = new ObjectMetadata.ObjectVersion(
                prev.versionId() != null ? prev.versionId() : "null",
                prev.objectId(), prev.size(), prev.etag(),
                prev.isDeleteMarker(), prev.lastModified());
        final var combined = new ArrayList<ObjectMetadata.ObjectVersion>();
        combined.add(entry);
        if (prev.versions() != null) combined.addAll(prev.versions());
        return Collections.unmodifiableList(combined);
    }

    /** Blocks overwrites of an object under active COMPLIANCE retention. */
    static void checkWormWrite(final ObjectMetadata meta) {
        if (meta.retention() != null
                && "COMPLIANCE".equals(meta.retention().mode())
                && meta.retention().retainUntilDate() != null
                && meta.retention().retainUntilDate().isAfter(Instant.now())) {
            throw new S3Exception("AccessDenied",
                    "Object is under COMPLIANCE retention and cannot be overwritten until "
                            + meta.retention().retainUntilDate(), 403);
        }
    }
}
